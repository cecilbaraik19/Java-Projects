package com.cecil.parisar.web;

import com.cecil.parisar.ai.Assistant;
import com.cecil.parisar.ai.EnergyForecaster;
import com.cecil.parisar.ai.RiskModel;
import com.cecil.parisar.cloud.CloudService;
import com.cecil.parisar.iot.IotService;
import com.cecil.parisar.iot.Simulator;
import com.cecil.parisar.model.AuditEntry;
import com.cecil.parisar.model.Building;
import com.cecil.parisar.model.Course;
import com.cecil.parisar.model.Role;
import com.cecil.parisar.model.Room;
import com.cecil.parisar.model.User;
import com.cecil.parisar.service.AcademicService;
import com.cecil.parisar.service.AnalyticsService;
import com.cecil.parisar.service.AuthService;
import com.cecil.parisar.service.Problem;
import com.cecil.parisar.store.DataStore;
import com.cecil.parisar.util.Clock;
import com.cecil.parisar.util.Json;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The web server:
 *   /                 the website (files from the frontend folder)
 *   /api/...          the JSON API for the website (needs sign-in; changes need the X-Parisar header = CSRF protection)
 *   /iot/ingest       where IoT devices send their readings (needs the device id + secret key, no cookie)
 *
 * Mistakes the person can fix (Problem) become friendly messages; anything else becomes "Something went wrong"
 * (the details go to the console, never to the browser).
 */
public class ApiServer {
    public static final String COOKIE = "parisar_session";

    private final Path frontend;
    private final DataStore store;
    private final AuthService auth;
    private final IotService iot;
    private final AcademicService academic;
    private final AnalyticsService analytics;
    private final Assistant assistant;
    private final EnergyForecaster energy;
    private final CloudService cloud;
    private final Simulator sim;
    private final RiskModel model;
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

    public record Parts(DataStore store, AuthService auth, IotService iot, AcademicService academic, AnalyticsService analytics,
                        Assistant assistant, EnergyForecaster energy, CloudService cloud, Simulator sim, RiskModel model) { }

