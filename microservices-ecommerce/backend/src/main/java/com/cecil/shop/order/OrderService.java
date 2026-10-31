package com.cecil.shop.order;

import com.cecil.shop.common.ApiError;
import com.cecil.shop.common.CircuitBreaker;
import com.cecil.shop.common.Config;
import com.cecil.shop.common.Json;
import com.cecil.shop.common.Req;
import com.cecil.shop.common.Resp;
import com.cecil.shop.common.Router;
import com.cecil.shop.common.ServiceBase;
import com.cecil.shop.common.ServiceClient;
import com.cecil.shop.seed.SeedData;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ORDER SERVICE - places orders using the SAGA pattern.
 *
 * Placing an order touches 3 services, each with its OWN database. We cannot use one database
 * transaction across them, so we run a saga: a list of steps, and for each step an "undo" step
 * (called a COMPENSATION) that runs if a later step fails.
 *
 *    step                     service      undo (compensation)
 *    1. Reserve stock         inventory    Release stock
 *    2. Take payment          payment      Refund payment
 *    3. Confirm stock         inventory    (last step - nothing after it can fail)
 *
 *    All OK            -> order CONFIRMED, event "order.confirmed"
 *    Step 1 fails      -> order REJECTED (nothing to undo)
 *    Step 2 fails      -> undo step 1 -> order FAILED
 *
 * This service is the "orchestrator": it tells the others what to do, one step at a time.
 * Each step is recorded on the order, so you can SEE the saga in the "My orders" page.
 *
 * Other ideas shown here:
 *  - Idempotency key: pressing "Place order" twice does not create two orders.
 *  - Circuit breakers around inventory and payment calls.
 *  - Undo steps that fail are kept in a retry list and tried again every few seconds.
 */
public class OrderService extends ServiceBase {
    private static final String ORDERS = "orders", RETRY = "pendingCompensations";
    public static final long FREE_SHIPPING_FROM = 999_00L, SHIPPING = 49_00L;
    private static final Pattern PIN = Pattern.compile("[1-9][0-9]{5}");
    private static final List<String> FLOW = List.of("CONFIRMED", "PACKED", "SHIPPED", "DELIVERED");

    private final Map<String, CircuitBreaker> breakers = new LinkedHashMap<>();

    public OrderService(Config cfg) {
        super(Config.ORDER, cfg);
        breakers.put(Config.INVENTORY, new CircuitBreaker(Config.INVENTORY));
        breakers.put(Config.PAYMENT, new CircuitBreaker(Config.PAYMENT));
    }

    @Override public String role() { return "Orders - runs the checkout saga"; }

    @Override
    protected void seed() {
        if (store.isEmpty(ORDERS)) for (Map<String, Object> o : SeedData.orders(System.currentTimeMillis())) store.insert(ORDERS, o);
    }

    @Override
    protected void afterStart() { every(3000, this::retryCompensations); }

    @Override
    protected void routes(Router r) {
        r.post("/orders", this::place);
        r.get("/orders", this::mine);
        r.get("/orders/all", this::all);
        r.get("/orders/stats", this::stats);
        r.get("/orders/breakers", req -> { List<Object> l = new ArrayList<>(); breakers.values().forEach(b -> l.add(b.info())); return l; });
        r.get("/orders/{id}", req -> visible(req, order(req.paramLong("id"))));
        r.post("/orders/{id}/cancel", this::cancel);
        r.put("/orders/{id}/status", this::setStatus);
    }

    private Map<String, Object> order(long id) {
        Map<String, Object> o = store.get(ORDERS, id);
        if (o == null) throw ApiError.notFound("Order not found");
        return o;
    }

    private static Map<String, Object> visible(Req r, Map<String, Object> o) {
        if (!r.isAdmin() && Json.lng(o, "userId") != r.requireUser()) throw ApiError.notFound("Order not found");
        return o;
    }

    // ======================================================================= place order (the saga)

