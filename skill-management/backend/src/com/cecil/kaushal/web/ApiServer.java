package com.cecil.kaushal.web;

import com.cecil.kaushal.model.User;
import com.cecil.kaushal.service.AdminService;
import com.cecil.kaushal.service.AuthService;
import com.cecil.kaushal.service.CatalogService;
import com.cecil.kaushal.service.GrowthService;
import com.cecil.kaushal.service.Problem;
import com.cecil.kaushal.service.ProfileService;
import com.cecil.kaushal.service.StaffingService;
import com.cecil.kaushal.service.ViewService;
import com.cecil.kaushal.store.DataStore;
import com.cecil.kaushal.util.Json;
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
 *   /             the website (files from the frontend folder)
 *   /api/...      the JSON API (needs sign-in; every change needs the X-Kaushal header = CSRF protection)
 *
 * Mistakes the person can fix (Problem) become friendly messages with status 400/403/404/409; anything else becomes
 * "Something went wrong" (the details go to the console, never to the browser).
 */
public class ApiServer {
    public static final String COOKIE = "kaushal_session";
    public static final String CSRF_HEADER = "X-Kaushal";

    /** All the parts the server talks to. */
    public record Parts(DataStore store, AuthService auth, CatalogService catalog, ProfileService profiles,
                        GrowthService growth, StaffingService staffing, AdminService admin, ViewService views) { }

    private final Path frontend;
    private final Parts p;
    private final List<Route> routes = new ArrayList<>();
    private final Map<String, Deque<Long>> tries = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService pool;

    /** What a route gets: the request, the signed-in person and the ids from the path. */
    public static final class Ctx {
        public final HttpExchange ex;
        public final User user;
        public final String id, id2;
        final String rawBody;

        Ctx(HttpExchange ex, User user, String id, String id2, String rawBody) {
            this.ex = ex; this.user = user; this.id = id; this.id2 = id2; this.rawBody = rawBody;
        }

        public Map<String, Object> body() {
            if (rawBody == null || rawBody.isBlank()) return new LinkedHashMap<>();
            try { return Json.parseObject(rawBody); } catch (IllegalArgumentException e) { throw Problem.bad("The request was not valid JSON."); }
        }

        public String q(String name) { return Http.query(ex).get(name); }
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
        post("/api/me/target", c -> me(p.growth().setTarget(c.user, Json.str(c.body(), "roleId"))));
        get("/api/meta", c -> p.views().meta(c.user));

        // pages
        get("/api/home", c -> p.views().home(c.user));
        get("/api/people/" + ID, c -> p.views().profile(c.user, c.id));
        get("/api/directory", c -> p.views().directory(c.user));
        get("/api/table", c -> p.views().table(c.user));
        get("/api/skills/" + ID, c -> p.views().skill(c.user, c.id));
        get("/api/matrix", c -> p.views().matrix(c.user, c.q("scope"), c.q("category")));
        get("/api/search", c -> p.views().search(c.user, c.q("q"), "1".equals(c.q("claimed"))));
        get("/api/growth", c -> p.views().growth(c.user, c.q("user")));
        get("/api/verify", c -> p.views().queue(c.user, p.profiles()));
        get("/api/reports", c -> p.views().reports(c.user));

        // my skills
        post("/api/claims", c -> p.profiles().claim(c.user, c.body()));
        post("/api/claims/" + ID + "/used", c -> p.profiles().used(c.user, c.id));
        del("/api/claims/" + ID, c -> { p.profiles().remove(c.user, c.id); return OK; });
        post("/api/claims/" + ID + "/verify", c -> {
            Map<String, Object> b = c.body();
            return p.profiles().verify(c.user, c.id, (int) Json.num(b, "level", 0), Json.str(b, "note"));
        });
        post("/api/claims/" + ID + "/return", c -> p.profiles().sendBack(c.user, c.id, Json.str(c.body(), "note")));
        post("/api/endorse", c -> {
            Map<String, Object> b = c.body();
            return p.profiles().endorse(c.user, Json.str(b, "toId"), Json.str(b, "skillId"), Json.str(b, "comment"));
        });
        del("/api/endorsements/" + ID, c -> { p.profiles().unendorse(c.user, c.id); return OK; });
        post("/api/certs", c -> p.profiles().saveCert(c.user, null, c.body()));
        put("/api/certs/" + ID, c -> p.profiles().saveCert(c.user, c.id, c.body()));
        del("/api/certs/" + ID, c -> { p.profiles().deleteCert(c.user, c.id); return OK; });

