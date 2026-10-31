package com.cecil.shop.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Matches "GET /orders/{id}" style routes to handler methods.
 * Each service fills its own router in its routes() method.
 */
public class Router {
    @FunctionalInterface
    public interface Handler {
        Object handle(Req r) throws Exception;
    }

    private record Route(String method, String[] parts, Handler handler) {}

    private final List<Route> routes = new ArrayList<>();

    public Router get(String path, Handler h) { return add("GET", path, h); }
    public Router post(String path, Handler h) { return add("POST", path, h); }
    public Router put(String path, Handler h) { return add("PUT", path, h); }
    public Router delete(String path, Handler h) { return add("DELETE", path, h); }

    public Router add(String method, String path, Handler h) {
        routes.add(new Route(method, split(path), h));
        return this;
    }

    private static String[] split(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? new String[0] : p.split("/");
    }

    /** Finds the handler. Fills r.params. Throws 404 / 405 when nothing matches. */
    public Handler find(Req r) {
        String[] parts = split(r.path);
        boolean pathMatched = false;
        for (Route route : routes) {
            if (route.parts.length != parts.length) continue;
            boolean ok = true;
            for (int i = 0; i < parts.length && ok; i++) {
                String rp = route.parts[i];
                if (!(rp.startsWith("{") || rp.equals(parts[i]))) ok = false;
            }
            if (!ok) continue;
            pathMatched = true;
            if (!route.method.equals(r.method)) continue;
            for (int i = 0; i < parts.length; i++) {
                String rp = route.parts[i];
                if (rp.startsWith("{")) r.params.put(rp.substring(1, rp.length() - 1), parts[i]);
            }
            return route.handler;
        }
        if (pathMatched) throw new ApiError(405, "Method " + r.method + " is not allowed here");
        throw ApiError.notFound("No such API: " + r.method + " " + r.path);
    }
}
