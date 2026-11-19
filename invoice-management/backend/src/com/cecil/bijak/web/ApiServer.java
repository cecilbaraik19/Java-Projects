package com.cecil.bijak.web;

import com.cecil.bijak.core.Gstin;
import com.cecil.bijak.core.Schedule;
import com.cecil.bijak.model.Recurring;
import com.cecil.bijak.model.User;
import com.cecil.bijak.service.Access;
import com.cecil.bijak.service.AdminService;
import com.cecil.bijak.service.AuthService;
import com.cecil.bijak.service.AutomationService;
import com.cecil.bijak.service.CustomerService;
import com.cecil.bijak.service.InvoiceService;
import com.cecil.bijak.service.PaymentService;
import com.cecil.bijak.service.Problem;
import com.cecil.bijak.service.ViewService;
import com.cecil.bijak.store.DataStore;
import com.cecil.bijak.util.Clock;
import com.cecil.bijak.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The web server (the JDK's own com.sun.net.httpserver - no Tomcat, no Spring):
 *   /            the website (files from the frontend folder)
 *   /api/...     the JSON API (needs sign-in; every change needs the X-Bijak header = CSRF protection)
 *
 * Mistakes the person can fix (Problem) become friendly messages with status 400/403/404/409; anything else becomes
 * "Something went wrong" (the details go to the console, never to the browser).
 */
public class ApiServer {
    public static final String COOKIE = "bijak_session";
    public static final String CSRF_HEADER = "X-Bijak";

    /** All the parts the server talks to. */
    public record Parts(DataStore store, AuthService auth, CustomerService customers, InvoiceService invoices,
                        PaymentService payments, AutomationService auto, ViewService views, AdminService admin) { }

    private final Path frontend;
    private final Parts p;
    private final List<Route> routes = new ArrayList<>();
    private final Map<String, Deque<Long>> tries = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService pool;

    /** What a route gets: the request, the signed-in person and the id from the path. */
    public static final class Ctx {
        public final HttpExchange ex;
        public final User user;
        public final String id;
        final String rawBody;

        Ctx(HttpExchange ex, User user, String id, String rawBody) {
            this.ex = ex; this.user = user; this.id = id; this.rawBody = rawBody;
        }

        public Map<String, Object> body() {
            if (rawBody == null || rawBody.isBlank()) return new LinkedHashMap<>();
            try { return Json.parseObject(rawBody); } catch (IllegalArgumentException e) { throw Problem.bad("The request was not valid JSON."); }
        }

        public String q(String name) { return Http.query(ex).get(name); }

        public int qi(String name, int fallback) {
            try { return Integer.parseInt(q(name)); } catch (Exception e) { return fallback; }
        }
    }

    @FunctionalInterface
    interface Handler { Object handle(Ctx c) throws Exception; }

    record Route(String method, Pattern path, boolean needsLogin, Handler handler) { }

    public ApiServer(Path frontend, Parts parts) {
        this.frontend = frontend;
        this.p = parts;
        defineRoutes();
    }

    private void get(String path, Handler h) { routes.add(new Route("GET", Pattern.compile(path), true, h)); }
    private void post(String path, Handler h) { routes.add(new Route("POST", Pattern.compile(path), true, h)); }
    private void put(String path, Handler h) { routes.add(new Route("PUT", Pattern.compile(path), true, h)); }
    private void del(String path, Handler h) { routes.add(new Route("DELETE", Pattern.compile(path), true, h)); }

    private static final String ID = "([A-Za-z0-9-]{1,40})";
    private static final Map<String, Object> OK = Map.of("ok", true);

    private void defineRoutes() {
        // signing in
        routes.add(new Route("POST", Pattern.compile("/api/login"), false, this::login));
        routes.add(new Route("POST", Pattern.compile("/api/logout"), false, c -> {
            p.auth().logout(Http.cookie(c.ex, COOKIE));
            c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            return OK;
        }));
        routes.add(new Route("GET", Pattern.compile("/api/me"), false, c -> Json.obj(
                "user", c.user == null ? null : me(c.user), "meta", c.user == null ? null : p.views().meta(c.user))));
        post("/api/me/password", c -> {
            Map<String, Object> b = c.body();
            p.auth().changePassword(c.user, Json.str(b, "current"), Json.str(b, "next"));
            return OK;
        });
        get("/api/meta", c -> p.views().meta(c.user));
        get("/api/dashboard", c -> p.views().dashboard(c.user));

        // invoices and credit notes
        get("/api/invoices", c -> p.views().invoices(c.q("status"), c.q("kind"), c.q("customer"), c.q("q"), c.q("from"), c.q("to")));
        post("/api/invoices", c -> p.invoices().create(c.user, c.body()));
        post("/api/invoices/preview", c -> p.invoices().preview(c.user, c.body()));
        get("/api/invoices/" + ID, c -> p.views().invoice(c.user, c.id));
        put("/api/invoices/" + ID, c -> p.invoices().update(c.user, c.id, c.body()));
        del("/api/invoices/" + ID, c -> p.invoices().deleteDraft(c.user, c.id));
        post("/api/invoices/" + ID + "/issue", c -> p.invoices().issue(c.user, c.id, Json.str(c.body(), "override")));
        post("/api/invoices/" + ID + "/approve", c -> p.invoices().approve(c.user, c.id));
        post("/api/invoices/" + ID + "/sendback", c -> p.invoices().sendBack(c.user, c.id, Json.str(c.body(), "note")));
        post("/api/invoices/" + ID + "/cancel", c -> p.invoices().cancel(c.user, c.id, Json.str(c.body(), "reason")));
        post("/api/invoices/" + ID + "/hold", c -> {
            Map<String, Object> b = c.body();
            return p.invoices().hold(c.user, c.id, Json.bool(b, "on", true), Json.str(b, "note"));
        });
        post("/api/invoices/" + ID + "/credit", c -> p.invoices().creditNote(c.user, c.id, c.body()));
        post("/api/invoices/" + ID + "/copy", c -> p.invoices().copy(c.user, c.id));
        post("/api/invoices/" + ID + "/remind", c -> p.auto().remindNow(c.user, c.id));

        // customers and items
        get("/api/customers", c -> p.views().customers(c.q("q")));
        post("/api/customers", c -> p.customers().save(c.user, null, c.body()));
        get("/api/customers/" + ID, c -> p.views().customer(c.id, c.q("from"), c.q("to")));
        put("/api/customers/" + ID, c -> p.customers().save(c.user, c.id, c.body()));
        post("/api/customers/" + ID + "/advance", c -> p.invoices().useAdvance(c.user, c.id));
        post("/api/gstin", c -> {
            String g = Gstin.normalize(Json.str(c.body(), "gstin", ""));
            String problem = Gstin.problem(g);
            return Json.obj("gstin", g, "ok", problem == null, "problem", problem,
                    "state", problem == null ? Gstin.stateOf(g) : null,
                    "stateName", problem == null ? Gstin.STATES.get(Gstin.stateOf(g)) : null,
                    "pan", problem == null ? Gstin.pan(g) : null);
        });
        get("/api/items", c -> p.views().items());
        post("/api/items", c -> p.customers().saveItem(c.user, null, c.body()));
        put("/api/items/" + ID, c -> p.customers().saveItem(c.user, c.id, c.body()));

        // money received
        get("/api/payments", c -> p.views().payments(c.q("q")));
        post("/api/payments", c -> p.payments().record(c.user, c.body()));
        post("/api/payments/" + ID + "/void", c -> p.payments().voidPayment(c.user, c.id, Json.str(c.body(), "reason")));

        // repeating invoices
        get("/api/recurring", c -> p.views().recurring());
        post("/api/recurring", c -> p.auto().save(c.user, null, c.body()));
        put("/api/recurring/" + ID, c -> p.auto().save(c.user, c.id, c.body()));
        post("/api/schedule", c -> {
            Map<String, Object> b = c.body();
            Recurring r = new Recurring();
            try { r.every = Recurring.Every.valueOf(Json.str(b, "every", "MONTHLY")); } catch (IllegalArgumentException e) { throw Problem.bad("Pick how often."); }
            r.anchorDay = (int) Json.num(b, "anchorDay", 1);
            String problem = Schedule.anchorProblem(r.every, r.anchorDay);
            if (problem != null) return Json.obj("problem", problem);
            LocalDate start;
            try { start = LocalDate.parse(Json.str(b, "startDate", Clock.today().toString())); } catch (Exception e) { return Json.obj("problem", "Start date is not a date."); }
            r.nextDate = Schedule.first(r.every, r.anchorDay, start.isBefore(Clock.today()) ? Clock.today() : start).toString();
            String end = Json.str(b, "endDate", "");
            r.endDate = end.isBlank() ? null : end;
            return Json.obj("describe", Schedule.describe(r.every, r.anchorDay), "upcoming", AutomationService.upcoming(r, 6));
        });

        // bank statement
        get("/api/bank", c -> p.views().bank(c.q("state")));
        post("/api/bank/import", c -> p.payments().importStatement(c.user, Json.str(c.body(), "csv")));
        post("/api/bank/auto", c -> p.payments().acceptAllSure(c.user));
        post("/api/bank/" + ID + "/accept", c -> p.payments().accept(c.user, c.id, c.body()));
        post("/api/bank/" + ID + "/ignore", c -> p.payments().ignore(c.user, c.id, Json.bool(c.body(), "ignore", true)));

        // outbox, reports, activity
        get("/api/outbox", c -> p.views().outbox(c.q("kind"), c.q("q")));
        get("/api/reports", c -> p.views().reports(c.q("month")));
        get("/api/activity", c -> p.views().activity(c.user, Math.min(500, Math.max(10, c.qi("limit", 200)))));

        // the Owner's desk
        get("/api/admin", c -> p.admin().desk(c.user));
        post("/api/admin/users", c -> ViewService.person(p.admin().saveUser(c.user, null, c.body())));
        put("/api/admin/users/" + ID, c -> ViewService.person(p.admin().saveUser(c.user, c.id, c.body())));
        put("/api/admin/settings", c -> p.admin().saveSettings(c.user, c.body()));
        post("/api/admin/tick", c -> { Access.money(c.user); return p.auto().tick(); });

        // CSV files for Excel (with a byte-order mark so Excel reads the rupee sign and Hindi names right)
        get("/api/export/invoices.csv", c -> csv(c, "bijak-invoice-register.csv", p.admin().invoiceRegister(c.q("from"), c.q("to"))));
        get("/api/export/gst.csv", c -> csv(c, "bijak-gst-" + c.q("month") + ".csv", p.admin().gstCsv(c.q("month"))));
        get("/api/export/payments.csv", c -> csv(c, "bijak-receipts.csv", p.admin().paymentsCsv()));
    }

    private Object csv(Ctx c, String name, String text) throws IOException {
        Http.download(c.ex, "text/csv; charset=utf-8", name, ("\uFEFF" + text).getBytes(StandardCharsets.UTF_8));
        return null;
    }

    private Map<String, Object> me(User u) {
        Map<String, Object> m = ViewService.person(u);
        m.put("username", u.username);
        m.put("money", u.role.money());
        m.put("owner", u.owner());
        m.put("writes", u.role.writes());
        return m;
    }

    private Object login(Ctx c) {
        String ip = Http.clientIp(c.ex);
        if (!allow("login:" + ip, 10, 60_000)) throw new Problem(429, "Too many tries. Wait a minute.");
        Map<String, Object> b = c.body();
        String token = p.auth().login(Json.str(b, "username"), Json.str(b, "password"));
        c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200");
        User u = p.auth().userFor(token);
        return Json.obj("user", me(u), "meta", p.views().meta(u));
    }

    /** A simple sliding-window rate limit: at most `max` tries per `windowMs` for one key. */
    boolean allow(String key, int max, long windowMs) {
        long now = System.currentTimeMillis();
        Deque<Long> q = tries.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < now - windowMs) q.pollFirst();
            if (q.size() >= max) return false;
            q.addLast(now);
            return true;
        }
    }

    // ------------------------------------------------------------------ the server

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        pool = Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "bijak-web");
            t.setDaemon(true);
            return t;
        });
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

    private void api(HttpExchange ex, String path) throws Exception {
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
                if (!"1".equals(ex.getRequestHeaders().getFirst(CSRF_HEADER))) {
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
            User user = p.auth().userFor(Http.cookie(ex, COOKIE));
            if (route.needsLogin() && user == null) {
                Http.error(ex, 401, "Please sign in.", null);
                return;
            }
            String body = changes ? Http.body(ex) : null;
            String id = found.groupCount() >= 1 ? found.group(1) : null;
            Object result = route.handler().handle(new Ctx(ex, user, id, body));
            if (result != null) Http.json(ex, 200, result);
        } catch (Problem pr) {
            Http.error(ex, pr.status, pr.getMessage(), pr.data);
        } catch (Http.TooBig t) {
            Http.error(ex, 413, "That is too much in one go (max 1.6 MB).", null);
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
