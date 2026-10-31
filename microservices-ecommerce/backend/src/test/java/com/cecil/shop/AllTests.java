package com.cecil.shop;

import com.cecil.shop.common.ApiError;
import com.cecil.shop.common.CircuitBreaker;
import com.cecil.shop.common.Config;
import com.cecil.shop.common.Json;
import com.cecil.shop.common.JsonStore;
import com.cecil.shop.common.Passwords;
import com.cecil.shop.common.Req;
import com.cecil.shop.common.Router;
import com.cecil.shop.common.ServiceBase;
import com.cecil.shop.common.Tokens;
import com.cecil.shop.eventbus.EventBusService;
import com.cecil.shop.gateway.RateLimiter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * All tests in one file. No JUnit download needed: run-tests.bat / run-tests.sh.
 *
 * The first tests check small parts (JSON, passwords, tokens, circuit breaker...).
 * The rest start ALL 10 services on free ports (data kept in memory) and test the real
 * system through the API gateway, exactly like the browser does: sagas, events, failures.
 */
public final class AllTests {
    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    private static Config cfg;
    private static final Map<String, ServiceBase> services = new LinkedHashMap<>();
    private static String gw;

    public static void main(String[] args) throws Exception {
        System.out.println("\nBandar Bazaar - tests\n");

        // ---------------------------------------------------------------- unit tests
        test("JSON: write and read back, with escaping", () -> {
            Map<String, Object> m = Json.obj("name", "Chai \"special\" <b>", "n", 42L, "price", 12.5, "ok", true, "list", List.of(1L, "two"), "none", null);
            Map<String, Object> back = Json.parseObject(Json.write(m));
            eq("Chai \"special\" <b>", back.get("name"));
            eq(42L, back.get("n"));
            eq(12.5, back.get("price"));
            eq(true, back.get("ok"));
            eq(2, Json.list(back, "list").size());
            check(back.containsKey("none") && back.get("none") == null, "null kept");
            check(!Json.write(m).contains("<b>"), "angle brackets escaped");
            eq("\u20B9 \u00e9", Json.parse("\"\\u20b9 \\u00e9\""));
        });
        test("JSON: bad input is refused", () -> {
            for (String bad : new String[]{"{", "{\"a\":}", "[1,2", "{\"a\":1} x", "tru", "\"abc"}) {
                try { Json.parse(bad); throw new AssertionError("accepted: " + bad); } catch (IllegalArgumentException expected) { }
            }
        });
        test("Passwords: hashed with salt, verified, never stored plain", () -> {
            String h1 = Passwords.hash("shop123"), h2 = Passwords.hash("shop123");
            check(!h1.equals(h2), "same password gives different hashes (salt)");
            check(!h1.contains("shop123"), "plain text not stored");
            check(Passwords.matches("shop123", h1), "right password");
            check(!Passwords.matches("shop124", h1), "wrong password");
        });
        test("Tokens: signed, checked, tampering and expiry detected", () -> {
            long now = 1_000_000;
            String t = Tokens.sign(Json.obj("uid", 2L, "role", "CUSTOMER", "exp", now + 1000), "key-1");
            eq(2L, Tokens.verify(t, "key-1", now).get("uid"));
            check(Tokens.verify(t, "key-2", now) == null, "wrong key");
            check(Tokens.verify(t, "key-1", now + 2000) == null, "expired");
            String body = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    Json.write(Json.obj("uid", 2L, "role", "ADMIN", "exp", now + 1000)).getBytes());
            String forged = body + t.substring(t.indexOf('.'));
            check(Tokens.verify(forged, "key-1", now) == null, "role changed -> signature fails");
            check(Tokens.verify("garbage", "key-1", now) == null, "garbage");
        });
        test("Circuit breaker: CLOSED -> OPEN -> HALF_OPEN -> CLOSED", () -> {
            long[] clock = {0};
            CircuitBreaker b = new CircuitBreaker("x", 3, 10_000, () -> clock[0]);
            for (int i = 0; i < 3; i++) { check(b.allow(), "allowed while closed"); b.failure(); }
            eq(CircuitBreaker.State.OPEN, b.state());
            check(!b.allow(), "open refuses calls");
            clock[0] = 10_000;
            eq(CircuitBreaker.State.HALF_OPEN, b.state());
            check(b.allow(), "one trial call");
            check(!b.allow(), "only ONE trial at a time");
            b.failure();
            eq(CircuitBreaker.State.OPEN, b.state());
            clock[0] = 20_000;
            check(b.allow(), "trial again");
            b.success();
            eq(CircuitBreaker.State.CLOSED, b.state());
        });
        test("Rate limiter: token bucket empties and refills", () -> {
            long[] clock = {0};
            RateLimiter r = new RateLimiter(60, () -> clock[0]);
            for (int i = 0; i < 60; i++) check(r.allow("ip"), "request " + i);
            check(!r.allow("ip"), "61st refused");
            check(r.allow("other-ip"), "other visitors not affected");
            clock[0] = 2000; // 2 seconds = 2 new tokens
            check(r.allow("ip") && r.allow("ip") && !r.allow("ip"), "refilled 2");
        });
        test("Event topics and routes match correctly", () -> {
            check(ServiceBase.topicMatches("order.*", "order.confirmed"), "wildcard");
            check(!ServiceBase.topicMatches("order.*", "payment.captured"), "other topic");
            check(ServiceBase.topicMatches("stock.low", "stock.low"), "exact");
            Router r = new Router();
            r.get("/orders/{id}", q -> q.param("id"));
            Req q = new Req("GET", "/orders/42", null, new HashMap<>(), "", "t");
            eq("42", r.find(q).handle(q));
            expectStatus(405, () -> r.find(new Req("POST", "/orders/42", null, new HashMap<>(), "", "t")));
            expectStatus(404, () -> r.find(new Req("GET", "/nope", null, new HashMap<>(), "", "t")));
        });
        test("Data store: saved to disk and loaded again", () -> {
            Path dir = Files.createTempDirectory("shop-store");
            JsonStore s = new JsonStore(dir);
            long id = Json.lng(s.insert("things", Json.obj("name", "mug")), "id");
            s.insert("things", Json.obj("name", "lamp"));
            JsonStore again = new JsonStore(dir);
            eq("mug", Json.str(again.get("things", id), "name"));
            eq(3L, Json.lng(again.insert("things", Json.obj("name", "pot")), "id"));
        });

