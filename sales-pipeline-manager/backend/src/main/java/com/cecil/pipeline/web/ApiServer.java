package com.cecil.pipeline.web;

import com.cecil.pipeline.model.Enums;
import com.cecil.pipeline.model.Enums.ActivityType;
import com.cecil.pipeline.model.Enums.LeadSource;
import com.cecil.pipeline.model.Enums.Priority;
import com.cecil.pipeline.model.Enums.Role;
import com.cecil.pipeline.model.Enums.Stage;
import com.cecil.pipeline.model.User;
import com.cecil.pipeline.service.AccountService;
import com.cecil.pipeline.service.ActivityService;
import com.cecil.pipeline.service.AnalyticsService;
import com.cecil.pipeline.service.AuthService;
import com.cecil.pipeline.service.DealService;
import com.cecil.pipeline.service.ServiceException;
import com.cecil.pipeline.store.DataStore;
import com.cecil.pipeline.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The REST API and the website, on one port.
 *   /            the app (frontend/index.html)
 *   /static/...  CSS, JS and images from the frontend folder
 *   /api/...     JSON API (needs "Authorization: Bearer <token>" except for login)
 */
public class ApiServer {

    private final DataStore store;
    private final Path frontend;
    private final AuthService auth;
    private final AccountService accounts;
    private final DealService deals;
    private final ActivityService activities;
    private final AnalyticsService analytics;
    private final List<Route> routes = new ArrayList<>();
    private HttpServer server;
    private ExecutorService pool;

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange ex, User me, Matcher path) throws IOException;
    }

    private record Route(String method, Pattern pattern, boolean open, Handler handler) { }

    public ApiServer(DataStore store, Path frontend) {
        this.store = store;
        this.frontend = frontend;
        this.auth = new AuthService(store);
        this.accounts = new AccountService(store);
        this.deals = new DealService(store);
        this.activities = new ActivityService(store);
        this.analytics = new AnalyticsService(store, deals);
        registerRoutes();
    }

    public AuthService auth() { return auth; }

    // ------------------------------------------------------------------ start / stop

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        pool = Executors.newFixedThreadPool(8);
        server.setExecutor(pool);
        server.createContext("/", this::dispatch);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    // ------------------------------------------------------------------ dispatch

    private void dispatch(HttpExchange ex) {
        try {
            Http.secure(ex);
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            try {
                if (!path.startsWith("/api/")) {
                    serveSite(ex, path, method);
                    return;
                }
                // a JSON API called from our own page only: block cross-site form posts
                if (!method.equals("GET") && !method.equals("HEAD") && !sameOrigin(ex)) {
                    Http.error(ex, 403, "Cross-site request blocked");
                    return;
                }
                boolean pathMatched = false;
                for (Route r : routes) {
                    Matcher m = r.pattern.matcher(path);
                    if (!m.matches()) continue;
                    pathMatched = true;
                    if (!r.method.equals(method)) continue;
                    User me = null;
                    if (!r.open) {
                        me = auth.userFor(Http.bearer(ex));
                        if (me == null) { Http.error(ex, 401, "Please sign in"); return; }
                    }
                    r.handler.handle(ex, me, m);
                    return;
                }
                Http.error(ex, pathMatched ? 405 : 404, pathMatched ? "Method not allowed" : "Not found");
            } catch (ServiceException e) {
                Http.error(ex, e.status, e.getMessage());
            } catch (RuntimeException e) {
                System.err.println("Error on " + method + " " + path + ": " + e);
                e.printStackTrace();
                Http.error(ex, 500, "Something went wrong on the server");
            }
        } catch (IOException e) {
            // client went away; nothing to do
        } finally {
            ex.close();
        }
    }

    private boolean sameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true; // not a browser cross-site request (curl, tests, same-origin GET)
        String host = ex.getRequestHeaders().getFirst("Host");
        return host != null && (origin.equals("http://" + host) || origin.equals("https://" + host));
    }

    private void serveSite(HttpExchange ex, String path, String method) throws IOException {
        if (!method.equals("GET") && !method.equals("HEAD")) { Http.error(ex, 405, "Method not allowed"); return; }
        if (path.equals("/") || path.equals("/index.html")) { Http.file(ex, frontend, "index.html"); return; }
        if (path.startsWith("/static/")) { Http.file(ex, frontend, path.substring("/static/".length())); return; }
        if (path.equals("/favicon.ico")) { Http.file(ex, frontend, "favicon.svg"); return; }
        if (path.equals("/health")) { Http.json(ex, 200, Json.obj("status", "ok")); return; }
        Http.error(ex, 404, "Not found");
    }

    // ------------------------------------------------------------------ routes

    private void get(String p, Handler h) { routes.add(new Route("GET", Pattern.compile(p), false, h)); }
    private void post(String p, Handler h) { routes.add(new Route("POST", Pattern.compile(p), false, h)); }
    private void put(String p, Handler h) { routes.add(new Route("PUT", Pattern.compile(p), false, h)); }
    private void delete(String p, Handler h) { routes.add(new Route("DELETE", Pattern.compile(p), false, h)); }

    private static final String ID = "([A-Za-z]-\\d{1,9})";

    private void registerRoutes() {
        // ---- auth (login is the only open route)
        routes.add(new Route("POST", Pattern.compile("/api/auth/login"), true, (ex, me, m) -> {
            Map<String, Object> b = Http.body(ex);
            String token = auth.login(Json.str(b, "username"), Json.str(b, "password"));
            Http.json(ex, 200, Json.obj("token", token, "user", auth.userFor(token).toPublic()));
        }));
        post("/api/auth/logout", (ex, me, m) -> { auth.logout(Http.bearer(ex)); Http.json(ex, 200, Json.obj("ok", true)); });
        get("/api/auth/me", (ex, me, m) -> Http.json(ex, 200, me.toPublic()));
        post("/api/auth/password", (ex, me, m) -> {
            Map<String, Object> b = Http.body(ex);
            auth.changePassword(me, Json.str(b, "current"), Json.str(b, "next"));
            Http.json(ex, 200, Json.obj("ok", true));
        });

        // ---- fixed lists for the forms
        get("/api/meta", (ex, me, m) -> Http.json(ex, 200, meta()));

        // ---- team
        get("/api/users", (ex, me, m) -> Http.json(ex, 200,
            auth.listUsers(me.isAdmin() && "1".equals(Http.query(ex).get("all")))));
        post("/api/users", (ex, me, m) -> Http.json(ex, 201, auth.createUser(me, Http.body(ex)).toPublic()));
        put("/api/users/([a-z0-9._-]+)", (ex, me, m) -> Http.json(ex, 200, auth.updateUser(me, m.group(1), Http.body(ex)).toPublic()));

        // ---- accounts and contacts
        get("/api/accounts", (ex, me, m) -> Http.json(ex, 200, accounts.listAccounts(Http.query(ex).get("q"))));
        post("/api/accounts", (ex, me, m) -> Http.json(ex, 201, accounts.createAccount(me, Http.body(ex)).toMap()));
        get("/api/accounts/" + ID, (ex, me, m) -> Http.json(ex, 200, accounts.getAccount(m.group(1))));
        put("/api/accounts/" + ID, (ex, me, m) -> Http.json(ex, 200, accounts.updateAccount(me, m.group(1), Http.body(ex)).toMap()));
        delete("/api/accounts/" + ID, (ex, me, m) -> { accounts.deleteAccount(me, m.group(1)); Http.json(ex, 200, Json.obj("ok", true)); });

        get("/api/contacts", (ex, me, m) -> {
            Map<String, String> q = Http.query(ex);
            Http.json(ex, 200, accounts.listContacts(q.get("accountId"), q.get("q")));
        });
        post("/api/contacts", (ex, me, m) -> Http.json(ex, 201, accounts.createContact(me, Http.body(ex)).toMap()));
        put("/api/contacts/" + ID, (ex, me, m) -> Http.json(ex, 200, accounts.updateContact(me, m.group(1), Http.body(ex)).toMap()));
        delete("/api/contacts/" + ID, (ex, me, m) -> { accounts.deleteContact(me, m.group(1)); Http.json(ex, 200, Json.obj("ok", true)); });

        // ---- deals
        get("/api/deals", (ex, me, m) -> Http.json(ex, 200, deals.list(me, filter(ex))));
        get("/api/deals/export\\.csv", (ex, me, m) -> Http.text(ex, 200, "text/csv; charset=utf-8",
            deals.exportCsv(me, filter(ex)), "deals-" + LocalDate.now() + ".csv"));
        post("/api/deals", (ex, me, m) -> {
            String id = deals.create(me, Http.body(ex)).id;
            Http.json(ex, 201, deals.get(me, id));
        });
        get("/api/deals/" + ID, (ex, me, m) -> Http.json(ex, 200, deals.get(me, m.group(1))));
        put("/api/deals/" + ID, (ex, me, m) -> {
            deals.update(me, m.group(1), Http.body(ex));
            Http.json(ex, 200, deals.get(me, m.group(1)));
        });
        post("/api/deals/" + ID + "/stage", (ex, me, m) -> {
            Map<String, Object> b = Http.body(ex);
            deals.moveStage(me, m.group(1), Json.str(b, "stage"), Json.str(b, "lostReason"));
            Http.json(ex, 200, deals.get(me, m.group(1)));
        });
        delete("/api/deals/" + ID, (ex, me, m) -> { deals.delete(me, m.group(1)); Http.json(ex, 200, Json.obj("ok", true)); });
        post("/api/deals/" + ID + "/notes", (ex, me, m) -> {
            deals.addNote(me, m.group(1), Json.str(Http.body(ex), "text"));
            Http.json(ex, 201, deals.get(me, m.group(1)));
        });
        delete("/api/deals/" + ID + "/notes/" + ID, (ex, me, m) -> {
            deals.deleteNote(me, m.group(1), m.group(2));
            Http.json(ex, 200, deals.get(me, m.group(1)));
        });

        // ---- activities
        get("/api/activities", (ex, me, m) -> {
            Map<String, String> q = Http.query(ex);
            Http.json(ex, 200, activities.list(me, q.get("owner"), q.get("dealId"), q.get("status")));
        });
        post("/api/activities", (ex, me, m) -> Http.json(ex, 201, activities.create(me, Http.body(ex)).toMap()));
        put("/api/activities/" + ID, (ex, me, m) -> Http.json(ex, 200, activities.update(me, m.group(1), Http.body(ex)).toMap()));
        post("/api/activities/" + ID + "/complete", (ex, me, m) -> {
            Map<String, Object> b = Http.body(ex);
            Http.json(ex, 200, activities.complete(me, m.group(1), Json.bool(b, "done", true), Json.str(b, "outcome")).toMap());
        });
        delete("/api/activities/" + ID, (ex, me, m) -> { activities.delete(me, m.group(1)); Http.json(ex, 200, Json.obj("ok", true)); });

        // ---- numbers
        get("/api/dashboard", (ex, me, m) -> Http.json(ex, 200, analytics.dashboard(me, owner(ex, me))));
        get("/api/reports", (ex, me, m) -> {
            Map<String, String> q = Http.query(ex);
            int months = 6;
            try { months = Integer.parseInt(q.getOrDefault("months", "6")); } catch (NumberFormatException ignored) { }
            Http.json(ex, 200, analytics.reports(owner(ex, me), months));
        });
    }

    /** ?owner=me | <username> | (blank = whole team). */
    private String owner(HttpExchange ex, User me) {
        String o = Http.query(ex).get("owner");
        if (o == null || o.isBlank() || o.equals("team")) return null;
        if (o.equals("me")) return me.username;
        if (store.user(o) == null) throw ServiceException.bad("Unknown person");
        return store.user(o).username;
    }

    private static DealService.Filter filter(HttpExchange ex) {
        Map<String, String> q = Http.query(ex);
        DealService.Filter f = new DealService.Filter();
        f.status = q.get("status");
        f.stage = q.get("stage");
        f.owner = q.get("owner");
        f.accountId = q.get("accountId");
        f.q = q.get("q");
        f.priority = q.get("priority");
        f.closing = q.get("closing");
        f.health = q.get("health");
        f.sort = q.get("sort");
        return f;
    }

    private static Map<String, Object> meta() {
        List<Object> stages = new ArrayList<>();
        for (Stage s : Stage.values()) stages.add(Json.obj("id", s, "label", s.label, "probability", s.probability, "open", s.isOpen()));
        List<Object> types = new ArrayList<>();
        for (ActivityType t : ActivityType.values()) types.add(Json.obj("id", t, "label", t.label));
        List<Object> sources = new ArrayList<>();
        for (LeadSource s : LeadSource.values()) sources.add(Json.obj("id", s, "label", s.label));
        return Json.obj(
            "stages", stages,
            "activityTypes", types,
            "sources", sources,
            "priorities", List.of(Priority.values()),
            "roles", List.of(Role.values()),
            "lostReasons", List.of(Enums.LOST_REASONS),
            "industries", List.of(Enums.INDUSTRIES),
            "sizes", List.of("1-10", "11-50", "51-200", "201-1000", "1000+"),
            "staleDays", DealService.STALE_DAYS,
            "today", LocalDate.now().toString());
    }
}
