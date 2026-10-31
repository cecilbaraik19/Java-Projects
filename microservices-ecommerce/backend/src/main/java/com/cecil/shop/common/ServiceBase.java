package com.cecil.shop.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Everything a microservice needs, written once and shared by all of them:
 *
 *  - its own small web server (com.sun.net.httpserver, part of the JDK) on its own port
 *  - registers itself in the service registry and sends a heartbeat every 3 seconds
 *  - GET /health                 is it alive?
 *  - GET /internal/stats         request count, errors, average time
 *  - GET /internal/spans         the trace records (spans) it saw, for distributed tracing
 *  - POST /internal/chaos        "switch off" / slow down the service to test resilience
 *  - POST /events                receives events from the event bus (with duplicate check)
 *  - publish(...)                sends an event; kept in an OUTBOX and retried if the bus is down
 *  - a ServiceClient to call other services by name
 *
 * A real service (CatalogService, OrderService ...) only adds its own routes and rules.
 */
public abstract class ServiceBase {
    public final String name;
    protected final Config cfg;
    protected final JsonStore store;
    protected final Discovery discovery;
    protected final ServiceClient client;
    protected final Router router = new Router();

    private HttpServer server;
    private ExecutorService workers;
    private ScheduledExecutorService timers;
    private String url;
    private long startedAt;

    // chaos switches (for learning how the system behaves when something breaks)
    protected volatile boolean down;
    protected volatile int extraLatencyMs;

    // metrics
    private long requests, errors;
    private final Deque<Long> recentMs = new ArrayDeque<>();
    private final Deque<Map<String, Object>> spans = new ArrayDeque<>();

    // events
    private final Map<String, Consumer<Map<String, Object>>> eventHandlers = new LinkedHashMap<>();
    private final Map<String, Boolean> subscribed = new ConcurrentHashMap<>();
    private final Deque<Map<String, Object>> outbox = new ArrayDeque<>();
    private final LinkedHashSet<String> seenEvents = new LinkedHashSet<>();
    private long eventsReceived, duplicateEvents;

    protected ServiceBase(String name, Config cfg) {
        this.name = name;
        this.cfg = cfg;
        this.store = cfg.dataRoot == null ? JsonStore.memory() : new JsonStore(cfg.dataDir(name));
        this.discovery = new Discovery(cfg);
        this.client = new ServiceClient(name, cfg, discovery);
    }

    /** Each service adds its routes here. */
    protected abstract void routes(Router r);

    /** Fill the service's own database with demo data on the first run. */
    protected void seed() {}

    /** One-line description shown in the architecture page. */
    public abstract String role();

    /** The registry itself does not register anywhere. */
    protected boolean usesRegistry() { return true; }

    /** Paths that do not need the internal key (only the gateway opens everything to the browser). */
    protected boolean isPublic(String path) { return path.equals("/health"); }

    // ================================================================ start / stop

    public synchronized ServiceBase start() {
        try {
            // Demo data from an older version of the project? Start this service's data fresh.
            if (store.dataVersion() < com.cecil.shop.seed.SeedData.VERSION) store.resetTo(com.cecil.shop.seed.SeedData.VERSION);
            seed();
            routes(router);
            server = HttpServer.create(new InetSocketAddress(cfg.host, cfg.port(name)), 64);
            workers = Executors.newFixedThreadPool(24, r -> { Thread t = new Thread(r, name + "-http"); t.setDaemon(true); return t; });
            server.setExecutor(workers);
            server.createContext("/", this::exchange);
            server.start();
            url = "http://" + cfg.host + ":" + server.getAddress().getPort();
            startedAt = System.currentTimeMillis();
            timers = Executors.newScheduledThreadPool(2, r -> { Thread t = new Thread(r, name + "-timer"); t.setDaemon(true); return t; });
            if (usesRegistry()) {
                timers.scheduleWithFixedDelay(this::safeHeartbeat, 0, cfg.heartbeatMs, TimeUnit.MILLISECONDS);
                timers.scheduleWithFixedDelay(this::safeBackground, 200, 700, TimeUnit.MILLISECONDS);
            }
            afterStart();
            return this;
        } catch (IOException e) {
            throw new IllegalStateException(name + " could not start on port " + cfg.port(name) + " (is it already in use?): " + e.getMessage(), e);
        }
    }

