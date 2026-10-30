package com.cecil.sld.web;

import com.cecil.sld.model.*;
import com.cecil.sld.service.*;
import com.cecil.sld.store.DataStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.cecil.sld.model.Role.*;

/**
 * The web server. It serves the website from the frontend folder and answers
 * the JSON API under /api/. Every API route checks the user's role here, on the
 * server - the browser is never trusted for that.
 */
public class ApiServer {
    private final DataStore db;
    private final AuthService auth;
    private final DifficultyEngine engine;
    private final QuizService quiz;
    private final CatalogService catalog;
    private final PlanService plans;
    private final UserService users;
    private final DashboardService dash;
    private final Path frontend;
    private final List<Route> routes = new ArrayList<>();
    private HttpServer server;
    private java.util.concurrent.ExecutorService pool;

    public ApiServer(DataStore db, Path frontend, Random random) {
        this.db = db;
        this.frontend = frontend;
        this.auth = new AuthService(db);
        this.engine = new DifficultyEngine(db);
        this.quiz = new QuizService(db, engine, random);
        this.catalog = new CatalogService(db);
        this.plans = new PlanService(db, engine);
        this.users = new UserService(db, auth);
        this.dash = new DashboardService(db, engine, quiz, plans);
        defineRoutes();
    }

    // ================================================================= routing

    @FunctionalInterface
    interface Handler { void handle(Ctx c) throws IOException; }

    /** Everything a route needs: the exchange, the signed-in user, path parts and query. */
    static final class Ctx {
        final HttpExchange ex;
        final User user;
        final Matcher path;
        final Map<String, String> query;
        Ctx(HttpExchange ex, User user, Matcher path, Map<String, String> query) {
            this.ex = ex; this.user = user; this.path = path; this.query = query;
        }
        String p(int i) { return path.group(i); }
        String q(String k) { String v = query.get(k); return v == null || v.isBlank() ? null : v; }
        Map<String, Object> body() throws IOException { return Http.jsonBody(ex); }
        void ok(Object v) throws IOException { Http.json(ex, 200, v); }
    }

    private record Route(String method, Pattern pattern, boolean needsLogin, Role[] roles, Handler handler) {}

    private void route(String method, String path, Handler h, Role... roles) {
        routes.add(new Route(method, Pattern.compile("^" + path + "$"), true, roles, h));
    }

    private void open(String method, String path, Handler h) {
        routes.add(new Route(method, Pattern.compile("^" + path + "$"), false, new Role[0], h));
    }

    private static final String ID = "([A-Za-z0-9]{1,20})";

    private void defineRoutes() {
        // ---------------- sign in
        open("POST", "/api/login", c -> {
            Map<String, Object> b = c.body();
            AuthService.Session s = auth.login(String.valueOf(b.getOrDefault("username", "")), String.valueOf(b.getOrDefault("password", "")));
            Http.setSessionCookie(c.ex, s.token);
            c.ok(Map.of("user", UserService.view(db.user(s.userId))));
        });
        open("POST", "/api/logout", c -> {
            auth.logout(Http.cookie(c.ex, Http.COOKIE));
            Http.clearSessionCookie(c.ex);
            c.ok(Map.of("ok", true));
        });
        route("GET", "/api/me", c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("user", UserService.view(c.user));
            Settings s = db.settings;
            out.put("institution", s.institution);
            out.put("department", s.department);
            out.put("limits", List.of(s.moderateFrom, s.difficultFrom, s.criticalFrom));
            out.put("subjects", catalog.subjects(false));
            out.put("batches", c.user.role == STUDENT ? List.of() : users.batches());
            out.put("actions", PlanService.ACTIONS);
            out.put("today", db.today().toString());
            c.ok(out);
        }, ADMIN, TEACHER, STUDENT);
        route("POST", "/api/me/password", c -> {
            Map<String, Object> b = c.body();
            users.changeOwnPassword(c.user, String.valueOf(b.getOrDefault("current", "")), String.valueOf(b.getOrDefault("next", "")));
            c.ok(Map.of("ok", true));
        }, ADMIN, TEACHER, STUDENT);

