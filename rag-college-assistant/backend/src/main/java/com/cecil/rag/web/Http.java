package com.cecil.rag.web;

import com.cecil.rag.service.ApiError;
import com.cecil.rag.util.Json;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Small helpers around Java's built-in HttpExchange: read the request, write the response, security headers. */
public final class Http {
    public static final int MAX_BODY = 12 * 1024 * 1024;
    public static final String COOKIE = "gazette_session";

    final HttpExchange ex;
    final String method;
    final String path;
    final Map<String, String> query = new HashMap<>();

    Http(HttpExchange ex) {
        this.ex = ex;
        this.method = ex.getRequestMethod().toUpperCase();
        URI uri = ex.getRequestURI();
        this.path = uri.getRawPath();
        String q = uri.getRawQuery();
        if (q != null) {
            for (String part : q.split("&")) {
                int eq = part.indexOf('=');
                String k = eq < 0 ? part : part.substring(0, eq);
                String v = eq < 0 ? "" : part.substring(eq + 1);
                query.put(decode(k), decode(v));
            }
        }
    }

    static String decode(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; }
    }

    String q(String name) { return query.getOrDefault(name, ""); }

    String cookie(String name) {
        String all = ex.getRequestHeaders().getFirst("Cookie");
        if (all == null) return null;
        for (String c : all.split(";")) {
            String t = c.strip();
            if (t.startsWith(name + "=")) return t.substring(name.length() + 1);
        }
        return null;
    }

    Map<String, Object> json() throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase().startsWith("application/json")) throw new ApiError(415, "Send JSON (Content-Type: application/json).");
        String body = new String(readBody(), StandardCharsets.UTF_8);
        if (body.isBlank()) return new HashMap<>();
        try { return Json.parseObject(body); }
        catch (IllegalArgumentException e) { throw ApiError.bad("The request is not valid JSON."); }
    }

    private byte[] readBody() throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) throw new ApiError(413, "The request is too big.");
            }
            return out.toByteArray();
        }
    }

    /**
     * CSRF protection: a request that changes something must come from this same site.
     * Browsers always send Origin on POST/PUT/DELETE, and a page on another site cannot fake it.
     */
    void checkSameOrigin() {
        if (method.equals("GET") || method.equals("HEAD")) return;
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (origin == null) {
            String ref = ex.getRequestHeaders().getFirst("Referer");
            if (ref == null) return; // non-browser tools (curl, tests) send neither
            origin = ref;
        }
        String o = origin.replaceFirst("^https?://", "");
        int slash = o.indexOf('/');
        if (slash >= 0) o = o.substring(0, slash);
        if (host == null || !o.equalsIgnoreCase(host)) throw new ApiError(403, "Request blocked: it did not come from this site.");
    }

    void securityHeaders() {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "same-origin");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
    }

    void setCookie(String value, int maxAgeSeconds) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAgeSeconds);
    }

    void send(int status, Object body) throws IOException {
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        securityHeaders();
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    void sendBytes(int status, String contentType, byte[] b, boolean cache) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", cache ? "public, max-age=300" : "no-store");
        securityHeaders();
        if (method.equals("HEAD")) { ex.sendResponseHeaders(status, -1); ex.close(); return; }
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static String contentType(String file) {
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
