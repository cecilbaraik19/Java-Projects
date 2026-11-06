package com.cecil.certify.web;

import com.cecil.certify.util.Clock;
import com.cecil.certify.util.Json;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small helpers around the JDK's built-in web server. */
public final class Http {
    private Http() {}

    public static final int MAX_BODY = 2_000_000; // 2 MB is plenty for a 500-row CSV

    /** Content-Security-Policy: only our own scripts and styles may run; no inline code at all. */
    public static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com; "
            + "font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; object-src 'none'; "
            + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    /** Thrown to send a clean error message back to the browser. */
    public static final class Error extends RuntimeException {
        public final int status;

        public Error(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY) throw new Error(413, "That is too big (2 MB at most)");
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    public static Map<String, Object> jsonBody(HttpExchange ex) throws IOException {
        String b = body(ex);
        if (b.isBlank()) return new LinkedHashMap<>();
        try {
            return Json.parseObject(b);
        } catch (IllegalArgumentException e) {
            throw new Error(400, "The request was not valid JSON");
        }
    }

    public static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            String k = i < 0 ? part : part.substring(0, i);
            String v = i < 0 ? "" : part.substring(i + 1);
            q.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return q;
    }

    public static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Content-Security-Policy", CSP);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    public static void send(HttpExchange ex, int status, String contentType, byte[] data) throws IOException {
        securityHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", contentType);
        if (!ex.getResponseHeaders().containsKey("Cache-Control")) ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, data.length == 0 ? -1 : data.length);
        if (data.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        } else {
            ex.close();
        }
    }

    public static void json(HttpExchange ex, int status, Object value) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.write(value).getBytes(StandardCharsets.UTF_8));
    }

    public static String cookie(HttpExchange ex, String name) {
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
            }
        }
        return null;
    }

    public static void setSessionCookie(HttpExchange ex, String name, String value, long maxAgeSeconds) {
        ex.getResponseHeaders().add("Set-Cookie", name + "=" + value + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAgeSeconds);
    }

    public static String clientIp(HttpExchange ex) {
        return ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
    }

    /** Simple "at most N in the last minute" limiter, per key (for example per IP address). */
    public static final class RateLimiter {
        private final int perMinute;
        private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

        public RateLimiter(int perMinute) {
            this.perMinute = perMinute;
        }

        public boolean allow(String key) {
            long now = Clock.now();
            Deque<Long> d = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (d) {
                while (!d.isEmpty() && now - d.peekFirst() > 60_000) d.pollFirst();
                if (d.size() >= perMinute) return false;
                d.addLast(now);
                return true;
            }
        }
    }
}
