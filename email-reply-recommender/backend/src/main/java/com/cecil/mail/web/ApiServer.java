package com.cecil.mail.web;

import com.cecil.mail.model.Role;
import com.cecil.mail.model.User;
import com.cecil.mail.service.AuthService;
import com.cecil.mail.service.MailService;
import com.cecil.mail.store.DataStore;
import com.cecil.mail.util.Clock;
import com.cecil.mail.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The web server. Serves the website from the frontend folder and the REST API under /api.
 *
 *   POST   /api/login                     sign in (sets the session cookie)
 *   POST   /api/logout                    sign out
 *   GET    /api/me                        who am I + app info
 *   PUT    /api/me                        change my name / title (used in signatures)
 *   POST   /api/me/password               change my password
 *   GET    /api/intents                   the 11 email types
 *   GET    /api/emails                    my inbox
 *   POST   /api/emails                    add (paste) an email
 *   DELETE /api/emails/{id}               remove an email
 *   GET    /api/emails/{id}/analysis      what the AI found in this email
 *   POST   /api/emails/{id}/suggest       three suggested replies  {intent?, stance?}
 *   POST   /api/emails/{id}/polish        optional: Claude polishes a draft  {draft, tone}
 *   POST   /api/tone-check                professional score for a draft  {draft}
 *   GET    /api/replies                   my saved replies
 *   POST   /api/replies                   save a reply
 *   PATCH  /api/replies/{id}              change the rating  {rating}
 *   DELETE /api/replies/{id}              delete a saved reply
 *   GET    /api/insights?scope=office     numbers for the Insights page (office = admin only)
 *   GET    /api/admin/test-bench          ADMIN: measure the classifier on the test emails
 *   GET    /api/admin/settings            ADMIN
 *   PUT    /api/admin/settings            ADMIN
 *   GET    /api/admin/people              ADMIN
 *   POST   /api/admin/people/{u}/unlock   ADMIN
 */
public class ApiServer {

    private final AuthService auth;
    private final MailService mail;
    private final DataStore store;
    private final Path frontend;
    private HttpServer server;
    private java.util.concurrent.ExecutorService pool;

    /** requests per minute: key -> times of recent requests */
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public ApiServer(AuthService auth, MailService mail, DataStore store, Path frontend) {
        this.auth = auth;
        this.mail = mail;
        this.store = store;
        this.frontend = frontend.toAbsolutePath().normalize();
    }