        // growth
        post("/api/goals", c -> p.growth().addGoal(c.user, c.body()));
        post("/api/goals/plan", c -> {
            Map<String, Object> b = c.body();
            return Json.obj("goals", p.growth().planForRole(c.user, Json.str(b, "userId", c.user.id), Json.str(b, "roleId")));
        });
        post("/api/goals/" + ID + "/step", c -> {
            Map<String, Object> b = c.body();
            return p.growth().step(c.user, c.id, (int) Json.num(b, "index", -1), Json.bool(b, "done", true));
        });
        post("/api/goals/" + ID + "/finish", c -> p.growth().finish(c.user, c.id));
        post("/api/goals/" + ID + "/drop", c -> p.growth().drop(c.user, c.id));

        // projects and staffing
        get("/api/projects", c -> p.views().projects(c.user));
        get("/api/projects/" + ID, c -> p.views().project(c.user, c.id));
        post("/api/projects", c -> p.staffing().saveProject(c.user, null, c.body()));
        put("/api/projects/" + ID, c -> p.staffing().saveProject(c.user, c.id, c.body()));
        get("/api/projects/" + ID + "/suggest", c -> p.staffing().suggest(c.user, c.id));
        post("/api/projects/" + ID + "/slots", c -> p.staffing().saveSlot(c.user, c.id, null, c.body()));
        put("/api/projects/" + ID + "/slots/" + ID, c -> p.staffing().saveSlot(c.user, c.id, c.id2, c.body()));
        del("/api/projects/" + ID + "/slots/" + ID, c -> p.staffing().deleteSlot(c.user, c.id, c.id2));
        post("/api/projects/" + ID + "/slots/" + ID + "/assign", c -> {
            Map<String, Object> b = c.body();
            return p.staffing().assign(c.user, c.id, c.id2, Json.str(b, "userId"), Json.bool(b, "stretch", false));
        });
        post("/api/projects/" + ID + "/slots/" + ID + "/unassign", c -> p.staffing().unassign(c.user, c.id, c.id2));

        // notifications
        get("/api/notes", c -> p.views().notes(c.user));
        post("/api/notes/read", c -> Json.obj("read", p.views().read(c.user, Json.str(c.body(), "id"))));

        // HR: catalogue, roles, people, import / export
        post("/api/catalog/categories", c -> p.catalog().addCategory(c.user, c.body()));
        post("/api/catalog/skills", c -> p.catalog().saveSkill(c.user, null, c.body()));
        put("/api/catalog/skills/" + ID, c -> p.catalog().saveSkill(c.user, c.id, c.body()));
        post("/api/catalog/skills/" + ID + "/active", c -> p.catalog().retire(c.user, c.id, Json.bool(c.body(), "active", true)));
        post("/api/roles", c -> p.growth().saveRole(c.user, null, c.body()));
        put("/api/roles/" + ID, c -> p.growth().saveRole(c.user, c.id, c.body()));
        get("/api/admin", c -> p.views().admin(c.user));
        post("/api/admin/users", c -> ViewService.person(p.admin().saveUser(c.user, null, c.body())));
        put("/api/admin/users/" + ID, c -> ViewService.person(p.admin().saveUser(c.user, c.id, c.body())));
        post("/api/admin/import", c -> {
            Map<String, Object> b = c.body();
            return p.admin().importCsv(c.user, Json.str(b, "csv"), Json.bool(b, "dryRun", true));
        });
        get("/api/export.csv", c -> {
            byte[] csv = ("﻿" + p.admin().exportCsv(c.user)).getBytes(StandardCharsets.UTF_8);
            Http.download(c.ex, "text/csv; charset=utf-8", "kaushal-skill-matrix.csv", csv);
            return null;
        });
    }

    private Map<String, Object> me(User u) {
        Map<String, Object> m = ViewService.person(u);
        m.put("username", u.username);
        m.put("admin", u.admin());
        m.put("manager", u.manager());
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
            Thread t = new Thread(r, "kaushal-web");
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
            String id2 = found.groupCount() >= 2 ? found.group(2) : null;
            Object result = route.handler().handle(new Ctx(ex, user, id, id2, body));
            if (result != null) Http.json(ex, 200, result);
        } catch (Problem pr) {
            Http.error(ex, pr.status, pr.getMessage(), pr.data);
        } catch (Http.TooBig t) {
            Http.error(ex, 413, "That is too much in one go (max 200 KB).", null);
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
