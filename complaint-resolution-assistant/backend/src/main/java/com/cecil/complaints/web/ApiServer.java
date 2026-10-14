package com.cecil.complaints.web;

import com.cecil.complaints.ai.AiEngine;
import com.cecil.complaints.model.Complaint;
import com.cecil.complaints.model.Enums;
import com.cecil.complaints.model.Solution;
import com.cecil.complaints.model.User;
import com.cecil.complaints.service.AnalyticsService;
import com.cecil.complaints.service.AuthService;
import com.cecil.complaints.service.ComplaintService;
import com.cecil.complaints.service.KnowledgeBaseService;
import com.cecil.complaints.service.ServiceException;
import com.cecil.complaints.store.DataStore;
import com.cecil.complaints.util.Json;
import com.cecil.complaints.web.Http.Access;
import com.cecil.complaints.web.Http.Request;
import com.cecil.complaints.web.Http.Response;
import com.cecil.complaints.web.Http.Router;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The REST API and the web pages, served by the JDK's built-in HTTP server.
 */
public class ApiServer {

    private static final int MAX_BODY = 1_000_000; // 1 MB
    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json");

    private final DataStore store;
    private final AiEngine ai;
    private final AuthService auth;
    private final ComplaintService complaints;
    private final KnowledgeBaseService kb;
    private final AnalyticsService analytics;
    private final Router router = new Router();
    private final Map<String, Deque<Long>> rateBuckets = new ConcurrentHashMap<>();
    private final Path frontendDir;
    private HttpServer server;
    private ExecutorService executor;