    private Object place(Req r) {
        long uid = r.requireUser();
        Map<String, Object> b = r.json();
        String key = requireText(b, "idempotencyKey", "Idempotency key", 64);
        Map<String, Object> address = address(Json.map(b, "address"));

        // 0. Idempotency: same key again -> return the order we already made.
        Map<String, Object> same = store.findFirst(ORDERS, o -> Json.lng(o, "userId") == uid && key.equals(Json.str(o, "idempotencyKey")));
        if (same != null) { same.put("repeat", true); return same; }

        // Read the cart (cart service) and the real prices (catalog service).
        List<Object> cartItems = Json.list(client.okJson(Config.CART, "GET", "/cart/raw", null, r), "items");
        if (cartItems.isEmpty()) throw ApiError.bad("Your cart is empty");
        StringBuilder ids = new StringBuilder();
        for (Object o : cartItems) ids.append(ids.length() == 0 ? "" : ",").append(Json.lng(Json.map(o), "productId"));
        Map<Long, Map<String, Object>> products = new LinkedHashMap<>();
        for (Object o : client.okList(Config.CATALOG, "GET", "/products/batch?ids=" + ids, null, r)) products.put(Json.lng(Json.map(o), "id"), Json.map(o));

        List<Object> lines = new ArrayList<>();
        long subtotal = 0;
        for (Object o : cartItems) {
            Map<String, Object> it = Json.map(o);
            Map<String, Object> p = products.get(Json.lng(it, "productId"));
            if (p == null || !Json.bool(p, "active", true)) throw ApiError.conflict("A product in your cart is no longer sold. Please remove it.");
            long qty = Json.lng(it, "qty"), price = Json.lng(p, "price");
            lines.add(Json.obj("productId", p.get("id"), "sku", p.get("sku"), "name", p.get("name"), "icon", p.get("icon"), "image", Json.str(p, "image", ""),
                    "price", price, "qty", qty, "lineTotal", price * qty));
            subtotal += price * qty;
        }
        long shipping = subtotal >= FREE_SHIPPING_FROM ? 0 : SHIPPING;
        long now = System.currentTimeMillis();
        Map<String, Object> order;
        synchronized (this) {
            Map<String, Object> again = store.findFirst(ORDERS, o -> Json.lng(o, "userId") == uid && key.equals(Json.str(o, "idempotencyKey")));
            if (again != null) return again;
            long id = store.nextId(ORDERS);
            order = store.insert(ORDERS, Json.obj("id", id, "orderNo", String.format("ORD-%d-%05d", LocalDate.now().getYear(), id),
                    "userId", uid, "userName", r.userName(), "items", lines, "subtotal", subtotal, "shipping", shipping,
                    "total", subtotal + shipping, "address", address, "status", "PENDING", "saga", new ArrayList<>(),
                    "history", new ArrayList<>(List.of(Json.obj("status", "PENDING", "at", now, "by", r.userName()))),
                    "createdAt", now, "updatedAt", now, "idempotencyKey", key, "cancelReason", "", "traceId", r.traceId));
        }
        publish("order.created", Json.obj("orderId", order.get("id"), "orderNo", order.get("orderNo"), "userId", uid,
                "total", order.get("total")), r.traceId);
        return Resp.created(runSaga(order, r));
    }

