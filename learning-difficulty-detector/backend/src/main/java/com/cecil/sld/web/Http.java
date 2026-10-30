package com.cecil.sld.web;

import com.cecil.sld.service.ApiError;
import com.cecil.sld.util.Json;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Helpers for reading requests and sending answers, plus the security headers. */
public final class Http {
    private Http() {}

    public static final int MAX_BODY = 256 * 1024;   // 256 KB is plenty for any form
    public static final String COOKIE = "sld_session";

    /** Security headers added to every answer. */
    public static void secure(Headers h) {
        h.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                        + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; "
                        + "form-action 'self'; frame-ancestors 'none'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    public static String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY) throw new ApiError(413, "The request is too large.");
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /** The JSON body as a map ({} when the body is empty). */
    public static Map<String, Object> jsonBody(HttpExchange ex) throws IOException {
        String b = body(ex);
        if (b.isBlank()) return new LinkedHashMap<>();
        try {
            return Json.parseObject(b);
        } catch (IllegalArgumentException e) {
            throw ApiError.bad("The request is not valid JSON.");
        }
    }

    public static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.isEmpty()) return out;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            String k = i < 0 ? part : part.substring(0, i);
            String v = i < 0 ? "" : part.substring(i + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    public static String cookie(HttpExchange ex, String name) {
        String all = ex.getRequestHeaders().getFirst("Cookie");
        if (all == null) return null;
        for (String c : all.split(";")) {
            String[] kv = c.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    public static void setSessionCookie(HttpExchange ex, String token) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
    }

    public static void clearSessionCookie(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    }

    public static void json(HttpExchange ex, int status, Object value) throws IOException {
        byte[] b = Json.write(value).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, b);
    }

    public static void error(HttpExchange ex, int status, String message) throws IOException {
        json(ex, status, Map.of("error", message));
    }

    public static void download(HttpExchange ex, String filename, String contentType, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename.replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, b);
    }

    public static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
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
