package com.cecil.shop.gateway;

import com.cecil.shop.common.ApiError;
import com.cecil.shop.common.CircuitBreaker;
import com.cecil.shop.common.Config;
import com.cecil.shop.common.Json;
import com.cecil.shop.common.Req;
import com.cecil.shop.common.Resp;
import com.cecil.shop.common.Router;
import com.cecil.shop.common.ServiceBase;
import com.cecil.shop.common.ServiceClient;
import com.cecil.shop.common.Tokens;
import com.cecil.shop.seed.SeedData;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * API GATEWAY - the ONLY door the browser uses. (Like Spring Cloud Gateway, Kong or NGINX.)
 *
 * The browser only knows http://localhost:8080. The gateway:
 *  1. serves the website files (frontend folder)
 *  2. ROUTES each /api/... call to the right service ("/api/orders" -> order-service)
 *  3. checks the login token ONCE, here, and passes the user on in X-User-* headers
 *  4. checks the ROLE (only the admin may change products, see all orders ...)
 *  5. RATE LIMITS each visitor
 *  6. wraps every service in a CIRCUIT BREAKER, and for GET requests keeps the last good answer
 *     as a FALLBACK when a service is down
 *  7. starts a TRACE ID for every request, so the whole journey can be seen later
 *  8. COMPOSES answers from several services (shop page = catalog + inventory)
 *  9. powers the admin monitoring console (services, traces, events, chaos switches)
 */
public class GatewayService extends ServiceBase {
    public static final String COOKIE = "shop_token";

    /** URL prefix -> service that owns it. */
    private static final Map<String, String> ROUTES = new LinkedHashMap<>();
    static {
        ROUTES.put("/api/auth/", Config.AUTH);
        ROUTES.put("/api/products", Config.CATALOG);
        ROUTES.put("/api/categories", Config.CATALOG);
        ROUTES.put("/api/stock", Config.INVENTORY);
        ROUTES.put("/api/cart", Config.CART);
        ROUTES.put("/api/orders", Config.ORDER);
        ROUTES.put("/api/payments", Config.PAYMENT);
        ROUTES.put("/api/wallet", Config.PAYMENT);
        ROUTES.put("/api/notifications", Config.NOTIFICATION);
    }

    /** Service-to-service APIs that the browser must never reach directly. */
    private static final List<String> INTERNAL_ONLY = List.of("/api/payments/charge", "/api/payments/refund", "/api/stock/reserve",
            "/api/stock/commit", "/api/stock/release", "/api/stock/return", "/api/cart/raw", "/api/orders/breakers");

    private static final Set<String> CHAOS_ALLOWED = Set.of(Config.EVENT_BUS, Config.AUTH, Config.CATALOG, Config.INVENTORY,
            Config.CART, Config.ORDER, Config.PAYMENT, Config.NOTIFICATION);

