package com.cecil.codereview.server;

import com.cecil.codereview.Cli;
import com.cecil.codereview.ReviewService;
import com.cecil.codereview.ai.AutoFixer;
import com.cecil.codereview.ai.ClaudeReviewer;
import com.cecil.codereview.ai.KnowledgeBase;
import com.cecil.codereview.analyzer.ReviewEngine;
import com.cecil.codereview.analyzer.Rule;
import com.cecil.codereview.analyzer.SourceFile;
import com.cecil.codereview.config.ReviewConfig;
import com.cecil.codereview.git.GitChanges;
import com.cecil.codereview.model.FileReport;
import com.cecil.codereview.model.ReviewReport;
import com.cecil.codereview.model.Severity;
import com.cecil.codereview.report.HistoryTracker;
import com.cecil.codereview.report.HtmlReporter;
import com.cecil.codereview.report.JsonReporter;
import com.cecil.codereview.report.MarkdownReporter;
import com.cecil.codereview.rules.RuleRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The backend web server.
 *
 * It does two jobs:
 *   1. REST API under /api/...  (the frontend calls these with fetch())
 *   2. Serves the frontend files (index.html, app.css, app.js) from the frontend folder
 *
 * It only listens on 127.0.0.1 (this computer), so nobody else on the network can use it.
 */
public final class ApiServer {
    private static final int MAX_BODY_BYTES = 20 * 1024 * 1024;
    private static final int MAX_FILES = 500;
    private static final long MAX_FILE_BYTES = 1024 * 1024;

    private final int port;
    private final Path frontendDir;
    private final Path dataDir;
    private final Path samplesDir;
    private HttpServer server;

