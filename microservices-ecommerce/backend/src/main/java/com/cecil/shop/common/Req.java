package com.cecil.shop.common;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One incoming HTTP request, already read and parsed.
 * The gateway checks the login and passes the user to the services in X-User-* headers,
 * so services can read r.userId() / r.role() without checking passwords themselves.
 */
public class Req {
    public final String method;
    public final String path;
    public final Map<String, String> query;
    public final Map<String, String> headers; // names in lower case
    public final String rawBody;
    public final Map<String, String> params = new HashMap<>();
    public final String traceId;
    public String remoteIp = "";
    private Object parsed;
    private boolean didParse;

    public Req(String method, String path, String rawQuery, Map<String, String> headers, String rawBody, String traceId) {
        this.method = method;
        this.path = path;
        this.query = parseQuery(rawQuery);
        this.headers = headers;
        this.rawBody = rawBody == null ? "" : rawBody;
        this.traceId = traceId;
    }

    public static Map<String, String> parseQuery(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return q;
        for (String part : raw.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            q.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return q;
    }

    public String header(String name) { return headers.get(name.toLowerCase()); }

    public String q(String name) { return query.get(name); }

    public String q(String name, String def) {
        String v = query.get(name);
        return v == null || v.isBlank() ? def : v;
    }

    public String param(String name) { return params.get(name); }

    public long paramLong(String name) {
        try { return Long.parseLong(params.get(name)); } catch (Exception e) { throw ApiError.notFound("Not found"); }
    }

    public Object body() {
        if (!didParse) {
            didParse = true;
            try { parsed = Json.parse(rawBody); } catch (IllegalArgumentException e) { throw ApiError.bad("Request body is not valid JSON: " + e.getMessage()); }
        }
        return parsed;
    }

    public Map<String, Object> json() {
        Object b = body();
        if (b == null) return new LinkedHashMap<>();
        if (!(b instanceof Map)) throw ApiError.bad("Request body must be a JSON object");
        return Json.map(b);
    }

    public List<Object> jsonList() {
        Object b = body();
        if (!(b instanceof List)) throw ApiError.bad("Request body must be a JSON list");
        return Json.list(b);
    }

    // --- who is calling (set by the gateway after it checks the login token)

    public long userId() {
        try { return Long.parseLong(header("x-user-id")); } catch (Exception e) { return 0; }
    }

    public String role() { String r = header("x-user-role"); return r == null ? "" : r; }

    public String userName() { String n = header("x-user-name"); return n == null ? "" : n; }

    public boolean isAdmin() { return "ADMIN".equals(role()); }

    /** Name of the service that made this call ("client" when it came from the browser through the gateway). */
    public String caller() { String c = header("x-caller"); return c == null ? "client" : c; }

    public long requireUser() {
        long id = userId();
        if (id <= 0) throw new ApiError(401, "Please sign in first");
        return id;
    }

    public void requireAdmin() {
        requireUser();
        if (!isAdmin()) throw ApiError.forbidden("Only the store admin can do this");
    }
}
