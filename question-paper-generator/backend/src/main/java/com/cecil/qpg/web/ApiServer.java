package com.cecil.qpg.web;

import com.cecil.qpg.model.Enums;
import com.cecil.qpg.model.User;
import com.cecil.qpg.service.ApiError;
import com.cecil.qpg.service.AuthService;
import com.cecil.qpg.service.BankService;
import com.cecil.qpg.service.BlueprintService;
import com.cecil.qpg.service.DashboardService;
import com.cecil.qpg.service.PaperService;
import com.cecil.qpg.service.UserService;
import com.cecil.qpg.store.DataStore;
import com.cecil.qpg.util.Csv;
import com.cecil.qpg.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The web server: every /api/... address is matched to a service method here,
 * and everything else is a file from the frontend folder.
 */
public class ApiServer {
    private final DataStore store;
    private final Path frontend;
    public final AuthService auth;
    public final UserService users;
    public final BankService bank;
    public final BlueprintService blueprints;
    public final PaperService papers;
    public final DashboardService dash;
    private HttpServer server;
    private ExecutorService pool;
    private final List<Route> routes = new ArrayList<>();

    /** What a route does once the user (if needed) is known. m = the matched parts of the address. */
    interface Handler { Object handle(HttpExchange ex, User me, Matcher m) throws Exception; }

    private record Route(String method, Pattern pattern, boolean open, Handler h) {}

    public ApiServer(DataStore store, Path frontend) {
        this.store = store;
        this.frontend = frontend.toAbsolutePath().normalize();
        this.auth = new AuthService(store);
        this.users = new UserService(store, auth);
        this.bank = new BankService(store);
        this.blueprints = new BlueprintService(store);
        this.papers = new PaperService(store);
        this.dash = new DashboardService(store);
        routes();
    }

    private void get(String p, Handler h) { routes.add(new Route("GET", Pattern.compile(p), false, h)); }
    private void post(String p, Handler h) { routes.add(new Route("POST", Pattern.compile(p), false, h)); }
    private void put(String p, Handler h) { routes.add(new Route("PUT", Pattern.compile(p), false, h)); }
    private void del(String p, Handler h) { routes.add(new Route("DELETE", Pattern.compile(p), false, h)); }

    private static final String ID = "([A-Za-z0-9_.-]{1,40})";