    /** Starts on 127.0.0.1 only, so other computers on the network cannot reach it. Port 0 = any free port. */
    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        pool = Executors.newFixedThreadPool(8);
        server.setExecutor(pool);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) api(ex, ex.getRequestMethod(), path.substring(4));
            else staticFile(ex, path);
        } catch (Http.Stop s) {
            Http.error(ex, s.status, s.getMessage());
        } catch (AuthService.AuthException e) {
            Http.error(ex, 400, e.getMessage());
        } catch (NoSuchElementException e) {
            Http.error(ex, 404, e.getMessage());
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage() == null ? "Invalid value." : clean(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            Http.error(ex, 500, "Something went wrong on the server.");
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------ API

    private void api(HttpExchange ex, String method, String p) throws IOException {
        boolean changes = !method.equals("GET") && !method.equals("HEAD");
        if (changes) Http.checkSameOrigin(ex);

        // ---- public
        if (p.equals("/login") && method.equals("POST")) {
            limit("login:" + ex.getRemoteAddress().getAddress().getHostAddress(), 10, "Too many sign-in attempts. Wait a minute.");
            Map<String, Object> b = Http.readJson(ex);
            String token = auth.login(Json.str(b, "username"), Json.str(b, "password"));
            Http.setSessionCookie(ex, token);
            Http.json(ex, 200, me(auth.userFor(token).orElseThrow()));
            return;
        }
        if (p.equals("/logout") && method.equals("POST")) {
            auth.logout(Http.cookie(ex, Http.COOKIE));
            Http.clearSessionCookie(ex);
            Http.noContent(ex);
            return;
        }
        if (p.equals("/info") && method.equals("GET")) {
            Http.json(ex, 200, Map.of("organisation", store.settings().organisation));
            return;
        }

        // ---- everything below needs a signed-in user
        User u = auth.userFor(Http.cookie(ex, Http.COOKIE)).orElseThrow(() -> new Http.Stop(401, "Please sign in."));
        Matcher m;

        if (p.equals("/me") && method.equals("GET")) { Http.json(ex, 200, me(u)); return; }
        if (p.equals("/me") && method.equals("PUT")) {
            Map<String, Object> b = Http.readJson(ex);
            String name = Json.str(b, "displayName");
            String title = Json.str(b, "title");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Your name cannot be empty.");
            u.displayName = name.trim().substring(0, Math.min(80, name.trim().length()));
            u.title = title == null ? "" : title.trim().substring(0, Math.min(80, title.trim().length()));
            store.saveUser(u);
            Http.json(ex, 200, me(u));
            return;
        }
        if (p.equals("/me/password") && method.equals("POST")) {
            Map<String, Object> b = Http.readJson(ex);
            auth.changePassword(u, Json.str(b, "oldPassword"), Json.str(b, "newPassword"));
            Http.clearSessionCookie(ex);
            Http.json(ex, 200, Map.of("message", "Password changed. Please sign in again."));
            return;
        }
        if (p.equals("/intents") && method.equals("GET")) { Http.json(ex, 200, mail.intents()); return; }

        // inbox
        if (p.equals("/emails") && method.equals("GET")) { Http.json(ex, 200, mail.inbox(u)); return; }
        if (p.equals("/emails") && method.equals("POST")) {
            limit("add:" + u.username, 30, "Too many emails added. Wait a minute.");
            Map<String, Object> b = Http.readJson(ex);
            Http.json(ex, 201, mail.addEmail(u, Json.str(b, "senderName"), Json.str(b, "senderEmail"),
                    Json.str(b, "subject"), Json.str(b, "body")));
            return;
        }
        if ((m = match("/emails/(\\d+)", p)) != null && method.equals("DELETE")) {
            mail.deleteEmail(u, id(m));
            Http.noContent(ex);
            return;
        }
        if ((m = match("/emails/(\\d+)/analysis", p)) != null && method.equals("GET")) {
            Http.json(ex, 200, mail.analyze(u, id(m)));
            return;
        }
        if ((m = match("/emails/(\\d+)/suggest", p)) != null && method.equals("POST")) {
            limit("ai:" + u.username, 60, "Too many requests. Wait a minute.");
            Map<String, Object> b = Http.readJson(ex);
            Http.json(ex, 200, mail.suggest(u, id(m), Json.str(b, "intent"), Json.str(b, "stance")));
            return;
        }
        if ((m = match("/emails/(\\d+)/polish", p)) != null && method.equals("POST")) {
            limit("claude:" + u.username, 10, "Claude can be used 10 times a minute. Wait a moment.");
            Map<String, Object> b = Http.readJson(ex);
            Http.json(ex, 200, mail.polish(u, id(m), Json.str(b, "draft"), Json.str(b, "tone")));
            return;
        }
        if (p.equals("/tone-check") && method.equals("POST")) {
            limit("tone:" + u.username, 120, "Too many requests. Wait a minute.");
            Http.json(ex, 200, mail.checkTone(Json.str(Http.readJson(ex), "draft")));
            return;
        }

        // saved replies
        if (p.equals("/replies") && method.equals("GET")) { Http.json(ex, 200, mail.replies(u)); return; }
        if (p.equals("/replies") && method.equals("POST")) {
            Map<String, Object> b = Http.readJson(ex);
            Http.json(ex, 201, mail.saveReply(u, Json.num(b, "emailId", -1), Json.str(b, "subject"), Json.str(b, "intent"),
                    Json.str(b, "tone"), Json.str(b, "stance"), Json.str(b, "body"), (int) Json.num(b, "rating", 0),
                    Json.str(b, "source")));
            return;
        }
        if ((m = match("/replies/(\\d+)", p)) != null && method.equals("PATCH")) {
            Http.json(ex, 200, mail.rate(u, id(m), (int) Json.num(Http.readJson(ex), "rating", 0)));
            return;
        }
        if ((m = match("/replies/(\\d+)", p)) != null && method.equals("DELETE")) {
            mail.deleteReply(u, id(m));
            Http.noContent(ex);
            return;
        }

        if (p.equals("/insights") && method.equals("GET")) {
            Http.json(ex, 200, mail.insights(u, "office".equals(Http.query(ex).get("scope"))));
            return;
        }

        // ---- admin only
        if (p.startsWith("/admin/")) {
            if (u.role != Role.ADMIN) throw new Http.Stop(403, "Only an admin can open this page.");
            if (p.equals("/admin/test-bench") && method.equals("GET")) { Http.json(ex, 200, mail.testBench()); return; }
            if (p.equals("/admin/settings") && method.equals("GET")) { Http.json(ex, 200, settingsJson()); return; }
            if (p.equals("/admin/settings") && method.equals("PUT")) {
                Map<String, Object> b = Http.readJson(ex);
                mail.saveSettings(Json.str(b, "organisation"),
                        b.get("claudeEnabled") instanceof Boolean x ? x : null,
                        b.get("learnFromFeedback") instanceof Boolean y ? y : null);
                Http.json(ex, 200, settingsJson());
                return;
            }
            if (p.equals("/admin/people") && method.equals("GET")) { Http.json(ex, 200, people()); return; }
            if ((m = match("/admin/people/([a-z0-9._-]+)/unlock", p)) != null && method.equals("POST")) {
                auth.unlock(m.group(1));
                Http.json(ex, 200, people());
                return;
            }
        }
        throw new Http.Stop(404, "No such API: " + method + " " + p);
    }

    private Map<String, Object> me(User u) {
        Map<String, Object> m = new LinkedHashMap<>(u.toPublicJson());
        m.put("organisation", store.settings().organisation);
        m.put("claudeReady", mail.claudeReady());
        return m;
    }

    private Map<String, Object> settingsJson() {
        Map<String, Object> m = new LinkedHashMap<>(mail.settings().toJson());
        m.put("claudeHasKey", mail.claudeHasKey());
        m.put("claudeModel", mail.claudeModel());
        return m;
    }

    private List<Map<String, Object>> people() {
        List<Map<String, Object>> out = new ArrayList<>();
        long now = Clock.millis();
        for (User x : store.users()) {
            Map<String, Object> m = new LinkedHashMap<>(x.toPublicJson());
            m.put("locked", x.lockedUntil > now);
            m.put("inbox", store.emailsOf(x.username).size());
            m.put("waiting", store.emailsOf(x.username).stream().filter(e -> !e.replied).count());
            m.put("replies", store.repliesOf(x.username).size());
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ static files

    private void staticFile(HttpExchange ex, String path) throws IOException {
        if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) throw new Http.Stop(405, "Method not allowed.");
        String rel;
        if (path.equals("/") || path.equals("/index.html")) rel = "index.html";
        else if (path.startsWith("/static/")) rel = path.substring("/static/".length());
        else if (path.equals("/favicon.ico")) rel = "favicon.svg";
        else throw new Http.Stop(404, "Not found.");

        Path file = frontend.resolve(rel).normalize();
        // path traversal guard: /static/../../data/users.json must never escape the frontend folder
        if (!file.startsWith(frontend) || rel.contains("..") || !Files.isRegularFile(file)) throw new Http.Stop(404, "Not found.");
        Http.send(ex, 200, Http.contentType(file.toString()), Files.readAllBytes(file));
    }

    // ------------------------------------------------------------------ helpers

    private void limit(String key, int perMinute, String message) {
        long now = Clock.millis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            if (q.size() >= perMinute) throw new Http.Stop(429, message);
            q.addLast(now);
        }
    }

    private static Matcher match(String regex, String path) {
        Matcher m = Pattern.compile("^" + regex + "$").matcher(path);
        return m.matches() ? m : null;
    }

    private static long id(Matcher m) { return Long.parseLong(m.group(1)); }

    /** Enum errors look like "No enum constant com.cecil...X" - make them readable. */
    private static String clean(String msg) {
        return msg.startsWith("No enum constant") ? "Unknown value: " + msg.substring(msg.lastIndexOf('.') + 1) : msg;
    }
}