        test("Old demo data is replaced when the data version goes up", () -> {
            Path dir = Files.createTempDirectory("shop-old");
            JsonStore old = new JsonStore(dir);
            old.insert("products", Json.obj("name", "old product without photo"));
            eq(0L, old.dataVersion());
            old.resetTo(com.cecil.shop.seed.SeedData.VERSION);
            JsonStore again = new JsonStore(dir);
            eq(com.cecil.shop.seed.SeedData.VERSION, again.dataVersion());
            check(again.all("products").isEmpty(), "old records removed");
            eq(30, com.cecil.shop.seed.SeedData.PHOTOS.length);
        });

        // ---------------------------------------------------------------- start the whole system
        startSystem();
        Client guest = new Client(), riya = new Client(), arjun = new Client(), admin = new Client();

        test("Registry: all 9 services registered and UP", () -> {
            waitFor(() -> upCount() >= 9, 5000, "services registered");
            Res r = admin.login("admin", "admin123");
            eq(200, r.status);
            Res s = admin.get("/api/arch/services");
            eq(200, s.status);
            eq(10, Json.list(s.json(), "instances").size()); // 9 + the registry itself
        });
        test("Services refuse calls that skip the gateway (no internal key)", () -> {
            String catalog = services.get(Config.CATALOG).url();
            HttpResponse<String> r = raw("GET", catalog + "/products", null, Map.of());
            eq(401, r.statusCode());
            eq(200, raw("GET", catalog + "/health", null, Map.of()).statusCode());
        });
        test("Shop page is composed from catalog + inventory (no login needed)", () -> {
            Res r = guest.get("/api/shop/products?category=Books");
            eq(200, r.status);
            List<Object> p = Json.list(r.json(), "products");
            eq(4, p.size());
            check(Json.str(Json.map(p.get(0)), "image").startsWith("/static/img/products/"), "product has a photo path");
            eq(404, guest.get("/static/img/products/..%2F..%2Fdata%2Finternal.key").status);
            eq(404, guest.get("/static/img/products/not-there.jpg").status);
            check(Json.map(p.get(0)).get("available") instanceof Number, "stock added by gateway");
            eq(true, r.json().get("stockKnown"));
        });
        test("Login: HttpOnly cookie, token hidden from page scripts, wrong password refused", () -> {
            Res r = riya.login("riya", "shop123");
            eq(200, r.status);
            check(r.header("set-cookie").contains("HttpOnly"), "HttpOnly cookie");
            check(!r.json().containsKey("token"), "token not in the body");
            eq(401, guest.login("riya", "nope").status);
            eq(401, guest.get("/api/orders").status);
            eq("Riya Sharma", Json.str(Json.map(riya.get("/api/session").json(), "user"), "name"));
        });
        test("Login lock-out after 5 wrong passwords", () -> {
            Client c = new Client();
            for (int i = 0; i < 5; i++) eq(401, c.login("meera", "wrong").status);
            eq(429, c.login("meera", "shop123").status);
        });
        test("Fake identity headers are ignored; customers cannot open admin or monitoring", () -> {
            Res r = riya.send("GET", "/api/orders/stats", null, Map.of("X-User-Role", "ADMIN", "X-User-Id", "1"));
            eq(403, r.status);
            eq(403, riya.send("POST", "/api/products", Json.obj("name", "x"), Map.of()).status);
            eq(403, riya.get("/api/arch/services").status); // monitoring console is for the admin only
            eq(403, riya.get("/api/arch/traces").status);
        });
        test("Internal APIs cannot be reached through the gateway", () -> {
            eq(404, riya.send("POST", "/api/payments/charge", Json.obj("orderId", 1, "userId", 2, "amount", 1), Map.of()).status);
            eq(404, riya.send("POST", "/api/stock/release", Json.obj("orderId", 1), Map.of()).status);
            eq(403, riya.send("POST", "/api/cart/items", Json.obj("productId", 1), Map.of("Origin", "http://evil.example")).status);
        });
        test("Cart: price comes from the catalog, quantity limits enforced", () -> {
            riya.del("/api/cart");
            Res r = riya.post("/api/cart/items", Json.obj("productId", 21, "qty", 2));
            eq(200, r.status);
            eq(109_800L, Json.lng(r.json(), "subtotal"));
            eq(400, riya.post("/api/cart/items", Json.obj("productId", 21, "qty", 11)).status);
            eq(404, riya.post("/api/cart/items", Json.obj("productId", 999, "qty", 1)).status);
        });

