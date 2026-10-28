package com.cecil.chatbot.web;

import com.cecil.chatbot.ai.ModelRegistry;
import com.cecil.chatbot.model.Enums;
import com.cecil.chatbot.model.User;
import com.cecil.chatbot.safety.RateLimiter;
import com.cecil.chatbot.service.AnalyticsService;
import com.cecil.chatbot.service.AuthService;
import com.cecil.chatbot.service.ChatService;
import com.cecil.chatbot.service.KnowledgeService;
import com.cecil.chatbot.service.PersonaService;
import com.cecil.chatbot.service.ServiceException;
import com.cecil.chatbot.service.SettingsService;
import com.cecil.chatbot.store.DataStore;
import com.cecil.chatbot.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The web server (built into the JDK: com.sun.net.httpserver). It does two jobs:
 *   1. /api/...       -> JSON API used by the frontend (every call except login needs a sign-in token)
 *      /api/chats/{id}/send streams the AI answer as Server-Sent Events
 *   2. everything else -> the website files from the frontend folder
 */
public class ApiServer {

    private final DataStore store;
    private final Path frontend;
    public final ModelRegistry models;
    public final AuthService auth;
    public final PersonaService personas;
    public final KnowledgeService knowledge;
    public final ChatService chats;
    public final SettingsService settings;
    public final AnalyticsService analytics;
    private HttpServer server;
    private ExecutorService pool;

