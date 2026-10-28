package com.cecil.edge.web;

import com.cecil.edge.service.Hub;
import com.cecil.edge.service.ServiceException;
import com.cecil.edge.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The web server: the JSON API under /api/ and the website files under /static/.
 * Uses com.sun.net.httpserver.HttpServer, which comes with the JDK (no Tomcat or Spring needed).
 */
public class ApiServer {
    private final Hub hub;
    private final Path frontend;
    private final List<Route> routes = new ArrayList<>();
    private HttpServer server;
    private ExecutorService pool;

    /** Answer used for file downloads (anything that is not JSON). */
    public record Download(String contentType, String fileName, String text) { }

    @FunctionalInterface
    interface Handler {
        Object handle(Http.Req r) throws IOException;
    }

    /** One URL pattern such as /api/nodes/{id}, turned into a regular expression. */
    private static final class Route {
        final String method;
        final Pattern pattern;
        final List<String> names = new ArrayList<>();
        final boolean open;      // true = no sign-in needed
        final Handler handler;

        Route(String method, String path, boolean open, Handler handler) {
            this.method = method;
            this.open = open;
            this.handler = handler;
            Matcher m = Pattern.compile("\\{(\\w+)}").matcher(path);
            StringBuilder re = new StringBuilder();
            int last = 0;
            while (m.find()) {
                re.append(Pattern.quote(path.substring(last, m.start()))).append("([A-Za-z0-9._-]+)");
                names.add(m.group(1));
                last = m.end();
            }
            re.append(Pattern.quote(path.substring(last)));
            this.pattern = Pattern.compile(re.toString());
        }
    }

    public ApiServer(Hub hub, Path frontend) {
        this.hub = hub;
        this.frontend = frontend;
        routes();
    }

    private void get(String p, Handler h) { routes.add(new Route("GET", p, false, h)); }
    private void post(String p, Handler h) { routes.add(new Route("POST", p, false, h)); }
    private void put(String p, Handler h) { routes.add(new Route("PUT", p, false, h)); }
    private void delete(String p, Handler h) { routes.add(new Route("DELETE", p, false, h)); }

    private static Object ok() { return Json.obj("ok", true); }

    // ------------------------------------------------------------------ all API routes

    private void routes() {
        // sign in / out
        routes.add(new Route("POST", "/api/auth/login", true, r -> {
            var b = r.body();
            return hub.auth.login(Json.str(b, "username"), b.get("password") == null ? null : String.valueOf(b.get("password")));
        }));
        post("/api/auth/logout", r -> { hub.auth.logout(r.token); return ok(); });
        get("/api/auth/me", r -> com.cecil.edge.service.AuthService.view(r.user));
        post("/api/auth/password", r -> {
            var b = r.body();
            hub.auth.changePassword(r.user, r.token, (String) b.get("current"), (String) b.get("next"));
            return ok();
        });

        // a real edge device sends data here with its API key (no sign-in)
        routes.add(new Route("POST", "/api/ingest", true, r -> hub.nodes.ingest(r.header("X-Node-Key"), r.body())));

        get("/api/meta", r -> hub.settings.meta());
        get("/api/live", r -> hub.analytics.live());
        get("/api/dashboard", r -> hub.analytics.dashboard());

        // sites
        get("/api/sites", r -> hub.nodes.sites());
        post("/api/sites", r -> hub.nodes.saveSite(r.user, null, r.body()));
        put("/api/sites/{id}", r -> hub.nodes.saveSite(r.user, r.p("id"), r.body()));
        delete("/api/sites/{id}", r -> { hub.nodes.deleteSite(r.user, r.p("id")); return ok(); });

        // edge nodes
        get("/api/nodes", r -> hub.nodes.nodes(r.q("site"), r.q("status"), r.q("q")));
        post("/api/nodes", r -> hub.nodes.createNode(r.user, r.body()));
        get("/api/nodes/{id}", r -> hub.analytics.node(r.p("id")));
        put("/api/nodes/{id}", r -> hub.nodes.updateNode(r.user, r.p("id"), r.body()));
        delete("/api/nodes/{id}", r -> { hub.nodes.deleteNode(r.user, r.p("id")); return ok(); });
        post("/api/nodes/{id}/commands", r -> hub.nodes.queueCommand(r.user, r.p("id"), r.body()));
        post("/api/nodes/{id}/key", r -> hub.nodes.rotateKey(r.user, r.p("id")));
        get("/api/nodes/{id}/compare", r -> hub.analytics.compare(r.p("id"), r.qNum("deadband"), r.qNum("window")));
        get("/api/commands", r -> hub.nodes.commands(r.q("node"), r.qInt("limit", 50, 1, 500)));

        // sensors
        get("/api/sensors", r -> hub.nodes.sensors(r.q("node"), r.q("type"), r.q("state"), r.q("q")));
        post("/api/sensors", r -> hub.nodes.saveSensor(r.user, null, r.body()));
        get("/api/sensors/{id}", r -> hub.analytics.sensor(r.p("id")));
        put("/api/sensors/{id}", r -> hub.nodes.saveSensor(r.user, r.p("id"), r.body()));
        delete("/api/sensors/{id}", r -> { hub.nodes.deleteSensor(r.user, r.p("id")); return ok(); });

        // rules
        get("/api/rules", r -> hub.alerts.rules());
        post("/api/rules", r -> hub.alerts.saveRule(r.user, null, r.body()));
        post("/api/rules/test", r -> hub.alerts.testRule(r.body()));
        put("/api/rules/{id}", r -> hub.alerts.saveRule(r.user, r.p("id"), r.body()));
        delete("/api/rules/{id}", r -> { hub.alerts.deleteRule(r.user, r.p("id")); return ok(); });

        // alerts
        get("/api/alerts", r -> hub.alerts.list(r.q("status"), r.q("severity"), r.q("node"), r.q("sensor"), r.q("q"),
                r.qInt("limit", 200, 1, 2000)));
        get("/api/alerts/export.csv", r -> new Download("text/csv; charset=utf-8", "edge-alerts.csv",
                "﻿" + hub.alerts.csv(r.q("status"), r.q("severity"), r.q("node"))));
        get("/api/alerts/{id}", r -> hub.alerts.view(hub.alerts.alert(r.p("id"))));
        post("/api/alerts/ack-all", r -> Json.obj("acknowledged", hub.alerts.ackAll(r.user, r.q("node"))));
        post("/api/alerts/{id}/ack", r -> hub.alerts.ack(r.user, r.p("id")));
        post("/api/alerts/{id}/resolve", r -> hub.alerts.resolve(r.user, r.p("id"), r.body()));

        // simulator and fault lab
        get("/api/faults", r -> hub.settings.faults());
        post("/api/faults", r -> hub.settings.inject(r.user, r.body()));
        delete("/api/faults/{id}", r -> { hub.settings.stopFault(r.user, r.p("id")); return ok(); });
        post("/api/simulator", r -> hub.settings.setSimulator(r.user, Boolean.TRUE.equals(Json.bool(r.body(), "on"))));

        get("/api/reports", r -> hub.analytics.reports(r.qInt("days", 7, 1, 60)));
        get("/api/events", r -> hub.alerts.events(r.q("node"), r.q("kind"), r.qInt("limit", 50, 1, 500)));
        get("/api/settings", r -> hub.settings.get());
        put("/api/settings", r -> hub.settings.update(r.user, r.body()));

        // team (admin)
        get("/api/users", r -> hub.auth.list());
        post("/api/users", r -> hub.auth.create(r.user, r.body()));
        put("/api/users/{username}", r -> hub.auth.update(r.user, r.p("username"), r.body()));
    }

