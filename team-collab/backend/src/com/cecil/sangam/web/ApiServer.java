package com.cecil.sangam.web;

import com.cecil.sangam.core.EventBus;
import com.cecil.sangam.core.WorkHours;
import com.cecil.sangam.model.Note;
import com.cecil.sangam.model.Task;
import com.cecil.sangam.model.User;
import com.cecil.sangam.service.Access;
import com.cecil.sangam.service.AuthService;
import com.cecil.sangam.service.ChatService;
import com.cecil.sangam.service.DocService;
import com.cecil.sangam.service.MeetingService;
import com.cecil.sangam.service.PeopleService;
import com.cecil.sangam.service.Problem;
import com.cecil.sangam.service.RhythmService;
import com.cecil.sangam.service.ViewService;
import com.cecil.sangam.service.WorkService;
import com.cecil.sangam.store.DataStore;
import com.cecil.sangam.util.Clock;
import com.cecil.sangam.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The web server (the JDK's own com.sun.net.httpserver – no Tomcat, no Spring):
 *   /             the website (files from the frontend folder)
 *   /api/...      the JSON API (needs sign-in; every change needs the X-Sangam header = CSRF protection)
 *   /api/events   live updates by long polling (the request waits up to 20 seconds for news)
 *
 * Mistakes the person can fix (Problem) become friendly messages with status 400/403/404/409; anything else becomes
 * "Something went wrong" (the details go to the console, never to the browser).
 */
public class ApiServer {
    public static final String COOKIE = "sangam_session";
    public static final String CSRF_HEADER = "X-Sangam";
    public static final long POLL_MS = 20_000;

    /** All the parts the server talks to. */
    public record Parts(DataStore store, EventBus events, AuthService auth, PeopleService people, WorkService work,
                        ChatService chat, DocService docs, RhythmService rhythm, MeetingService meetings, ViewService views) { }

    private final Path frontend;
    private final Parts p;
    private final DataStore store;
    private final List<Route> routes = new ArrayList<>();
    private final Map<String, Deque<Long>> tries = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService pool;
    /** Shorter waits in the tests. */
    public long pollMs = POLL_MS;

    /** What a route gets: the request, the signed-in person and the {id} from the path. */
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
                "user", c.user == null ? null : me(c.user), "meta", c.user == null ? null : meta(c.user))));
        put("/api/me", c -> me(p.people().updateMe(c.user, c.body())));
        post("/api/me/focus", c -> me(p.people().focus(c.user, (int) Json.num(c.body(), "minutes", 0))));
        post("/api/me/password", c -> {
            Map<String, Object> b = c.body();
            p.auth().changePassword(c.user, Json.str(b, "current"), Json.str(b, "next"));
            return Json.obj("ok", true);
        });

        // live updates
        get("/api/events", this::events);

        // looking around
        get("/api/home", c -> p.views().home(c.user));
        get("/api/people", c -> p.views().people(c.user));
        get("/api/zones", c -> p.views().zones(c.user, date(c.query().get("date")), c.query().get("project")));
        get("/api/search", c -> p.views().search(c.user, c.query().get("q")));

        // projects and tasks
        get("/api/projects", c -> p.views().projects(c.user));
        post("/api/projects", c -> p.work().createProject(c.user, c.body()).toJson());
        get("/api/projects/" + ID, c -> p.views().board(c.user, c.id));
        put("/api/projects/" + ID, c -> p.work().updateProject(c.user, c.id, c.body()).toJson());
        get("/api/projects/" + ID + "/report", c -> p.views().report(c.user, c.id));
        post("/api/projects/" + ID + "/tasks", c -> view(c.user, p.work().create(c.user, c.id, c.body())));
        post("/api/projects/" + ID + "/docs", c -> p.views().doc(c.user, p.docs().create(c.user, c.id, c.body()).id));
        post("/api/projects/" + ID + "/handoff", c -> Json.obj("ok", true, "handoff", p.rhythm().handoff(c.user, c.id, c.body()).toJson()));
        get("/api/tasks/" + ID, c -> {
            Task t;
            synchronized (store) {
                t = store.tasks.get(c.id);
                if (t == null) t = store.taskByKey(c.id);
                if (t == null || !Access.seeProject(c.user, store.projects.get(t.projectId))) throw Problem.notFound("Task");
            }
            return p.views().task(c.user, t);
        });
        put("/api/tasks/" + ID, c -> view(c.user, p.work().update(c.user, c.id, c.body())));
        post("/api/tasks/" + ID + "/move", c -> {
            Map<String, Object> b = c.body();
            return view(c.user, p.work().move(c.user, c.id, Json.str(b, "status"), Json.str(b, "beforeId")));
        });
        post("/api/tasks/" + ID + "/blockers", c -> view(c.user, p.work().setBlockers(c.user, c.id, Json.strings(c.body(), "blockedBy"))));
        post("/api/tasks/" + ID + "/comments", c -> view(c.user, p.work().comment(c.user, c.id, Json.str(c.body(), "text"))));

        // chat
        get("/api/channels", c -> p.views().channels(c.user));
        post("/api/channels", c -> p.chat().create(c.user, c.body()).toJson());
        post("/api/dm/" + ID, c -> Json.obj("id", p.chat().direct(c.user, c.id).id));
        get("/api/channels/" + ID, c -> p.views().channel(c.user, c.id, num(c.query().get("before"), 0)));
        post("/api/channels/" + ID + "/messages", c -> {
            ChatService.Posted r = p.chat().post(c.user, c.id, c.body());
            Map<String, Object> m;
            synchronized (store) { m = p.views().message(r.message(), c.user, Clock.millis()); }
            return Json.obj("message", m, "later", r.later());
        });
        post("/api/channels/" + ID + "/read", c -> { p.chat().markRead(c.user, c.id); return Json.obj("ok", true); });
        get("/api/messages/" + ID + "/thread", c -> p.views().thread(c.user, c.id));
        put("/api/messages/" + ID, c -> msg(c.user, p.chat().edit(c.user, c.id, Json.str(c.body(), "text"))));
        post("/api/messages/" + ID + "/delete", c -> msg(c.user, p.chat().delete(c.user, c.id)));
        post("/api/messages/" + ID + "/react", c -> msg(c.user, p.chat().react(c.user, c.id, Json.str(c.body(), "emoji"))));

        // documents
        get("/api/docs", c -> p.views().allDocs(c.user));
        get("/api/docs/" + ID, c -> p.views().doc(c.user, c.id));
        put("/api/docs/" + ID, c -> {
            DocService.Saved s = p.docs().save(c.user, c.id, c.body());
            return Json.obj("doc", p.views().doc(c.user, s.doc().id), "merged", s.merged(), "with", s.with());
        });
        get("/api/docs/" + ID + "/diff", c -> p.views().docDiff(c.user, c.id, (int) num(c.query().get("from"), 1), (int) num(c.query().get("to"), 1)));
        post("/api/docs/" + ID + "/restore", c -> Json.obj("doc", p.views().doc(c.user, p.docs().restore(c.user, c.id, (int) Json.num(c.body(), "version", 0)).doc().id)));
        post("/api/docs/" + ID + "/editing", c -> {
            List<String> names = new ArrayList<>();
            for (String id : p.docs().editing(c.user, c.id, Json.bool(c.body(), "stop", false))) names.add(store.userName(id));
            return Json.obj("editing", names);
        });

        // rhythm
        get("/api/standups", c -> p.rhythm().digest(c.user, c.query().get("project")));
        post("/api/standups", c -> p.rhythm().postStandup(c.user, c.body()).toJson());
        post("/api/handoffs/" + ID + "/ack", c -> p.rhythm().ack(c.user, c.id).toJson());

        // meetings
        get("/api/meetings", c -> p.views().meetings(c.user));
        post("/api/meetings/suggest", c -> p.meetings().suggest(c.user, c.body()));
        post("/api/meetings", c -> p.meetings().schedule(c.user, c.body()).toJson());
        post("/api/meetings/" + ID + "/cancel", c -> p.meetings().cancel(c.user, c.id).toJson());

        // notifications
        get("/api/notes", c -> p.views().notes(c.user));
        post("/api/notes/read", c -> {
            long now = Clock.millis();
            synchronized (store) {
                for (Note n : store.notes.values()) if (c.user.id.equals(n.userId) && n.deliverAt <= now) n.read = true;
                store.save("notes");
            }
            return Json.obj("ok", true);
        });

        // admin
        get("/api/admin/audit", c -> p.views().audit(c.user, 300));
        post("/api/admin/people", c -> me(p.people().add(c.user, c.body())));
    }

    private Map<String, Object> view(User u, Task t) { return p.views().task(u, t); }

    private Map<String, Object> msg(User u, com.cecil.sangam.model.Message m) {
        synchronized (store) { return p.views().message(m, u, Clock.millis()); }
    }

    private static long num(String s, long fallback) {
        try { return s == null ? fallback : Long.parseLong(s); } catch (NumberFormatException e) { return fallback; }
    }

    /** A UTC date from the query, default today (UTC). */
    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return LocalDate.ofInstant(Clock.now(), ZoneOffset.UTC);
        try {
            LocalDate d = LocalDate.parse(s);
            if (d.getYear() < 2000 || d.getYear() > 2100) throw Problem.bad("Unknown date.");
            return d;
        } catch (DateTimeParseException e) {
            throw Problem.bad("Dates look like 2026-11-16.");
        }
    }

    private Map<String, Object> me(User u) {
        long now = Clock.millis();
        Map<String, Object> card;
        synchronized (store) { card = PeopleService.card(u, now); }
        card.put("admin", u.admin());
        card.put("guest", u.guest());
        return card;
    }

    private Map<String, Object> meta(User u) {
        return Json.obj("zones", PeopleService.ZONES, "colors", WorkService.COLORS, "reactions", ChatService.REACTIONS,
                "today", Clock.today().toString(), "now", Clock.millis(), "seq", p.events().seq(),
                "people", p.views().people(u), "personalWip", WorkService.PERSONAL_WIP, "urgentPerDay", ChatService.URGENT_PER_DAY);
    }

    // ------------------------------------------------------------------ live updates

    private Object events(Ctx c) throws Exception {
        long since = num(c.query().get("since"), -1);
        User u = c.user;
        EventBus.Batch b = p.events().await(since, pollMs, e -> visible(u, e));
        List<Map<String, Object>> list = new ArrayList<>();
        for (EventBus.Event e : b.events()) list.add(e.toJson());
        int unread = 0;
        long now = Clock.millis();
        synchronized (store) {
            for (Note n : store.notes.values()) if (u.id.equals(n.userId) && !n.read && n.deliverAt <= now) unread++;
        }
        return Json.obj("seq", b.seq(), "reset", b.reset(), "events", list, "unreadNotes", unread, "now", now);
    }

    private boolean visible(User u, EventBus.Event e) {
        synchronized (store) {
            if (e.userIds() != null) return e.userIds().contains(u.id);
            if (e.channelId() != null) return Access.seeChannel(store, u, store.channels.get(e.channelId()));
            if (e.projectId() != null) return Access.seeProject(u, store.projects.get(e.projectId()));
            return true;
        }
    }

    // ------------------------------------------------------------------ signing in

    private Object login(Ctx c) {
        String ip = Http.clientIp(c.ex);
        if (!allow("login:" + ip, 10, 60_000)) throw new Problem(429, "Too many tries. Wait a minute.");
        Map<String, Object> b = c.body();
        String token = p.auth().login(Json.str(b, "username"), Json.str(b, "password"));
        c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200");
        User u = p.auth().userFor(token);
        return Json.obj("user", me(u), "meta", meta(u));
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
        // long polls keep a thread waiting, so the pool may grow (up to 200 open browser tabs)
        pool = new ThreadPoolExecutor(4, 200, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "sangam-web");
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
            java.util.regex.Matcher found = null;
            boolean pathKnown = false;
            for (Route r : routes) {
                java.util.regex.Matcher m = r.path().matcher(path);
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

    /** Used by the background job: new notifications that just came out of quiet hours. */
    public static void releaseHeld(DataStore store, EventBus events, long lastTick, long now) {
        List<String> who = new ArrayList<>();
        synchronized (store) {
            for (Note n : store.notes.values())
                if (n.deliverAt > lastTick && n.deliverAt <= now && !who.contains(n.userId)) who.add(n.userId);
        }
        if (!who.isEmpty()) events.publish("notes", who, null, null, Json.obj("released", true));
        events.publish("tick", null, null, null, Json.obj("now", now, "minute", now / WorkHours.MIN));
    }
}
