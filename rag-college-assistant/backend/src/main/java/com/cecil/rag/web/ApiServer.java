package com.cecil.rag.web;

import com.cecil.rag.model.Doc;
import com.cecil.rag.model.QaRecord;
import com.cecil.rag.model.Settings;
import com.cecil.rag.model.User;
import com.cecil.rag.service.ApiError;
import com.cecil.rag.service.AskService;
import com.cecil.rag.service.AuthService;
import com.cecil.rag.service.EvalService;
import com.cecil.rag.service.InsightService;
import com.cecil.rag.service.KnowledgeService;
import com.cecil.rag.service.UserService;
import com.cecil.rag.store.DataStore;
import com.cecil.rag.util.Clock;
import com.cecil.rag.util.Json;
import com.cecil.rag.util.Text;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The web server: REST API under /api/ and the website files from the frontend folder.
 * Every API call checks who is signed in and whether their role allows it.
 */
public final class ApiServer {
    private final DataStore store;
    private final AuthService auth;
    private final UserService users;
    private final KnowledgeService knowledge;
    private final AskService ask;
    private final InsightService insights;
    private final EvalService eval;
    private final Path frontend;
    private HttpServer server;

    public ApiServer(DataStore store, AuthService auth, UserService users, KnowledgeService knowledge, AskService ask,
                     InsightService insights, EvalService eval, Path frontend) {
        this.store = store;
        this.auth = auth;
        this.users = users;
        this.knowledge = knowledge;
        this.ask = ask;
        this.insights = insights;
        this.eval = eval;
        this.frontend = frontend;
    }

