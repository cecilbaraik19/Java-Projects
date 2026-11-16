package com.cecil.dainandini.web;

import com.cecil.dainandini.ai.WorkTime;
import com.cecil.dainandini.model.Task;
import com.cecil.dainandini.model.User;
import com.cecil.dainandini.service.AdminService;
import com.cecil.dainandini.service.AssistantService;
import com.cecil.dainandini.service.AuthService;
import com.cecil.dainandini.service.Brain;
import com.cecil.dainandini.service.CalendarService;
import com.cecil.dainandini.service.FocusService;
import com.cecil.dainandini.service.NoteService;
import com.cecil.dainandini.service.Problem;
import com.cecil.dainandini.service.TaskService;
import com.cecil.dainandini.store.DataStore;
import com.cecil.dainandini.util.Clock;
import com.cecil.dainandini.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
 * The web server (the JDK's own com.sun.net.httpserver – no Tomcat, no Spring):
 *   /             the website (files from the frontend folder)
 *   /api/...      the JSON API (needs sign-in; every change needs the X-Dainandini header = CSRF protection)
 *
 * Mistakes the person can fix (Problem) become friendly messages with status 400/403/404/409; anything else becomes
 * "Something went wrong" (the details go to the console, never to the browser).
 */
public class ApiServer {
    public static final String COOKIE = "dainandini_session";
    public static final String CSRF_HEADER = "X-Dainandini";

    /** All the parts the server talks to. */
    public record Parts(DataStore store, AuthService auth, Brain brain, TaskService tasks, CalendarService calendar,
                        FocusService focus, NoteService notes, AssistantService assistant, AdminService admin) { }

    private final Path frontend;
    private final Parts p;
    private final DataStore store;
    private final List<Route> routes = new ArrayList<>();
    private final Map<String, Deque<Long>> tries = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService pool;

    /** What a route gets: the request, the signed-in person and the {id} from the path. */
    public static final class Ctx {
        public final HttpExchange ex;
        public final User user;
        public final String id;
        final String rawBody;

        Ctx(HttpExchange ex, User user, String id, String rawBody) { this.ex = ex; this.user = user; this.id = id; this.rawBody = rawBody; }

        public Map<String, Object> body() {
            if (rawBody == null || rawBody.isBlank()) return new LinkedHashMap<>();
            try { return Json.parseObject(rawBody); } catch (IllegalArgumentException e) { throw Problem.bad("The request was not valid JSON."); }
        }

        public Map<String, String> query() { return Http.query(ex); }
    }

    @FunctionalInterface
    interface Handler { Object handle(Ctx c) throws Exception; }

    record Route(String method, Pattern path, boolean needsLogin, Handler handler) { }

    public ApiServer(Path frontend, Parts parts) {
        this.frontend = frontend;
        this.p = parts;
        this.store = parts.store();
        defineRoutes();
    }

    // ------------------------------------------------------------------ routes

    private void get(String path, Handler h) { routes.add(new Route("GET", Pattern.compile(path), true, h)); }
    private void post(String path, Handler h) { routes.add(new Route("POST", Pattern.compile(path), true, h)); }
    private void put(String path, Handler h) { routes.add(new Route("PUT", Pattern.compile(path), true, h)); }

    private static final String ID = "([A-Za-z0-9-]{1,40})";

    private void defineRoutes() {
        // signing in and me
        routes.add(new Route("POST", Pattern.compile("/api/login"), false, this::login));
        routes.add(new Route("POST", Pattern.compile("/api/logout"), false, c -> {
            p.auth().logout(Http.cookie(c.ex, COOKIE));
            c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            return Json.obj("ok", true);
        }));
        routes.add(new Route("GET", Pattern.compile("/api/me"), false, c -> Json.obj(
                "user", c.user == null ? null : c.user.card(), "meta", c.user == null ? null : meta(c.user))));
        put("/api/me", c -> p.admin().updateMe(c.user, c.body()).card());
        post("/api/me/password", c -> {
            Map<String, Object> b = c.body();
            p.auth().changePassword(c.user, Json.str(b, "current"), Json.str(b, "next"));
            return Json.obj("ok", true);
        });
        get("/api/people", c -> p.admin().people());

        // today, plan, insights, team
        get("/api/today", c -> p.brain().today(c.user));
        get("/api/plan", c -> p.brain().planJson(p.brain().plan(c.user, date(c.query().get("date")), Clock.millis())));
        get("/api/insights", c -> p.brain().insights(c.user, (int) Math.max(7, Math.min(42, num(c.query().get("days"), 14)))));
        get("/api/team", c -> p.brain().team(c.user));
        post("/api/team/suggest", c -> {
            Map<String, Object> b = c.body();
            return p.brain().suggestOwners(c.user, Json.str(b, "title", ""), Json.strings(b, "tags"), Json.str(b, "except"));
        });

        // tasks
        post("/api/capture/preview", c -> p.tasks().preview(c.user, Json.str(c.body(), "text", "")));
        get("/api/tasks", c -> p.tasks().list(c.user, c.query().get("filter"), c.query().get("tag")));
        post("/api/tasks", c -> p.tasks().view(c.user, p.tasks().create(c.user, c.body()).id));
        get("/api/tasks/" + ID, c -> p.tasks().view(c.user, c.id));
        put("/api/tasks/" + ID, c -> p.tasks().view(c.user, p.tasks().update(c.user, c.id, c.body()).id));
        post("/api/tasks/" + ID + "/start", c -> p.tasks().view(c.user, p.tasks().start(c.user, c.id).id));
        post("/api/tasks/" + ID + "/done", c -> {
            Task next = p.tasks().done(c.user, c.id, c.body());
            return Json.obj("task", p.tasks().view(c.user, c.id), "next", next == null ? null : p.tasks().view(c.user, next.id));
        });
        post("/api/tasks/" + ID + "/reopen", c -> p.tasks().view(c.user, p.tasks().reopen(c.user, c.id).id));
        post("/api/tasks/" + ID + "/drop", c -> p.tasks().view(c.user, p.tasks().drop(c.user, c.id).id));
        post("/api/tasks/" + ID + "/delete", c -> { p.tasks().delete(c.user, c.id); return Json.obj("ok", true); });
        post("/api/tasks/" + ID + "/time", c -> p.tasks().view(c.user, p.tasks().logTime(c.user, c.id, (int) Json.num(c.body(), "minutes", 0)).id));
        post("/api/tasks/" + ID + "/assign", c -> p.tasks().view(c.user, p.tasks().assign(c.user, c.id, Json.str(c.body(), "userId")).id));
        get("/api/tasks/" + ID + "/owners", c -> {
            Map<String, Object> t = p.tasks().view(c.user, c.id);
            return p.brain().suggestOwners(c.user, String.valueOf(t.get("title")), Json.strings(t, "tags"), String.valueOf(t.get("ownerId")));
        });

        // calendar
        get("/api/calendar", c -> p.calendar().week(c.user, date(c.query().get("date"))));
        post("/api/events", c -> p.calendar().eventJson(p.calendar().create(c.user, c.body()), c.user));
        post("/api/events/find", c -> p.calendar().findTimes(c.user, c.body()));
        post("/api/events/" + ID + "/delete", c -> { p.calendar().delete(c.user, c.id); return Json.obj("ok", true); });

        // focus
        get("/api/focus", c -> p.focus().page(c.user));
        post("/api/focus/start", c -> {
            Map<String, Object> b = c.body();
            return p.brain().focusJson(p.focus().start(c.user, Json.str(b, "taskId"), (int) Json.num(b, "minutes", 25)));
        });
        post("/api/focus/interrupt", c -> p.brain().focusJson(p.focus().interrupt(c.user)));
        post("/api/focus/stop", c -> p.brain().focusJson(p.focus().stop(c.user, Json.bool(c.body(), "done", false))));

        // meeting notes
        get("/api/notes", c -> p.notes().list(c.user));
        post("/api/notes/analyze", c -> p.notes().analyze(c.user, Json.str(c.body(), "text", ""), Clock.millis()));
        post("/api/notes", c -> p.notes().view(c.user, p.notes().save(c.user, c.body()).id));
        get("/api/notes/" + ID, c -> p.notes().view(c.user, c.id));
        post("/api/notes/" + ID + "/task", c -> {
            Map<String, Object> b = c.body();
            p.notes().makeTask(c.user, c.id, (int) Json.num(b, "index", -1), Json.str(b, "ownerId"));
            return p.notes().view(c.user, c.id);
        });
        post("/api/notes/" + ID + "/delete", c -> { p.notes().delete(c.user, c.id); return Json.obj("ok", true); });

        // assistant
        get("/api/assistant", c -> p.assistant().history(c.user));
        post("/api/assistant", c -> {
            String ip = Http.clientIp(c.ex);
            if (!allow("ask:" + c.user.id + ip, 30, 60_000)) throw new Problem(429, "Slow down a little – 30 questions a minute at most.");
            return Json.obj("messages", p.assistant().ask(c.user, Json.str(c.body(), "text", "")));
        });
        post("/api/assistant/clear", c -> { p.assistant().clear(c.user); return Json.obj("ok", true); });

        // admin
        get("/api/admin/people", c -> {
            if (!c.user.admin()) throw Problem.forbidden("Only the admin can do this.");
            List<Object> all = new ArrayList<>();
            synchronized (store) { for (User u : store.users.values()) all.add(u.card()); }
            return all;
        });
        post("/api/admin/people", c -> p.admin().addPerson(c.user, c.body()).card());
        put("/api/admin/people/" + ID, c -> p.admin().updatePerson(c.user, c.id, c.body()).card());
        get("/api/admin/settings", c -> p.admin().settings(c.user));
        put("/api/admin/settings", c -> p.admin().saveSettings(c.user, c.body()));
        get("/api/admin/audit", c -> p.admin().audit(c.user, 300));
    }

    private static long num(String s, long fallback) {
        try { return s == null ? fallback : Long.parseLong(s); } catch (NumberFormatException e) { return fallback; }
    }

    /** A date from the query (India time), default today. */
    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return WorkTime.dateOf(Clock.millis());
        try {
            LocalDate d = LocalDate.parse(s);
            if (d.getYear() < 2000 || d.getYear() > 2100) throw Problem.bad("Unknown date.");
            return d;
        } catch (DateTimeParseException e) {
            throw Problem.bad("Dates look like 2026-11-16.");
        }
    }

    private Map<String, Object> meta(User u) {
        return Json.obj("today", WorkTime.dateOf(Clock.millis()).toString(), "now", Clock.millis(), "people", p.admin().people(),
                "claude", p.assistant().claudeReady());
    }

    // ------------------------------------------------------------------ signing in

    private Object login(Ctx c) {
        String ip = Http.clientIp(c.ex);
        if (!allow("login:" + ip, 10, 60_000)) throw new Problem(429, "Too many tries. Wait a minute.");
        Map<String, Object> b = c.body();
        String token = p.auth().login(Json.str(b, "username"), Json.str(b, "password"));
        c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200");
        User u = p.auth().userFor(token);
        return Json.obj("user", u.card(), "meta", meta(u));
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
        pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "dainandini-web");
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
            Http.json(ex, 200, result);
        } catch (Problem pr) {
            Http.error(ex, pr.status, pr.getMessage(), pr.data);
        } catch (Http.TooBig t) {
            Http.error(ex, 413, "That is too much text in one go.", null);
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
