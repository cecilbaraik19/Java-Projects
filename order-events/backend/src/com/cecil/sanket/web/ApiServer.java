package com.cecil.sanket.web;

import com.cecil.sanket.Platform;
import com.cecil.sanket.broker.ConsumerGroup;
import com.cecil.sanket.broker.EventBroker;
import com.cecil.sanket.broker.Schemas;
import com.cecil.sanket.model.AuditEntry;
import com.cecil.sanket.model.DeadLetter;
import com.cecil.sanket.model.Event;
import com.cecil.sanket.model.OutboxEntry;
import com.cecil.sanket.model.OrderView;
import com.cecil.sanket.model.Role;
import com.cecil.sanket.model.User;
import com.cecil.sanket.service.Problem;
import com.cecil.sanket.service.ShippingService;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The web server:
 *   /           the website (files from the frontend folder)
 *   /api/...    the JSON API for the website (needs sign-in; changes need the X-Sanket header = CSRF protection)
 *
 * Mistakes the person can fix (Problem) become friendly messages; anything else becomes "Something went wrong"
 * (the details go to the console, never to the browser).
 */
public class ApiServer {
    public static final String COOKIE = "sanket_session";

    private final Path frontend;
    private final Platform p;
    private final DataStore store;
    private final List<Route> routes = new ArrayList<>();
    private final Map<String, Deque<Long>> loginTries = new ConcurrentHashMap<>();
    private HttpServer server;
    private java.util.concurrent.ExecutorService pool;

    /** What a route handler gets: the request, the signed-in user and the {id} from the path. */
    public static final class Ctx {
        public final HttpExchange ex;
        public final User user;
        public final String id;
        final String rawBody;

        Ctx(HttpExchange ex, User user, String id, String rawBody) {
            this.ex = ex; this.user = user; this.id = id; this.rawBody = rawBody;
        }

        public Map<String, Object> body() {
            if (rawBody == null || rawBody.isBlank()) return new java.util.LinkedHashMap<>();
            try { return Json.parseObject(rawBody); } catch (IllegalArgumentException e) { throw Problem.bad("The request was not valid JSON."); }
        }

        public Map<String, String> query() { return Http.query(ex); }
    }

    @FunctionalInterface
    interface Handler { Object handle(Ctx c) throws IOException; }

    record Route(String method, Pattern path, boolean needsLogin, Handler handler) { }

    public ApiServer(Path frontend, Platform platform) {
        this.frontend = frontend;
        this.p = platform;
        this.store = platform.store;
        defineRoutes();
    }

    // ------------------------------------------------------------------ routes

    private void get(String path, Handler h) { routes.add(new Route("GET", Pattern.compile(path), true, h)); }
    private void post(String path, Handler h) { routes.add(new Route("POST", Pattern.compile(path), true, h)); }
    private void put(String path, Handler h) { routes.add(new Route("PUT", Pattern.compile(path), true, h)); }

    private static final String ID = "([A-Za-z0-9-]{1,60})";