    public ApiServer(Path frontend, Parts p) {
        this.frontend = frontend;
        this.store = p.store();
        this.auth = p.auth();
        this.iot = p.iot();
        this.academic = p.academic();
        this.analytics = p.analytics();
        this.assistant = p.assistant();
        this.energy = p.energy();
        this.cloud = p.cloud();
        this.sim = p.sim();
        this.model = p.model();
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
            auth.logout(Http.cookie(c.ex, COOKIE));
            c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            return Json.obj("ok", true);
        }));
        routes.add(new Route("GET", Pattern.compile("/api/me"), false, c -> Json.obj(
                "user", c.user == null ? null : c.user.publicView(), "meta", c.user == null ? null : meta(c.user))));
        post("/api/me/password", c -> {
            Map<String, Object> b = c.body();
            auth.changeOwnPassword(c.user, Json.str(b, "current"), Json.str(b, "next"));
            return Json.obj("ok", true);
        });

        get("/api/overview", c -> analytics.overview(c.user));
        get("/api/map", c -> analytics.map());
        get("/api/rooms/" + ID, c -> analytics.roomDetail(c.user, c.id));
        post("/api/rooms/" + ID + "/control", c -> iot.control(c.user, c.id, c.body()).toJson());

        // IoT devices and alerts
        get("/api/devices", c -> { staff(c.user); return Json.obj("devices", iot.devices()); });
        post("/api/devices", c -> iot.register(c.user, c.body()));
        get("/api/devices/" + ID, c -> { staff(c.user); return iot.deviceDetail(c.id); });
        post("/api/devices/" + ID + "/key", c -> iot.rotateKey(c.user, c.id));
        post("/api/devices/" + ID + "/reboot", c -> sim.reboot(c.user, c.id));
        post("/api/devices/" + ID + "/active", c -> iot.setActive(c.user, c.id, Json.bool(c.body(), "active", true)).publicView(Clock.millis(), IotService.OFFLINE_MS));
        get("/api/alerts", c -> { staff(c.user); return Json.obj("alerts", iot.alerts(c.user, c.query().getOrDefault("status", "OPEN"))); });
        post("/api/alerts/" + ID + "/ack", c -> iot.ack(c.user, c.id).toJson());
        post("/api/alerts/" + ID + "/resolve", c -> iot.resolveByHand(c.user, c.id, Json.str(c.body(), "note")).toJson());

        // classes and attendance
        get("/api/classes", c -> academic.classesView(c.user));
        post("/api/courses/" + ID + "/start", c -> academic.sessionDetail(c.user, academic.startExtra(c.user, c.id).id));
        get("/api/sessions/" + ID, c -> academic.sessionDetail(c.user, c.id));
        post("/api/sessions/" + ID + "/end", c -> academic.sessionDetail(c.user, academic.end(c.user, c.id).id));
        post("/api/sessions/" + ID + "/mark", c -> {
            Map<String, Object> b = c.body();
            academic.mark(c.user, c.id, Json.str(b, "studentId"), Json.bool(b, "present", true));
            return academic.sessionDetail(c.user, c.id);
        });
        post("/api/sessions/" + ID + "/simulate", c -> {
            Map<String, Object> s = academic.sessionDetail(c.user, c.id);
            if (!Boolean.TRUE.equals(s.get("open"))) throw Problem.bad("The class is not running.");
            sim.scheduleTaps(store.sessions.get(c.id));
            return Json.obj("ok", true, "note", "Students will start tapping their cards in the next minutes.");
        });

        // students and the AI model
        get("/api/students", c -> analytics.students(c.user, c.query()));
        get("/api/students/" + ID, c -> analytics.studentDetail(c.user, c.id));
        get("/api/ai/model", c -> { staff(c.user); return model.report(); });
        post("/api/ai/retrain", c -> {
            if (c.user.role != Role.ADMIN) throw Problem.forbidden("Only the campus admin can retrain the model.");
            Map<String, Object> b = c.body();
            int epochs = (int) Math.max(50, Math.min(5000, Json.num(b, "epochs", 1500)));
            double lr = Math.max(0.001, Math.min(2, Json.dbl(b, "learningRate", 0.3)));
            double l2 = Math.max(0, Math.min(1, Json.dbl(b, "l2", 0.01)));
            Map<String, Object> r = model.train(RiskModel.history(7), epochs, lr, l2);
            store.log(c.user.id, "ai.retrain", "risk model", "accuracy " + r.get("accuracy") + "%");
            store.save("audit");
            return r;
        });
        post("/api/assistant", c -> assistant.ask(c.user, Json.str(c.body(), "question")));

        // energy, analytics, cloud
        get("/api/energy", c -> {
            String b = c.query().get("meter");
            if (b != null && !b.isBlank() && !store.devices.containsKey(b)) throw Problem.notFound("Meter");
            Map<String, Object> f = energy.forecast(b == null || b.isBlank() ? null : b, 12);
            f.put("meters", iot.devices().stream().filter(d -> "ENERGY".equals(d.get("type"))).toList());
            return f;
        });
        get("/api/analytics", c -> analytics.analytics(c.user));
        get("/api/cloud", c -> cloud.status(c.user));
        post("/api/cloud/sync", c -> { admin(c.user); return cloud.sync(c.user.id); });
        post("/api/cloud/backup", c -> { admin(c.user); return cloud.backup(c.user.id); });
        post("/api/cloud/verify", c -> { admin(c.user); return cloud.verify(c.user.id); });
        get("/api/cloud/object", c -> Json.obj("key", c.query().get("key"), "text", cloud.read(c.user, c.query().get("key"))));

        // simulator (demo tools)
        get("/api/sim", c -> { admin(c.user); return sim.status(); });
        post("/api/sim/fault", c -> {
            Map<String, Object> b = c.body();
            return sim.inject(c.user, Json.str(b, "target"), Json.str(b, "kind"), (int) Json.num(b, "minutes", 30));
        });
        post("/api/sim/clear", c -> sim.clear(c.user, Json.str(c.body(), "key")));
        post("/api/sim/settings", c -> sim.settings(c.user, c.body()));

        // notices, users, activity log
        get("/api/notices", c -> Json.obj("notices", academic.notices()));
        post("/api/notices", c -> academic.postNotice(c.user, c.body()).toJson());
        post("/api/notices/" + ID + "/delete", c -> { academic.deleteNotice(c.user, c.id); return Json.obj("ok", true); });
        get("/api/users", c -> { admin(c.user); return Json.obj("users", auth.listUsers(), "students", unlinkedStudents()); });
        post("/api/users", c -> auth.createUser(c.user, c.body()).publicView());
        put("/api/users/" + ID, c -> auth.updateUser(c.user, c.id, c.body()).publicView());
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

    private List<Object> unlinkedStudents() {
        synchronized (store) {
            java.util.Set<String> linked = new java.util.HashSet<>();
            for (User u : store.users.values()) if (u.studentId != null) linked.add(u.studentId);
            List<Object> out = new ArrayList<>();
            for (var s : store.students.values()) if (!linked.contains(s.id)) out.add(Json.obj("id", s.id, "name", s.name, "roll", s.roll));
            return out;
        }
    }

    private static void staff(User u) {
        if (!u.role.atLeast(Role.FACULTY)) throw Problem.forbidden("This page is for staff.");
    }

    private static void admin(User u) {
        if (u.role != Role.ADMIN) throw Problem.forbidden("Only the campus admin can do this.");
    }

    /** Lists for the forms (rooms, buildings ...) so the browser does not hard-code them. */
    private Map<String, Object> meta(User u) {
        synchronized (store) {
            List<Object> rooms = new ArrayList<>(), buildings = new ArrayList<>(), courses = new ArrayList<>();
            for (Room r : store.rooms.values()) rooms.add(Json.obj("id", r.id, "name", r.name, "buildingId", r.buildingId, "hasAC", r.hasAC, "kind", r.kind));
            for (Building b : store.buildings.values()) buildings.add(Json.obj("id", b.id, "name", b.name, "code", b.code));
            for (Course c : store.courses.values()) courses.add(Json.obj("id", c.id, "name", c.name, "mine", u.id.equals(c.facultyId)));
            return Json.obj("rooms", rooms, "buildings", buildings, "courses", courses, "today", Clock.today().toString(),
                    "faults", Simulator.FAULTS, "college", "Kanchan Institute of Technology, Ranchi");
        }
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
            if (path.equals("/iot/ingest")) ingest(ex);
            else if (path.startsWith("/api/")) api(ex, path);
            else staticFile(ex, path);
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e);
            e.printStackTrace();
            try { Http.error(ex, 500, "Something went wrong on the server.", null); } catch (Exception ignored) { }
        } finally {
            ex.close();
        }
    }

    /** POST /iot/ingest  with headers X-Device-Id and X-Device-Key and a JSON body of readings. */
    private void ingest(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) { Http.error(ex, 405, "Use POST.", null); return; }
            String type = ex.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.toLowerCase().startsWith("application/json")) { Http.error(ex, 415, "Send JSON.", null); return; }
            String body = Http.body(ex);
            Map<String, Object> m;
            try { m = Json.parseObject(body); } catch (IllegalArgumentException e) { throw Problem.bad("The message was not valid JSON."); }
            Map<String, Object> result = iot.ingest(ex.getRequestHeaders().getFirst("X-Device-Id"), ex.getRequestHeaders().getFirst("X-Device-Key"), m);
            Http.json(ex, 200, result);
        } catch (Problem p) {
            Http.error(ex, p.status, p.getMessage(), p.data);
        } catch (Http.TooBig t) {
            Http.error(ex, 413, "Message too large.", null);
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
                if (!"1".equals(ex.getRequestHeaders().getFirst("X-Parisar"))) {
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
            Http.json(ex, 200, result);
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
