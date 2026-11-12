package com.cecil.sikho.web;

import com.cecil.sikho.util.Json;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small helpers for reading requests and writing responses with the JDK's built-in web server. */
public final class Http {
    public static final int MAX_BODY = 200_000;   // a large message plus JSON quoting

    private Http() { }

    /** Security headers sent with every answer. */
    public static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com; "
                + "font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; "
                + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
    }

    public static String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY) throw new TooBig();
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    public static final class TooBig extends IOException {
        private static final long serialVersionUID = 1L;
        TooBig() { super("Request is too large"); }
    }

    public static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return q;
        for (String part : raw.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = decode(eq < 0 ? part : part.substring(0, eq));
            String v = eq < 0 ? "" : decode(part.substring(eq + 1));
            q.putIfAbsent(k, v);
        }
        return q;
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8); } catch (IllegalArgumentException e) { return ""; }
    }

    public static String cookie(HttpExchange ex, String name) {
        String all = ex.getRequestHeaders().getFirst("Cookie");
        if (all == null) return null;
        for (String c : all.split(";")) {
            String t = c.trim();
            if (t.startsWith(name + "=")) return t.substring(name.length() + 1);
        }
        return null;
    }

    public static void json(HttpExchange ex, int status, Object data) throws IOException {
        byte[] b = Json.write(data).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, b);
    }

    public static void error(HttpExchange ex, int status, String message, Object data) throws IOException {
        json(ex, status, Json.obj("error", message, "data", data));
    }

    public static void download(HttpExchange ex, String contentType, String fileName, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName.replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, bytes);
    }

    public static void send(HttpExchange ex, int status, byte[] bytes) throws IOException {
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            if (bytes.length > 0) out.write(bytes);
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
        if (f.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    public static String clientIp(HttpExchange ex) {
        return ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
    }
}