        test("Saga success: stock taken, money taken, cart emptied and inbox message by events", () -> {
            long walletBefore = Json.lng(riya.get("/api/wallet").json(), "balance");
            long stockBefore = available(admin, "BOK-BK-21");
            Res r = riya.post("/api/orders", order("key-ok-1"));
            eq(201, r.status);
            Map<String, Object> o = r.json();
            eq("CONFIRMED", o.get("status"));
            eq(3, Json.list(o, "saga").size());
            eq(walletBefore - Json.lng(o, "total"), Json.lng(riya.get("/api/wallet").json(), "balance"));
            eq(stockBefore - 2, available(admin, "BOK-BK-21"));
            waitFor(() -> Json.list(riya.get("/api/cart").json(), "items").isEmpty(), 3000, "cart emptied by order.confirmed");
            waitFor(() -> Json.list(riya.get("/api/notifications").json(), "items").stream()
                    .anyMatch(n -> Json.str(Json.map(n), "title").contains("confirmed")), 3000, "notification");
        });
        test("Idempotency: same key again returns the same order, no second charge", () -> {
            riya.post("/api/cart/items", Json.obj("productId", 14, "qty", 1));
            long wallet = Json.lng(riya.get("/api/wallet").json(), "balance");
            Res again = riya.post("/api/orders", order("key-ok-1"));
            eq(200, again.status);
            eq(true, again.json().get("repeat"));
            eq(wallet, Json.lng(riya.get("/api/wallet").json(), "balance"));
            riya.del("/api/cart");
        });
        test("Saga payment failure: stock hold is undone (compensation)", () -> {
            arjun.login("arjun", "shop123");
            arjun.post("/api/cart/items", Json.obj("productId", 4, "qty", 1)); // laptop, wallet too small
            long before = available(admin, "ELC-LP-04");
            Map<String, Object> o = arjun.post("/api/orders", order("arjun-1")).json();
            eq("FAILED", o.get("status"));
            List<Object> saga = Json.list(o, "saga");
            eq("OK", Json.str(Json.map(saga.get(0)), "status"));
            eq("FAILED", Json.str(Json.map(saga.get(1)), "status"));
            eq("COMPENSATED", Json.str(Json.map(saga.get(2)), "status"));
            eq(before, available(admin, "ELC-LP-04"));
            eq(120_000L, Json.lng(arjun.get("/api/wallet").json(), "balance"));
            check(!Json.list(arjun.get("/api/cart").json(), "items").isEmpty(), "cart kept so the customer can retry");
            arjun.del("/api/cart");
        });
        test("Saga out of stock: order REJECTED, nothing held", () -> {
            riya.post("/api/cart/items", Json.obj("productId", 19, "qty", 5)); // only 2 in stock
            Map<String, Object> o = riya.post("/api/orders", order("oos-1")).json();
            eq("REJECTED", o.get("status"));
            check(Json.str(o, "cancelReason").contains("Not enough stock"), "reason given");
            eq(2L, available(admin, "HOM-SP-19"));
            riya.del("/api/cart");
        });
        test("Cancel: refund + stock back on the shelf + message", () -> {
            riya.post("/api/cart/items", Json.obj("productId", 26, "qty", 1));
            Map<String, Object> o = riya.post("/api/orders", order("cancel-1")).json();
            long wallet = Json.lng(riya.get("/api/wallet").json(), "balance");
            long stock = available(admin, "SPT-BL-26");
            Res c = riya.post("/api/orders/" + o.get("id") + "/cancel", Json.obj("reason", "test"));
            eq(200, c.status);
            eq("CANCELLED", c.json().get("status"));
            eq(wallet + Json.lng(o, "total"), Json.lng(riya.get("/api/wallet").json(), "balance"));
            eq(stock + 1, available(admin, "SPT-BL-26"));
            eq(409, riya.post("/api/orders/" + o.get("id") + "/cancel", Json.obj()).status);
        });
        test("Admin moves orders forward only; customer is told by event", () -> {
            riya.post("/api/cart/items", Json.obj("productId", 17, "qty", 1));
            long id = Json.lng(riya.post("/api/orders", order("ship-1")).json(), "id");
            eq(409, admin.put("/api/orders/" + id + "/status", Json.obj("status", "DELIVERED")).status);
            eq(200, admin.put("/api/orders/" + id + "/status", Json.obj("status", "PACKED")).status);
            eq(200, admin.put("/api/orders/" + id + "/status", Json.obj("status", "SHIPPED")).status);
            eq(403, riya.put("/api/orders/" + id + "/status", Json.obj("status", "DELIVERED")).status);
            waitFor(() -> Json.list(riya.get("/api/notifications").json(), "items").stream()
                    .anyMatch(n -> Json.str(Json.map(n), "title").contains("shipped")), 3000, "shipped message");
            eq(404, arjun.get("/api/orders/" + id).status); // someone else's order is invisible
        });
        test("New product: catalog publishes product.created, inventory creates stock", () -> {
            Res r = admin.post("/api/products", Json.obj("name", "Test Kettle", "sku", "HOM-KT-99", "category", "Home & Kitchen",
                    "price", 999, "mrp", 1299, "icon", "pot", "initialStock", 12));
            eq(201, r.status);
            waitFor(() -> available(admin, "HOM-KT-99") == 12, 3000, "stock record from event");
            eq(409, admin.post("/api/products", Json.obj("name", "Dup", "sku", "HOM-KT-99", "category", "Books", "price", 10)).status);
            eq(400, admin.post("/api/products", Json.obj("name", "Bad", "sku", "BAD-1", "category", "Books", "price", 100, "mrp", 50)).status);
        });
        test("Register: payment opens a wallet with the welcome bonus (event)", () -> {
            Client neo = new Client();
            eq(201, neo.post("/api/auth/register", Json.obj("username", "neo_1", "name", "Neo Tirkey", "email", "neo@example.test", "password", "secret1")).status);
            waitFor(() -> Json.lng(neo.get("/api/wallet").json(), "balance") == 1_000_000L, 3000, "welcome bonus");
            eq(409, new Client().post("/api/auth/register", Json.obj("username", "neo_1", "name", "X", "email", "x@example.test", "password", "secret1")).status);
        });
        test("Payment charges only once per order and only for the order service", () -> {
            String pay = services.get(Config.PAYMENT).url();
            Map<String, String> h = Map.of("X-Internal-Key", cfg.internalKey, "X-Caller", Config.ORDER);
            String body = Json.write(Json.obj("orderId", 9001, "orderNo", "T-1", "userId", 4, "amount", 10_000));
            long before = Json.lng(Json.parseObject(raw("GET", pay + "/wallet", null, Map.of("X-Internal-Key", cfg.internalKey, "X-User-Id", "4")).body()), "balance");
            eq(200, raw("POST", pay + "/payments/charge", body, h).statusCode());
            eq(200, raw("POST", pay + "/payments/charge", body, h).statusCode());
            long after = Json.lng(Json.parseObject(raw("GET", pay + "/wallet", null, Map.of("X-Internal-Key", cfg.internalKey, "X-User-Id", "4")).body()), "balance");
            eq(before - 10_000, after);
            eq(403, raw("POST", pay + "/payments/charge", body, Map.of("X-Internal-Key", cfg.internalKey, "X-Caller", Config.CART)).statusCode());
        });

