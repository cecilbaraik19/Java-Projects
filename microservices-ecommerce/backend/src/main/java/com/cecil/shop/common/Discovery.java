package com.cecil.shop.common;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service discovery (client side).
 *
 * Instead of writing "the catalog is at port 8082" in the code, a service asks the registry
 * "where is catalog-service?" and gets a list of running copies (instances).
 * If there are several copies we take turns between them (round-robin load balancing).
 *
 * Answers are kept for a short time (cache), and if the registry itself is down we keep using
 * the last answer we got, so the shop keeps working.
 */
public class Discovery {
    private record Cached(List<String> urls, long at) {}

    private final Config cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1000)).proxy(HttpClient.Builder.NO_PROXY).build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> turn = new ConcurrentHashMap<>();

    public Discovery(Config cfg) { this.cfg = cfg; }

    /** Addresses of the running (UP) instances of a service. */
    public List<String> instances(String name) {
        Cached c = cache.get(name);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at < cfg.discoveryCacheMs) return c.urls;
        try {
            Map<String, Object> res = Json.map(get("/services/" + name));
            List<String> urls = new ArrayList<>();
            for (Object o : Json.list(res, "instances")) {
                Map<String, Object> inst = Json.map(o);
                if ("UP".equals(Json.str(inst, "status"))) urls.add(Json.str(inst, "url"));
            }
            cache.put(name, new Cached(urls, now));
            return urls;
        } catch (Exception e) {
            // registry not reachable: fall back to the last list we knew
            return c != null ? c.urls : List.of();
        }
    }

    /** Pick one instance (round-robin). Throws 503 if none is running. */
    public String pick(String name) {
        List<String> urls = instances(name);
        if (urls.isEmpty()) throw ApiError.unavailable(name + " is not running (no healthy copy in the service registry)");
        int i = Math.floorMod(turn.computeIfAbsent(name, k -> new AtomicInteger()).getAndIncrement(), urls.size());
        return urls.get(i);
    }

    /** Every instance the registry knows about, including DOWN ones. */
    public List<Object> allInstances() {
        try {
            return Json.list(Json.map(get("/services")).get("instances"));
        } catch (Exception e) {
            return List.of();
        }
    }

    public void forget(String name) { cache.remove(name); }

    private Object get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.registryUrl + path))
                .timeout(Duration.ofMillis(1500))
                .header("X-Internal-Key", cfg.internalKey)
                .header("X-Caller", "discovery")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("registry said " + res.statusCode());
        return Json.parse(res.body());
    }
}
