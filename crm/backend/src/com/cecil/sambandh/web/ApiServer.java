package com.cecil.sambandh.web;

import com.cecil.sambandh.model.Interaction;
import com.cecil.sambandh.model.Role;
import com.cecil.sambandh.model.Stage;
import com.cecil.sambandh.model.User;
import com.cecil.sambandh.service.ActivityService;
import com.cecil.sambandh.service.AuthService;
import com.cecil.sambandh.service.CustomerService;
import com.cecil.sambandh.service.DashboardService;
import com.cecil.sambandh.service.Duplicates;
import com.cecil.sambandh.service.ImportService;
import com.cecil.sambandh.service.Insights;
import com.cecil.sambandh.service.Problem;
import com.cecil.sambandh.store.DataStore;
import com.cecil.sambandh.util.Clock;
import com.cecil.sambandh.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The web server: the website files from the frontend folder, and the JSON API under /api.
 *
 * Every API call goes through handle(): security headers, sign-in check, CSRF check, then the route.
 * Mistakes the person can fix (Problem) become friendly messages; anything else becomes "Something went wrong"
 * (the details go to the console, never to the browser).
 */
public class ApiServer {
    public static final String COOKIE = "sambandh_session";

    private final Path frontend;
    private final DataStore store;
    private final AuthService auth;
    private final CustomerService customers;
    private final ActivityService activity;
    private final Duplicates duplicates;
    private final ImportService importer;
    private final DashboardService dashboard;
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

    /** Returned by a handler that already wrote its own answer (downloads). */
    private static final Object DONE = new Object();

    public ApiServer(Path frontend, DataStore store) {
        this.frontend = frontend;
        this.store = store;
        Insights insights = new Insights(store);
        this.auth = new AuthService(store);
        this.customers = new CustomerService(store, insights);
        this.duplicates = new Duplicates(store, customers);
        this.customers.setDuplicates(duplicates);
        this.activity = new ActivityService(store, customers);
        this.importer = new ImportService(store, customers);
        this.dashboard = new DashboardService(store, insights);
        defineRoutes();
    }

    public AuthService auth() { return auth; }

    // ------------------------------------------------------------------ routes

    private void get(String path, Handler h) { routes.add(new Route("GET", Pattern.compile(path), true, h)); }
    private void post(String path, Handler h) { routes.add(new Route("POST", Pattern.compile(path), true, h)); }
    private void put(String path, Handler h) { routes.add(new Route("PUT", Pattern.compile(path), true, h)); }

    private static final String ID = "([A-Z]-\\d{1,7})";