    private void routes() {
        // ---- sign in (the only routes that work without a token)
        routes.add(new Route("POST", Pattern.compile("/api/auth/login"), true, (ex, me, m) -> {
            Map<String, Object> b = Http.jsonBody(ex);
            AuthService.Session s = auth.login(Json.str(b, "username"), Json.str(b, "password"));
            return Json.map("token", s.token, "user", store.user(s.username).publicView());
        }));
        routes.add(new Route("POST", Pattern.compile("/api/auth/logout"), true, (ex, me, m) -> {
            auth.logout(Http.bearer(ex));
            return Json.map("ok", true);
        }));
        get("/api/auth/me", (ex, me, m) -> me.publicView());
        post("/api/auth/password", (ex, me, m) -> {
            Map<String, Object> b = Http.jsonBody(ex);
            auth.changePassword(me, Json.str(b, "current"), Json.str(b, "next"), Http.bearer(ex));
            return Json.map("ok", true);
        });

        // ---- lists for the screens
        get("/api/meta", (ex, me, m) -> {
            Map<String, Object> meta = Enums.meta();
            meta.put("subjects", bank.subjects());
            meta.put("users", users.list());
            meta.put("settings", dash.settings());
            return meta;
        });
        get("/api/dashboard", (ex, me, m) -> dash.dashboard(me));
        get("/api/events", (ex, me, m) -> {
            Map<String, String> q = Http.query(ex);
            int limit = 50;
            try { if (q.get("limit") != null) limit = Math.max(1, Math.min(500, Integer.parseInt(q.get("limit")))); }
            catch (NumberFormatException e) { throw ApiError.bad("limit must be a number"); }
            return dash.events(q.get("ref"), limit);
        });
        get("/api/settings", (ex, me, m) -> dash.settings());
        put("/api/settings", (ex, me, m) -> dash.saveSettings(me, Http.jsonBody(ex)));

        // ---- team
        get("/api/users", (ex, me, m) -> users.list());
        post("/api/users", (ex, me, m) -> users.create(me, Http.jsonBody(ex)));
        put("/api/users/" + ID, (ex, me, m) -> users.update(me, m.group(1), Http.jsonBody(ex)));

        // ---- subjects and coverage
        get("/api/subjects", (ex, me, m) -> bank.subjects());
        post("/api/subjects", (ex, me, m) -> bank.saveSubject(me, null, Http.jsonBody(ex)));
        put("/api/subjects/" + ID, (ex, me, m) -> bank.saveSubject(me, m.group(1), Http.jsonBody(ex)));
        del("/api/subjects/" + ID, (ex, me, m) -> { bank.deleteSubject(me, m.group(1)); return Json.map("ok", true); });
        get("/api/coverage", (ex, me, m) -> bank.coverage(Http.query(ex).get("subject")));

        // ---- question bank (fixed addresses first, so "similar" is not read as an id)
        get("/api/questions/export\\.csv", (ex, me, m) -> {
            Http.download(ex, "text/csv; charset=utf-8", "question-bank.csv", bank.exportCsv(Http.query(ex)));
            return null;
        });
        get("/api/questions/template\\.csv", (ex, me, m) -> {
            String t = "﻿" + Csv.row((Object[]) BankService.CSV_HEAD)
                    + Csv.row("CSIT135", 2, "MCQ", "EASY", "REMEMBER", 1, "A program in execution is called a", "Thread | Process | File | Job", "Process", "process")
                    + Csv.row("CSIT135", 4, "SHORT", "MEDIUM", "UNDERSTAND", 5, "Explain paging with a diagram.", "", "Pages, frames, page table, address translation", "paging, memory")
                    + Csv.row("CSIT135", 3, "TRUE_FALSE", "EASY", "REMEMBER", 1, "A binary semaphore can only be 0 or 1.", "", "True", "");
            Http.download(ex, "text/csv; charset=utf-8", "question-template.csv", t);
            return null;
        });
        post("/api/questions/import", (ex, me, m) -> {
            Map<String, Object> b = Http.jsonBody(ex);
            return bank.importCsv(me, Json.str(b, "csv"), Json.bool(b, "dryRun", true));
        });
        post("/api/questions/similar", (ex, me, m) -> {
            Map<String, Object> b = Http.jsonBody(ex);
            String text = Json.str(b, "text", "");
            if (text.length() > 2000) throw ApiError.bad("Text is too long");
            return bank.similar(Json.str(b, "subjectCode"), text, Json.str(b, "exceptId", ""));
        });
        get("/api/questions", (ex, me, m) -> bank.list(Http.query(ex)));
        post("/api/questions", (ex, me, m) -> bank.create(me, Http.jsonBody(ex)));
        get("/api/questions/" + ID, (ex, me, m) -> bank.get(m.group(1)));
        put("/api/questions/" + ID, (ex, me, m) -> bank.update(me, m.group(1), Http.jsonBody(ex)));
        del("/api/questions/" + ID, (ex, me, m) -> { bank.delete(me, m.group(1)); return Json.map("ok", true); });
        post("/api/questions/" + ID + "/archive", (ex, me, m) -> bank.setArchived(me, m.group(1), true));
        post("/api/questions/" + ID + "/restore", (ex, me, m) -> bank.setArchived(me, m.group(1), false));

        // ---- blueprints
        post("/api/blueprints/check", (ex, me, m) -> blueprints.checkDraft(Http.jsonBody(ex)));
        get("/api/blueprints", (ex, me, m) -> blueprints.list(Http.query(ex).get("subject")));
        post("/api/blueprints", (ex, me, m) -> blueprints.create(me, Http.jsonBody(ex)));
        get("/api/blueprints/" + ID, (ex, me, m) -> blueprints.get(m.group(1)));
        put("/api/blueprints/" + ID, (ex, me, m) -> blueprints.update(me, m.group(1), Http.jsonBody(ex)));
        del("/api/blueprints/" + ID, (ex, me, m) -> { blueprints.delete(me, m.group(1)); return Json.map("ok", true); });
        post("/api/blueprints/" + ID + "/copy", (ex, me, m) -> blueprints.copy(me, m.group(1)));

        // ---- papers
        post("/api/papers/generate", (ex, me, m) -> papers.generate(me, Http.jsonBody(ex)));
        get("/api/papers", (ex, me, m) -> papers.list(Http.query(ex)));
        get("/api/papers/" + ID, (ex, me, m) -> papers.get(m.group(1)));
        put("/api/papers/" + ID, (ex, me, m) -> papers.updateHeader(me, m.group(1), Http.jsonBody(ex)));
        del("/api/papers/" + ID, (ex, me, m) -> { papers.delete(me, m.group(1)); return Json.map("ok", true); });
        get("/api/papers/" + ID + "/candidates", (ex, me, m) -> {
            Map<String, String> q = Http.query(ex);
            try { return papers.candidates(m.group(1), Integer.parseInt(q.get("section")), Integer.parseInt(q.get("item"))); }
            catch (NumberFormatException e) { throw ApiError.bad("section and item must be numbers"); }
        });
        get("/api/papers/" + ID + "/export\\.txt", (ex, me, m) -> {
            boolean key = "1".equals(Http.query(ex).get("key"));
            Http.download(ex, "text/plain; charset=utf-8", m.group(1) + (key ? "-answer-key" : "") + ".txt", papers.asText(m.group(1), key));
            return null;
        });
        post("/api/papers/" + ID + "/swap", (ex, me, m) -> papers.swap(me, m.group(1), Http.jsonBody(ex)));
        post("/api/papers/" + ID + "/move", (ex, me, m) -> papers.move(me, m.group(1), Http.jsonBody(ex)));
        post("/api/papers/" + ID + "/regenerate", (ex, me, m) -> papers.regenerate(me, m.group(1)));
        post("/api/papers/" + ID + "/submit", (ex, me, m) -> papers.submit(me, m.group(1)));
        post("/api/papers/" + ID + "/approve", (ex, me, m) -> papers.approve(me, m.group(1)));
        post("/api/papers/" + ID + "/send-back", (ex, me, m) -> papers.sendBack(me, m.group(1), Http.jsonBody(ex)));
        post("/api/papers/" + ID + "/release", (ex, me, m) -> papers.release(me, m.group(1)));
    }