    public ApiServer(DataStore store, AiEngine ai, AuthService auth, ComplaintService complaints,
                     KnowledgeBaseService kb, AnalyticsService analytics) {
        this.store = store;
        this.ai = ai;
        this.auth = auth;
        this.complaints = complaints;
        this.kb = kb;
        this.analytics = analytics;
        Path fe = findFrontendDir();
        if (fe == null) {
            throw new IllegalStateException("Frontend folder not found. Run from the project folder, or set FRONTEND_DIR.");
        }
        this.frontendDir = fe;
        registerRoutes();
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        executor = Executors.newFixedThreadPool(16);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public Path frontendDir() {
        return frontendDir;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // ================================================================ routes

    private void registerRoutes() {
        // ---- auth
        router.add("POST", "/api/auth/login", Access.PUBLIC, req -> {
            if (!allow("login:" + req.clientIp, 20, 300)) {
                throw new ServiceException(429, "Too many login attempts. Please wait a few minutes.");
            }
            Map<String, Object> in = req.json();
            String token = auth.login(Json.str(in, "username"), Json.str(in, "password"));
            User u = auth.userForToken(token);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", token);
            out.put("user", u.toPublicMap());
            return Response.json(out);
        });
        router.add("POST", "/api/auth/logout", Access.AGENT, req -> {
            auth.logout(req.bearerToken());
            return Response.json(Map.of("ok", true));
        });
        router.add("GET", "/api/auth/me", Access.AGENT, req -> Response.json(req.user.toPublicMap()));

        // ---- users
        router.add("GET", "/api/users", Access.AGENT, req -> {
            List<Object> out = new ArrayList<>();
            auth.users().forEach(u -> out.add(u.toPublicMap()));
            return Response.json(out);
        });
        router.add("POST", "/api/users", Access.ADMIN, req -> {
            Map<String, Object> in = req.json();
            User u = auth.createUser(Json.str(in, "username"), Json.str(in, "displayName"),
                    Json.str(in, "role"), Json.str(in, "password"));
            return Response.json(201, u.toPublicMap());
        });

        // ---- complaints
        router.add("GET", "/api/complaints", Access.AGENT, req -> {
            List<Object> out = new ArrayList<>();
            for (Complaint c : complaints.list(req.query("status"), req.query("priority"), req.query("category"),
                    req.query("assignedTo"), req.query("q"), "true".equals(req.query("overdue")))) {
                out.add(c.toSummaryMap());
            }
            return Response.json(out);
        });
        router.add("POST", "/api/complaints", Access.AGENT, req ->
                Response.json(201, complaints.create(req.json(), req.user.username).toMap()));
        router.add("GET", "/api/complaints/{id}", Access.AGENT, req ->
                Response.json(withSolutionDetails(complaints.get(req.param("id")))));
        router.add("DELETE", "/api/complaints/{id}", Access.ADMIN, req -> {
            complaints.delete(req.param("id"), req.user);
            return Response.json(Map.of("ok", true));
        });
        router.add("PUT", "/api/complaints/{id}/status", Access.AGENT, req -> {
            Map<String, Object> in = req.json();
            return detail(complaints.updateStatus(req.param("id"), Json.str(in, "status"), Json.str(in, "note"), req.user));
        });
        router.add("PUT", "/api/complaints/{id}/assign", Access.AGENT, req ->
                detail(complaints.assign(req.param("id"), Json.str(req.json(), "assignedTo"), req.user)));
        router.add("PUT", "/api/complaints/{id}/priority", Access.AGENT, req ->
                detail(complaints.changePriority(req.param("id"), Json.str(req.json(), "priority"), req.user)));
        router.add("PUT", "/api/complaints/{id}/category", Access.AGENT, req ->
                detail(complaints.changeCategory(req.param("id"), Json.str(req.json(), "category"), req.user)));
        router.add("POST", "/api/complaints/{id}/notes", Access.AGENT, req ->
                detail(complaints.addNote(req.param("id"), Json.str(req.json(), "text"), req.user)));
        router.add("POST", "/api/complaints/{id}/apply", Access.AGENT, req -> {
            Map<String, Object> in = req.json();
            return detail(complaints.applySolution(req.param("id"), Json.str(in, "solutionId"),
                    Json.bool(in, "useLlm", false), req.user));
        });
        router.add("POST", "/api/complaints/{id}/feedback", Access.AGENT, req -> {
            Map<String, Object> in = req.json();
            return detail(complaints.feedback(req.param("id"), Json.str(in, "solutionId"),
                    Json.bool(in, "helpful", true), req.user));
        });
        router.add("POST", "/api/complaints/{id}/respond", Access.AGENT, req -> {
            Map<String, Object> in = req.json();
            return detail(complaints.sendResponse(req.param("id"), Json.str(in, "response"),
                    Json.bool(in, "resolve", false), req.user));
        });
        router.add("POST", "/api/complaints/{id}/reanalyze", Access.AGENT, req ->
                detail(complaints.reanalyze(req.param("id"), req.user)));

        // ---- AI
        router.add("POST", "/api/ai/analyze", Access.AGENT, req -> {
            Map<String, Object> in = req.json();
            String subject = Json.str(in, "subject", "");
            String description = Json.str(in, "description", "");
            if ((subject + description).trim().length() < 5) {
                throw ServiceException.badRequest("Type a few words to analyse");
            }
            return Response.json(ai.preview(limit(subject, 200), limit(description, 5000), kb.list(null, null, false)));
        });
        router.add("GET", "/api/ai/status", Access.AGENT, req -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("engine", "Offline NLP (Naive Bayes + TF-IDF + sentiment lexicon)");
            m.put("trainingExamples", ai.classifier().trainingSize());
            m.put("llmEnabled", ai.llm().isEnabled());
            m.put("llmModel", ai.llm().isEnabled() ? ai.llm().model() : null);
            return Response.json(m);
        });

        // ---- knowledge base
        router.add("GET", "/api/solutions", Access.AGENT, req -> {
            List<Object> out = new ArrayList<>();
            for (Solution s : kb.list(req.query("category"), req.query("q"), "true".equals(req.query("all")))) {
                out.add(s.toMap());
            }
            return Response.json(out);
        });
        router.add("GET", "/api/solutions/{id}", Access.AGENT, req -> Response.json(kb.get(req.param("id")).toMap()));
        router.add("POST", "/api/solutions", Access.ADMIN, req -> Response.json(201, kb.create(req.json(), req.user).toMap()));
        router.add("PUT", "/api/solutions/{id}", Access.ADMIN, req ->
                Response.json(kb.update(req.param("id"), req.json(), req.user).toMap()));
        router.add("DELETE", "/api/solutions/{id}", Access.ADMIN, req ->
                Response.json(Map.of("result", kb.delete(req.param("id"), req.user))));

        // ---- analytics & export
        router.add("GET", "/api/analytics", Access.AGENT, req -> Response.json(analytics.summary()));
        router.add("GET", "/api/export/complaints.csv", Access.AGENT, req ->
                Response.text(200, "text/csv; charset=utf-8", complaints.exportCsv())
                        .header("Content-Disposition", "attachment; filename=\"complaints.csv\""));
        router.add("GET", "/api/meta", Access.PUBLIC, req -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("categories", enumList(Enums.Category.values()));
            m.put("statuses", enumList(Enums.Status.values()));
            m.put("priorities", enumList(Enums.Priority.values()));
            m.put("channels", enumList(Enums.Channel.values()));
            m.put("tiers", enumList(Enums.CustomerTier.values()));
            Map<String, String> labels = new LinkedHashMap<>();
            for (Enums.Category c : Enums.Category.values()) {
                labels.put(c.name(), c.label);
            }
            m.put("categoryLabels", labels);
            return Response.json(m);
        });

        // ---- public customer portal (no login, rate limited)
        router.add("POST", "/api/public/complaints", Access.PUBLIC, req -> {
            if (!allow("submit:" + req.clientIp, 10, 600)) {
                throw new ServiceException(429, "Too many complaints from your network. Please try again later.");
            }
            Complaint c = complaints.create(req.json(), null);
            return Response.json(201, complaints.customerView(c));
        });
        router.add("POST", "/api/public/track", Access.PUBLIC, req -> {
            if (!allow("track:" + req.clientIp, 30, 300)) {
                throw new ServiceException(429, "Too many requests. Please try again later.");
            }
            Map<String, Object> in = req.json();
            return Response.json(complaints.track(Json.str(in, "id"), Json.str(in, "email")));
        });
        router.add("POST", "/api/public/rate", Access.PUBLIC, req -> {
            if (!allow("rate:" + req.clientIp, 20, 300)) {
                throw new ServiceException(429, "Too many requests. Please try again later.");
            }
            Map<String, Object> in = req.json();
            return Response.json(complaints.rate(Json.str(in, "id"), Json.str(in, "email"),
                    (int) Json.num(in, "rating", 0)));
        });
        router.add("GET", "/api/health", Access.PUBLIC, req -> Response.json(Map.of("status", "UP")));
    }