    public int start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 64);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", ex -> {
            Http h = new Http(ex);
            try {
                if (h.path.startsWith("/api/")) api(h);
                else staticFile(h);
            } catch (ApiError e) {
                h.send(e.status, Json.map("error", e.getMessage()));
            } catch (Exception e) {
                System.err.println("Error on " + h.method + " " + h.path + ": " + e);
                h.send(500, Json.map("error", "Something went wrong on the server."));
            } finally {
                ex.close();
            }
        });
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() { if (server != null) server.stop(0); }

    // ================================================================= static files

    private void staticFile(Http h) throws IOException {
        if (!h.method.equals("GET") && !h.method.equals("HEAD")) throw new ApiError(405, "Method not allowed.");
        String p = h.path;
        if (p.equals("/") || p.equals("/index.html")) p = "/static/index.html";
        if (!p.startsWith("/static/")) { h.sendBytes(404, "text/plain", "Not found".getBytes(), false); return; }
        String name = Http.decode(p.substring("/static/".length()));
        if (!name.matches("[A-Za-z0-9._-]+") || name.startsWith(".")) { h.sendBytes(404, "text/plain", "Not found".getBytes(), false); return; }
        Path f = frontend.resolve(name).normalize();
        if (!f.startsWith(frontend) || !Files.isRegularFile(f)) { h.sendBytes(404, "text/plain", "Not found".getBytes(), false); return; }
        h.sendBytes(200, Http.contentType(name), Files.readAllBytes(f), !name.endsWith(".html"));
    }

    // ================================================================= API

    private void api(Http h) throws IOException {
        h.checkSameOrigin();
        String p = h.path.substring(4); // after "/api"
        String m = h.method;

        if (p.equals("/login") && m.equals("POST")) {
            Map<String, Object> b = h.json();
            AuthService.Session s = auth.login(Json.str(b, "username", ""), Json.str(b, "password", ""));
            h.setCookie(s.token, 12 * 3600);
            h.send(200, me(store.users.get(s.userId)));
            return;
        }
        if (p.equals("/health")) { h.send(200, Json.map("ok", true)); return; }

        User u = auth.user(h.cookie(Http.COOKIE));
        if (u == null) throw new ApiError(401, "Please sign in.");

        String[] seg = p.substring(1).split("/");
        String a = seg[0];
        String id = seg.length > 1 ? Http.decode(seg[1]) : null;
        String sub = seg.length > 2 ? seg[2] : null;

        switch (a) {
            case "logout" -> { auth.logout(h.cookie(Http.COOKIE)); h.setCookie("", 0); h.send(200, Json.map("ok", true)); }
            case "me" -> {
                if (id == null && m.equals("GET")) h.send(200, me(u));
                else if ("password".equals(id) && m.equals("POST")) {
                    Map<String, Object> b = h.json();
                    auth.changePassword(u, Json.str(b, "current", ""), Json.str(b, "password", ""));
                    h.send(200, Json.map("ok", true));
                } else notFound();
            }
            case "ask" -> { post(m); h.send(200, ask.ask(u, h.json())); }
            case "front" -> h.send(200, front(u));
            case "sessions" -> {
                if (id == null) h.send(200, ask.sessions(u));
                else h.send(200, ask.session(u, id));
            }
            case "history" -> h.send(200, ask.history(u, h.q("q")));
            case "questions" -> {
                if (id != null && "feedback".equals(sub) && m.equals("POST")) h.send(200, ask.feedback(u, id, h.json()));
                else notFound();
            }
            case "trace" -> h.send(200, ask.trace(u, h.q("q"), h.q("mode")));
            case "docs" -> docs(h, u, id, m);
            case "index" -> {
                if (id == null && m.equals("GET")) h.send(200, knowledge.stats());
                else if ("rebuild".equals(id) && m.equals("POST")) { admin(u); knowledge.reindex(); h.send(200, knowledge.stats()); }
                else notFound();
            }
            case "verified" -> {
                if (id == null && m.equals("GET")) h.send(200, ask.verifiedList());
                else if (id == null && m.equals("POST")) h.send(200, ask.saveVerified(u, null, h.json()));
                else if (id != null && m.equals("PUT")) h.send(200, ask.saveVerified(u, id, h.json()));
                else if (id != null && m.equals("DELETE")) { ask.deleteVerified(u, id); h.send(200, Json.map("ok", true)); }
                else notFound();
            }
            case "gaps" -> {
                if (id == null && m.equals("GET")) h.send(200, ask.gaps(u, "1".equals(h.q("resolved"))));
                else if ("resolve".equals(id) && m.equals("POST")) h.send(200, Json.map("resolved", ask.resolve(u, h.json())));
                else notFound();
            }
            case "insights" -> {
                if (!u.role.canCurate()) throw ApiError.forbidden();
                int days = 30;
                try { days = Integer.parseInt(h.q("days")); } catch (NumberFormatException ignored) {}
                h.send(200, insights.dashboard(days));
            }
            case "users" -> {
                admin(u);
                if (id == null && m.equals("GET")) h.send(200, users.list());
                else if (id == null && m.equals("POST")) h.send(200, users.create(h.json()).publicJson());
                else if (id != null && m.equals("PUT")) h.send(200, users.update(u, id, h.json()).publicJson());
                else notFound();
            }
            case "settings" -> {
                admin(u);
                if (m.equals("GET")) h.send(200, settingsView());
                else if (m.equals("PUT")) h.send(200, saveSettings(h.json()));
                else notFound();
            }
            case "eval" -> {
                admin(u);
                if (id == null && m.equals("GET")) h.send(200, eval.view());
                else if ("run".equals(id) && m.equals("POST")) h.send(200, eval.run());
                else if ("cases".equals(id) && sub == null && m.equals("POST")) h.send(200, eval.addCase(u, h.json()));
                else if ("cases".equals(id) && sub != null && m.equals("DELETE")) { eval.deleteCase(u, Http.decode(sub)); h.send(200, Json.map("ok", true)); }
                else notFound();
            }
            default -> notFound();
        }
    }

    private void docs(Http h, User u, String id, String m) throws IOException {
        if (id == null && m.equals("GET")) h.send(200, knowledge.list(u));
        else if (id == null && m.equals("POST")) h.send(200, knowledge.create(u, h.json()).summary());
        else if (id != null && m.equals("GET")) h.send(200, knowledge.get(u, id));
        else if (id != null && m.equals("PUT")) h.send(200, knowledge.update(u, id, h.json()).summary());
        else if (id != null && m.equals("DELETE")) { knowledge.delete(u, id); h.send(200, Json.map("ok", true)); }
        else notFound();
    }

    private Map<String, Object> me(User u) {
        Settings s = store.settings;
        return Json.map("user", u.publicJson(),
                "can", Json.map("curate", u.role.canCurate(), "admin", u.role.isAdmin()),
                "college", s.collegeName,
                "categories", List.of(Doc.CATEGORIES),
                "claude", Json.map("on", s.useClaude, "available", ask.claudeAvailable(), "model", s.claudeModel));
    }

    /** The front page: trending questions, latest notices, and what the assistant knows. */
    private Map<String, Object> front(User u) {
        long since = Clock.now() - 14L * 86_400_000;
        Map<String, long[]> counts = new HashMap<>();
        Map<String, String> label = new HashMap<>();
        List<Map<String, Object>> latest = new ArrayList<>();
        synchronized (store) {
            for (QaRecord r : store.records) {
                if (r.at < since || !"answered".equals(r.status) || !r.standalone.isBlank()) continue;
                String key = String.join(" ", Text.tokenSet(r.question));
                counts.computeIfAbsent(key, k -> new long[1])[0]++;
                label.putIfAbsent(key, r.question);
            }
            List<Doc> docs = new ArrayList<>(store.docs.values());
            docs.sort((x, y) -> Long.compare(y.updatedAt, x.updatedAt));
            for (Doc d : docs) {
                if (!KnowledgeService.visible(u, d) || !d.searchable()) continue;
                latest.add(Json.map("id", d.id, "title", d.title, "category", d.category, "updatedAt", d.updatedAt,
                        "preview", d.preview(150)));
                if (latest.size() == 5) break;
            }
        }
        List<Map<String, Object>> trending = new ArrayList<>();
        counts.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(8)
                .forEach(e -> trending.add(Json.map("question", label.get(e.getKey()), "count", e.getValue()[0])));
        Map<String, Object> stats = knowledge.stats();
        return Json.map("trending", trending, "latest", latest, "docs", stats.get("published"),
                "chunks", stats.get("chunks"), "today", Clock.today().toString());
    }

    private Map<String, Object> settingsView() {
        Map<String, Object> m = store.settings.toJson();
        m.put("claudeAvailable", ask.claudeAvailable());
        return m;
    }

    private Map<String, Object> saveSettings(Map<String, Object> body) {
        boolean rebuild;
        synchronized (store) {
            Settings s = store.settings;
            int cw = s.chunkWords, ow = s.overlapWords, vd = s.vectorDims;
            s.apply(body);
            rebuild = cw != s.chunkWords || ow != s.overlapWords || vd != s.vectorDims;
            store.saveSettings();
        }
        if (rebuild) knowledge.reindex();
        Map<String, Object> m = settingsView();
        m.put("rebuilt", rebuild);
        return m;
    }

    private static void post(String m) { if (!m.equals("POST")) throw new ApiError(405, "Method not allowed."); }
    private static void admin(User u) { if (!u.role.isAdmin()) throw ApiError.forbidden(); }
    private static void notFound() { throw new ApiError(404, "Not found."); }
}
