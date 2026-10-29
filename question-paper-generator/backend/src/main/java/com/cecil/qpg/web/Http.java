package com.cecil.qpg.web;

import com.cecil.qpg.service.ApiError;
import com.cecil.qpg.util.Json;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small helpers for reading requests and sending answers, plus the security headers sent with every answer. */
public final class Http {
    private Http() {}

    public static final int MAX_BODY = 1024 * 1024; // 1 MB

    /** Content-Security-Policy: only our own scripts and styles may run; no inline code, no frames. */
    public static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";

    public static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Content-Security-Policy", CSP);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
    }

    public static String body(HttpExchange ex) throws IOException {
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        if (len != null) {
            try { if (Long.parseLong(len) > MAX_BODY) throw new ApiError(413, "Request is too large (max 1 MB)"); }
            catch (NumberFormatException ignored) { /* checked while reading */ }
        }
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY) throw new ApiError(413, "Request is too large (max 1 MB)");
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    public static Map<String, Object> jsonBody(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase().startsWith("application/json")) throw new ApiError(415, "Send JSON (Content-Type: application/json)");
        String b = body(ex);
        if (b.isBlank()) return new LinkedHashMap<>();
        try { return Json.parseObject(b); }
        catch (IllegalArgumentException e) { throw ApiError.bad("Bad JSON: " + e.getMessage()); }
    }

    public static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new LinkedHashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.isEmpty()) return m;
        for (String part : q.split("&")) {
            if (part.isEmpty()) continue;
            int i = part.indexOf('=');
            String k = i < 0 ? part : part.substring(0, i);
            String v = i < 0 ? "" : part.substring(i + 1);
            try {
                m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw ApiError.bad("Bad characters in the address");
            }
        }
        return m;
    }

    public static void json(HttpExchange ex, int status, Object data) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.write(data).getBytes(StandardCharsets.UTF_8), true);
    }

    public static void error(HttpExchange ex, int status, String msg) throws IOException {
        json(ex, status, Json.map("error", msg));
    }

    public static void download(HttpExchange ex, String type, String fileName, String text) throws IOException {
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName.replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
        send(ex, 200, type, text.getBytes(StandardCharsets.UTF_8), true);
    }

    public static void send(HttpExchange ex, int status, String type, byte[] bytes, boolean noStore) throws IOException {
        securityHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", type);
        if (noStore) ex.getResponseHeaders().set("Cache-Control", "no-store");
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
        ex.close();
    }

    public static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return null;
        String t = h.substring(7).trim();
        return t.isEmpty() || t.length() > 100 ? null : t;
    }

    /**
     * Blocks requests from other websites: if the browser sends an Origin header, it must be this server.
     * (Sessions use a header token, not a cookie, so this is a second layer of protection.)
     */
    public static void checkOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return;
        String host = ex.getRequestHeaders().getFirst("Host");
        String o = origin.replaceFirst("^https?://", "");
        if (host == null || !o.equalsIgnoreCase(host)) throw ApiError.forbidden("Requests from other websites are not allowed");
    }
}
