package com.cecil.sql.web;

import com.cecil.sql.util.Json;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Small helpers around the JDK's built-in HTTP server: reading bodies, sending JSON, cookies, headers, rate limits. */
public final class Http {
    private Http() {}

    public static final String COOKIE = "prashna_sid";

    /** Thrown to send an error status with a plain-English message. */
    public static final class Fail extends RuntimeException {
        public final int status;
        public Fail(int status, String message) { super(message); this.status = status; }
    }

    public static String body(HttpExchange ex, int maxBytes) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n, total = 0;
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > maxBytes) throw new Fail(413, "The request is too large.");
                buf.write(b, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    public static Map<String, Object> jsonBody(HttpExchange ex, int maxBytes) throws IOException {
        String text = body(ex, maxBytes);
        if (text.isBlank()) return new LinkedHashMap<>();
        try { return Json.parseObject(text); }
        catch (IllegalArgumentException e) { throw new Fail(400, "The request is not valid JSON."); }
    }

    public static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com; "
                + "font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; "
                + "base-uri 'none'; form-action 'self'; object-src 'none'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
    }

    public static void json(HttpExchange ex, int status, Object value) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.write(value).getBytes(StandardCharsets.UTF_8));
    }

    public static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        securityHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", type);
        if (!ex.getResponseHeaders().containsKey("Cache-Control")) ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (status == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
        ex.close();
    }

    public static String cookie(HttpExchange ex, String name) {
        List<String> all = ex.getRequestHeaders().get("Cookie");
        if (all == null) return null;
        for (String line : all)
            for (String part : line.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq > 0 && p.substring(0, eq).equals(name)) return p.substring(eq + 1);
            }
        return null;
    }

    public static void setSession(HttpExchange ex, String token) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200");
    }

    public static void clearSession(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    }

    public static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            m.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    /**
     * Cross-site request forgery check for anything that changes data: the request must be JSON
     * (a plain HTML form cannot send that) and, if the browser says where it came from, it must be this site.
     */
    public static void checkSameOrigin(HttpExchange ex) {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase().startsWith("application/json")) throw new Fail(415, "Requests must be JSON.");
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (origin != null && host != null && !origin.equals("http://" + host) && !origin.equals("https://" + host))
            throw new Fail(403, "Request from another website was blocked.");
        String site = ex.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) throw new Fail(403, "Request from another website was blocked.");
    }

    /** Sliding-window rate limiter: at most {@code max} calls per {@code windowMillis} for each key. */
    public static final class RateLimiter {
        private final int max;
        private final long windowMillis;
        private final Map<String, Deque<Long>> calls = new ConcurrentHashMap<>();

        public RateLimiter(int max, long windowMillis) { this.max = max; this.windowMillis = windowMillis; }

        public boolean allow(String key) {
            long now = System.currentTimeMillis();
            Deque<Long> d = calls.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (d) {
                while (!d.isEmpty() && now - d.peekFirst() > windowMillis) d.pollFirst();
                if (d.size() >= max) return false;
                d.addLast(now);
                return true;
            }
        }
    }

    public static String contentType(String file) {
        String f = file.toLowerCase();
        if (f.endsWith(".html")) return "text/html; charset=utf-8";
        if (f.endsWith(".css")) return "text/css; charset=utf-8";
        if (f.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (f.endsWith(".svg")) return "image/svg+xml";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".ico")) return "image/x-icon";
        if (f.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
}
