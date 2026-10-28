package com.cecil.energy.web;

import com.cecil.energy.model.Enums;
import com.cecil.energy.model.User;
import com.cecil.energy.service.AnalyticsService;
import com.cecil.energy.service.ApplianceService;
import com.cecil.energy.service.AuthService;
import com.cecil.energy.service.ServiceException;
import com.cecil.energy.service.SettingsService;
import com.cecil.energy.service.TipService;
import com.cecil.energy.service.UsageService;
import com.cecil.energy.store.DataStore;
import com.cecil.energy.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The web server. It does two jobs:
 *   1. /api/...    -> JSON API used by the frontend (every call except login needs a sign-in token)
 *   2. everything else -> the website files from the frontend folder
 */
public class ApiServer {

    private final DataStore store;
    private final Path frontend;
    public final AuthService auth;
    public final ApplianceService appliances;
    public final UsageService usage;
    public final AnalyticsService analytics;
    public final TipService tips;
    public final SettingsService settings;
    private HttpServer server;
    private ExecutorService pool;

    public ApiServer(DataStore store, Path frontend) {
        this.store = store;
        this.frontend = frontend.toAbsolutePath().normalize();
        this.auth = new AuthService(store);
        this.appliances = new ApplianceService(store);
        this.usage = new UsageService(store);
        this.analytics = new AnalyticsService(store, appliances);
        this.tips = new TipService(store);
        this.settings = new SettingsService(store);
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        pool = Executors.newFixedThreadPool(8);
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                api(ex, path.substring(5));
            } else {
                staticFile(ex, path);
            }
        } catch (ServiceException e) {
            Http.error(ex, e.status, e.getMessage());
        } catch (Exception e) {
            System.err.println("Error on " + ex.getRequestMethod() + " " + ex.getRequestURI() + ": " + e);
            e.printStackTrace();
            Http.error(ex, 500, "Something went wrong on the server.");
        } finally {
            ex.close();
        }
    }

    // ================================================================== website files

    private void staticFile(HttpExchange ex, String path) throws IOException {
        String m = ex.getRequestMethod();
        if (!m.equals("GET") && !m.equals("HEAD")) throw new ServiceException(405, "Method not allowed.");
        String name;
        if (path.equals("/") || path.equals("/index.html")) name = "index.html";
        else if (path.startsWith("/static/")) name = path.substring(8);
        else if (path.equals("/favicon.ico") || path.equals("/favicon.svg")) name = "favicon.svg";
        else throw ServiceException.notFound("Page not found.");
        Path file = frontend.resolve(name).normalize();
        // never serve anything outside the frontend folder (blocks "../../" tricks)
        if (!file.startsWith(frontend) || !Files.isRegularFile(file)) throw ServiceException.notFound("Page not found.");
        Http.file(ex, file);
    }

    // ================================================================== API routes

    private void api(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod();
        String[] p = path.split("/");
        for (int i = 0; i < p.length; i++) p[i] = Http.decode(p[i]);
        boolean write = !method.equals("GET") && !method.equals("HEAD");
        if (write && !Http.sameOrigin(ex)) throw ServiceException.forbidden("Request from another website was blocked.");
        Map<String, String> q = Http.query(ex);

        // ---- no sign-in needed
        if (path.equals("auth/login") && method.equals("POST")) {
            Map<String, Object> b = Http.body(ex);
            Http.json(ex, 200, auth.login(Json.str(b, "username"), Json.str(b, "password")));
            return;
        }
        if (path.equals("health")) {
            Http.json(ex, 200, Map.of("ok", true));
            return;
        }

        String token = Http.bearer(ex);
        User me = auth.require(token);
        String a = p[0];
        String id = p.length > 1 ? p[1] : null;
        String sub = p.length > 2 ? p[2] : null;

        switch (a) {
            case "auth" -> {
                if ("me".equals(id) && method.equals("GET")) { Http.json(ex, 200, me.toPublic()); return; }
                if ("logout".equals(id) && method.equals("POST")) { auth.logout(token); Http.json(ex, 200, Map.of("ok", true)); return; }
                if ("password".equals(id) && method.equals("POST")) {
                    Map<String, Object> b = Http.body(ex);
                    auth.changePassword(me, Json.str(b, "current"), Json.str(b, "next"), token);
                    Http.json(ex, 200, Map.of("ok", true));
                    return;
                }
            }
            case "meta" -> {
                if (method.equals("GET")) {
                    Map<String, Object> m = Enums.meta();
                    m.put("rooms", appliances.rooms());
                    Http.json(ex, 200, m);
                    return;
                }
            }
            case "users" -> {
                if (id == null && method.equals("GET")) { Http.json(ex, 200, auth.listUsers()); return; }
                if (id == null && method.equals("POST")) { Http.json(ex, 201, auth.createUser(me, Http.body(ex))); return; }
                if (id != null && method.equals("PUT")) { Http.json(ex, 200, auth.updateUser(me, id, Http.body(ex))); return; }
            }
            case "rooms" -> {
                if (id == null && method.equals("GET")) { Http.json(ex, 200, appliances.rooms()); return; }
                if (id == null && method.equals("POST")) { Http.json(ex, 201, appliances.saveRoom(me, null, Http.body(ex))); return; }
                if (id != null && method.equals("PUT")) { Http.json(ex, 200, appliances.saveRoom(me, id, Http.body(ex))); return; }
                if (id != null && method.equals("DELETE")) { appliances.deleteRoom(me, id); Http.json(ex, 200, Map.of("ok", true)); return; }
            }
            case "appliances" -> {
                if (id == null && method.equals("GET")) { Http.json(ex, 200, appliances.list(q.get("room"), q.get("type"), q.get("q"))); return; }
                if (id == null && method.equals("POST")) { Http.json(ex, 201, appliances.create(me, Http.body(ex))); return; }
                if ("all-off".equals(id) && method.equals("POST")) {
                    Map<String, Object> b = Http.body(ex);
                    int n = appliances.allOff(me, Json.bool(b, "keepAlwaysOn", true));
                    Http.json(ex, 200, Map.of("switchedOff", n));
                    return;
                }
                if (id != null && sub == null && method.equals("GET")) { Http.json(ex, 200, analytics.appliance(id)); return; }
                if (id != null && sub == null && method.equals("PUT")) { Http.json(ex, 200, appliances.update(me, id, Http.body(ex))); return; }
                if (id != null && sub == null && method.equals("DELETE")) { appliances.delete(me, id); Http.json(ex, 200, Map.of("ok", true)); return; }
                if (id != null && "power".equals(sub) && method.equals("POST")) {
                    Map<String, Object> b = Http.body(ex);
                    Http.json(ex, 200, appliances.setPower(me, id, Json.bool(b, "on", false)));
                    return;
                }
            }
            case "usage" -> {
                if ("export.csv".equals(id) && method.equals("GET")) {
                    String csv = usage.csv(q.get("appliance"), q.get("room"), q.get("from"), q.get("to"), q.get("source"));
                    Http.download(ex, "energy-usage-" + store.today() + ".csv", "text/csv; charset=utf-8", csv);
                    return;
                }
                if ("autofill".equals(id) && method.equals("POST")) {
                    if (!me.role.canManage()) throw ServiceException.forbidden("Only an energy manager or admin can do this.");
                    Map<String, Object> b = Http.body(ex);
                    LocalDate to = LocalDate.parse(Json.str(b, "to", store.today().minusDays(1).toString()));
                    LocalDate from = LocalDate.parse(Json.str(b, "from", to.minusDays(6).toString()));
                    if (to.isAfter(store.today()) || from.isAfter(to) || from.isBefore(to.minusDays(62))) {
                        throw ServiceException.bad("Choose up to 62 days, not in the future.");
                    }
                    Http.json(ex, 200, Map.of("created", usage.autofill(from, to, me.username)));
                    return;
                }
                if (id == null && method.equals("GET")) {
                    int limit = (int) Math.max(1, Math.min(2000, parseNum(q.get("limit"), 400)));
                    Http.json(ex, 200, usage.list(q.get("appliance"), q.get("room"), q.get("from"), q.get("to"), q.get("source"), limit));
                    return;
                }
                if (id == null && method.equals("POST")) { Http.json(ex, 201, usage.saveManual(me, null, Http.body(ex))); return; }
                if (id != null && method.equals("PUT")) { Http.json(ex, 200, usage.saveManual(me, id, Http.body(ex))); return; }
                if (id != null && method.equals("DELETE")) { usage.delete(me, id); Http.json(ex, 200, Map.of("ok", true)); return; }
            }
            case "meter" -> {
                if (id == null && method.equals("GET")) { Http.json(ex, 200, usage.meterReadings()); return; }
                if (id == null && method.equals("POST")) { Http.json(ex, 201, usage.addMeter(me, Http.body(ex))); return; }
                if (id != null && method.equals("DELETE")) { usage.deleteMeter(me, id); Http.json(ex, 200, Map.of("ok", true)); return; }
            }
            case "tips" -> {
                if (id == null && method.equals("GET")) { Http.json(ex, 200, tips.list()); return; }
                if (id != null && method.equals("POST")) {
                    switch (sub == null ? "" : sub) {
                        case "apply" -> { Http.json(ex, 200, tips.apply(me, id)); return; }
                        case "dismiss" -> { Http.json(ex, 200, tips.dismiss(me, id)); return; }
                        case "restore" -> { tips.restore(me, id); Http.json(ex, 200, Map.of("ok", true)); return; }
                        default -> { }
                    }
                }
            }
            case "planner" -> {
                if (method.equals("POST")) { Http.json(ex, 200, analytics.plan(Http.body(ex))); return; }
            }
            case "dashboard" -> {
                if (method.equals("GET")) { Http.json(ex, 200, analytics.dashboard(me)); return; }
            }
            case "reports" -> {
                if (method.equals("GET")) { Http.json(ex, 200, analytics.reports((int) parseNum(q.get("months"), 6))); return; }
            }
            case "events" -> {
                if (method.equals("GET")) {
                    int limit = (int) Math.max(1, Math.min(300, parseNum(q.get("limit"), 60)));
                    Http.json(ex, 200, appliances.events(q.get("appliance"), limit));
                    return;
                }
            }
            case "settings" -> {
                if (id == null && method.equals("GET")) { Http.json(ex, 200, settings.get()); return; }
                if (id == null && method.equals("PUT")) { Http.json(ex, 200, settings.update(me, Http.body(ex))); return; }
                if ("bill".equals(id) && method.equals("GET")) {
                    Http.json(ex, 200, settings.billPreview(parseNum(q.get("units"), 250), parseNum(q.get("peak"), 0)));
                    return;
                }
            }
            default -> { }
        }
        throw ServiceException.notFound("Unknown API address.");
    }

    private static double parseNum(String s, double def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw ServiceException.bad("Expected a number, got: " + s);
        }
    }
}