    private void defineRoutes() {
        routes.add(new Route("POST", Pattern.compile("/api/login"), false, this::login));
        routes.add(new Route("POST", Pattern.compile("/api/logout"), false, c -> {
            p.auth.logout(Http.cookie(c.ex, COOKIE));
            c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            return Json.obj("ok", true);
        }));
        routes.add(new Route("GET", Pattern.compile("/api/me"), false, c -> Json.obj(
                "user", c.user == null ? null : c.user.publicView(), "meta", c.user == null ? null : meta(c.user))));
        post("/api/me/password", c -> {
            Map<String, Object> b = c.body();
            p.auth.changeOwnPassword(c.user, Json.str(b, "current"), Json.str(b, "next"));
            return Json.obj("ok", true);
        });

        // shop and orders (customers and staff)
        get("/api/shop", c -> {
            List<Object> customers = new ArrayList<>();
            if (c.user.role.atLeast(Role.STAFF)) synchronized (store) { customers.addAll(store.customers.values()); }
            return Json.obj("products", p.inventory.products(), "customers", customers,
                    "customer", c.user.customerId == null ? null : store.customers.get(c.user.customerId));
        });
        get("/api/orders", c -> p.analytics.orders(c.user, c.query()));
        post("/api/orders", c -> {
            var o = p.orders.place(c.user, c.body());
            return Json.obj("order", o, "note", "Order received. The other services are working on it now.");
        });
        get("/api/orders/" + ID, c -> p.analytics.orderDetail(c.user, c.id));
        post("/api/orders/" + ID + "/cancel", c -> { p.orders.cancel(c.user, c.id); return Json.obj("ok", true); });
        post("/api/orders/" + ID + "/dispatch", c -> { staff(c.user); return p.shipping.dispatch(c.user, c.id); });
        post("/api/orders/" + ID + "/deliver", c -> { staff(c.user); return p.shipping.deliver(c.user, c.id); });
        get("/api/notifications", c -> Json.obj("messages", p.notifier.inbox(c.user)));
        post("/api/notifications/read", c -> Json.obj("marked", p.notifier.markRead(c.user)));

        // the signal box (staff)
        get("/api/overview", c -> { staff(c.user); return withFaults(p.analytics.overview()); });
        get("/api/inventory", c -> { staff(c.user); return Json.obj("products", p.inventory.products()); });
        post("/api/inventory/" + ID + "/restock", c -> p.inventory.restock(c.user, c.id, (int) Json.num(c.body(), "qty", 0)).view());
        get("/api/events", c -> { staff(c.user); return p.analytics.eventLog(c.query()); });
        get("/api/events/" + ID, c -> {
            staff(c.user);
            Event e = p.broker.byId(c.id);
            if (e == null) throw Problem.notFound("Event");
            return Json.obj("event", e, "causedBy", e.causationId == null ? null : p.broker.byId(e.causationId),
                    "trace", e.correlationId == null ? List.of() : p.analytics.trace(e.correlationId));
        });
        get("/api/consumers", c -> {
            staff(c.user);
            long now = Clock.millis();
            List<Object> gs = new ArrayList<>();
            for (ConsumerGroup g : p.dispatcher.groups()) gs.add(p.dispatcher.view(g, now));
            return Json.obj("groups", gs, "brokerDown", p.broker.isDown(), "maxAttempts", com.cecil.sanket.broker.Dispatcher.MAX_ATTEMPTS);
        });
        post("/api/consumers/" + ID + "/pause", c -> {
            admin(c.user);
            p.dispatcher.pause(c.id, true, "Paused by " + c.user.name);
            log(c, "consumer.pause", c.id, null);
            return Json.obj("ok", true);
        });
        post("/api/consumers/" + ID + "/resume", c -> {
            admin(c.user);
            p.dispatcher.pause(c.id, false, null);
            p.sim.forgetCrash(c.id);
            log(c, "consumer.resume", c.id, "continues from its committed offset");
            return Json.obj("ok", true);
        });
        post("/api/consumers/" + ID + "/reset", c -> {
            admin(c.user);
            String to = Json.str(c.body(), "to", "");
            if (c.id.equals("order-view") && "earliest".equals(to)) {
                int n = p.projection.rebuild(c.user);
                return Json.obj("ok", true, "note", "Read model dropped (" + n + " orders). Replaying the log from offset 0.");
            }
            p.dispatcher.reset(c.id, to);
            log(c, "consumer.reset", c.id, "offsets moved to " + to);
            return Json.obj("ok", true, "note", "earliest".equals(to)
                    ? "Replaying the whole log. The service checks its own records, so nothing is done twice."
                    : "Skipped everything that was waiting.");
        });
        get("/api/schemas", c -> {
            staff(c.user);
            List<Object> out = new ArrayList<>();
            for (Schemas.Schema s : Schemas.ALL) {
                Map<String, Object> m = s.toJson();
                List<String> readers = new ArrayList<>();
                for (ConsumerGroup g : p.dispatcher.groups()) if (g.handles.contains(s.type()) && g.topics.contains(s.topic())) readers.add(g.name);
                m.put("consumers", readers);
                m.put("latest", Schemas.latest(s.type()) == s.version());
                out.add(m);
            }
            List<Object> topics = new ArrayList<>();
            for (String t : EventBroker.TOPICS.keySet()) topics.add(Json.obj("name", t, "partitions", p.broker.partitions(t), "events", p.broker.total(t)));
            return Json.obj("schemas", out, "topics", topics);
        });
        get("/api/outbox", c -> {
            staff(c.user);
            boolean pending = "1".equals(c.query().get("pending"));
            List<OutboxEntry> rows = p.relay.rows(pending, 120);
            return Json.obj("rows", rows, "pending", p.relay.pending(), "published", p.relay.published, "brokerDown", p.broker.isDown());
        });
        get("/api/dead-letters", c -> {
            staff(c.user);
            String state = c.query().getOrDefault("state", "OPEN");
            List<Object> out = new ArrayList<>();
            Map<String, Long> counts = new java.util.LinkedHashMap<>();
            counts.put("OPEN", 0L); counts.put("REDRIVEN", 0L); counts.put("DISCARDED", 0L);
            synchronized (store) {
                List<DeadLetter> all = new ArrayList<>(store.deadLetters.values());
                for (int i = all.size() - 1; i >= 0; i--) {
                    DeadLetter d = all.get(i);
                    counts.merge(d.state, 1L, Long::sum);
                    if (!state.equals("ALL") && !state.equals(d.state)) continue;
                    if (out.size() < 200) {
                        Map<String, Object> m = d.toJson();
                        OrderView v = d.correlationId == null ? null : store.views.get(d.correlationId);
                        m.put("orderStatus", v == null ? null : v.status);
                        out.add(m);
                    }
                }
            }
            return Json.obj("deadLetters", out, "counts", counts, "safeTemplates", store.flag("notifier.safeTemplates"),
                    "gatewayDown", p.payment.gatewayDown);
        });
        post("/api/dead-letters/" + ID + "/redrive", c -> { admin(c.user); return p.dispatcher.redrive(c.user, c.id); });
        post("/api/dead-letters/" + ID + "/discard", c -> { admin(c.user); return p.dispatcher.discard(c.user, c.id, Json.str(c.body(), "note")); });
        get("/api/analytics", c -> { staff(c.user); return p.analytics.analytics(); });

        // simulator (demo tools)
        get("/api/sim", c -> { admin(c.user); return p.sim.status(); });
        post("/api/sim/fault", c -> {
            Map<String, Object> b = c.body();
            return p.sim.inject(c.user, Json.str(b, "kind"), Json.str(b, "group"), (int) Json.num(b, "minutes", 3), (int) Json.num(b, "count", 5));
        });
        post("/api/sim/clear", c -> p.sim.clear(c.user, Json.str(c.body(), "key")));
        post("/api/sim/settings", c -> p.sim.settings(c.user, c.body()));
        post("/api/sim/order", c -> {
            admin(c.user);
            var o = p.sim.randomOrder();
            log(c, "sim.order", o.id, "one random order");
            return Json.obj("order", o);
        });

        // users, activity log
        get("/api/users", c -> { admin(c.user); return Json.obj("users", p.auth.listUsers()); });
        post("/api/users", c -> p.auth.createUser(c.user, c.body()).publicView());
        put("/api/users/" + ID, c -> p.auth.updateUser(c.user, c.id, c.body()).publicView());
        get("/api/audit", c -> {
            admin(c.user);
            String q = c.query().getOrDefault("q", "").toLowerCase();
            List<Object> out = new ArrayList<>();
            synchronized (store) {
                for (int i = store.audit.size() - 1; i >= 0 && out.size() < 300; i--) {
                    AuditEntry a = store.audit.get(i);
                    String who = a.userId == null ? "system" : store.userName(a.userId);
                    String line = (who + " " + a.action + " " + a.target + " " + (a.detail == null ? "" : a.detail)).toLowerCase();
                    if (!q.isEmpty() && !line.contains(q)) continue;
                    Map<String, Object> m = a.toJson();
                    m.put("who", who);
                    out.add(m);
                }
            }
            return Json.obj("entries", out);
        });
    }

