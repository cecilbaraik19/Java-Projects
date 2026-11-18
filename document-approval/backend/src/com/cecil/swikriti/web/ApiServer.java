package com.cecil.swikriti.web;

import com.cecil.swikriti.core.Condition;
import com.cecil.swikriti.model.Attachment;
import com.cecil.swikriti.model.Document;
import com.cecil.swikriti.model.User;
import com.cecil.swikriti.service.Access;
import com.cecil.swikriti.service.AdminService;
import com.cecil.swikriti.service.AuthService;
import com.cecil.swikriti.service.DocumentService;
import com.cecil.swikriti.service.Problem;
import com.cecil.swikriti.service.ViewService;
import com.cecil.swikriti.service.WorkflowService;
import com.cecil.swikriti.store.DataStore;
import com.cecil.swikriti.util.Json;
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
 *   /                    the website (files from the frontend folder)
 *   /api/...             the JSON API (needs sign-in; every change needs the X-Swikriti header = CSRF protection)
 *   /api/public/verify   checking an approval certificate - the only API that works without signing in
 *
 * Mistakes the person can fix (Problem) become friendly messages with status 400/403/404/409; anything else becomes
 * "Something went wrong" (the details go to the console, never to the browser).
 */
public class ApiServer {
    public static final String COOKIE = "swikriti_session";
    public static final String CSRF_HEADER = "X-Swikriti";

    /** All the parts the server talks to. */
    public record Parts(DataStore store, AuthService auth, DocumentService docs, WorkflowService flow,
                        AdminService admin, ViewService views) { }

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

        // public: check a certificate (rate limited, no sign-in)
        routes.add(new Route("GET", Pattern.compile("/api/public/verify"), false, c -> {
            if (!allow("verify:" + Http.clientIp(c.ex), 30, 60_000)) throw new Problem(429, "Too many checks. Wait a minute.");
            return p.views().verify(c.q("code"));
        }));

        // pages
        get("/api/inbox", c -> p.views().inbox(c.user));
        get("/api/docs", c -> p.views().docs(c.user, c.q("scope"), c.q("status"), c.q("type"), c.q("q")));
        get("/api/library", c -> p.views().library(c.user));
        get("/api/docs/" + ID, c -> p.views().doc(c.user, c.id));
        get("/api/docs/" + ID + "/diff", c -> p.views().diff(c.user, c.id, c.qi("a", 0), c.qi("b", 0)));
        get("/api/docs/" + ID + "/file", this::file);
        get("/api/reports", c -> p.views().reports(c.user));

        // writing documents
        post("/api/docs", c -> p.docs().create(c.user, c.body()));
        put("/api/docs/" + ID, c -> p.docs().update(c.user, c.id, c.body()));
        post("/api/docs/" + ID + "/attach", c -> p.docs().attach(c.user, c.id, c.body()));
        del("/api/docs/" + ID + "/attach", c -> p.docs().detach(c.user, c.id));
        post("/api/docs/" + ID + "/submit", c -> p.docs().submit(c.user, c.id, Json.str(c.body(), "note")));
        post("/api/docs/" + ID + "/withdraw", c -> p.docs().withdraw(c.user, c.id, Json.str(c.body(), "reason")));
        post("/api/docs/" + ID + "/comments", c -> {
            Map<String, Object> b = c.body();
            return p.docs().comment(c.user, c.id, (int) Json.num(b, "line", 0), Json.str(b, "text"));
        });
        post("/api/comments/" + ID + "/resolve", c -> p.docs().resolve(c.user, c.id));
        post("/api/preview", c -> Json.obj("stages", p.docs().preview(c.user, c.body())));

        // deciding
        post("/api/tasks/" + ID + "/decide", c -> {
            Map<String, Object> b = c.body();
            return p.flow().decide(c.user, c.id, Json.str(b, "action"), Json.str(b, "comment"));
        });

        // delegation
        get("/api/delegations", c -> p.views().delegations(c.user));
        post("/api/delegations", c -> p.flow().delegate(c.user, c.body()));
        post("/api/delegations/" + ID + "/cancel", c -> p.flow().cancelDelegation(c.user, c.id));

        // notifications
        get("/api/notes", c -> p.views().notes(c.user));
        post("/api/notes/read", c -> Json.obj("read", p.views().read(c.user, Json.str(c.body(), "id"))));

        // the Document Controller
        get("/api/admin", c -> p.views().admin(c.user));
        post("/api/admin/users", c -> ViewService.person(p.admin().saveUser(c.user, null, c.body())));
        put("/api/admin/users/" + ID, c -> ViewService.person(p.admin().saveUser(c.user, c.id, c.body())));
        post("/api/admin/templates", c -> p.admin().saveTemplate(c.user, null, c.body()));
        put("/api/admin/templates/" + ID, c -> p.admin().saveTemplate(c.user, c.id, c.body()));
        post("/api/admin/holidays", c -> Json.obj("holidays", p.admin().setHolidays(c.user, Json.strings(c.body(), "dates"))));
        get("/api/admin/ledger", c -> p.views().ledger(c.user));
        post("/api/admin/tick", c -> { Access.admin(c.user); return p.flow().tick(); });
        post("/api/condition", c -> {
            Map<String, Object> b = c.body();
            String cond = Json.str(b, "condition", "");
            String err = Condition.check(cond);
            Object result = null;
            if (err == null && b.get("facts") instanceof Map<?, ?> f) result = Condition.test(cond, cast(f));
            return Json.obj("ok", err == null, "error", err, "result", result);
        });
        get("/api/export.csv", c -> {
            byte[] csv = ("﻿" + p.admin().exportCsv(c.user)).getBytes(StandardCharsets.UTF_8);
            Http.download(c.ex, "text/csv; charset=utf-8", "swikriti-document-register.csv", csv);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }

    /** Download (or show, for images) the attachment of a revision (rev=0: the draft's working copy, author only). */
    private Object file(Ctx c) throws IOException {
        Document d = Access.see(p.store(), c.user, c.id);
        int rev = c.qi("rev", d.rev());
        Attachment a;
        if (rev == 0) {
            if (!d.authorId.equals(c.user.id)) throw Problem.notFound("File");
            a = d.attachment;
        } else {
            if (rev < 1 || rev > d.rev()) throw Problem.notFound("Revision");
            a = d.revisions.get(rev - 1).attachment;
        }
        if (a == null) throw Problem.notFound("File");
        byte[] bytes = p.store().getFile(a.sha);
        if (bytes == null) throw Problem.notFound("File");
        boolean inline = "1".equals(c.q("inline")) && a.mime.startsWith("image/");
        c.ex.getResponseHeaders().set("Content-Type", a.mime);
        c.ex.getResponseHeaders().set("Cache-Control", "private, max-age=600");
        c.ex.getResponseHeaders().set("Content-Disposition", (inline ? "inline" : "attachment") + "; filename=\""
                + a.name.replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
        Http.send(c.ex, 200, bytes);
        return null;
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
            Thread t = new Thread(r, "swikriti-web");
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
        if (path.equals("/") || path.equals("/index.html") || path.equals("/verify")) rel = "index.html";
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