    public ApiServer(int port, Path projectRoot) {
        this.port = port;
        Path root = projectRoot.toAbsolutePath().normalize();
        this.frontendDir = root.resolve("frontend");
        this.dataDir = root.resolve("data");
        this.samplesDir = root.resolve("samples");
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    public int port() {
        return server != null ? server.getAddress().getPort() : port;
    }

    // ===================================================================== API

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            if (!isLocalHost(ex)) {
                send(ex, 403, "application/json", error("Only localhost requests are allowed"));
                return;
            }
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if ("POST".equals(method) && !isJson(ex)) {
                send(ex, 415, "application/json", error("Content-Type must be application/json"));
                return;
            }
            switch (method + " " + path) {
                case "GET /api/info": sendJson(ex, info()); break;
                case "GET /api/rules": sendJson(ex, rules()); break;
                case "GET /api/samples": sendJson(ex, samples()); break;
                case "GET /api/history": sendJson(ex, history()); break;
                case "DELETE /api/history":
                    new HistoryTracker(dataDir).clear();
                    sendJson(ex, "{\"ok\":true}");
                    break;
                case "POST /api/review": sendJson(ex, review(body(ex))); break;
                case "POST /api/fix": sendJson(ex, fix(body(ex))); break;
                case "POST /api/export": export(ex, body(ex)); break;
                case "POST /api/load-path": sendJson(ex, loadPath(body(ex))); break;
                default: send(ex, 404, "application/json", error("Unknown API: " + method + " " + path));
            }
        } catch (IllegalArgumentException e) {
            send(ex, 400, "application/json", error(e.getMessage()));
        } catch (IOException | RuntimeException e) {
            System.err.println("API error: " + e);
            send(ex, 500, "application/json", error(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /** GET /api/info - version, defaults and whether Claude is available. */
    private String info() {
        ReviewConfig d = ReviewConfig.defaults();
        ClaudeReviewer claude = ClaudeReviewer.fromEnvironment();
        JsonBuilder j = new JsonBuilder().beginObject()
                .field("name", "AI Code Review Assistant")
                .field("version", Cli.VERSION)
                .field("claudeAvailable", claude != null)
                .field("claudeModel", claude != null ? claude.model() : "")
                .field("ruleCount", RuleRegistry.allRules().size())
                .key("defaults").beginObject()
                .field("maxMethodLines", d.maxMethodLines())
                .field("maxComplexity", d.maxComplexity())
                .field("maxParameters", d.maxParameters())
                .field("maxLineLength", d.maxLineLength())
                .field("maxNesting", d.maxNesting())
                .field("maxClassLines", d.maxClassLines())
                .field("duplicateBlockLines", d.duplicateBlockLines())
                .endObject()
                .key("extensions").beginArray();
        for (String e : new java.util.TreeSet<>(d.includeExtensions())) j.value(e);
        return j.endArray().endObject().toString();
    }

    /** GET /api/rules - every rule with its explanation and before/after example. */
    private String rules() {
        JsonBuilder j = new JsonBuilder().beginArray();
        for (Rule r : RuleRegistry.allRules()) {
            KnowledgeBase.Advice a = KnowledgeBase.forRule(r.id());
            j.beginObject()
                    .field("id", r.id())
                    .field("name", r.name())
                    .field("category", r.category().label())
                    .field("severity", r.defaultSeverity().name())
                    .field("description", r.description())
                    .field("autoFixable", r.autoFixable())
                    .field("why", a != null ? a.why : "")
                    .field("before", a != null ? a.before : "")
                    .field("after", a != null ? a.after : "")
                    .endObject();
        }
        return j.endArray().toString();
    }

    /** GET /api/samples - the demo files in the samples folder. */
    private String samples() throws IOException {
        JsonBuilder j = new JsonBuilder().beginObject().key("files").beginArray();
        if (Files.isDirectory(samplesDir)) {
            List<Path> list = new ArrayList<>();
            try (var stream = Files.list(samplesDir)) {
                stream.filter(Files::isRegularFile).sorted().forEach(list::add);
            }
            for (Path p : list) {
                j.beginObject().field("name", p.getFileName().toString())
                        .field("content", Files.readString(p, StandardCharsets.UTF_8)).endObject();
            }
        }
        return j.endArray().endObject().toString();
    }

    /** GET /api/history - past scores. */
    private String history() {
        JsonBuilder j = new JsonBuilder().beginObject().key("entries").beginArray();
        for (Map<String, String> row : new HistoryTracker(dataDir).entries()) {
            j.beginObject();
            row.forEach(j::field);
            j.endObject();
        }
        return j.endArray().endObject().toString();
    }

    /** POST /api/review - body: {files:[{name,content}], settings:{...}, useClaude, saveHistory, target}. */
    private String review(Map<String, Object> req) {
        ReviewReport report = runReview(req, true);
        return new JsonBuilder().beginObject().key("report").raw(JsonReporter.render(report)).endObject().toString();
    }

    private ReviewReport runReview(Map<String, Object> req, boolean allowHistory) {
        List<SourceFile> files = files(req);
        ReviewConfig config = config(req);
        ClaudeReviewer claude = bool(req.get("useClaude")) ? ClaudeReviewer.fromEnvironment() : null;
        ReviewService service = new ReviewService(config, claude);
        List<String> log = new ArrayList<>();
        ReviewReport report = service.reviewSources(files, log::add);
        String target = str(req.get("target"));
        if (target.isBlank()) target = files.size() == 1 ? files.get(0).displayName() : files.size() + " files";
        service.summarize(report, dataDir, "web:" + target, allowHistory && bool(req.get("saveHistory")));
        return report;
    }

    /** POST /api/fix - returns the fixed text of every file that changed. */
    private String fix(Map<String, Object> req) {
        List<SourceFile> files = files(req);
        ReviewEngine engine = new ReviewEngine(config(req));
        JsonBuilder j = new JsonBuilder().beginObject().key("files").beginArray();
        int total = 0;
        for (SourceFile sf : files) {
            FileReport r = engine.reviewFile(sf);
            AutoFixer.FixResult fr = AutoFixer.fix(sf, r.issues());
            if (!fr.changed()) continue;
            total += fr.changes.size();
            j.beginObject().field("name", sf.displayName()).field("content", fr.fixedText).key("changes").beginArray();
            for (AutoFixer.Change c : fr.changes) {
                j.beginObject().field("line", c.line).field("rule", c.ruleId)
                        .field("before", c.before).field("after", c.after).endObject();
            }
            j.endArray().endObject();
        }
        return j.endArray().field("totalChanges", total).endObject().toString();
    }

    /** POST /api/export?format=html|md|json - a downloadable report. */
    private void export(HttpExchange ex, Map<String, Object> req) throws IOException {
        String format = query(ex.getRequestURI()).getOrDefault("format", "html");
        ReviewReport report = runReview(req, false);
        String body;
        String type;
        String ext;
        switch (format) {
            case "md": body = MarkdownReporter.render(report); type = "text/markdown; charset=utf-8"; ext = "md"; break;
            case "json": body = JsonReporter.render(report); type = "application/json; charset=utf-8"; ext = "json"; break;
            case "html": body = HtmlReporter.render(report); type = "text/html; charset=utf-8"; ext = "html"; break;
            default: throw new IllegalArgumentException("format must be html, md or json");
        }
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"review-report." + ext + "\"");
        send(ex, 200, type, body);
    }

    /**
     * POST /api/load-path - body: {path, git, base}.
     * Reads code files from a folder on this computer (or only the Git-changed ones)
     * so the website can show and review them.
     */
    private String loadPath(Map<String, Object> req) throws IOException {
        String raw = str(req.get("path")).trim().replace("\"", "");
        if (raw.isEmpty()) throw new IllegalArgumentException("Please enter a file or folder path");
        Path input = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.exists(input)) throw new IllegalArgumentException("Path not found: " + input);
        ReviewEngine engine = new ReviewEngine(ReviewConfig.defaults());
        List<Path> paths;
        Path base;
        if (bool(req.get("git"))) {
            String gitBase = str(req.get("base")).trim();
            GitChanges git = new GitChanges(Files.isDirectory(input) ? input : input.getParent(),
                    gitBase.isEmpty() ? null : gitBase);
            base = git.repoRoot();
            paths = new ArrayList<>();
            for (Path p : git.changedFiles()) {
                if (hasCodeExtension(p)) paths.add(p);
            }
        } else {
            base = Files.isDirectory(input) ? input : input.getParent();
            paths = engine.collectFiles(Collections.singletonList(input));
        }
        if (paths.size() > MAX_FILES) {
            throw new IllegalArgumentException("Too many files (" + paths.size() + "). Choose a smaller folder (max " + MAX_FILES + ").");
        }
        JsonBuilder j = new JsonBuilder().beginObject().field("root", base.toString()).key("files").beginArray();
        int skipped = 0;
        for (Path p : paths) {
            if (Files.size(p) > MAX_FILE_BYTES) { skipped++; continue; }
            String name = base.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
            j.beginObject().field("name", name).field("content", Files.readString(p, StandardCharsets.UTF_8)).endObject();
        }
        return j.endArray().field("skipped", skipped).endObject().toString();
    }

    private static boolean hasCodeExtension(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".java") || n.endsWith(".js") || n.endsWith(".ts") || n.endsWith(".py");
    }