        // ---------------------------------------------------------------- failures (chaos)
        test("Catalog down: gateway serves its saved copy (fallback cache)", () -> {
            eq(200, guest.get("/api/shop/products?category=Beauty").status);
            services.get(Config.CATALOG).setChaos(true, 0);
            try {
                Res r = guest.get("/api/shop/products?category=Beauty");
                eq(200, r.status);
                eq("cache", r.header("x-fallback"));
                eq(503, guest.get("/api/shop/products?category=Fashion").status); // never cached
            } finally {
                services.get(Config.CATALOG).setChaos(false, 0);
                admin.post("/api/arch/breakers/reset", Json.obj());
            }
        });
        test("Inventory down: shop still lists products, stock shown as unknown", () -> {
            services.get(Config.INVENTORY).setChaos(true, 0);
            try {
                Res r = guest.get("/api/shop/products?category=Books");
                eq(200, r.status);
                eq(false, r.json().get("stockKnown"));
                check(Json.map(Json.list(r.json(), "products").get(0)).get("available") == null, "unknown stock");
            } finally {
                services.get(Config.INVENTORY).setChaos(false, 0);
                admin.post("/api/arch/breakers/reset", Json.obj());
            }
        });
        test("Payment down: order fails safely and reserved stock is released", () -> {
            riya.post("/api/cart/items", Json.obj("productId", 15, "qty", 1));
            long stock = available(admin, "HOM-LP-15");
            long wallet = Json.lng(riya.get("/api/wallet").json(), "balance");
            services.get(Config.PAYMENT).setChaos(true, 0);
            Map<String, Object> o;
            try {
                o = riya.post("/api/orders", order("paydown-1")).json();
            } finally {
                services.get(Config.PAYMENT).setChaos(false, 0);
            }
            eq("FAILED", o.get("status"));
            eq(stock, available(admin, "HOM-LP-15"));
            eq(wallet, Json.lng(riya.get("/api/wallet").json(), "balance"));
            riya.del("/api/cart");
        });
        test("Event bus down: events wait in the outbox and are delivered later", () -> {
            ServiceBase bus = services.get(Config.EVENT_BUS);
            bus.setChaos(true, 0);
            Client late = new Client();
            try {
                eq(201, late.post("/api/auth/register", Json.obj("username", "late_one", "name", "Late One", "email", "late@example.test", "password", "secret1")).status);
                Thread.sleep(600);
                check(services.get(Config.AUTH).outboxSize() == 1, "event waiting in auth's outbox");
                eq(0L, Json.lng(late.get("/api/wallet").json(), "balance"));
            } finally {
                bus.setChaos(false, 0);
            }
            waitFor(() -> services.get(Config.AUTH).outboxSize() == 0, 4000, "outbox emptied");
            waitFor(() -> Json.lng(late.get("/api/wallet").json(), "balance") == 1_000_000L, 4000, "wallet created after bus came back");
        });
        test("Receiver down: bus retries, then dead-letter; replay delivers it", () -> {
            ServiceBase notes = services.get(Config.NOTIFICATION);
            notes.setChaos(true, 0);
            riya.post("/api/cart/items", Json.obj("productId", 29, "qty", 1));
            long id;
            try {
                id = Json.lng(riya.post("/api/orders", order("dead-1")).json(), "id");
                waitFor(() -> !Json.list(admin.get("/api/arch/events").json(), "deadLetters").isEmpty(), 5000, "dead letter");
            } finally {
                notes.setChaos(false, 0);
            }
            String orderNo = Json.str(riya.get("/api/orders/" + id).json(), "orderNo");
            Map<String, Object> dl = Json.map(Json.list(admin.get("/api/arch/events").json(), "deadLetters").get(0));
            eq(200, admin.post("/api/arch/dead-letters/" + Json.str(dl, "id") + "/replay", Json.obj()).status);
            waitFor(() -> Json.list(riya.get("/api/notifications").json(), "items").stream()
                    .anyMatch(n -> Json.str(Json.map(n), "title").contains(orderNo)), 4000, "message after replay");
        });
        test("Tracing: one order shows spans from many services under one trace id", () -> {
            riya.post("/api/cart/items", Json.obj("productId", 30, "qty", 1));
            Res r = riya.post("/api/orders", order("trace-1"));
            String trace = r.header("x-trace-id");
            check(trace != null && trace.equals(Json.str(r.json(), "traceId")), "trace id returned and stored");
            Thread.sleep(500);
            Map<String, Object> t = admin.get("/api/arch/traces/" + trace).json();
            java.util.Set<String> svcs = new java.util.HashSet<>();
            for (Object s : Json.list(t, "spans")) svcs.add(Json.str(Json.map(s), "service"));
            for (String s : List.of(Config.GATEWAY, Config.ORDER, Config.CART, Config.CATALOG, Config.INVENTORY, Config.PAYMENT)) check(svcs.contains(s), "span from " + s);
            eq(403, riya.post("/api/arch/chaos", Json.obj("service", Config.CART, "down", true)).status);
        });
        test("Registry marks a silent service DOWN, and UP again when it returns", () -> {
            ServiceBase cart = services.get(Config.CART);
            cart.setChaos(true, 0);
            try {
                waitFor(() -> statusOf(Config.CART).equals("DOWN"), 4000, "marked DOWN");
            } finally {
                cart.setChaos(false, 0);
            }
            waitFor(() -> statusOf(Config.CART).equals("UP"), 4000, "back UP");
        });

