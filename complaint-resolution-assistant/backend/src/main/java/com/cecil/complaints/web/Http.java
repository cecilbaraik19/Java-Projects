package com.cecil.complaints.web;

import com.cecil.complaints.model.User;
import com.cecil.complaints.service.ServiceException;
import com.cecil.complaints.util.Json;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny request/response/router classes on top of the JDK's built-in HTTP server.
 * (No Spring, no external libraries - just Java.)
 */
public final class Http {

    private Http() {
    }

    /** Who may call a route. */
    public enum Access {
        PUBLIC, AGENT, ADMIN
    }

    public interface Handler {
        Response handle(Request req) throws Exception;
    }

    // -------------------------------------------------------------------- request

    public static final class Request {
        public final String method;
        public final String path;
        public final Map<String, String> query;
        public final Map<String, String> headers;
        public final String body;
        public final String clientIp;
        public Map<String, String> params = new HashMap<>();
        public User user;

        public Request(String method, String path, String rawQuery, Map<String, String> headers, String body,
                       String clientIp) {
            this.method = method;
            this.path = path;
            this.query = parseQuery(rawQuery);
            this.headers = headers;
            this.body = body;
            this.clientIp = clientIp;
        }

        public String param(String name) {
            return params.get(name);
        }

        public String query(String name) {
            return query.get(name);
        }

        public Map<String, Object> json() {
            if (body == null || body.isBlank()) {
                return new LinkedHashMap<>();
            }
            try {
                return Json.parseObject(body);
            } catch (Json.JsonException ex) {
                throw ServiceException.badRequest("Invalid JSON: " + ex.getMessage());
            }
        }

        public String bearerToken() {
            String h = headers.get("authorization");
            if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return h.substring(7).trim();
            }
            return null;
        }

        static Map<String, String> parseQuery(String raw) {
            Map<String, String> m = new HashMap<>();
            if (raw == null || raw.isEmpty()) {
                return m;
            }
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            }
            return m;
        }
    }

    // -------------------------------------------------------------------- response

    public static final class Response {
        public final int status;
        public final String contentType;
        public final byte[] body;
        public final Map<String, String> headers = new LinkedHashMap<>();

        public Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        public static Response json(Object data) {
            return json(200, data);
        }

        public static Response json(int status, Object data) {
            return new Response(status, "application/json; charset=utf-8",
                    Json.stringify(data).getBytes(StandardCharsets.UTF_8));
        }

        public static Response error(int status, String message) {
            return json(status, Map.of("error", message));
        }

        public static Response text(int status, String contentType, String text) {
            return new Response(status, contentType, text.getBytes(StandardCharsets.UTF_8));
        }

        public Response header(String k, String v) {
            headers.put(k, v);
            return this;
        }
    }

    // -------------------------------------------------------------------- router

    public static final class Router {

        public record Route(String method, Pattern pattern, List<String> names, Access access, Handler handler) {
        }

        public record Match(Route route, Map<String, String> params) {
        }

        private final List<Route> routes = new ArrayList<>();

        /** Pattern like "/api/complaints/{id}/notes". */
        public void add(String method, String template, Access access, Handler handler) {
            List<String> names = new ArrayList<>();
            StringBuilder regex = new StringBuilder("^");
            for (String part : template.split("/")) {
                if (part.isEmpty()) {
                    continue;
                }
                regex.append("/");
                if (part.startsWith("{") && part.endsWith("}")) {
                    names.add(part.substring(1, part.length() - 1));
                    regex.append("([A-Za-z0-9._-]+)");
                } else {
                    regex.append(Pattern.quote(part));
                }
            }
            regex.append("/?$");
            routes.add(new Route(method, Pattern.compile(regex.toString()), names, access, handler));
        }

        /** @return the match, or null. Sets methodNotAllowed[0] if the path exists with another method. */
        public Match find(String method, String path, boolean[] methodNotAllowed) {
            for (Route r : routes) {
                Matcher m = r.pattern().matcher(path);
                if (m.matches()) {
                    if (!r.method().equals(method)) {
                        methodNotAllowed[0] = true;
                        continue;
                    }
                    Map<String, String> params = new HashMap<>();
                    for (int i = 0; i < r.names().size(); i++) {
                        params.put(r.names().get(i), m.group(i + 1));
                    }
                    return new Match(r, params);
                }
            }
            return null;
        }
    }
}