    protected void afterStart() {}

    /** Run a job again and again in the background (call from afterStart()). */
    protected void every(long ms, Runnable job) {
        timers.scheduleWithFixedDelay(() -> {
            if (down) return;
            try { job.run(); } catch (Exception ignored) { /* try again next time */ }
        }, ms, ms, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (server == null) return;
        if (usesRegistry()) {
            try {
                client.callUrl(cfg.registryUrl, "POST", "/deregister", Json.obj("name", name, "url", url), null, Ids.trace(), 800, Config.REGISTRY);
            } catch (Exception ignored) { /* registry may already be gone */ }
        }
        timers.shutdownNow();
        server.stop(0);
        workers.shutdownNow();
        server = null;
    }

    public String url() { return url; }

    public int port() { return server.getAddress().getPort(); }

    // ================================================================ registry heartbeat

    private void safeHeartbeat() {
        try { heartbeat(); } catch (Exception ignored) { /* registry not up yet - try again next beat */ }
    }

    private void heartbeat() {
        if (down) return; // a "crashed" service stops sending heartbeats, so the registry marks it DOWN
        Map<String, Object> me = Json.obj("name", name, "url", url, "role", role(), "startedAt", startedAt);
        ServiceClient.Result r = client.callUrl(cfg.registryUrl, "POST", "/heartbeat", me, null, Ids.trace(), 1500, Config.REGISTRY);
        if (r.status() == 404) client.callUrl(cfg.registryUrl, "POST", "/register", me, null, Ids.trace(), 1500, Config.REGISTRY);
    }

    private void safeBackground() {
        if (down) return;
        try { flushOutbox(); } catch (Exception ignored) { }
        try { subscribeMissing(); } catch (Exception ignored) { }
    }

    // ================================================================ events (publish / subscribe)

    /** Send an event to the event bus. It goes into the outbox first, so it is never lost if the bus is down. */
    protected void publish(String topic, Map<String, Object> payload, String traceId) {
        synchronized (outbox) {
            // The event gets its id HERE, so if it is ever sent twice, receivers can spot the duplicate.
            outbox.addLast(Json.obj("id", name.replace("-service", "") + "-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                    "topic", topic, "source", name, "traceId", traceId, "payload", payload, "createdAt", System.currentTimeMillis()));
        }
        if (timers != null && usesRegistry()) timers.execute(this::safeBackground);
    }

    private final Object flushLock = new Object();

    private void flushOutbox() {
        synchronized (flushLock) { flushOutboxLocked(); } // only one thread sends at a time, so nothing is sent twice
    }

    private void flushOutboxLocked() {
        while (true) {
            Map<String, Object> next;
            synchronized (outbox) { next = outbox.peekFirst(); }
            if (next == null) return;
            String bus = discovery.pick(Config.EVENT_BUS);
            ServiceClient.Result r = client.callUrl(bus, "POST", "/publish", next, null, Json.str(next, "traceId"), 2000, Config.EVENT_BUS);
            if (!r.ok()) return; // keep it, try again later
            synchronized (outbox) { outbox.pollFirst(); }
        }
    }

    public int outboxSize() { synchronized (outbox) { return outbox.size(); } }

    /** Listen to a topic, e.g. "order.confirmed" or "order.*". */
    protected void on(String topicPattern, Consumer<Map<String, Object>> handler) {
        eventHandlers.put(topicPattern, handler);
        subscribed.put(topicPattern, false);
    }

    private void subscribeMissing() {
        for (Map.Entry<String, Boolean> e : subscribed.entrySet()) {
            if (e.getValue()) continue;
            String bus = discovery.pick(Config.EVENT_BUS);
            ServiceClient.Result r = client.callUrl(bus, "POST", "/subscribe",
                    Json.obj("topic", e.getKey(), "service", name, "path", "/events"), null, Ids.trace(), 2000, Config.EVENT_BUS);
            if (r.ok()) e.setValue(true);
        }
    }

    public boolean allSubscribed() { return !subscribed.containsValue(false); }

    public static boolean topicMatches(String pattern, String topic) {
        if (pattern.equals(topic) || pattern.equals("*")) return true;
        return pattern.endsWith(".*") && topic.startsWith(pattern.substring(0, pattern.length() - 1));
    }

    private Object receiveEvent(Req r) {
        Map<String, Object> ev = r.json();
        String id = Json.str(ev, "id", "");
        String topic = Json.str(ev, "topic", "");
        synchronized (seenEvents) {
            // "at-least-once" delivery means the same event can arrive twice. Handle it only once (idempotent consumer).
            if (!id.isEmpty() && seenEvents.contains(id)) { duplicateEvents++; return Json.obj("ok", true, "duplicate", true); }
        }
        for (Map.Entry<String, Consumer<Map<String, Object>>> h : eventHandlers.entrySet()) {
            if (topicMatches(h.getKey(), topic)) {
                Map<String, Object> payload = Json.map(ev.get("payload"));
                payload.put("_topic", topic);
                payload.put("_traceId", r.traceId);
                h.getValue().accept(payload);
            }
        }
        synchronized (seenEvents) {
            eventsReceived++;
            if (!id.isEmpty()) seenEvents.add(id);
            while (seenEvents.size() > 5000) seenEvents.remove(seenEvents.iterator().next());
        }
        return Json.obj("ok", true);
    }

    // ================================================================ HTTP handling

    private void exchange(HttpExchange ex) {
        long t0 = System.currentTimeMillis();
        int status = 500;
        String path = ex.getRequestURI().getRawPath();
        String method = ex.getRequestMethod();
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> { if (!v.isEmpty()) headers.put(k.toLowerCase(), v.get(0)); });
        String trace = Ids.clean(headers.get("x-trace-id"));
        if (trace == null) trace = Ids.trace();
        String caller = headers.getOrDefault("x-caller", "client");
        boolean quiet = headers.containsKey("x-no-trace"); // calls made by the architecture console itself
        try {
            String body = readBody(ex);
            Req r = new Req(method, java.net.URLDecoder.decode(path, StandardCharsets.UTF_8), ex.getRequestURI().getRawQuery(), headers, body, trace);
            r.remoteIp = ex.getRemoteAddress().getAddress().getHostAddress();
            Object out;
            try {
                out = handleBuiltIn(r);
            } catch (ApiError e) {
                out = new Resp(e.status, Json.obj("error", e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                out = new Resp(500, Json.obj("error", "Something went wrong inside " + name + ": " + e.getClass().getSimpleName()));
            }
            status = write(ex, out, trace);
        } catch (Exception e) {
            status = 500;
            try { write(ex, new Resp(500, Json.obj("error", "Bad request")), trace); } catch (Exception ignored) { }
        } finally {
            ex.close();
            long ms = System.currentTimeMillis() - t0;
            if (!path.startsWith("/internal/") && !path.equals("/health") && !path.equals("/heartbeat") && !quiet && traced(path)) {
                record(trace, caller, method, path, status, t0, ms);
            }
        }
    }

    private Object handleBuiltIn(Req r) throws Exception {
        if (r.path.equals("/health")) {
            return down ? new Resp(503, Json.obj("status", "DOWN", "service", name)) : Json.obj("status", "UP", "service", name);
        }
        boolean internalOk = keyMatches(r.header("x-internal-key"));
        if (r.path.equals("/internal/chaos") && r.method.equals("POST")) {
            if (!internalOk) throw new ApiError(401, "Internal key required");
            Map<String, Object> b = r.json();
            if (b.containsKey("down")) down = Json.bool(b, "down", false);
            if (b.containsKey("latencyMs")) extraLatencyMs = (int) Math.max(0, Math.min(5000, Json.lng(b, "latencyMs")));
            if (!down && usesRegistry()) timers.execute(this::safeHeartbeat);
            return stats();
        }
        if (down) throw ApiError.unavailable(name + " is switched off (chaos test)");
        if (extraLatencyMs > 0 && !r.path.startsWith("/internal/")) Thread.sleep(extraLatencyMs);
        if (!isPublic(r.path) && !internalOk) {
            throw new ApiError(401, "This is an internal service. Call it through the API gateway.");
        }
        if (r.path.equals("/internal/stats")) return stats();
        if (r.path.equals("/internal/spans")) return spans(Json.lng(Json.obj("s", r.q("since")), "s", 0));
        if (r.path.equals("/events") && r.method.equals("POST")) return receiveEvent(r);
        return dispatch(r);
    }

    /** Normal routing. The gateway overrides this to proxy requests. */
    protected Object dispatch(Req r) throws Exception {
        return router.find(r).handle(r);
    }

    private boolean keyMatches(String given) {
        if (given == null) return false;
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), cfg.internalKey.getBytes(StandardCharsets.UTF_8));
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(1_000_001);
            if (b.length > 1_000_000) throw ApiError.bad("Request body too large");
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private int write(HttpExchange ex, Object out, String trace) throws IOException {
        Resp resp = out instanceof Resp rr ? rr : (out == null ? new Resp(204, null) : new Resp(200, out));
        byte[] bytes;
        if (resp.body instanceof byte[] raw) bytes = raw;
        else bytes = resp.body == null ? new byte[0] : Json.write(resp.body).getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", resp.contentType);
        h.set("X-Trace-Id", trace);
        h.set("X-Served-By", name);
        h.set("X-Content-Type-Options", "nosniff");
        addHeaders(h);
        resp.headers.forEach(h::set);
        if (resp.status == 204 || ex.getRequestMethod().equals("HEAD")) {
            ex.sendResponseHeaders(resp.status, -1);
        } else {
            ex.sendResponseHeaders(resp.status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
        return resp.status;
    }

    /** Should this request be recorded as a span? (The gateway skips its own console and file requests.) */
    protected boolean traced(String path) { return true; }

    /** Extra headers on every answer (the gateway adds browser security headers here). */
    protected void addHeaders(com.sun.net.httpserver.Headers h) {}

    // ================================================================ metrics + tracing

    private synchronized void record(String trace, String caller, String method, String path, int status, long start, long ms) {
        requests++;
        if (status >= 500) errors++;
        recentMs.addLast(ms);
        if (recentMs.size() > 100) recentMs.pollFirst();
        spans.addLast(Json.obj("traceId", trace, "service", name, "caller", caller, "method", method,
                "path", path, "status", status, "start", start, "ms", ms));
        if (spans.size() > 600) spans.pollFirst();
    }

    /** Adds a span for work that is not an HTTP request (for example one saga step). */
    protected synchronized void recordStep(String trace, String label, int status, long start, long ms) {
        spans.addLast(Json.obj("traceId", trace, "service", name, "caller", name, "method", "STEP",
                "path", label, "status", status, "start", start, "ms", ms));
        if (spans.size() > 600) spans.pollFirst();
    }

    private synchronized List<Object> spans(long since) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> s : spans) if (Json.lng(s, "start") >= since) out.add(s);
        return out;
    }

    public synchronized Map<String, Object> stats() {
        long sum = 0;
        for (long v : recentMs) sum += v;
        return Json.obj("name", name, "role", role(), "url", url, "startedAt", startedAt,
                "down", down, "latencyMs", extraLatencyMs,
                "requests", requests, "errors", errors,
                "avgMs", recentMs.isEmpty() ? 0 : Math.round(sum * 10.0 / recentMs.size()) / 10.0,
                "outbox", outboxSize(), "eventsReceived", eventsReceived, "duplicateEvents", duplicateEvents,
                "subscriptions", new ArrayList<>(eventHandlers.keySet()));
    }

    public boolean isDown() { return down; }

    /** Used by tests and the launcher. */
    public void setChaos(boolean down, int latencyMs) {
        this.down = down;
        this.extraLatencyMs = latencyMs;
    }

    // ================================================================ small helpers for services

    protected static String requireText(Map<String, Object> b, String key, String label, int max) {
        String v = Json.str(b, key);
        if (v == null || v.isBlank()) throw ApiError.bad(label + " is required");
        v = v.trim();
        if (v.length() > max) throw ApiError.bad(label + " is too long (max " + max + " characters)");
        return v;
    }

    protected static String optText(Map<String, Object> b, String key, int max) {
        String v = Json.str(b, key);
        if (v == null) return "";
        v = v.trim();
        if (v.length() > max) v = v.substring(0, max);
        return v;
    }
}