        // ---------------- home
        route("GET", "/api/dashboard", c -> {
            if (c.user.role == STUDENT) c.ok(dash.student(c.user));
            else c.ok(dash.teacher(c.user, c.q("batch")));
        }, ADMIN, TEACHER, STUDENT);

        // ---------------- difficulty detection
        route("GET", "/api/students", c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            String batch = c.q("batch");
            for (User st : db.students()) {
                if (batch != null && !batch.equals(st.batch)) continue;
                List<DifficultyEngine.TopicResult> rs = engine.forStudent(st.id, null);
                int hard = 0, critical = 0, surveyed = 0;
                for (DifficultyEngine.TopicResult r : rs) {
                    if (r.band != Band.NO_DATA) surveyed++;
                    if (r.band.ordinal() >= Band.DIFFICULT.ordinal()) hard++;
                    if (r.band == Band.CRITICAL) critical++;
                }
                int tests = 0, right = 0, total = 0;
                String last = null;
                for (Attempt a : db.attempts) if (a.studentId.equals(st.id)) {
                    tests++; right += a.score; total += a.total;
                    if (last == null || a.finishedAt.compareTo(last) > 0) last = a.finishedAt;
                }
                Map<String, Object> m = UserService.view(st);
                m.put("tests", tests);
                m.put("avgScore", total == 0 ? -1 : Math.round(100.0 * right / total) / 100.0);
                m.put("hard", hard);
                m.put("critical", critical);
                m.put("surveyed", surveyed);
                m.put("topics", rs.size());
                m.put("lastActive", last);
                out.add(m);
            }
            c.ok(out);
        }, ADMIN, TEACHER);
        route("GET", "/api/students/" + ID + "/map", c -> {
            String id = c.p(1);
            if (c.user.role == STUDENT && !c.user.id.equals(id)) throw ApiError.forbidden();
            User st = db.user(id);
            if (st == null || st.role != STUDENT) throw ApiError.notFound("Student");
            String subject = c.q("subject");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("student", UserService.view(st));
            out.put("results", engine.forStudent(st.id, subject));
            out.put("plan", engine.studyPlan(st.id, subject));
            out.put("attempts", quiz.attempts(st.id, subject, 12));
            List<Map<String, Object>> sp = new ArrayList<>();
            for (Map<String, Object> p : plans.list(c.user, null))
                if (st.id.equals(p.get("studentId"))) sp.add(p);
            out.put("supportPlans", sp);
            c.ok(out);
        }, ADMIN, TEACHER, STUDENT);
        route("GET", "/api/heatmap", c -> {
            String subject = need(c.q("subject"), "subject");
            c.ok(engine.heatmap(subject, c.q("batch")));
        }, ADMIN, TEACHER);
        route("GET", "/api/class-topics", c -> c.ok(engine.classTopics(c.q("subject"), c.q("batch"))), ADMIN, TEACHER);
        route("GET", "/api/topics/" + ID + "/report", c -> c.ok(engine.topicReport(c.p(1), c.q("batch"))), ADMIN, TEACHER);

        // ---------------- quizzes (students)
        route("POST", "/api/quiz/start", c -> {
            Map<String, Object> b = c.body();
            int size = b.get("size") instanceof Number n ? n.intValue() : 10;
            c.ok(quiz.start(c.user, String.valueOf(b.get("subjectId")), String.valueOf(b.get("kind")), size));
        }, STUDENT);
        route("POST", "/api/quiz/" + ID + "/hint", c -> {
            Map<String, Object> b = c.body();
            c.ok(Map.of("hint", quiz.hint(c.user, c.p(1), String.valueOf(b.get("questionId")))));
        }, STUDENT);
        route("POST", "/api/quiz/" + ID + "/submit", c -> {
            Map<String, Object> b = c.body();
            Object answers = b.get("answers");
            @SuppressWarnings("unchecked") List<Object> list = answers instanceof List ? (List<Object>) answers : null;
            c.ok(quiz.submit(c.user, c.p(1), list));
        }, STUDENT);
        route("GET", "/api/attempts", c -> {
            String student = c.user.role == STUDENT ? c.user.id : c.q("student");
            int limit = 0;
            try { if (c.q("limit") != null) limit = Integer.parseInt(c.q("limit")); } catch (NumberFormatException ignored) { }
            c.ok(quiz.attempts(student, c.q("subject"), limit));
        }, ADMIN, TEACHER, STUDENT);
        route("GET", "/api/attempts/" + ID, c -> c.ok(quiz.detail(c.user, c.p(1))), ADMIN, TEACHER, STUDENT);
        route("POST", "/api/ratings", c -> {
            Map<String, Object> b = c.body();
            int rating = b.get("rating") instanceof Number n ? n.intValue() : 0;
            c.ok(quiz.rate(c.user, String.valueOf(b.get("topicId")), rating, String.valueOf(b.getOrDefault("note", ""))));
        }, STUDENT);
        route("GET", "/api/ratings", c -> {
            Map<String, SelfReport> latest = new LinkedHashMap<>();
            for (SelfReport r : db.reports) {
                if (!r.studentId.equals(c.user.id)) continue;
                SelfReport prev = latest.get(r.topicId);
                if (prev == null || r.date.compareTo(prev.date) >= 0) latest.put(r.topicId, r);
            }
            c.ok(new ArrayList<>(latest.values()));
        }, STUDENT);

        // ---------------- subjects, topics, questions
        route("GET", "/api/subjects", c -> c.ok(catalog.subjects(c.user.role != STUDENT && c.q("all") != null)), ADMIN, TEACHER, STUDENT);
        route("POST", "/api/subjects", c -> c.ok(catalog.saveSubject(c.user, null, c.body())), ADMIN);
        route("PUT", "/api/subjects/" + ID, c -> c.ok(catalog.saveSubject(c.user, c.p(1), c.body())), ADMIN);
        route("GET", "/api/topics", c -> c.ok(catalog.topics(need(c.q("subject"), "subject"))), ADMIN, TEACHER, STUDENT);
        route("POST", "/api/topics", c -> c.ok(catalog.saveTopic(c.user, null, c.body())), ADMIN, TEACHER);
        route("PUT", "/api/topics/" + ID, c -> c.ok(catalog.saveTopic(c.user, c.p(1), c.body())), ADMIN, TEACHER);
        route("DELETE", "/api/topics/" + ID, c -> c.ok(Map.of("message", catalog.deleteTopic(c.user, c.p(1)))), ADMIN, TEACHER);
        route("GET", "/api/questions", c -> c.ok(catalog.questions(c.q("subject"), c.q("topic"))), ADMIN, TEACHER);
        route("POST", "/api/questions", c -> c.ok(catalog.saveQuestion(c.user, null, c.body())), ADMIN, TEACHER);
        route("PUT", "/api/questions/" + ID, c -> c.ok(catalog.saveQuestion(c.user, c.p(1), c.body())), ADMIN, TEACHER);
        route("DELETE", "/api/questions/" + ID, c -> c.ok(Map.of("message", catalog.deleteQuestion(c.user, c.p(1)))), ADMIN, TEACHER);

        // ---------------- support plans
        route("GET", "/api/plans", c -> c.ok(plans.list(c.user, c.q("status"))), ADMIN, TEACHER, STUDENT);
        route("POST", "/api/plans", c -> c.ok(plans.create(c.user, c.body())), ADMIN, TEACHER);
        route("POST", "/api/plans/" + ID + "/status", c -> c.ok(plans.setStatus(c.user, c.p(1), String.valueOf(c.body().get("status")))), ADMIN, TEACHER);
        route("DELETE", "/api/plans/" + ID, c -> { plans.delete(c.user, c.p(1)); c.ok(Map.of("ok", true)); }, ADMIN, TEACHER);

        // ---------------- admin
        route("GET", "/api/users", c -> c.ok(users.list(null)), ADMIN);
        route("POST", "/api/users", c -> c.ok(UserService.view(users.save(c.user, null, c.body()))), ADMIN);
        route("PUT", "/api/users/" + ID, c -> c.ok(UserService.view(users.save(c.user, c.p(1), c.body()))), ADMIN);
        route("POST", "/api/users/" + ID + "/active", c -> c.ok(UserService.view(users.setActive(c.user, c.p(1), Boolean.TRUE.equals(c.body().get("active"))))), ADMIN);
        route("GET", "/api/settings", c -> c.ok(db.settings), ADMIN);
        route("PUT", "/api/settings", c -> c.ok(dash.saveSettings(c.user, c.body())), ADMIN);
        route("GET", "/api/events", c -> {
            List<Event> list = new ArrayList<>(db.events);
            Collections.reverse(list);
            c.ok(list.subList(0, Math.min(300, list.size())));
        }, ADMIN);

        // ---------------- exports
        route("GET", "/api/export/heatmap.csv", c -> {
            String subject = need(c.q("subject"), "subject");
            Http.download(c.ex, "difficulty-" + db.subject(subject).code + ".csv", "text/csv", dash.heatmapCsv(subject, c.q("batch")));
        }, ADMIN, TEACHER);
        route("GET", "/api/export/attempts.csv", c -> Http.download(c.ex, "attempts.csv", "text/csv", dash.attemptsCsv()), ADMIN, TEACHER);
    }

    private static String need(String v, String name) {
        if (v == null) throw ApiError.bad("Missing " + name + ".");
        return v;
    }

    // ================================================================= server

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
            Http.secure(ex.getResponseHeaders());
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) api(ex, path);
            else staticFile(ex, path);
        } catch (ApiError e) {
            Http.error(ex, e.status, e.getMessage());
        } catch (Exception e) {
            System.err.println("Error on " + ex.getRequestURI() + ": " + e);
            e.printStackTrace();
            Http.error(ex, 500, "Something went wrong on the server.");
        } finally {
            ex.close();
        }
    }

    private void api(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) checkOrigin(ex);
        boolean pathMatched = false;
        for (Route r : routes) {
            Matcher m = r.pattern.matcher(path);
            if (!m.matches()) continue;
            pathMatched = true;
            if (!r.method.equals(method) && !(method.equals("HEAD") && r.method.equals("GET"))) continue;
            User u = auth.check(Http.cookie(ex, Http.COOKIE));
            if (r.needsLogin) AuthService.require(u, r.roles);
            r.handler.handle(new Ctx(ex, u, m, Http.query(ex)));
            return;
        }
        if (pathMatched) throw new ApiError(405, "This method is not allowed here.");
        throw new ApiError(404, "No such API address.");
    }

    /** Refuse changes coming from other websites (a simple CSRF guard on top of SameSite cookies). */
    private static void checkOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return;                // not from a browser form on another site
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || !(origin.equals("http://" + host) || origin.equals("https://" + host)))
            throw new ApiError(403, "Requests from other websites are not allowed.");
    }

    private void staticFile(HttpExchange ex, String path) throws IOException {
        if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) throw new ApiError(405, "Not allowed.");
        String rel;
        if (path.equals("/") || path.equals("/index.html")) rel = "index.html";
        else if (path.startsWith("/static/")) rel = path.substring("/static/".length());
        else if (path.equals("/favicon.ico") || path.equals("/favicon.svg")) rel = "favicon.svg";
        else { ex.getResponseHeaders().set("Location", "/"); Http.send(ex, 302, new byte[0]); return; }
        Path file = frontend.resolve(rel).normalize();
        if (!file.startsWith(frontend) || !Files.isRegularFile(file)) {   // blocks ../ tricks
            Http.send(ex, 404, "Not found".getBytes());
            return;
        }
        ex.getResponseHeaders().set("Content-Type", Http.contentType(rel));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        Http.send(ex, 200, Files.readAllBytes(file));
    }
}