    private Map<String, Object> withFaults(Map<String, Object> m) {
        m.put("faults", p.sim.activeFaults());
        return m;
    }

    private void log(Ctx c, String action, String target, String detail) {
        synchronized (store) {
            store.log(c.user.id, action, target, detail);
            store.markDirty("audit");
        }
    }

    /** Lists for the forms (cities, groups ...) so the browser does not hard-code them. */
    private Map<String, Object> meta(User u) {
        List<String> groups = new ArrayList<>();
        for (ConsumerGroup g : p.dispatcher.groups()) groups.add(g.name);
        return Json.obj("shop", "Jharkhand Haat", "cities", new TreeSet<>(ShippingService.COURIER.keySet()), "groups", groups,
                "topics", new ArrayList<>(EventBroker.TOPICS.keySet()), "statuses", OrderView.STATUSES, "today", Clock.today().toString(),
                "faults", com.cecil.sanket.sim.Simulator.FAULTS);
    }

    private static void staff(User u) {
        if (!u.role.atLeast(Role.STAFF)) throw Problem.forbidden("This page is for operations staff.");
    }

    private static void admin(User u) {
        if (u.role != Role.ADMIN) throw Problem.forbidden("Only the platform admin can do this.");
    }

    // ------------------------------------------------------------------ login with a rate limit