        for (ServiceBase s : services.values()) s.stop();
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        for (String f : failures) System.out.println("  FAILED: " + f);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ==================================================================== system setup

    private static void startSystem() {
        cfg = new Config(null, Passwords.randomKey(32));
        for (String s : Config.DEFAULT_PORTS.keySet()) cfg.ports.put(s, 0); // any free port
        cfg.heartbeatMs = 300;
        cfg.instanceTtlMs = 1200;
        cfg.discoveryCacheMs = 100;
        cfg.callTimeoutMs = 3000;
        cfg.gatewayRatePerMinute = 100_000;
        cfg.loginRatePerMinute = 1000;
        ServiceBase reg = App.create(Config.REGISTRY, cfg, null).start();
        cfg.registryUrl = reg.url();
        services.put(Config.REGISTRY, reg);
        for (String name : Config.DEFAULT_PORTS.keySet()) {
            if (name.equals(Config.REGISTRY)) continue;
            ServiceBase s = App.create(name, cfg, App.projectRoot().resolve("frontend"));
            if (s instanceof EventBusService bus) bus.retryScale = 20;
            services.put(name, s.start());
        }
        gw = services.get(Config.GATEWAY).url();
        waitFor(() -> upCount() >= 9 && services.values().stream().allMatch(ServiceBase::allSubscribed), 8000, "system ready");
    }

