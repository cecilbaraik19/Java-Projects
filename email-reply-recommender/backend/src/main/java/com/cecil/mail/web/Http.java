package com.cecil.mail.web;

import com.cecil.mail.util.Json;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Small helpers for reading requests and writing responses with the JDK's built-in HTTP server. */
public final class Http {

    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final String COOKIE = "patra_session";

    private Http() { }

    /** Thrown to stop a request with an HTTP status and a message for the user. */
    public static class Stop extends RuntimeException {
        public final int status;

        public Stop(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY_BYTES) throw new Stop(413, "The request is too large.");
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    public static Map<String, Object> readJson(HttpExchange ex) throws IOException {
        try {
            return Json.parseObject(readBody(ex));
        } catch (IllegalArgumentException e) {
            throw new Stop(400, "The request is not valid JSON.");
        }
    }

    public static void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    public static void error(HttpExchange ex, int status, String message) throws IOException {
        json(ex, status, Map.of("error", message));
    }

    public static void noContent(HttpExchange ex) throws IOException {
        securityHeaders(ex.getResponseHeaders());
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    public static void send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        securityHeaders(h);
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head ? -1 : bytes.length);
        if (!head) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    /**
     * Security headers on every response:
     *  CSP: scripts and styles only from this server (no inline scripts), fonts from Google Fonts.
     *  nosniff: the browser must trust our Content-Type.  frame-ancestors none: no clickjacking.
     */
    static void securityHeaders(Headers h) {
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
                + "style-src 'self' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; "
                + "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; "
                + "form-action 'self'; frame-ancestors 'none'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Frame-Options", "DENY");
        h.set("Cache-Control", "no-store");
    }

    public static String cookie(HttpExchange ex, String name) {
        String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    public static void setSessionCookie(HttpExchange ex, String token) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200");
    }

    public static void clearSessionCookie(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    }

    public static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    /**
     * CSRF check for requests that change data: another website cannot make the browser send
     * a JSON request with our cookie, because (1) the cookie is SameSite=Strict, (2) we require
     * Content-Type application/json, and (3) if the browser sends an Origin it must be this server.
     */
    public static void checkSameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (origin != null && host != null && !origin.equals("http://" + host) && !origin.equals("https://" + host)) {
            throw new Stop(403, "Request blocked: it came from another website.");
        }
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase().startsWith("application/json")) {
            throw new Stop(415, "Send the request as JSON.");
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
        return "application/octet-stream";
    }
}
