package com.cecil.shop.inventory;

import com.cecil.shop.common.ApiError;
import com.cecil.shop.common.Config;
import com.cecil.shop.common.Json;
import com.cecil.shop.common.Req;
import com.cecil.shop.common.Resp;
import com.cecil.shop.common.Router;
import com.cecil.shop.common.ServiceBase;
import com.cecil.shop.seed.SeedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * INVENTORY SERVICE - owns stock: how many of each SKU are in the warehouse.
 *
 *   onHand    = items physically in the warehouse
 *   reserved  = items held for orders that are not finished yet
 *   available = onHand - reserved   (what a new customer can still buy)
 *
 * Order saga steps handled here:
 *   POST /stock/reserve  hold items for an order (all items or nothing)
 *   POST /stock/commit   order paid  -> items leave the warehouse
 *   POST /stock/release  order failed -> held items become available again (COMPENSATION)
 *   POST /stock/return   a confirmed order was cancelled -> items go back on the shelf
 * Every step is IDEMPOTENT: doing it twice for the same order has the same effect as doing it once.
 */
public class InventoryService extends ServiceBase {
    private static final String STOCK = "stock", RES = "reservations", MOVES = "movements";

    public InventoryService(Config cfg) {
        super(Config.INVENTORY, cfg);
        on("product.created", this::onProductCreated);
        on("product.updated", ev -> {
            synchronized (this) {
                Map<String, Object> s = bySku(Json.str(ev, "sku"));
                if (s != null) { s.put("name", ev.get("name")); store.update(STOCK, s); }
            }
        });
    }

    @Override public String role() { return "Stock levels and reservations"; }

    @Override
    protected void seed() {
        if (store.isEmpty(STOCK)) for (Map<String, Object> s : SeedData.stock()) store.insert(STOCK, s);
    }

    @Override
    protected void routes(Router r) {
        r.get("/stock", this::list);
        r.get("/stock/low", req -> { req.requireAdmin(); return withAvailable(store.where(STOCK, this::isLow)); });
        r.get("/stock/movements", req -> {
            req.requireAdmin();
            List<Map<String, Object>> m = store.all(MOVES);
            List<Object> out = new ArrayList<>();
            for (int i = m.size() - 1; i >= 0 && out.size() < 100; i--) out.add(m.get(i));
            return out;
        });
        r.post("/stock/reserve", this::reserve);
        r.post("/stock/commit", req -> commit(req, orderId(req)));
        r.post("/stock/release", req -> release(req, orderId(req)));
        r.post("/stock/return", req -> giveBack(req, orderId(req)));
        r.post("/stock/{sku}/adjust", this::adjust);
    }

    // ---------------------------------------------------------------- reading

    private Object list(Req r) {
        String skus = r.q("skus");
        if (skus != null) {
            // Light view used by the gateway when it shows the shop: sku -> available
            Map<String, Object> out = new LinkedHashMap<>();
            for (String sku : skus.split(",")) {
                Map<String, Object> s = bySku(sku.trim());
                if (s != null) out.put(sku.trim(), Json.obj("available", available(s), "low", isLow(s)));
            }
            return out;
        }
        r.requireAdmin();
        return withAvailable(store.all(STOCK));
    }