    private final Path frontendDir;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, String> fallbackCache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 300; }
    });
    private final RateLimiter limiter, loginLimiter;
    private final ExecutorService fanOut = Executors.newFixedThreadPool(12, r -> { Thread t = new Thread(r, "gateway-fanout"); t.setDaemon(true); return t; });

    public GatewayService(Config cfg, Path frontendDir) {
        super(Config.GATEWAY, cfg);
        this.frontendDir = frontendDir;
        this.limiter = new RateLimiter(cfg.gatewayRatePerMinute);
        this.loginLimiter = new RateLimiter(cfg.loginRatePerMinute);
        for (String s : new LinkedHashSet<>(ROUTES.values())) breakers.put(s, new CircuitBreaker(s));
    }

    @Override public String role() { return "Front door: routing, login check, limits, fallbacks"; }

    @Override protected boolean isPublic(String path) { return !path.startsWith("/internal/") && !path.equals("/events"); }

    @Override protected boolean traced(String path) {
        return path.startsWith("/api/") && !path.startsWith("/api/arch") && !path.equals("/api/session") && !path.startsWith("/api/notifications");
    }

    @Override
    protected void addHeaders(com.sun.net.httpserver.Headers h) {
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https:; "
                + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", "no-store");
    }

    // ============================================================== gateway's own routes

    @Override
    protected void routes(Router r) {
        r.get("/api/session", this::session);
        r.post("/api/auth/logout", req -> new Resp(200, Json.obj("ok", true)).header("Set-Cookie", cookie("", 0)));
        r.get("/api/shop/products", this::shopProducts);
        r.get("/api/shop/products/{id}", this::shopProduct);
        r.get("/api/admin/overview", this::overview);
        r.get("/api/arch/services", this::archServices);
        r.get("/api/arch/traces", this::archTraces);
        r.get("/api/arch/traces/{id}", this::archTrace);
        r.get("/api/arch/events", this::archEvents);
        r.post("/api/arch/dead-letters/{id}/replay", req -> ok(client.call(Config.EVENT_BUS, "POST",
                "/dead-letters/" + enc(req.param("id")) + "/replay", null, req)));
        r.post("/api/arch/chaos", this::chaos);
        r.post("/api/arch/breakers/reset", req -> { breakers.values().forEach(CircuitBreaker::reset); fallbackCache.clear(); return Json.obj("ok", true); });
    }

    // ============================================================== entry point for every request

    @Override
    protected Object dispatch(Req r) throws Exception {
        if (r.path.equals("/") || r.path.equals("/index.html")) return file("index.html");
        if (r.path.startsWith("/static/")) return file(r.path.substring("/static/".length()));
        if (!r.path.startsWith("/api/")) throw ApiError.notFound("Page not found");

        // Never trust identity headers sent by the browser. Only the gateway sets them, after checking the token.
        r.headers.keySet().removeIf(k -> k.startsWith("x-user-") || k.equals("x-internal-key") || k.equals("x-caller"));

        boolean login = r.method.equals("POST") && (r.path.equals("/api/auth/login") || r.path.equals("/api/auth/register"));
        if (!limiter.allow(r.remoteIp) || (login && !loginLimiter.allow(r.remoteIp))) {
            return new Resp(429, Json.obj("error", "Too many requests. Please slow down and try again in a minute.")).header("Retry-After", "30");
        }
        if (!r.method.equals("GET") && !r.method.equals("HEAD")) {
            String origin = r.header("origin"), host = r.header("host");
            if (origin != null && host != null && !origin.equals("http://" + host)) throw ApiError.forbidden("Request from another website was blocked");
        }

        Map<String, Object> user = Tokens.verify(cookieValue(r.header("cookie")), cfg.internalKey, System.currentTimeMillis());
        if (user != null) {
            r.headers.put("x-user-id", String.valueOf(Json.lng(user, "uid")));
            r.headers.put("x-user-role", Json.str(user, "role"));
            r.headers.put("x-user-name", Json.str(user, "name"));
        }
        for (String p : INTERNAL_ONLY) if (r.path.startsWith(p)) throw ApiError.notFound("This API is internal and cannot be called through the gateway");
        checkAccess(r, user != null);
        if (r.path.startsWith("/api/shop/") || r.path.startsWith("/api/admin/") || r.path.startsWith("/api/arch/")
                || r.path.equals("/api/session") || r.path.equals("/api/auth/logout")) {
            return router.find(r).handle(r);
        }
        for (Map.Entry<String, String> route : ROUTES.entrySet()) {
            if (r.path.startsWith(route.getKey())) return proxy(r, route.getValue(), login);
        }
        throw ApiError.notFound("No such API: " + r.path);
    }

    /** Who may call what. Services check again, but the gateway stops bad calls early. */
    private static void checkAccess(Req r, boolean signedIn) {
        String p = r.path, m = r.method;
        boolean read = m.equals("GET");
        boolean open = (read && (p.startsWith("/api/shop/") || p.startsWith("/api/products") || p.equals("/api/categories") || p.equals("/api/session")))
                || (m.equals("POST") && (p.equals("/api/auth/login") || p.equals("/api/auth/register") || p.equals("/api/auth/logout")));
        if (open) return;
        if (!signedIn) throw new ApiError(401, "Please sign in first");
        // The monitoring console (/api/arch: services, traces, events, chaos) is a staff tool, so it is admin-only too.
        boolean adminOnly = p.startsWith("/api/admin/") || p.startsWith("/api/stock") || p.equals("/api/auth/users") || p.startsWith("/api/arch/")
                || (!read && p.startsWith("/api/products"))
                || (read && (p.equals("/api/payments") || p.equals("/api/orders/all") || p.equals("/api/orders/stats")))
                || (m.equals("PUT") && p.matches("/api/orders/\\d+/status"));
        if (adminOnly && !r.isAdmin()) throw ApiError.forbidden("Only the store admin can do this");
    }

    // ============================================================== proxying with circuit breaker + fallback

    private Object proxy(Req r, String service, boolean login) {
        String path = encodePath(r.path.substring(4)); // "/api/orders/3" -> "/orders/3"
        String qs = query(r);
        String cacheKey = service + " " + path + qs + " u" + r.userId();
        CircuitBreaker b = breakers.get(service);
        boolean isGet = r.method.equals("GET");
        if (!b.allow()) return fallback(isGet, cacheKey, service + " is having problems, so the gateway is not calling it for a few seconds (circuit OPEN)");
        ServiceClient.Result res;
        try {
            String base = discovery.pick(service);
            res = client.callUrl(base, r.method, path + qs, r.rawBody.isEmpty() ? null : r.rawBody, r, r.traceId, cfg.callTimeoutMs + 2000, service);
        } catch (ApiError e) {
            b.failure();
            return fallback(isGet, cacheKey, e.getMessage());
        }
        if (res.status() >= 500) {
            b.failure();
            if (isGet && fallbackCache.containsKey(cacheKey)) return fallback(true, cacheKey, res.error());
        } else {
            b.success();
        }
        Object body = res.body();
        Resp out = new Resp(res.status(), body).header("X-Route", service);
        if (isGet && res.status() == 200) fallbackCache.put(cacheKey, Json.write(body));
        if (login && res.ok()) {
            // Put the token in an HttpOnly cookie: page scripts cannot read it, so XSS cannot steal it.
            Map<String, Object> m = Json.map(body);
            String token = Json.str(m, "token");
            m.remove("token");
            out = new Resp(res.status(), m).header("X-Route", service).header("Set-Cookie", cookie(token, 8 * 3600));
        }
        return out;
    }

    private Object fallback(boolean isGet, String key, String why) {
        String cached = isGet ? fallbackCache.get(key) : null;
        if (cached != null) {
            return new Resp(200, Json.parse(cached)).header("X-Fallback", "cache").header("X-Fallback-Reason", ascii(why));
        }
        throw ApiError.unavailable(why);
    }

    private static Object ok(ServiceClient.Result res) {
        return new Resp(res.status(), res.body());
    }

    // ============================================================== API composition (aggregator)

    /** Shop page = products (catalog-service) + stock (inventory-service), joined here. */
    private Object shopProducts(Req r) {
        String qs = query(r);
        ServiceClient.Result cat = guarded(Config.CATALOG, "GET", "/products" + qs, r);
        boolean fromCache = false;
        List<Object> products;
        if (cat == null) {
            String cached = fallbackCache.get("shop" + qs);
            if (cached == null) throw ApiError.unavailable("The product catalog is not available right now. Please try again in a moment.");
            products = Json.list(Json.parse(cached));
            fromCache = true;
        } else {
            products = cat.jsonList();
            fallbackCache.put("shop" + qs, Json.write(products));
        }
        boolean stockKnown = addStock(products, r);
        Resp out = new Resp(200, Json.obj("products", products, "stockKnown", stockKnown, "fromCache", fromCache,
                "composedFrom", fromCache ? List.of("gateway cache", stockKnown ? Config.INVENTORY : "") : List.of(Config.CATALOG, stockKnown ? Config.INVENTORY : "")));
        return fromCache ? out.header("X-Fallback", "cache") : out;
    }

    private Object shopProduct(Req r) {
        long id = r.paramLong("id");
        ServiceClient.Result cat = guarded(Config.CATALOG, "GET", "/products/" + id, r);
        if (cat == null) throw ApiError.unavailable("The product catalog is not available right now.");
        if (!cat.ok()) throw new ApiError(cat.status(), cat.error());
        Map<String, Object> p = cat.json();
        List<Object> one = new ArrayList<>(List.of(p));
        boolean stockKnown = addStock(one, r);
        List<Object> related = new ArrayList<>();
        ServiceClient.Result rel = guarded(Config.CATALOG, "GET", "/products?category=" + enc(Json.str(p, "category")) + "&sort=rating", r);
        if (rel != null && rel.ok()) {
            for (Object o : rel.jsonList()) if (Json.lng(Json.map(o), "id") != id && related.size() < 4) related.add(o);
        }
        return Json.obj("product", p, "stockKnown", stockKnown, "related", related);
    }

    /** Adds "available" to each product. If inventory is down, the shop still works (stock shown as unknown). */
    private boolean addStock(List<Object> products, Req r) {
        if (products.isEmpty()) return true;
        StringBuilder skus = new StringBuilder();
        for (Object o : products) skus.append(skus.length() == 0 ? "" : ",").append(Json.str(Json.map(o), "sku"));
        ServiceClient.Result st = guarded(Config.INVENTORY, "GET", "/stock?skus=" + enc(skus.toString()), r);
        if (st == null || !st.ok()) {
            for (Object o : products) Json.map(o).put("available", null);
            return false;
        }
        Map<String, Object> stock = st.json();
        for (Object o : products) {
            Map<String, Object> p = Json.map(o);
            Map<String, Object> s = Json.map(stock.get(Json.str(p, "sku")));
            p.put("available", s.isEmpty() ? 0L : Json.lng(s, "available"));
            p.put("low", Json.bool(s, "low", false));
        }
        return true;
    }

    /** Admin home: numbers from 4 services. Each part may fail on its own without breaking the page. */
    private Object overview(Req r) {
        CompletableFuture<ServiceClient.Result> orders = async(Config.ORDER, "/orders/stats", r);
        CompletableFuture<ServiceClient.Result> low = async(Config.INVENTORY, "/stock/low", r);
        CompletableFuture<ServiceClient.Result> pays = async(Config.PAYMENT, "/payments", r);
        CompletableFuture<ServiceClient.Result> prods = async(Config.CATALOG, "/products", r);
        List<String> missing = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", part(orders, Config.ORDER, missing));
        out.put("lowStock", part(low, Config.INVENTORY, missing));
        Object p = part(pays, Config.PAYMENT, missing);
        if (p instanceof Map<?, ?> pm) Json.map(p).remove("payments");
        out.put("payments", p);
        Object pr = part(prods, Config.CATALOG, missing);
        out.put("productCount", pr == null ? null : Json.list(pr).size());
        out.put("missing", missing);
        return out;
    }

    private CompletableFuture<ServiceClient.Result> async(String service, String path, Req r) {
        return CompletableFuture.supplyAsync(() -> guarded(service, "GET", path, r), fanOut);
    }

    private static Object part(CompletableFuture<ServiceClient.Result> f, String service, List<String> missing) {
        try {
            ServiceClient.Result res = f.get();
            if (res != null && res.ok()) return res.body();
        } catch (Exception ignored) { }
        missing.add(service);
        return null;
    }

    /** Call with the breaker. Returns null when the service is down or the circuit is open. */
    private ServiceClient.Result guarded(String service, String method, String path, Req r) {
        CircuitBreaker b = breakers.get(service);
        if (!b.allow()) return null;
        try {
            ServiceClient.Result res = client.call(service, method, path, null, r);
            if (res.status() >= 500) { b.failure(); return null; }
            b.success();
            return res;
        } catch (ApiError e) {
            b.failure();
            return null;
        }
    }

    // ============================================================== session

    private Object session(Req r) {
        Map<String, Object> out = Json.obj("store", SeedData.STORE_NAME);
        if (r.userId() > 0) {
            Map<String, Object> token = Tokens.verify(cookieValue(r.header("cookie")), cfg.internalKey, System.currentTimeMillis());
            out.put("user", Json.obj("id", r.userId(), "name", r.userName(), "role", r.role(), "username", Json.str(token, "username")));
        } else {
            out.put("user", null);
        }
        return out;
    }

    // ============================================================== architecture console

    private Object archServices(Req r) {
        List<Object> instances = discovery.allInstances();
        Map<String, Object> regStats = null;
        try {
            ServiceClient.Result rs = client.callUrl(cfg.registryUrl, "GET", "/internal/stats", null, null, r.traceId, 800, Config.REGISTRY);
            if (rs.ok()) regStats = rs.json();
        } catch (ApiError ignored) { }
        List<CompletableFuture<Map<String, Object>>> stats = new ArrayList<>();
        for (Object o : instances) {
            Map<String, Object> inst = Json.map(o);
            stats.add(CompletableFuture.supplyAsync(() -> {
                try {
                    ServiceClient.Result s = client.callUrl(Json.str(inst, "url"), "GET", "/internal/stats", null, null, r.traceId, 900, Json.str(inst, "name"));
                    if (s.ok()) inst.put("stats", s.json());
                    else inst.put("stats", Json.obj("down", true));
                } catch (ApiError e) {
                    inst.put("stats", Json.obj("down", true, "unreachable", true));
                }
                return inst;
            }, fanOut));
        }
        List<Object> out = new ArrayList<>();
        if (regStats != null) {
            out.add(Json.obj("name", Config.REGISTRY, "url", cfg.registryUrl, "role", regStats.get("role"), "status", "UP",
                    "silentMs", 0, "stats", regStats));
        }
        for (CompletableFuture<Map<String, Object>> f : stats) out.add(f.join());
        out.sort(Comparator.comparingInt(o -> order(Json.str(Json.map(o), "name"))));
        List<Object> gw = new ArrayList<>();
        breakers.values().forEach(b -> gw.add(b.info()));
        List<Object> ob = List.of();
        try {
            ServiceClient.Result res = client.call(Config.ORDER, "GET", "/orders/breakers", null, r);
            if (res.ok()) ob = res.jsonList();
        } catch (ApiError ignored) { }
        Map<String, Object> bus = null;
        try {
            ServiceClient.Result res = client.call(Config.EVENT_BUS, "GET", "/bus-stats", null, r);
            if (res.ok()) bus = res.json();
        } catch (ApiError ignored) { }
        return Json.obj("instances", out, "gatewayBreakers", gw, "orderBreakers", ob, "bus", bus, "registryUp", regStats != null,
                "ttlMs", cfg.instanceTtlMs, "heartbeatMs", cfg.heartbeatMs, "now", System.currentTimeMillis());
    }

    private static int order(String name) {
        int i = new ArrayList<>(Config.DEFAULT_PORTS.keySet()).indexOf(name);
        return name.equals(Config.GATEWAY) ? -1 : (i < 0 ? 99 : i);
    }

    private List<Map<String, Object>> allSpans(Req r) {
        long since = System.currentTimeMillis() - 30 * 60_000L;
        List<CompletableFuture<List<Object>>> calls = new ArrayList<>();
        for (Object o : discovery.allInstances()) {
            Map<String, Object> inst = Json.map(o);
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    ServiceClient.Result s = client.callUrl(Json.str(inst, "url"), "GET", "/internal/spans?since=" + since, null, null, r.traceId, 900, Json.str(inst, "name"));
                    return s.ok() ? s.jsonList() : List.of();
                } catch (ApiError e) {
                    return List.of();
                }
            }, fanOut));
        }
        List<Map<String, Object>> spans = new ArrayList<>();
        for (CompletableFuture<List<Object>> c : calls) for (Object s : c.join()) spans.add(Json.map(s));
        return spans;
    }

    private Object archTraces(Req r) {
        Map<String, List<Map<String, Object>>> byTrace = new LinkedHashMap<>();
        for (Map<String, Object> s : allSpans(r)) byTrace.computeIfAbsent(Json.str(s, "traceId"), k -> new ArrayList<>()).add(s);
        List<Map<String, Object>> traces = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byTrace.entrySet()) {
            List<Map<String, Object>> sp = e.getValue();
            sp.sort(Comparator.comparingLong(s -> Json.lng(s, "start")));
            Map<String, Object> root = sp.get(0);
            for (Map<String, Object> s : sp) if (Config.GATEWAY.equals(Json.str(s, "service"))) { root = s; break; }
            if (!Config.GATEWAY.equals(Json.str(root, "service")) && !"client".equals(Json.str(root, "caller"))) {
                // background work (for example a retry or an event without a browser request) - still show it
            }
            long start = Json.lng(sp.get(0), "start"), end = 0;
            Set<String> services = new LinkedHashSet<>();
            boolean error = false;
            for (Map<String, Object> s : sp) {
                end = Math.max(end, Json.lng(s, "start") + Json.lng(s, "ms"));
                services.add(Json.str(s, "service"));
                if (Json.lng(s, "status") >= 500) error = true;
            }
            traces.add(Json.obj("traceId", e.getKey(), "method", root.get("method"), "path", root.get("path"), "status", root.get("status"),
                    "start", start, "ms", end - start, "spans", (long) sp.size(), "services", new ArrayList<>(services), "error", error,
                    "async", sp.stream().anyMatch(s -> Config.EVENT_BUS.equals(Json.str(s, "caller")))));
        }
        traces.sort(Comparator.comparingLong((Map<String, Object> t) -> Json.lng(t, "start")).reversed());
        String filter = r.q("service", "");
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> t : traces) {
            if (!filter.isEmpty() && !Json.list(t, "services").contains(filter)) continue;
            if (out.size() < 60) out.add(t);
        }
        return out;
    }

    private Object archTrace(Req r) {
        String id = r.param("id");
        List<Map<String, Object>> sp = new ArrayList<>();
        for (Map<String, Object> s : allSpans(r)) if (id.equals(Json.str(s, "traceId"))) sp.add(s);
        if (sp.isEmpty()) throw ApiError.notFound("Trace not found (traces are kept for 30 minutes)");
        sp.sort(Comparator.comparingLong((Map<String, Object> s) -> Json.lng(s, "start")).thenComparing(s -> -Json.lng(s, "ms")));
        List<Object> events = new ArrayList<>();
        try {
            ServiceClient.Result ev = client.call(Config.EVENT_BUS, "GET", "/events?limit=300", null, r);
            if (ev.ok()) for (Object o : ev.jsonList()) if (id.equals(Json.str(Json.map(o), "traceId"))) events.add(o);
        } catch (ApiError ignored) { }
        return Json.obj("traceId", id, "spans", sp, "events", events);
    }

    private Object archEvents(Req r) {
        ServiceClient.Result ev = client.call(Config.EVENT_BUS, "GET", "/events?limit=" + enc(r.q("limit", "60")), null, r);
        ServiceClient.Result dl = client.call(Config.EVENT_BUS, "GET", "/dead-letters", null, r);
        ServiceClient.Result subs = client.call(Config.EVENT_BUS, "GET", "/subscriptions", null, r);
        ServiceClient.Result st = client.call(Config.EVENT_BUS, "GET", "/bus-stats", null, r);
        return Json.obj("events", ev.ok() ? ev.body() : List.of(), "deadLetters", dl.ok() ? dl.body() : List.of(),
                "subscriptions", subs.ok() ? subs.body() : List.of(), "stats", st.ok() ? st.body() : null);
    }

    private Object chaos(Req r) {
        Map<String, Object> b = r.json();
        String service = Json.str(b, "service", "");
        if (!CHAOS_ALLOWED.contains(service)) throw ApiError.bad("You can switch off any service except the gateway and the registry (the console needs them)");
        Map<String, Object> body = Json.obj();
        if (b.containsKey("down")) body.put("down", Json.bool(b, "down", false));
        if (b.containsKey("latencyMs")) body.put("latencyMs", Math.max(0, Math.min(5000, Json.lng(b, "latencyMs"))));
        int changed = 0;
        for (Object o : discovery.allInstances()) {
            Map<String, Object> inst = Json.map(o);
            if (!service.equals(Json.str(inst, "name"))) continue;
            ServiceClient.Result res = client.callUrl(Json.str(inst, "url"), "POST", "/internal/chaos", body, r, r.traceId, 1500, service);
            if (res.ok()) changed++;
        }
        if (changed == 0) throw ApiError.notFound(service + " is not running");
        discovery.forget(service);
        return Json.obj("ok", true, "service", service, "instances", changed);
    }

    // ============================================================== files + small helpers

    private Object file(String name) throws Exception {
        String type = switch (name) {
            case "index.html" -> "text/html; charset=utf-8";
            case "app.css" -> "text/css; charset=utf-8";
            case "app.js", "theme.js", "arch.js" -> "application/javascript; charset=utf-8";
            case "favicon.svg", "icons.svg" -> "image/svg+xml";
            default -> null; // only these files exist - no path tricks like ../../secret
        };
        if (type == null && name.matches("img/products/[a-z0-9-]{1,40}\\.(jpg|png|webp)")) {
            type = name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp" : "image/jpeg";
        }
        Path f = frontendDir == null ? null : frontendDir.resolve(name);
        if (type == null || f == null || !Files.isRegularFile(f)) throw ApiError.notFound("File not found");
        Resp out = new Resp(200, Files.readAllBytes(f));
        out.contentType = type;
        if (type.startsWith("image/jp") || type.startsWith("image/png") || type.startsWith("image/webp")) out.header("Cache-Control", "public, max-age=86400");
        return out;
    }

    static String cookieValue(String header) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) return p.substring(COOKIE.length() + 1);
        }
        return null;
    }

    private static String cookie(String value, int maxAge) {
        return COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge;
    }

    private static String query(Req r) {
        if (r.query.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        r.query.forEach((k, v) -> sb.append(sb.length() == 0 ? "?" : "&").append(enc(k)).append('=').append(enc(v)));
        return sb.toString();
    }

    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.substring(1).split("/", -1)) sb.append('/').append(enc(seg));
        return sb.toString();
    }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20"); }

    private static String ascii(String s) { return s == null ? "" : s.replaceAll("[^\\x20-\\x7e]", "?"); }

    @Override
    public synchronized void stop() {
        fanOut.shutdownNow();
        super.stop();
    }
}
