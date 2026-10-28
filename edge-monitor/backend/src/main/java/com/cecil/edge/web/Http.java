package com.cecil.edge.web;

import com.cecil.edge.model.User;
import com.cecil.edge.service.ServiceException;
import com.cecil.edge.util.Json;
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

/** Helpers for reading requests and writing answers with the JDK's built-in HTTP server. */
public final class Http {
    private Http() { }

    public static final int MAX_BODY = 1_000_000; // 1 MB

    /** The page may only load its own scripts and styles: no inline code, no other websites. */
    public static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    /** One request, plus what the router found out about it. */
    public static final class Req {
        public final HttpExchange ex;
        public final String method;
        public final String path;
        public final Map<String, String> query;
        public final Map<String, String> params = new HashMap<>();
        public User user;
        public String token;
        private Map<String, Object> body;

        Req(HttpExchange ex) {
            this.ex = ex;
            this.method = ex.getRequestMethod().toUpperCase();
            this.path = ex.getRequestURI().getRawPath();
            this.query = parseQuery(ex.getRequestURI().getRawQuery());
        }

        public String header(String name) {
            return ex.getRequestHeaders().getFirst(name);
        }

        public String q(String k) {
            String v = query.get(k);
            return v == null || v.isBlank() ? null : v.trim();
        }

        public int qInt(String k, int dflt, int min, int max) {
            String v = q(k);
            if (v == null) return dflt;
            try {
                return Math.max(min, Math.min(max, Integer.parseInt(v)));
            } catch (NumberFormatException e) {
                throw ServiceException.bad("'" + k + "' must be a whole number");
            }
        }

        public Double qNum(String k) {
            String v = q(k);
            if (v == null) return null;
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException e) {
                throw ServiceException.bad("'" + k + "' must be a number");
            }
        }

        public String p(String k) {
            return params.get(k);
        }

        /** The JSON body as a map (empty map when there is no body). */
        public Map<String, Object> body() throws IOException {
            if (body != null) return body;
            byte[] raw = readLimited(ex.getRequestBody());
            if (raw.length == 0) return body = new HashMap<>();
            String text = new String(raw, StandardCharsets.UTF_8);
            try {
                return body = Json.parseObject(text);
            } catch (IllegalArgumentException e) {
                throw ServiceException.bad("The request body is not valid JSON");
            }
        }
    }

    static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > MAX_BODY) throw new ServiceException(413, "Request is too large (limit 1 MB)");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            String k = i < 0 ? part : part.substring(0, i);
            String v = i < 0 ? "" : part.substring(i + 1);
            try {
                m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) { /* bad escape: skip it */ }
        }
        return m;
    }

    static void securityHeaders(Headers h) {
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", CSP);
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    public static void send(HttpExchange ex, int status, String contentType, byte[] data, String extraHeaderName, String extraHeaderValue) throws IOException {
        Headers h = ex.getResponseHeaders();
        securityHeaders(h);
        h.set("Content-Type", contentType);
        h.set("Cache-Control", "no-store");
        if (extraHeaderName != null) h.set(extraHeaderName, extraHeaderValue);
        boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head || status == 204 ? -1 : data.length);
        if (!head && status != 204) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
        ex.close();
    }

    public static void json(HttpExchange ex, int status, Object value) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.write(value).getBytes(StandardCharsets.UTF_8), null, null);
    }

    public static void error(HttpExchange ex, int status, String message) throws IOException {
        json(ex, status, Json.obj("error", message));
    }

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json; charset=utf-8",
            "txt", "text/plain; charset=utf-8");

    /** Sends a file from the frontend folder. Paths that try to leave the folder (../) get a 404. */
    public static void file(HttpExchange ex, Path root, String rel) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path f = base.resolve(rel).normalize();
        if (!f.startsWith(base) || !Files.isRegularFile(f)) {
            error(ex, 404, "Not found");
            return;
        }
        String name = f.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        String type = TYPES.get(ext);
        if (type == null) {
            error(ex, 404, "Not found");
            return;
        }
        Headers h = ex.getResponseHeaders();
        securityHeaders(h);
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-cache");
        byte[] data = Files.readAllBytes(f);
        boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
        ex.sendResponseHeaders(200, head ? -1 : data.length);
        if (!head) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
        ex.close();
    }
}