    private Map<String, Object> runSaga(Map<String, Object> order, Req r) {
        long id = Json.lng(order, "id");
        List<Object> skuItems = new ArrayList<>();
        for (Object o : Json.list(order, "items")) skuItems.add(Json.obj("sku", Json.map(o).get("sku"), "qty", Json.map(o).get("qty")));

        // STEP 1 - reserve stock
        long t = System.currentTimeMillis();
        try {
            ServiceClient.Result res = guarded(Config.INVENTORY, "POST", "/stock/reserve",
                    Json.obj("orderId", id, "orderNo", order.get("orderNo"), "items", skuItems), r);
            if (!res.ok()) {
                step(order, "Reserve stock", Config.INVENTORY, "FAILED", res.error(), t, r);
                return finish(order, "REJECTED", res.error(), r);
            }
            step(order, "Reserve stock", Config.INVENTORY, "OK", "All items held for this order", t, r);
        } catch (ApiError e) {
            step(order, "Reserve stock", Config.INVENTORY, "FAILED", e.getMessage(), t, r);
            compensate(order, "release", r); // in case the hold happened but the answer was lost
            return finish(order, "REJECTED", "Stock service is not available right now. Nothing was charged. Please try again.", r);
        }

        // STEP 2 - take payment
        t = System.currentTimeMillis();
        try {
            ServiceClient.Result res = guarded(Config.PAYMENT, "POST", "/payments/charge",
                    Json.obj("orderId", id, "orderNo", order.get("orderNo"), "userId", order.get("userId"), "amount", order.get("total")), r);
            if (!res.ok()) {
                step(order, "Take payment", Config.PAYMENT, "FAILED", res.error(), t, r);
                compensate(order, "release", r);
                return finish(order, "FAILED", "Payment failed: " + res.error(), r);
            }
            step(order, "Take payment", Config.PAYMENT, "OK", "Paid from wallet", t, r);
        } catch (ApiError e) {
            step(order, "Take payment", Config.PAYMENT, "FAILED", e.getMessage(), t, r);
            compensate(order, "refund", r);  // in case money was taken but the answer was lost
            compensate(order, "release", r);
            return finish(order, "FAILED", "Payment service is not available right now. Your items were released. Please try again.", r);
        }

        // STEP 3 - confirm stock (items leave the warehouse). Try a few times: payment is already done.
        t = System.currentTimeMillis();
        String lastError = "";
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                ServiceClient.Result res = guarded(Config.INVENTORY, "POST", "/stock/commit", Json.obj("orderId", id), r);
                if (res.ok()) {
                    step(order, "Confirm stock", Config.INVENTORY, "OK", attempt == 1 ? "Stock taken out of the warehouse" : "Worked on try " + attempt, t, r);
                    publish("order.confirmed", Json.obj("orderId", id, "orderNo", order.get("orderNo"), "userId", order.get("userId"),
                            "total", order.get("total"), "items", order.get("items")), r.traceId);
                    return finish(order, "CONFIRMED", "", r);
                }
                lastError = res.error();
                if (res.status() < 500) break;
            } catch (ApiError e) {
                lastError = e.getMessage();
            }
            sleep(200L * attempt);
        }
        step(order, "Confirm stock", Config.INVENTORY, "FAILED", lastError, t, r);
        compensate(order, "refund", r);
        compensate(order, "release", r);
        return finish(order, "FAILED", "Could not confirm stock: " + lastError + ". Your money was refunded.", r);
    }

    /** A call protected by a circuit breaker. */
    private ServiceClient.Result guarded(String service, String method, String path, Object body, Req r) {
        CircuitBreaker b = breakers.get(service);
        if (!b.allow()) throw ApiError.unavailable(service + " circuit is OPEN (it failed several times) - not calling it for a few seconds");
        try {
            ServiceClient.Result res = client.call(service, method, path, body, r);
            if (res.status() >= 500) b.failure(); else b.success();
            return res;
        } catch (ApiError e) {
            b.failure();
            throw e;
        }
    }

    /** Undo a step. If the other service is down, keep it in a list and try again later. */
    private void compensate(Map<String, Object> order, String action, Req r) {
        String service = action.equals("refund") ? Config.PAYMENT : Config.INVENTORY;
        String path = switch (action) { case "refund" -> "/payments/refund"; case "return" -> "/stock/return"; default -> "/stock/release"; };
        String label = switch (action) { case "refund" -> "Refund payment"; case "return" -> "Put stock back"; default -> "Release stock"; };
        long t = System.currentTimeMillis();
        try {
            ServiceClient.Result res = client.call(service, "POST", path, Json.obj("orderId", order.get("id")), r);
            if (res.ok()) {
                Map<String, Object> body = res.json();
                String note = Json.str(body, "note");
                step(order, label, service, "COMPENSATED", note != null ? note : (action.equals("refund") ? "Money returned to wallet" : "Items available again"), t, r);
                return;
            }
            throw ApiError.unavailable(res.error());
        } catch (ApiError e) {
            step(order, label, service, "RETRYING", service + " not reachable - will retry automatically", t, r);
            store.insert(RETRY, Json.obj("orderId", order.get("id"), "action", action, "tries", 0L, "at", System.currentTimeMillis(), "traceId", r.traceId));
        }
    }

    private void retryCompensations() {
        for (Map<String, Object> job : store.all(RETRY)) {
            Map<String, Object> order = store.get(ORDERS, Json.lng(job, "orderId"));
            if (order == null) { store.delete(RETRY, Json.lng(job, "id")); continue; }
            String action = Json.str(job, "action");
            String service = action.equals("refund") ? Config.PAYMENT : Config.INVENTORY;
            String path = switch (action) { case "refund" -> "/payments/refund"; case "return" -> "/stock/return"; default -> "/stock/release"; };
            try {
                ServiceClient.Result res = client.call(service, "POST", path, Json.obj("orderId", order.get("id")), null);
                if (!res.ok()) throw ApiError.unavailable(res.error());
                store.delete(RETRY, Json.lng(job, "id"));
                synchronized (this) {
                    Map<String, Object> fresh = store.get(ORDERS, Json.lng(order, "id"));
                    for (Object o : Json.list(fresh, "saga")) {
                        Map<String, Object> s = Json.map(o);
                        if ("RETRYING".equals(Json.str(s, "status")) && service.equals(Json.str(s, "service"))) {
                            s.put("status", "COMPENSATED");
                            s.put("note", "Done on automatic retry");
                            break;
                        }
                    }
                    store.update(ORDERS, fresh);
                }
            } catch (Exception e) {
                job.put("tries", Json.lng(job, "tries") + 1);
                store.update(RETRY, job);
            }
        }
    }

    private synchronized void step(Map<String, Object> order, String step, String service, String status, String note, long start, Req r) {
        long ms = System.currentTimeMillis() - start;
        Map<String, Object> fresh = store.get(ORDERS, Json.lng(order, "id"));
        Json.list(fresh, "saga").add(Json.obj("step", step, "service", service, "status", status, "note", note, "ms", ms, "at", start));
        store.update(ORDERS, fresh);
        order.put("saga", fresh.get("saga"));
        recordStep(r.traceId, "saga: " + step + " -> " + status, status.equals("FAILED") ? 409 : 200, start, ms);
    }

    private synchronized Map<String, Object> finish(Map<String, Object> order, String status, String reason, Req r) {
        Map<String, Object> fresh = store.get(ORDERS, Json.lng(order, "id"));
        fresh.put("status", status);
        fresh.put("cancelReason", reason);
        fresh.put("updatedAt", System.currentTimeMillis());
        Json.list(fresh, "history").add(Json.obj("status", status, "at", System.currentTimeMillis(), "by", Config.ORDER));
        Map<String, Object> saved = store.update(ORDERS, fresh);
        if (!status.equals("CONFIRMED")) {
            publish("order." + status.toLowerCase(), Json.obj("orderId", saved.get("id"), "orderNo", saved.get("orderNo"),
                    "userId", saved.get("userId"), "reason", reason), r.traceId);
        }
        return saved;
    }

    private static Map<String, Object> address(Map<String, Object> a) {
        String name = requireText(a, "name", "Name", 60);
        String phone = requireText(a, "phone", "Phone", 20).replaceAll("[\\s-]", "");
        if (!phone.matches("[6-9][0-9]{9}")) throw ApiError.bad("Phone must be a 10-digit Indian mobile number");
        String line = requireText(a, "line", "Address", 120);
        String city = requireText(a, "city", "City", 40);
        String pin = requireText(a, "pin", "PIN code", 6);
        if (!PIN.matcher(pin).matches()) throw ApiError.bad("PIN code must be 6 digits");
        return Json.obj("name", name, "phone", phone.substring(0, 5) + " " + phone.substring(5), "line", line, "city", city, "pin", pin);
    }

    // ======================================================================= after the order

    private Object cancel(Req r) {
        Map<String, Object> order = visible(r, order(r.paramLong("id")));
        String st = Json.str(order, "status");
        if (!st.equals("CONFIRMED") && !st.equals("PACKED")) {
            throw ApiError.conflict(st.equals("CANCELLED") ? "This order is already cancelled" : "An order that is " + st.toLowerCase() + " cannot be cancelled");
        }
        String reason = optText(r.json(), "reason", 120);
        // Money first: if the payment service is down we stop here and change nothing.
        long t = System.currentTimeMillis();
        ServiceClient.Result res = guarded(Config.PAYMENT, "POST", "/payments/refund", Json.obj("orderId", order.get("id")), r);
        ServiceClient.requireOk(Config.PAYMENT, res);
        step(order, "Refund payment", Config.PAYMENT, "COMPENSATED", "Cancelled - money returned to wallet", t, r);
        compensate(order, "return", r);
        Map<String, Object> fresh = store.get(ORDERS, Json.lng(order, "id"));
        fresh.put("status", "CANCELLED");
        fresh.put("cancelReason", reason.isEmpty() ? "Cancelled by " + (r.isAdmin() ? "the store" : "customer") : reason);
        fresh.put("updatedAt", System.currentTimeMillis());
        Json.list(fresh, "history").add(Json.obj("status", "CANCELLED", "at", System.currentTimeMillis(), "by", r.userName()));
        Map<String, Object> saved = store.update(ORDERS, fresh);
        publish("order.cancelled", Json.obj("orderId", saved.get("id"), "orderNo", saved.get("orderNo"), "userId", saved.get("userId"),
                "reason", saved.get("cancelReason"), "total", saved.get("total")), r.traceId);
        return saved;
    }

    private Object setStatus(Req r) {
        r.requireAdmin();
        String to = Json.str(r.json(), "status", "");
        Map<String, Object> saved;
        synchronized (this) {
            Map<String, Object> o = order(r.paramLong("id"));
            String from = Json.str(o, "status");
            int i = FLOW.indexOf(from);
            if (i < 0 || i + 1 >= FLOW.size() || !FLOW.get(i + 1).equals(to)) {
                String next = (i >= 0 && i + 1 < FLOW.size()) ? FLOW.get(i + 1) : "nothing";
                throw ApiError.conflict("An order that is " + from + " can only move to " + next);
            }
            o.put("status", to);
            o.put("updatedAt", System.currentTimeMillis());
            Json.list(o, "history").add(Json.obj("status", to, "at", System.currentTimeMillis(), "by", r.userName()));
            saved = store.update(ORDERS, o);
        }
        publish("order.status", Json.obj("orderId", saved.get("id"), "orderNo", saved.get("orderNo"), "userId", saved.get("userId"), "status", to), r.traceId);
        return saved;
    }

    // ======================================================================= lists and numbers

    private Object mine(Req r) {
        long uid = r.requireUser();
        List<Map<String, Object>> l = store.where(ORDERS, o -> Json.lng(o, "userId") == uid);
        l.sort(Comparator.comparingLong((Map<String, Object> o) -> Json.lng(o, "createdAt")).reversed());
        return l;
    }

    private Object all(Req r) {
        r.requireAdmin();
        String status = r.q("status", "");
        String q = r.q("q", "").toLowerCase();
        List<Map<String, Object>> l = store.where(ORDERS, o -> (status.isEmpty() || status.equals(Json.str(o, "status")))
                && (q.isEmpty() || (Json.str(o, "orderNo") + " " + Json.str(o, "userName")).toLowerCase().contains(q)));
        l.sort(Comparator.comparingLong((Map<String, Object> o) -> Json.lng(o, "createdAt")).reversed());
        return l;
    }

    private Object stats(Req r) {
        r.requireAdmin();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Map<String, Object> byStatus = new LinkedHashMap<>();
        for (String s : List.of("PENDING", "CONFIRMED", "PACKED", "SHIPPED", "DELIVERED", "CANCELLED", "REJECTED", "FAILED")) byStatus.put(s, 0L);
        long revToday = 0, rev7 = 0, rev30 = 0, paidCount = 0, paidSum = 0;
        Map<LocalDate, long[]> daily = new LinkedHashMap<>();
        for (int i = 13; i >= 0; i--) daily.put(today.minusDays(i), new long[2]);
        Map<String, long[]> top = new LinkedHashMap<>();
        Map<String, String> topIcon = new LinkedHashMap<>(), topImage = new LinkedHashMap<>();
        for (Map<String, Object> o : store.all(ORDERS)) {
            String st = Json.str(o, "status");
            byStatus.merge(st, 1L, (a, b) -> (long) a + (long) b);
            if (!FLOW.contains(st)) continue;
            long total = Json.lng(o, "total");
            LocalDate d = Instant.ofEpochMilli(Json.lng(o, "createdAt")).atZone(zone).toLocalDate();
            paidCount++;
            paidSum += total;
            if (d.equals(today)) revToday += total;
            if (!d.isBefore(today.minusDays(6))) rev7 += total;
            if (!d.isBefore(today.minusDays(29))) rev30 += total;
            long[] day = daily.get(d);
            if (day != null) { day[0]++; day[1] += total; }
            for (Object it : Json.list(o, "items")) {
                Map<String, Object> line = Json.map(it);
                long[] v = top.computeIfAbsent(Json.str(line, "name"), k -> new long[2]);
                v[0] += Json.lng(line, "qty");
                v[1] += Json.lng(line, "lineTotal");
                topIcon.put(Json.str(line, "name"), Json.str(line, "icon"));
                topImage.put(Json.str(line, "name"), Json.str(line, "image", ""));
            }
        }
        List<Object> days = new ArrayList<>();
        daily.forEach((d, v) -> days.add(Json.obj("date", d.toString(), "orders", v[0], "revenue", v[1])));
        List<Map.Entry<String, long[]>> topList = new ArrayList<>(top.entrySet());
        topList.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        List<Object> topOut = new ArrayList<>();
        for (Map.Entry<String, long[]> e : topList.subList(0, Math.min(5, topList.size()))) {
            topOut.add(Json.obj("name", e.getKey(), "icon", topIcon.get(e.getKey()), "image", topImage.get(e.getKey()), "qty", e.getValue()[0], "revenue", e.getValue()[1]));
        }
        return Json.obj("byStatus", byStatus, "revenueToday", revToday, "revenue7", rev7, "revenue30", rev30,
                "avgOrder", paidCount == 0 ? 0 : paidSum / paidCount, "daily", days, "top", topOut,
                "pendingCompensations", store.all(RETRY).size());
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