    private Response detail(Complaint c) {
        return Response.json(withSolutionDetails(c));
    }

    /** Complaint JSON plus the full text of each recommended solution, so the UI needs one call. */
    private Map<String, Object> withSolutionDetails(Complaint c) {
        Map<String, Object> m = c.toMap();
        Map<String, Object> sols = new LinkedHashMap<>();
        synchronized (store) {
            for (Complaint.Recommendation r : c.recommendations) {
                Solution s = store.solutions().get(r.solutionId);
                if (s != null) {
                    sols.put(s.id, s.toMap());
                }
            }
            if (c.appliedSolutionId != null && store.solutions().containsKey(c.appliedSolutionId)) {
                sols.put(c.appliedSolutionId, store.solutions().get(c.appliedSolutionId).toMap());
            }
        }
        m.put("solutionDetails", sols);
        return m;
    }

    private static List<String> enumList(Enum<?>[] values) {
        List<String> out = new ArrayList<>();
        for (Enum<?> v : values) {
            out.add(v.name());
        }
        return out;
    }

    // ================================================================ request handling

    private void handle(HttpExchange ex) throws IOException {
        Response res;
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                res = handleApi(ex, path);
            } else {
                res = serveStatic(path, ex.getRequestMethod());
            }
        } catch (Exception e) {
            System.err.println("[Server] " + e);
            res = Response.error(500, "Something went wrong on the server");
        }
        send(ex, res);
    }

    private Response handleApi(HttpExchange ex, String path) {
        try {
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            boolean[] wrongMethod = {false};
            Router.Match match = router.find(method, path, wrongMethod);
            if (match == null) {
                return wrongMethod[0] ? Response.error(405, "Method not allowed") : Response.error(404, "Not found");
            }
            Map<String, String> headers = new HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> {
                if (!v.isEmpty()) {
                    headers.put(k.toLowerCase(Locale.ROOT), v.get(0));
                }
            });
            // simple CSRF guard: state-changing calls must send JSON
            if (!method.equals("GET") && !method.equals("DELETE")) {
                String ct = headers.getOrDefault("content-type", "");
                if (!ct.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                    return Response.error(415, "Content-Type must be application/json");
                }
            }
            String body = readBody(ex.getRequestBody());
            Request req = new Request(method, path, ex.getRequestURI().getRawQuery(), headers, body,
                    ex.getRemoteAddress().getAddress().getHostAddress());
            req.params = match.params();

            Access access = match.route().access();
            if (access != Access.PUBLIC) {
                req.user = auth.userForToken(req.bearerToken());
                if (req.user == null) {
                    return Response.error(401, "Please log in");
                }
                if (access == Access.ADMIN && req.user.role != Enums.Role.ADMIN) {
                    return Response.error(403, "Admins only");
                }
            }
            return match.route().handler().handle(req);
        } catch (ServiceException se) {
            return Response.error(se.status(), se.getMessage());
        } catch (Exception e) {
            System.err.println("[API] " + path + " -> " + e);
            return Response.error(500, "Something went wrong on the server");
        }
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_BODY) {
                throw new ServiceException(413, "Request is too large");
            }
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private Response serveStatic(String path, String method) throws IOException {
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return Response.error(405, "Method not allowed");
        }
        String file = switch (path) {
            case "/", "/index.html" -> "index.html";
            case "/portal", "/portal.html" -> "portal.html";
            default -> path.startsWith("/static/") ? path.substring("/static/".length()) : null;
        };
        // only simple file names - no "..", no sub folders, so nobody can read other files
        if (file == null || !file.matches("[A-Za-z0-9_-]+\\.[a-z]{2,4}")) {
            return Response.text(404, "text/plain; charset=utf-8", "Not found");
        }
        Path target = frontendDir.resolve(file).normalize();
        if (!target.startsWith(frontendDir) || !Files.isRegularFile(target)) {
            return Response.text(404, "text/plain; charset=utf-8", "Not found");
        }
        String ext = file.substring(file.lastIndexOf('.') + 1);
        return new Response(200, MIME.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(target))
                .header("Cache-Control", "no-cache");
    }

    /**
     * Finds the frontend folder. Order: FRONTEND_DIR setting, then "frontend" (when started from the
     * project root, e.g. IntelliJ), then "../frontend" (when started from inside the backend folder).
     */
    public static Path findFrontendDir() {
        List<String> candidates = new ArrayList<>();
        String env = System.getenv("FRONTEND_DIR");
        if (env != null && !env.isBlank()) {
            candidates.add(env);
        }
        candidates.add("frontend");
        candidates.add("../frontend");
        for (String c : candidates) {
            Path p = Path.of(c).toAbsolutePath().normalize();
            if (Files.isRegularFile(p.resolve("index.html"))) {
                return p;
            }
        }
        return null;
    }

    private static void send(HttpExchange ex, Response res) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", res.contentType);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
        if (res.contentType.startsWith("application/json")) {
            h.set("Cache-Control", "no-store");
        }
        res.headers.forEach(h::set);
        boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
        ex.sendResponseHeaders(res.status, head || res.body.length == 0 ? -1 : res.body.length);
        if (!head && res.body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(res.body);
            }
        }
        ex.close();
    }

    /** Sliding-window rate limit: at most `max` calls per `seconds` for a key. */
    private boolean allow(String key, int max, int seconds) {
        long now = Instant.now().toEpochMilli();
        Deque<Long> q = rateBuckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > seconds * 1000L) {
                q.pollFirst();
            }
            if (q.size() >= max) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
