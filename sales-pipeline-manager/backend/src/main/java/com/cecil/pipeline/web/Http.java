package com.cecil.pipeline.web;

import com.cecil.pipeline.service.ServiceException;
import com.cecil.pipeline.util.Json;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Small helpers on top of the JDK's built-in HTTP server. */
public final class Http {

    /** Biggest request body we accept (JSON only, so 1 MB is plenty). */
    public static final int MAX_BODY = 1024 * 1024;

    private static final Map<String, String> TYPES = Map.of(
        "html", "text/html; charset=utf-8",
        "css", "text/css; charset=utf-8",
        "js", "text/javascript; charset=utf-8",
        "svg", "image/svg+xml",
        "png", "image/png",
        "ico", "image/x-icon",
        "json", "application/json; charset=utf-8",
        "txt", "text/plain; charset=utf-8");

    private Http() { }

    /** Security headers added to every response. */
    public static void secure(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; "
                + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    public static void json(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, bytes);
    }

    public static void error(HttpExchange ex, int status, String message) throws IOException {
        json(ex, status, Json.obj("error", message));
    }

    public static void text(HttpExchange ex, int status, String contentType, String body, String downloadName) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (downloadName != null) ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + downloadName + "\"");
        send(ex, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, byte[] bytes) throws IOException {
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head || status == 204 ? -1 : bytes.length);
        if (!head && status != 204) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            ex.close();
        }
    }

    /** Reads the request body as a JSON object. An empty body gives an empty map. */
    public static Map<String, Object> body(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        String raw = readBody(ex);
        if (raw.isBlank()) return new HashMap<>();
        if (ct == null || !ct.toLowerCase().contains("application/json")) {
            throw new ServiceException(415, "Send the data as JSON");
        }
        try {
            return Json.parseObject(raw);
        } catch (IllegalArgumentException e) {
            throw ServiceException.bad("The data sent is not valid JSON");
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0, n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BODY) throw new ServiceException(413, "Request is too large");
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    public static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return q;
        for (String part : raw.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            try {
                q.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // skip badly encoded parameters
            }
        }
        return q;
    }

    public static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) return h.substring(7).trim();
        return null;
    }

    /** Serves a file from the frontend folder. Blocks "../" tricks so nothing outside that folder can be read. */
    public static void file(HttpExchange ex, Path root, String relative) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path p = base.resolve(relative).normalize();
        if (!p.startsWith(base) || !Files.isRegularFile(p)) {
            error(ex, 404, "Not found");
            return;
        }
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        send(ex, 200, Files.readAllBytes(p));
    }
}