    // ------------------------------------------------------------------ server

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 64);
        pool = Executors.newFixedThreadPool(8);
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getRawPath();
            if (!path.startsWith("/api/")) {
                serveStatic(ex, path);
                return;
            }
            Http.Req r = new Http.Req(ex);
            Route route = null;
            boolean pathKnown = false;
            for (Route rt : routes) {
                Matcher m = rt.pattern.matcher(r.path);
                if (!m.matches()) continue;
                pathKnown = true;
                if (!rt.method.equals(r.method)) continue;
                route = rt;
                for (int i = 0; i < rt.names.size(); i++) r.params.put(rt.names.get(i), m.group(i + 1));
                break;
            }
            if (route == null) {
                Http.error(ex, pathKnown ? 405 : 404, pathKnown ? "Method not allowed" : "Not found");
                return;
            }
            if (!r.method.equals("GET") && !sameOrigin(ex)) {
                Http.error(ex, 403, "Cross-site request blocked");
                return;
            }
            String auth = r.header("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) r.token = auth.substring(7).trim();
            Object result;
            synchronized (hub) {
                if (!route.open) {
                    r.user = hub.auth.userFor(r.token);
                    if (r.user == null) {
                        Http.error(ex, 401, "Please sign in");
                        return;
                    }
                }
                result = route.handler.handle(r);
            }
            if (result instanceof Download d) {
                Http.send(ex, 200, d.contentType(), d.text().getBytes(StandardCharsets.UTF_8),
                        "Content-Disposition", "attachment; filename=\"" + d.fileName() + "\"");
            } else {
                Http.json(ex, 200, result);
            }
        } catch (ServiceException e) {
            Http.error(ex, e.status, e.getMessage());
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage() == null ? "Bad request" : e.getMessage());
        } catch (Exception e) {
            System.err.println("Server error: " + e);
            e.printStackTrace();
            try {
                Http.error(ex, 500, "Something went wrong on the server");
            } catch (IOException ignored) { /* the browser went away */ }
        }
    }

    /** Blocks forms or scripts on other websites from posting to this app (CSRF). Devices send no Origin header. */
    private static boolean sameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        String host = ex.getRequestHeaders().getFirst("Host");
        try {
            URI o = new URI(origin);
            String oh = o.getHost() + (o.getPort() > 0 ? ":" + o.getPort() : "");
            return host != null && host.equalsIgnoreCase(oh);
        } catch (Exception e) {
            return false;
        }
    }

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        String m = ex.getRequestMethod();
        if (!m.equalsIgnoreCase("GET") && !m.equalsIgnoreCase("HEAD")) {
            Http.error(ex, 405, "Method not allowed");
            return;
        }
        if (path.equals("/") || path.equals("/index.html")) {
            Http.file(ex, frontend, "index.html");
        } else if (path.equals("/favicon.ico") || path.equals("/favicon.svg")) {
            Http.file(ex, frontend, "favicon.svg");
        } else if (path.startsWith("/static/")) {
            String rel = java.net.URLDecoder.decode(path.substring("/static/".length()), StandardCharsets.UTF_8);
            if (rel.contains("..") || rel.contains("\\") || rel.startsWith("/")) {
                Http.error(ex, 404, "Not found");
                return;
            }
            Http.file(ex, frontend, rel);
        } else {
            Http.error(ex, 404, "Not found");
        }
    }
}