    private List<Object> withAvailable(List<Map<String, Object>> items) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> s : items) {
            s.put("available", available(s));
            s.put("low", isLow(s));
            out.add(s);
        }
        return out;
    }

    private Map<String, Object> bySku(String sku) {
        return store.findFirst(STOCK, s -> Json.str(s, "sku").equals(sku));
    }

    private static long available(Map<String, Object> s) { return Json.lng(s, "onHand") - Json.lng(s, "reserved"); }

    private boolean isLow(Map<String, Object> s) { return available(s) <= Json.lng(s, "lowAt"); }

    private static long orderId(Req r) {
        long id = Json.lng(r.json(), "orderId");
        if (id <= 0) throw ApiError.bad("orderId is required");
        return id;
    }

    private Map<String, Object> reservation(long orderId) {
        return store.findFirst(RES, x -> Json.lng(x, "orderId") == orderId);
    }

    // ---------------------------------------------------------------- saga steps

    private synchronized Object reserve(Req r) {
        Map<String, Object> b = r.json();
        long orderId = orderId(r);
        Map<String, Object> existing = reservation(orderId);
        if (existing != null) {
            if ("RELEASED".equals(Json.str(existing, "status"))) throw ApiError.conflict("This order's reservation was already released");
            return existing; // idempotent: same answer as the first time
        }
        Map<String, Long> wanted = new LinkedHashMap<>();
        for (Object o : Json.list(b, "items")) {
            Map<String, Object> it = Json.map(o);
            long qty = Json.lng(it, "qty");
            if (qty <= 0 || qty > 100) throw ApiError.bad("Bad quantity for " + Json.str(it, "sku"));
            wanted.merge(Json.str(it, "sku", ""), qty, Long::sum);
        }
        if (wanted.isEmpty()) throw ApiError.bad("No items to reserve");
        // Check EVERYTHING first. Only if all items are available do we hold any of them (all or nothing).
        List<Object> short_ = new ArrayList<>();
        for (Map.Entry<String, Long> w : wanted.entrySet()) {
            Map<String, Object> s = bySku(w.getKey());
            long avail = s == null ? 0 : available(s);
            if (avail < w.getValue()) {
                short_.add(Json.obj("sku", w.getKey(), "name", s == null ? w.getKey() : s.get("name"), "available", Math.max(0, avail), "wanted", w.getValue()));
            }
        }
        if (!short_.isEmpty()) {
            Map<String, Object> first = Json.map(short_.get(0));
            throw ApiError.conflict("Not enough stock: " + Json.str(first, "name") + " (only " + Json.lng(first, "available") + " left)");
        }
        List<Object> items = new ArrayList<>();
        for (Map.Entry<String, Long> w : wanted.entrySet()) {
            Map<String, Object> s = bySku(w.getKey());
            s.put("reserved", Json.lng(s, "reserved") + w.getValue());
            store.update(STOCK, s);
            items.add(Json.obj("sku", w.getKey(), "qty", w.getValue()));
        }
        return store.insert(RES, Json.obj("orderId", orderId, "orderNo", Json.str(b, "orderNo", ""), "items", items,
                "status", "HELD", "at", System.currentTimeMillis()));
    }

    private synchronized Object commit(Req r, long orderId) {
        Map<String, Object> res = reservation(orderId);
        if (res == null) throw ApiError.notFound("No reservation for this order");
        String st = Json.str(res, "status");
        if (st.equals("COMMITTED")) return res;
        if (!st.equals("HELD")) throw ApiError.conflict("Reservation is " + st);
        for (Object o : Json.list(res, "items")) {
            Map<String, Object> it = Json.map(o);
            Map<String, Object> s = bySku(Json.str(it, "sku"));
            long qty = Json.lng(it, "qty");
            boolean wasLow = isLow(s);
            s.put("onHand", Json.lng(s, "onHand") - qty);
            s.put("reserved", Math.max(0, Json.lng(s, "reserved") - qty));
            store.update(STOCK, s);
            move(Json.str(s, "sku"), -qty, "Sold - " + Json.str(res, "orderNo"), r);
            if (!wasLow && isLow(s)) {
                publish("stock.low", Json.obj("sku", s.get("sku"), "name", s.get("name"), "available", available(s)), r.traceId);
            }
        }
        res.put("status", "COMMITTED");
        return store.update(RES, res);
    }

    private synchronized Object release(Req r, long orderId) {
        Map<String, Object> res = reservation(orderId);
        if (res == null) return Json.obj("ok", true, "note", "Nothing was held for this order");
        if (!"HELD".equals(Json.str(res, "status"))) return res; // already released or committed
        for (Object o : Json.list(res, "items")) {
            Map<String, Object> it = Json.map(o);
            Map<String, Object> s = bySku(Json.str(it, "sku"));
            s.put("reserved", Math.max(0, Json.lng(s, "reserved") - Json.lng(it, "qty")));
            store.update(STOCK, s);
        }
        res.put("status", "RELEASED");
        return store.update(RES, res);
    }

    private synchronized Object giveBack(Req r, long orderId) {
        Map<String, Object> res = reservation(orderId);
        if (res == null) return Json.obj("ok", true, "note", "No stock record for this order");
        String st = Json.str(res, "status");
        if (st.equals("HELD")) return release(r, orderId);
        if (!st.equals("COMMITTED")) return res;
        for (Object o : Json.list(res, "items")) {
            Map<String, Object> it = Json.map(o);
            Map<String, Object> s = bySku(Json.str(it, "sku"));
            s.put("onHand", Json.lng(s, "onHand") + Json.lng(it, "qty"));
            store.update(STOCK, s);
            move(Json.str(s, "sku"), Json.lng(it, "qty"), "Returned - " + Json.str(res, "orderNo"), r);
        }
        res.put("status", "RETURNED");
        return store.update(RES, res);
    }

    // ---------------------------------------------------------------- admin

    private synchronized Object adjust(Req r) {
        r.requireAdmin();
        Map<String, Object> b = r.json();
        Map<String, Object> s = bySku(r.param("sku"));
        if (s == null) throw ApiError.notFound("SKU not found");
        long delta = Json.lng(b, "delta");
        if (delta == 0 || Math.abs(delta) > 10_000) throw ApiError.bad("Change must be between -10000 and 10000 (not 0)");
        String reason = requireText(b, "reason", "Reason", 80);
        long newOnHand = Json.lng(s, "onHand") + delta;
        if (newOnHand < Json.lng(s, "reserved")) throw ApiError.conflict("Cannot go below the " + Json.lng(s, "reserved") + " items held for open orders");
        s.put("onHand", newOnHand);
        if (b.containsKey("lowAt")) s.put("lowAt", Math.max(0, Math.min(1000, Json.lng(b, "lowAt"))));
        store.update(STOCK, s);
        move(Json.str(s, "sku"), delta, reason, r);
        s.put("available", available(s));
        return s;
    }

    private void move(String sku, long delta, String reason, Req r) {
        store.insert(MOVES, Json.obj("sku", sku, "delta", delta, "reason", reason, "by", r.userName().isEmpty() ? r.caller() : r.userName(),
                "at", System.currentTimeMillis()));
    }

    private synchronized void onProductCreated(Map<String, Object> ev) {
        String sku = Json.str(ev, "sku");
        if (bySku(sku) != null) return;
        long initial = Math.max(0, Json.lng(ev, "initialStock"));
        store.insert(STOCK, Json.obj("id", Json.lng(ev, "productId"), "sku", sku, "productId", Json.lng(ev, "productId"),
                "name", ev.get("name"), "onHand", initial, "reserved", 0L, "lowAt", 5L));
        if (initial > 0) store.insert(MOVES, Json.obj("sku", sku, "delta", initial, "reason", "Starting stock (from product.created event)",
                "by", "event-bus", "at", System.currentTimeMillis()));
    }

    /** For tests. */
    public synchronized Resp debugStock(String sku) { return new Resp(200, bySku(sku)); }
}