    // ------------------------------------------------------------------ running

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 64);
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

    void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.startsWith("/api/")) {
                if (!method.equals("GET") && !method.equals("HEAD")) Http.checkOrigin(ex);
                boolean pathFound = false;
                for (Route r : routes) {
                    Matcher m = r.pattern.matcher(path);
                    if (!m.matches()) continue;
                    pathFound = true;
                    if (!r.method.equals(method)) continue;
                    User me = r.open ? null : auth.require(Http.bearer(ex));
                    Object out = r.h.handle(ex, me, m);
                    if (out != null) Http.json(ex, 200, out);
                    return;
                }
                if (pathFound) Http.error(ex, 405, "Method not allowed");
                else Http.error(ex, 404, "Unknown address");
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) { Http.error(ex, 405, "Method not allowed"); return; }
            serveFile(ex, path);
        } catch (ApiError e) {
            Http.error(ex, e.status, e.getMessage());
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
        } catch (Exception e) {
            System.err.println("Server error: " + e);
            e.printStackTrace();
            try { Http.error(ex, 500, "Something went wrong on the server"); } catch (IOException ignored) { /* client gone */ }
        }
    }

    /** Sends a file from the frontend folder. "/" and unknown page addresses get index.html. */
    private void serveFile(HttpExchange ex, String path) throws IOException {
        String rel;
        if (path.equals("/") || path.equals("/index.html")) rel = "index.html";
        else if (path.startsWith("/static/")) rel = path.substring("/static/".length());
        else if (path.equals("/favicon.ico") || path.equals("/favicon.svg")) rel = "favicon.svg";
        else { Http.error(ex, 404, "Not found"); return; }
        Path f = frontend.resolve(rel).normalize();
        if (!f.startsWith(frontend) || !Files.isRegularFile(f) || rel.contains("..")) { Http.error(ex, 404, "Not found"); return; }
        String name = f.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : name.endsWith(".png") ? "image/png"
                : "application/octet-stream";
        Http.send(ex, 200, type, Files.readAllBytes(f), true);
    }
}