    private void defineRoutes() {
        routes.add(new Route("POST", Pattern.compile("/api/login"), false, this::login));
        routes.add(new Route("POST", Pattern.compile("/api/logout"), false, c -> {
            auth.logout(Http.cookie(c.ex, COOKIE));
            c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            return Json.obj("ok", true);
        }));
        routes.add(new Route("GET", Pattern.compile("/api/me"), false, c -> Json.obj(
                "user", c.user == null ? null : c.user.publicView(), "meta", meta())));

        post("/api/me/password", c -> {
            Map<String, Object> b = c.body();
            auth.changeOwnPassword(c.user, Json.str(b, "current"), Json.str(b, "next"));
            return Json.obj("ok", true);
        });

        get("/api/dashboard", c -> dashboard.build(c.user));

        get("/api/customers", c -> customers.list(c.user, c.query()));
        post("/api/customers", c -> customers.detail(c.user, customers.create(c.user, c.body()).id));
        post("/api/customers/check", c -> Json.obj("matches", duplicates.check(c.user, c.body())));
        post("/api/customers/assign", c -> {
            Map<String, Object> b = c.body();
            return Json.obj("moved", customers.bulkAssign(c.user, Json.strings(b, "ids"), Json.str(b, "ownerId")));
        });
        get("/api/customers/export\\.csv", c -> {
            String csv = customers.exportCsv(c.user, c.query());
            Http.download(c.ex, "text/csv; charset=utf-8", "sambandh-customers-" + Clock.today() + ".csv",
                    ("﻿" + csv).getBytes(StandardCharsets.UTF_8));
            return DONE;
        });
        get("/api/customers/" + ID, c -> customers.detail(c.user, c.id));
        put("/api/customers/" + ID, c -> customers.detail(c.user, customers.update(c.user, c.id, c.body()).id));
        post("/api/customers/" + ID + "/interactions", c -> { activity.logInteraction(c.user, c.id, c.body()); return customers.detail(c.user, c.id); });
        post("/api/customers/" + ID + "/followups", c -> { activity.addFollowUp(c.user, c.id, c.body()); return customers.detail(c.user, c.id); });
        post("/api/customers/" + ID + "/orders", c -> { activity.addOrder(c.user, c.id, c.body()); return customers.detail(c.user, c.id); });
        post("/api/customers/" + ID + "/tickets", c -> { activity.openTicket(c.user, c.id, c.body()); return customers.detail(c.user, c.id); });
        get("/api/customers/" + ID + "/privacy-export", c -> {
            Map<String, Object> data = customers.privacyExport(c.user, c.id);
            Http.download(c.ex, "application/json; charset=utf-8", "personal-data-" + c.id + ".json",
                    Json.pretty(data).getBytes(StandardCharsets.UTF_8));
            return DONE;
        });
        post("/api/customers/" + ID + "/erase", c -> customers.detail(c.user, customers.erase(c.user, c.id, Json.str(c.body(), "confirm")).id));

        post("/api/followups/" + ID + "/done", c -> activity.setFollowUpDone(c.user, c.id, Json.bool(c.body(), "done", true)).toJson());
        get("/api/followups", c -> activity.followUpQueue(c.user, c.query().get("scope")));

        post("/api/orders/" + ID + "/status", c -> activity.setOrderStatus(c.user, c.id, Json.str(c.body(), "status")).toJson());

        get("/api/tickets", c -> Json.obj("tickets", activity.ticketList(c.user, c.query().get("status"))));
        post("/api/tickets/" + ID + "/status", c -> activity.setTicketStatus(c.user, c.id, Json.str(c.body(), "status")).toJson());

        get("/api/duplicates", c -> {
            if (!c.user.role.atLeast(Role.MANAGER)) throw Problem.forbidden("Only managers can review duplicates.");
            return Json.obj("pairs", duplicates.findAll(c.user));
        });
        post("/api/merge", c -> {
            Map<String, Object> b = c.body();
            return customers.detail(c.user, duplicates.merge(c.user, Json.str(b, "keepId"), Json.str(b, "otherId")).id);
        });

        get("/api/import/sample", c -> Json.obj("csv", ImportService.sampleCsv()));
        post("/api/import/preview", c -> importer.preview(c.user, Json.str(c.body(), "csv")));
        post("/api/import/commit", c -> {
            Map<String, Object> b = c.body();
            return importer.commit(c.user, Json.str(b, "csv"), Json.bool(b, "skipDuplicates", true));
        });

        get("/api/users", c -> {
            if (c.user.role == Role.ADMIN) return Json.obj("users", auth.listUsers());
            List<Object> staff = new ArrayList<>();      // everyone else gets just names (for "owner" lists)
            synchronized (store) { for (User u : store.users.values()) if (u.active) staff.add(u.publicView()); }
            return Json.obj("users", staff);
        });
        post("/api/users", c -> auth.createUser(c.user, c.body()).publicView());
        put("/api/users/" + ID, c -> auth.updateUser(c.user, c.id, c.body()).publicView());

        get("/api/audit", c -> {
            int limit = 300;
            try { limit = Math.max(1, Math.min(1000, Integer.parseInt(c.query().getOrDefault("limit", "300")))); } catch (NumberFormatException ignored) { }
            return dashboard.audit(c.user, c.query().get("q"), limit);
        });
    }

    /** Lists for the forms (stages, sources ...) so the browser does not hard-code them. */
    private static Map<String, Object> meta() {
        List<Object> stages = new ArrayList<>();
        for (Stage s : Stage.values()) stages.add(Json.obj("id", s.name(), "label", s.label, "meaning", s.meaning));
        return Json.obj("stages", stages, "sources", CustomerService.SOURCES, "interactionTypes", Interaction.TYPES,
                "outcomes", Interaction.OUTCOMES, "priorities", ActivityService.PRIORITIES, "segments", Insights.SEGMENTS,
                "today", Clock.today().toString());
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
        String token = auth.login(Json.str(b, "username"), Json.str(b, "password"));
        c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200");
        User u = auth.userFor(token);
        return Json.obj("user", u.publicView(), "meta", meta());
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
                if (!"1".equals(ex.getRequestHeaders().getFirst("X-Sambandh"))) {
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
            User user = auth.userFor(Http.cookie(ex, COOKIE));
            if (route.needsLogin() && user == null) {
                Http.error(ex, 401, "Please sign in.", null);
                return;
            }
            String body = changes ? Http.body(ex) : null;
            String id = found.groupCount() >= 1 ? found.group(1) : null;
            Object result = route.handler().handle(new Ctx(ex, user, id, body));
            if (result != DONE) Http.json(ex, 200, result);
        } catch (Problem p) {
            Http.error(ex, p.status, p.getMessage(), p.data);
        } catch (Http.TooBig t) {
            Http.error(ex, 413, "That is too much data in one go (1 MB max).", null);
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
