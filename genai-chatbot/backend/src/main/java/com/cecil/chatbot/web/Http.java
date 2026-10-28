package com.cecil.chatbot.web;

import com.cecil.chatbot.service.ServiceException;
import com.cecil.chatbot.util.Json;
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
import java.util.LinkedHashMap;
import java.util.Map;

/** Small helpers around the JDK's built-in HTTP server: read requests, send JSON / files, add security headers. */
public final class Http {

    static final int MAX_BODY = 1_000_000; // 1 MB is plenty for any form in this app

    /**
     * Content-Security-Policy: the browser may only load scripts, styles and data from this server.
     * No inline scripts or inline styles, so injected HTML cannot run code.
     */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    private Http() { }

    public static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return q;
        for (String part : raw.split("&")) {
            if (part.isEmpty()) continue;
            int i = part.indexOf('=');
            String k = decode(i < 0 ? part : part.substring(0, i));
            String v = i < 0 ? "" : decode(part.substring(i + 1));
            q.put(k, v);
        }
        return q;
    }

    public static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw ServiceException.bad("Bad characters in the address.");
        }
    }

    /** Reads the request body as a JSON object (empty body = empty object). */
    public static Map<String, Object> body(HttpExchange ex) throws IOException {
        String text = readBody(ex);
        if (text.isBlank()) return new LinkedHashMap<>();
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase().startsWith("application/json")) {
            throw ServiceException.bad("Send the data as JSON.");
        }
        try {
            return Json.parseObject(text);
        } catch (IllegalArgumentException e) {
            throw ServiceException.bad("The data is not valid JSON: " + e.getMessage());
        }
    }

    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY) throw new ServiceException(413, "The data sent is too large.");
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    public static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return null;
        return h.substring(7).trim();
    }

    static void securityHeaders(Headers h) {
        h.set("Content-Security-Policy", CSP);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    public static void json(HttpExchange ex, int status, Object data) throws IOException {
        byte[] bytes = Json.write(data).getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        securityHeaders(h);
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        send(ex, status, bytes);
    }

    public static void error(HttpExchange ex, int status, String message) throws IOException {
        json(ex, status, Map.of("error", message));
    }

    public static void download(HttpExchange ex, String fileName, String contentType, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        securityHeaders(h);
        h.set("Content-Type", contentType);
        h.set("Content-Disposition", "attachment; filename=\"" + fileName.replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
        h.set("Cache-Control", "no-store");
        send(ex, 200, bytes);
    }

    /**
     * Starts a "Server-Sent Events" stream (text/event-stream). The reply is sent in small pieces
     * while the AI is still writing, so the person sees the answer appear word by word.
     */
    public static OutputStream startStream(HttpExchange ex) throws IOException {
        Headers h = ex.getResponseHeaders();
        securityHeaders(h);
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0); // 0 = length not known yet (chunked)
        return ex.getResponseBody();
    }

    /** Writes one event: "event: name" + "data: {json}" + blank line. Json.write never puts a line break inside. */
    public static void event(OutputStream os, String name, Object data) throws IOException {
        os.write(("event: " + name + "\ndata: " + Json.write(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /** Sends a file from the frontend folder. */
    public static void file(HttpExchange ex, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        Headers h = ex.getResponseHeaders();
        securityHeaders(h);
        h.set("Content-Type", contentType(file.getFileName().toString()));
        h.set("Cache-Control", "no-cache");
        send(ex, 200, bytes);
    }

    private static void send(HttpExchange ex, int status, byte[] bytes) throws IOException {
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            if (bytes.length > 0) os.write(bytes);
        }
    }

    static String contentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    /**
     * Blocks cross-site form posts (CSRF): a request that changes data must come from this same site.
     * Browsers always send an Origin header with such requests.
     */
    static boolean sameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isEmpty()) return true; // not from a browser form (curl, tests)
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) return false;
        return origin.equals("http://" + host) || origin.equals("https://" + host);
    }
}