    private static long upCount() {
        try {
            Map<String, Object> m = Json.parseObject(raw("GET", cfg.registryUrl + "/services", null, Map.of("X-Internal-Key", cfg.internalKey)).body());
            return Json.list(m, "instances").stream().filter(i -> "UP".equals(Json.str(Json.map(i), "status"))).count();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String statusOf(String name) {
        Map<String, Object> m = Json.parseObject(raw("GET", cfg.registryUrl + "/services/" + name, null, Map.of("X-Internal-Key", cfg.internalKey)).body());
        List<Object> l = Json.list(m, "instances");
        return l.isEmpty() ? "GONE" : Json.str(Json.map(l.get(0)), "status");
    }

    private static Map<String, Object> order(String key) {
        return Json.obj("idempotencyKey", key, "address", Json.obj("name", "Riya", "phone", "98765 43210", "line", "14 Lake View Road", "city", "Ranchi", "pin", "834008"));
    }

    private static long available(Client admin, String sku) {
        for (Object o : admin.get("/api/stock").jsonList()) {
            Map<String, Object> s = Json.map(o);
            if (sku.equals(Json.str(s, "sku"))) return Json.lng(s, "available");
        }
        return -1;
    }

    // ==================================================================== tiny HTTP client with cookies

    private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(2)).build();

    private static HttpResponse<String> raw(String method, String url, String body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json");
            headers.forEach(b::header);
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Res(int status, Object body, HttpResponse<String> http) {
        Map<String, Object> json() { return Json.map(body); }
        List<Object> jsonList() { return Json.list(body); }
        String header(String n) { return http.headers().firstValue(n).orElse(null); }
    }

    private static final class Client {
        String cookie;

        Res send(String method, String path, Object body, Map<String, String> extra) {
            Map<String, String> h = new HashMap<>(extra);
            if (cookie != null) h.put("Cookie", cookie);
            HttpResponse<String> r = raw(method, gw + path, body == null ? null : Json.write(body), h);
            r.headers().firstValue("set-cookie").ifPresent(c -> cookie = c.split(";")[0]);
            Object parsed;
            try { parsed = Json.parse(r.body()); } catch (IllegalArgumentException e) { parsed = null; }
            return new Res(r.statusCode(), parsed, r);
        }
        Res get(String p) { return send("GET", p, null, Map.of()); }
        Res post(String p, Object b) { return send("POST", p, b, Map.of()); }
        Res put(String p, Object b) { return send("PUT", p, b, Map.of()); }
        Res del(String p) { return send("DELETE", p, null, Map.of()); }
        Res login(String u, String pw) { return post("/api/auth/login", Json.obj("username", u, "password", pw)); }
    }

    // ==================================================================== mini test framework

    @FunctionalInterface
    private interface Body { void run() throws Exception; }

    private static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + " -> " + t.getMessage());
            System.out.println("  FAIL  " + name + "\n        " + t);
        }
    }

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError("expected: " + what); }

    private static void eq(Object expected, Object actual) {
        boolean same = expected == null ? actual == null
                : (expected instanceof Number e && actual instanceof Number a && !(expected instanceof Double))
                ? e.longValue() == a.longValue() && !(actual instanceof Double d && d % 1 != 0)
                : expected.equals(actual);
        if (!same) throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
    }

    private static void expectStatus(int status, Body b) throws Exception {
        try { b.run(); } catch (ApiError e) { eq(status, e.status); return; }
        throw new AssertionError("expected error " + status);
    }

    private static void waitFor(BooleanSupplier cond, long ms, String what) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            try { if (cond.getAsBoolean()) return; } catch (Exception ignored) { }
            try { Thread.sleep(60); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("timed out waiting for: " + what);
    }
}