    public ApiServer(DataStore store, Path frontend) {
        this.store = store;
        this.frontend = frontend.toAbsolutePath().normalize();
        this.models = new ModelRegistry(store.clock());
        this.auth = new AuthService(store);
        this.personas = new PersonaService(store);
        this.knowledge = new KnowledgeService(store);
        this.chats = new ChatService(store, models, personas, knowledge, new RateLimiter(System::currentTimeMillis));
        this.settings = new SettingsService(store, models);
        this.analytics = new AnalyticsService(store);
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        // answers can take a while, so allow several at once
        pool = Executors.newFixedThreadPool(16);
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
            try {
                Http.error(ex, 500, "Something went wrong on the server.");
            } catch (IOException | RuntimeException ignored) {
                // the response was already started (streaming)
            }
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
                if (method.equals("GET")) { Http.json(ex, 200, meta(me)); return; }
            }
            case "users" -> {
                if (id == null && method.equals("GET")) { Http.json(ex, 200, auth.listUsers()); return; }
                if (id == null && method.equals("POST")) { Http.json(ex, 201, auth.createUser(me, Http.body(ex))); return; }
                if (id != null && method.equals("PUT")) { Http.json(ex, 200, auth.updateUser(me, id, Http.body(ex))); return; }
            }
            case "chats" -> {
                if (id == null && method.equals("GET")) { Http.json(ex, 200, chats.list(me, q.get("q"))); return; }
                if (id == null && method.equals("POST")) { Http.json(ex, 201, chats.create(me, Http.body(ex))); return; }
                if (id == null && method.equals("DELETE")) { Http.json(ex, 200, Map.of("deleted", chats.deleteAll(me))); return; }
                if (id != null && sub == null && method.equals("GET")) { Http.json(ex, 200, chats.get(me, id)); return; }
                if (id != null && sub == null && method.equals("PUT")) { Http.json(ex, 200, chats.update(me, id, Http.body(ex))); return; }
                if (id != null && sub == null && method.equals("DELETE")) { chats.delete(me, id); Http.json(ex, 200, Map.of("ok", true)); return; }
                if ("send".equals(sub) && method.equals("POST")) { send(ex, me, id); return; }
                if ("stop".equals(sub) && method.equals("POST")) { Http.json(ex, 200, Map.of("stopped", chats.stop(me, id))); return; }
                if ("export.md".equals(sub) && method.equals("GET")) {
                    Http.download(ex, "chat-" + id + ".md", "text/markdown; charset=utf-8", chats.exportMarkdown(me, id));
                    return;
                }
                if ("export.json".equals(sub) && method.equals("GET")) {
                    Http.download(ex, "chat-" + id + ".json", "application/json; charset=utf-8", chats.exportJson(me, id));
                    return;
                }
                if ("messages".equals(sub) && p.length == 5 && "feedback".equals(p[4]) && method.equals("POST")) {
                    Http.json(ex, 200, chats.feedback(me, id, p[3], Http.body(ex)));
                    return;
                }
            }
            case "personas" -> {
                if (id == null && method.equals("GET")) { Http.json(ex, 200, personas.list(me)); return; }
                if (id == null && method.equals("POST")) { Http.json(ex, 201, personas.save(me, null, Http.body(ex))); return; }
                if (id != null && method.equals("PUT")) { Http.json(ex, 200, personas.save(me, id, Http.body(ex))); return; }
                if (id != null && method.equals("DELETE")) { personas.delete(me, id); Http.json(ex, 200, Map.of("ok", true)); return; }
            }
            case "knowledge" -> {
                if ("search".equals(id) && method.equals("GET")) {
                    Http.json(ex, 200, knowledge.search(q.get("q"), (int) parseNum(q.get("k"), 4)));
                    return;
                }
                if (id == null && method.equals("GET")) { Http.json(ex, 200, knowledge.list()); return; }
                if (id == null && method.equals("POST")) { Http.json(ex, 201, knowledge.save(me, null, Http.body(ex))); return; }
                if (id != null && method.equals("GET")) { Http.json(ex, 200, knowledge.get(id)); return; }
                if (id != null && method.equals("PUT")) { Http.json(ex, 200, knowledge.save(me, id, Http.body(ex))); return; }
                if (id != null && method.equals("DELETE")) { knowledge.delete(me, id); Http.json(ex, 200, Map.of("ok", true)); return; }
            }
            case "providers" -> {
                if (id == null && method.equals("GET")) { Http.json(ex, 200, settings.providers(me)); return; }
                if (id == null && method.equals("POST")) { Http.json(ex, 201, settings.saveProvider(me, null, Http.body(ex))); return; }
                if (id != null && sub == null && method.equals("PUT")) { Http.json(ex, 200, settings.saveProvider(me, id, Http.body(ex))); return; }
                if (id != null && sub == null && method.equals("DELETE")) { settings.deleteProvider(me, id); Http.json(ex, 200, Map.of("ok", true)); return; }
                if ("test".equals(sub) && method.equals("POST")) { Http.json(ex, 200, settings.test(me, id)); return; }
            }
            case "settings" -> {
                if (method.equals("GET")) { Http.json(ex, 200, settings.get(me)); return; }
                if (method.equals("PUT")) { Http.json(ex, 200, settings.update(me, Http.body(ex))); return; }
            }
            case "dashboard" -> {
                if (method.equals("GET")) { Http.json(ex, 200, analytics.dashboard(me)); return; }
            }
            default -> { }
        }
        throw ServiceException.notFound("Unknown API address.");
    }

    /** Lists and facts the browser needs after sign-in. */
    private Map<String, Object> meta(User me) {
        Map<String, Object> m = Enums.meta();
        m.put("providers", settings.providers(me));
        m.put("personas", personas.list(me));
        synchronized (store.lock) {
            m.put("botName", store.settings.botName);
            m.put("defaultProviderId", store.settings.defaultProviderId);
            m.put("maxMessage", ChatService.MAX_MESSAGE);
        }
        return m;
    }

    /**
     * POST /api/chats/{id}/send  {text, mode}
     * Answers with an event stream:
     *   event: start  {userMessage, assistantId, providerName, model, sources, notes ...}
     *   event: delta  {t: "next piece of text"}      (many times)
     *   event: done   {message}                      (the saved answer)
     *   event: error  {error}
     * Problems found before the answer starts (limits, blocked words) come back as a normal JSON error.
     */
    private void send(HttpExchange ex, User me, String chatId) throws IOException {
        Map<String, Object> b = Http.body(ex);
        OutputStream[] os = {null};
        ChatService.Sink sink = new ChatService.Sink() {
            @Override
            public void start(Map<String, Object> info) throws IOException {
                os[0] = Http.startStream(ex);
                Http.event(os[0], "start", info);
            }

            @Override
            public void delta(String text) throws IOException {
                Http.event(os[0], "delta", Map.of("t", text));
            }
        };
        try {
            Map<String, Object> msg = chats.generate(me, chatId, Json.str(b, "mode", "new"), Json.str(b, "text", ""), sink);
            if (os[0] != null) {
                try {
                    Map<String, Object> done = new LinkedHashMap<>();
                    done.put("message", msg);
                    done.put("chatTitle", msg.remove("chatTitle"));
                    Http.event(os[0], "done", done);
                } catch (IOException ignored) {
                    // the browser has gone; the answer is saved anyway
                }
            }
        } catch (ServiceException e) {
            if (os[0] == null) throw e;
            try { Http.event(os[0], "error", Map.of("error", e.getMessage())); } catch (IOException ignored) { }
        } catch (RuntimeException e) {
            if (os[0] == null) throw e;
            System.err.println("Error while answering: " + e);
            try { Http.event(os[0], "error", Map.of("error", "Something went wrong on the server.")); } catch (IOException ignored) { }
        }
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
