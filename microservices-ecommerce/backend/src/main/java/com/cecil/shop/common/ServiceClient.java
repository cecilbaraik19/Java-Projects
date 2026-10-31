package com.cecil.shop.common;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * How one service calls another service: a plain HTTP request with JSON.
 *
 * It always adds:
 *   X-Internal-Key  - proves the call comes from inside our system
 *   X-Trace-Id      - the same id for every hop of one user action (distributed tracing)
 *   X-Caller        - the name of the calling service
 *   X-User-*        - who the user is (copied from the incoming request)
 *
 * The address of the other service is found through the service registry (Discovery),
 * never hard-coded.
 */
public class ServiceClient {
    public record Result(int status, Object body, Map<String, List<String>> headers) {
        public boolean ok() { return status >= 200 && status < 300; }
        public Map<String, Object> json() { return Json.map(body); }
        public List<Object> jsonList() { return Json.list(body); }
        public String error() {
            String e = Json.str(Json.map(body), "error");
            return e == null ? "HTTP " + status : e;
        }
        public String header(String name) {
            if (headers == null) return null;
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
            }
            return null;
        }
    }

    private final String self;
    private final Config cfg;
    private final Discovery discovery;
    private final HttpClient http;

    public ServiceClient(String self, Config cfg, Discovery discovery) {
        this.self = self;
        this.cfg = cfg;
        this.discovery = discovery;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1500))
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    /** Call a service by NAME. The registry tells us where it is running. */
    public Result call(String service, String method, String path, Object body, Req ctx) {
        String base = discovery.pick(service);
        return callUrl(base, method, path, body, ctx, ctx == null ? Ids.trace() : ctx.traceId, cfg.callTimeoutMs, service);
    }

    /** Same as call(), but turns any error answer into an ApiError, and returns the JSON object. */
    public Map<String, Object> okJson(String service, String method, String path, Object body, Req ctx) {
        return requireOk(service, call(service, method, path, body, ctx)).json();
    }

    public List<Object> okList(String service, String method, String path, Object body, Req ctx) {
        return requireOk(service, call(service, method, path, body, ctx)).jsonList();
    }

    public static Result requireOk(String service, Result r) {
        if (r.ok()) return r;
        if (r.status >= 500) throw ApiError.unavailable(service + ": " + r.error());
        throw new ApiError(r.status, r.error());
    }

    /** Low-level call to an exact address. A String body is sent as-is; anything else is turned into JSON. */
    public Result callUrl(String baseUrl, String method, String path, Object body, Req ctx, String traceId, int timeoutMs, String targetName) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Key", cfg.internalKey)
                    .header("X-Trace-Id", traceId == null ? Ids.trace() : traceId)
                    .header("X-Caller", self);
            if (ctx != null) {
                if (ctx.userId() > 0) b.header("X-User-Id", String.valueOf(ctx.userId()));
                if (!ctx.role().isEmpty()) b.header("X-User-Role", ctx.role());
                if (!ctx.userName().isEmpty()) b.header("X-User-Name", ctx.userName());
                if (ctx.path.startsWith("/api/arch")) b.header("X-No-Trace", "1");
            }
            String text = body == null ? null : (body instanceof String s ? s : Json.write(body));
            HttpRequest.BodyPublisher pub = text == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8);
            b.method(method, pub);
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Object parsed;
            try { parsed = Json.parse(res.body()); } catch (IllegalArgumentException e) { parsed = Json.obj("error", "Bad reply from " + targetName); }
            return new Result(res.statusCode(), parsed, res.headers().map());
        } catch (java.net.http.HttpTimeoutException e) {
            throw ApiError.unavailable(targetName + " did not answer in time (timeout " + timeoutMs + " ms)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiError.unavailable(targetName + " call was interrupted");
        } catch (java.io.IOException e) {
            throw ApiError.unavailable(targetName + " cannot be reached");
        }
    }
}