    // ------------------------------------------------------------ request data

    @SuppressWarnings("unchecked")
    private static List<SourceFile> files(Map<String, Object> req) {
        Object f = req.get("files");
        if (!(f instanceof List) || ((List<?>) f).isEmpty()) {
            throw new IllegalArgumentException("Send at least one file: {\"files\":[{\"name\":\"A.java\",\"content\":\"...\"}]}");
        }
        List<Object> list = (List<Object>) f;
        if (list.size() > MAX_FILES) throw new IllegalArgumentException("Too many files (max " + MAX_FILES + ")");
        List<SourceFile> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            String name = str(m.get("name")).trim();
            if (name.isEmpty()) name = "Untitled.java";
            out.add(SourceFile.fromText(name, str(m.get("content"))));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static ReviewConfig config(Map<String, Object> req) {
        ReviewConfig c = ReviewConfig.defaults();
        Object s = req.get("settings");
        if (!(s instanceof Map)) return c;
        Map<String, Object> m = (Map<String, Object>) s;
        Severity min = Severity.parse(str(m.get("minSeverity")), null);
        if (min != null) c.setMinSeverity(min);
        Object disabled = m.get("disabledRules");
        if (disabled instanceof List) {
            for (Object id : (List<Object>) disabled) c.disableRule(String.valueOf(id));
        }
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getValue() instanceof Double) c.setLimit(e.getKey(), ((Double) e.getValue()).intValue());
        }
        return c;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean bool(Object o) {
        return Boolean.TRUE.equals(o);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> q = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) return q;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                q.put(java.net.URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return q;
    }

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_BODY_BYTES) throw new IllegalArgumentException("Request is too large (max 20 MB)");
            }
            String text = buf.toString(StandardCharsets.UTF_8);
            return text.isBlank() ? new HashMap<>() : JsonParser.parseObject(text);
        }
    }

    // ---------------------------------------------------------------- security

    /**
     * Blocks "DNS rebinding": a random website could point its own domain name at
     * 127.0.0.1 and call our API from the browser. Its requests would carry its own
     * domain in the Host header, so we only accept localhost / 127.0.0.1.
     */
    private static boolean isLocalHost(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && !h.endsWith("]")) h = h.substring(0, colon);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]");
    }

    /**
     * Requiring JSON means another website's page can't send a "simple" form POST
     * to us (CSRF); the browser would first ask permission (CORS), which we never give.
     */
    private static boolean isJson(HttpExchange ex) {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        return ct != null && ct.toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    // ------------------------------------------------------------------ static

    private void handleStatic(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "Method not allowed");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        // normalize, then make sure we are still inside the frontend folder (no ../ tricks)
        Path file = frontendDir.resolve(path.substring(1)).normalize();
        if (!file.startsWith(frontendDir) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", "Not found");
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", mime(file.getFileName().toString()));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, "HEAD".equals(ex.getRequestMethod()) ? -1 : bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            if (!"HEAD".equals(ex.getRequestMethod())) os.write(bytes);
        }
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    // ----------------------------------------------------------------- helpers

    private static String error(String message) {
        return new JsonBuilder().beginObject().field("error", message == null ? "Unknown error" : message).endObject().toString();
    }

    private static void sendJson(HttpExchange ex, String json) throws IOException {
        send(ex, 200, "application/json; charset=utf-8", json);
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