    private Object login(Ctx c) {
        String ip = Http.clientIp(c.ex);
        long now = Clock.millis();
        Deque<Long> tries = loginTries.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (tries) {
            while (!tries.isEmpty() && now - tries.peekFirst() > 60_000) tries.pollFirst();
            if (tries.size() >= 10) throw new Problem(429, "Too many sign-in attempts from this computer. Wait a minute.");
            tries.addLast(now);
        }
        Map<String, Object> b = c.body();
        String token = p.auth.login(Json.str(b, "username"), Json.str(b, "password"));
        c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200");
        User u = p.auth.userFor(token);
        return Json.obj("user", u.publicView(), "meta", meta(u));
    }

    // ------------------------------------------------------------------ the server

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        pool = Executors.newFixedThreadPool(8);
        server.setExecutor(pool);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    void handle(HttpExchange ex) throws IOException {
        try {
            Http.securityHeaders(ex);
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) api(ex, path);
            else staticFile(ex, path);
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e);
            e.printStackTrace();
            try { Http.error(ex, 500, "Something went wrong on the server.", null); } catch (Exception ignored) { }
        } finally {
            ex.close();
        }
    }

    private void api(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod();
        try {
            Route route = null;
            Matcher found = null;
            boolean pathKnown = false;
            for (Route r : routes) {
                Matcher m = r.path().matcher(path);
                if (!m.matches()) continue;
                pathKnown = true;
                if (r.method().equals(method) || ("HEAD".equals(method) && "GET".equals(r.method()))) { route = r; found = m; break; }
            }
            if (route == null) {
                Http.error(ex, pathKnown ? 405 : 404, pathKnown ? "Method not allowed." : "No such API.", null);
                return;
            }
            boolean changes = !"GET".equals(method) && !"HEAD".equals(method);
            if (changes) {
                // CSRF protection: a page on another website cannot send this header without our permission,
                // and if the browser says where the request came from it must be this site.
                if (!"1".equals(ex.getRequestHeaders().getFirst("X-Sanket"))) {
                    Http.error(ex, 403, "Request blocked (missing app header).", null);
                    return;
                }
                String origin = ex.getRequestHeaders().getFirst("Origin");
                String host = ex.getRequestHeaders().getFirst("Host");
                if (origin != null && !origin.equals("null") && host != null
                        && !origin.equals("http://" + host) && !origin.equals("https://" + host)) {
                    Http.error(ex, 403, "Request blocked (different website).", null);
                    return;
                }
                String type = ex.getRequestHeaders().getFirst("Content-Type");
                if (type == null || !type.toLowerCase().startsWith("application/json")) {
                    Http.error(ex, 415, "Send JSON.", null);
                    return;
                }
            }
            User user = p.auth.userFor(Http.cookie(ex, COOKIE));
            if (route.needsLogin() && user == null) {
                Http.error(ex, 401, "Please sign in.", null);
                return;
            }
            String body = changes ? Http.body(ex) : null;
            String id = found.groupCount() >= 1 ? found.group(1) : null;
            Object result = route.handler().handle(new Ctx(ex, user, id, body));
            Http.json(ex, 200, result);
        } catch (Problem pr) {
            Http.error(ex, pr.status, pr.getMessage(), pr.data);
        } catch (com.cecil.sanket.broker.BrokerDown down) {
            Http.error(ex, 503, down.getMessage(), null);
        } catch (Http.TooBig t) {
            Http.error(ex, 413, "That is too much data in one go.", null);
        }
    }

    private void staticFile(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            Http.send(ex, 405, new byte[0]);
            return;
        }
        String rel;
        if (path.equals("/") || path.equals("/index.html")) rel = "index.html";
        else if (path.startsWith("/static/")) rel = path.substring("/static/".length());
        else if (path.equals("/favicon.ico")) rel = "favicon.svg";
        else { notFound(ex); return; }
        // only plain file names inside the frontend folder: no "..", no sub-folders, no hidden files
        if (!rel.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || rel.contains("..")) { notFound(ex); return; }
        Path file = frontend.resolve(rel).normalize();
        if (!file.startsWith(frontend) || !Files.isRegularFile(file)) { notFound(ex); return; }
        ex.getResponseHeaders().set("Content-Type", Http.contentType(rel));
        ex.getResponseHeaders().set("Cache-Control", rel.equals("index.html") ? "no-cache" : "max-age=300");
        Http.send(ex, 200, Files.readAllBytes(file));
    }

    private static void notFound(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        Http.send(ex, 404, "Not found".getBytes(StandardCharsets.UTF_8));
    }
}
