package com.cecil.kaksh.web;

import com.cecil.kaksh.core.RoomCalendar;
import com.cecil.kaksh.core.Rules;
import com.cecil.kaksh.core.SlotFinder;
import com.cecil.kaksh.model.Booking;
import com.cecil.kaksh.model.Note;
import com.cecil.kaksh.model.Room;
import com.cecil.kaksh.model.User;
import com.cecil.kaksh.service.AuthService;
import com.cecil.kaksh.service.BookingService;
import com.cecil.kaksh.service.Ics;
import com.cecil.kaksh.service.Problem;
import com.cecil.kaksh.service.ViewService;
import com.cecil.kaksh.store.DataStore;
import com.cecil.kaksh.util.Clock;
import com.cecil.kaksh.util.Json;
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
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The web server (the JDK's own com.sun.net.httpserver – no Tomcat, no Spring):
 *   /             the website (files from the frontend folder)
 *   /api/...      the JSON API (needs sign-in; every change needs the X-Kaksh header = CSRF protection)
 *
 * Mistakes the person can fix (Problem) become friendly messages with status 400/403/404/409; anything else becomes
 * "Something went wrong" (the details go to the console, never to the browser).
 */
public class ApiServer {
    public static final String COOKIE = "kaksh_session";
    public static final String CSRF_HEADER = "X-Kaksh";

    /** All the parts the server talks to. */
    public record Parts(DataStore store, RoomCalendar calendar, SlotFinder finder, AuthService auth,
                        BookingService bookings, ViewService views) { }

    private final Path frontend;
    private final Parts p;
    private final DataStore store;
    private final List<Route> routes = new ArrayList<>();
    private final Map<String, Deque<Long>> tries = new ConcurrentHashMap<>();
    private HttpServer server;
    private java.util.concurrent.ExecutorService pool;

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
    interface Handler { Object handle(Ctx c) throws IOException; }

    record Route(String method, Pattern path, boolean needsLogin, Handler handler) { }

    /** Marker: the handler already sent its own answer (a file download). */
    private static final Object SENT = new Object();

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
        // signing in
        routes.add(new Route("POST", Pattern.compile("/api/login"), false, this::login));
        routes.add(new Route("POST", Pattern.compile("/api/logout"), false, c -> {
            p.auth().logout(Http.cookie(c.ex, COOKIE));
            c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            return Json.obj("ok", true);
        }));
        routes.add(new Route("GET", Pattern.compile("/api/me"), false, c -> Json.obj(
                "user", c.user == null ? null : me(c.user), "meta", c.user == null ? null : meta())));
        post("/api/me/password", c -> {
            Map<String, Object> b = c.body();
            p.auth().changePassword(c.user, Json.str(b, "current"), Json.str(b, "next"));
            return Json.obj("ok", true);
        });

        // looking
        get("/api/home", c -> p.views().home(c.user));
        get("/api/board", c -> p.views().board(c.user, date(c.query().get("date"))));
        get("/api/rooms/" + ID, c -> p.views().roomPage(c.user, c.id, date(c.query().get("date"))));

        // checking and finding (POST because they take a body; they change nothing)
        post("/api/check", c -> {
            Map<String, Object> b = c.body();
            String ignore = Json.str(b, "bookingId");
            User who = c.user;
            if (ignore != null) {
                Booking old;
                synchronized (store) { old = store.bookings.get(ignore); }
                if (old != null && (old.organiserId.equals(c.user.id) || c.user.admin())) {
                    synchronized (store) { who = store.users.getOrDefault(old.organiserId, c.user); }
                } else ignore = null;
            }
            return p.bookings().check(who, p.bookings().read(who, b), ignore);
        });
        post("/api/find", c -> {
            Map<String, Object> b = c.body();
            long s = Json.num(b, "start", 0), e = Json.num(b, "end", 0);
            Rules.checkTime(s, e, c.user);
            BookingService.Req q = p.bookings().read(c.user, b);
            return Json.obj("start", s, "end", e, "rooms", p.finder().rooms(s, e, q.people().size() + 1 + q.guests(), q.needs(), c.user, null));
        });
        post("/api/common", c -> {
            Map<String, Object> b = c.body();
            BookingService.Req q = p.bookings().read(c.user, b);
            int minutes = (int) Json.num(b, "minutes", 60);
            if (minutes < Rules.MIN_MIN || minutes > Rules.MAX_MIN || minutes % Rules.STEP_MIN != 0) throw Problem.bad("Length: 15 minutes to 4 hours, in quarter hours.");
            int days = (int) Math.max(1, Math.min(10, Json.num(b, "days", 5)));
            List<String> everyone = new ArrayList<>(q.people());
            everyone.add(0, c.user.id);
            return Json.obj("slots", p.finder().commonTimes(everyone, date(Json.str(b, "date")), days, minutes,
                    everyone.size() + q.guests(), q.needs(), c.user, 12));
        });

        // bookings
        get("/api/bookings", c -> p.views().mine(c.user, c.query().getOrDefault("tab", "upcoming")));
        post("/api/bookings", c -> {
            List<Booking> made = p.bookings().create(c.user, c.body());
            List<Map<String, Object>> out = new ArrayList<>();
            for (Booking b : made) out.add(p.views().booking(b, c.user, false));
            return Json.obj("bookings", out);
        });
        get("/api/bookings/" + ID, c -> p.views().booking(visible(c.user, c.id), c.user, true));
        put("/api/bookings/" + ID, c -> p.views().booking(p.bookings().update(c.user, c.id, c.body()), c.user, true));
        post("/api/bookings/" + ID + "/cancel", c -> {
            Map<String, Object> b = c.body();
            List<Booking> done = p.bookings().cancel(c.user, c.id, Json.str(b, "scope", "one"), Json.str(b, "reason"));
            return Json.obj("cancelled", done.size(), "booking", p.views().booking(visible(c.user, c.id), c.user, true));
        });
        post("/api/bookings/" + ID + "/checkin", c -> p.views().booking(p.bookings().checkIn(c.user, c.id), c.user, true));
        post("/api/bookings/" + ID + "/end", c -> p.views().booking(p.bookings().endNow(c.user, c.id), c.user, true));
        post("/api/bookings/" + ID + "/extend", c -> p.views().booking(
                p.bookings().extend(c.user, c.id, (int) Json.num(c.body(), "minutes", 15)), c.user, true));
        post("/api/bookings/" + ID + "/approve", c -> p.views().booking(p.bookings().decide(c.user, c.id, true, Json.str(c.body(), "note")), c.user, true));
        post("/api/bookings/" + ID + "/decline", c -> p.views().booking(p.bookings().decide(c.user, c.id, false, Json.str(c.body(), "note")), c.user, true));
        get("/api/bookings/" + ID + "/ics", c -> {
            Booking b = visible(c.user, c.id);
            Http.download(c.ex, "text/calendar; charset=utf-8", b.id + ".ics", Ics.of(store, List.of(b), c.user.id).getBytes(StandardCharsets.UTF_8));
            return SENT;
        });
        get("/api/calendar.ics", c -> {
            List<Booking> list = new ArrayList<>();
            long now = Clock.millis();
            synchronized (store) {
                for (Booking b : store.bookings.values()) if (b.involves(c.user.id) && b.status.open() && b.end > now) list.add(b);
            }
            list.sort(Comparator.comparingLong(b -> b.start));
            Http.download(c.ex, "text/calendar; charset=utf-8", "my-meetings.ics", Ics.of(store, list, c.user.id).getBytes(StandardCharsets.UTF_8));
            return SENT;
        });

        // waitlist
        post("/api/waitlist", c -> p.views().wait(p.bookings().waitFor(c.user, c.body())));
        post("/api/waitlist/" + ID + "/leave", c -> p.views().wait(p.bookings().leaveWait(c.user, c.id)));

        // notifications
        get("/api/notes", c -> p.views().notes(c.user));
        post("/api/notes/read", c -> {
            synchronized (store) {
                for (Note n : store.notes.values()) if (c.user.id.equals(n.userId)) n.read = true;
                store.save("notes");
            }
            return Json.obj("ok", true);
        });

        // facility manager
        get("/api/admin/approvals", c -> p.views().approvals(c.user));
        get("/api/admin/insights", c -> p.views().insights(c.user, (int) Math.max(1, Math.min(12, num(c.query().get("weeks"), 4)))));
        get("/api/admin/audit", c -> p.views().audit(c.user, 300));
        post("/api/blocks", c -> p.bookings().block(c.user, c.body(), "1".equals(c.query().get("dry"))));
        post("/api/blocks/" + ID + "/delete", c -> p.bookings().unblock(c.user, c.id).toJson());
        put("/api/rooms/" + ID, c -> p.views().room(p.bookings().updateRoom(c.user, c.id, c.body())));
    }

    private static long num(String s, long fallback) {
        try { return s == null ? fallback : Long.parseLong(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return Clock.today();
        try {
            LocalDate d = LocalDate.parse(s);
            if (d.getYear() < 2000 || d.getYear() > 2100) throw Problem.bad("Unknown date.");
            return d;
        } catch (DateTimeParseException e) {
            throw Problem.bad("Dates look like 2026-11-16.");
        }
    }

    /** A booking anyone signed in may look at (private details are hidden by ViewService). */
    private Booking visible(User u, String id) {
        synchronized (store) {
            Booking b = store.bookings.get(id);
            if (b == null) throw Problem.notFound("Booking");
            return b;
        }
    }

    private Map<String, Object> me(User u) {
        return Json.obj("id", u.id, "username", u.username, "name", u.name, "email", u.email, "team", u.team,
                "floor", u.floor, "role", u.role, "admin", u.admin());
    }

    private Map<String, Object> meta() {
        List<Map<String, Object>> rooms = new ArrayList<>();
        synchronized (store) { for (Room r : store.rooms.values()) rooms.add(p.views().room(r)); }
        return Json.obj("rooms", rooms, "equipment", Room.EQUIPMENT, "rules", Rules.asJson(), "people", p.views().people(),
                "today", Clock.today().toString(), "now", Clock.millis());
    }

    // ------------------------------------------------------------------ signing in

    private Object login(Ctx c) {
        String ip = Http.clientIp(c.ex);
        if (!allow("login:" + ip, 10, 60_000)) throw new Problem(429, "Too many tries. Wait a minute.");
        Map<String, Object> b = c.body();
        String token = p.auth().login(Json.str(b, "username"), Json.str(b, "password"));
        c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=36000");
        User u = p.auth().userFor(token);
        return Json.obj("user", me(u), "meta", meta());
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
            if (result != SENT) Http.json(ex, 200, result);
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
