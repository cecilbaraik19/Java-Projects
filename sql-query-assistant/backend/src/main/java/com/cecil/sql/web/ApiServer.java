package com.cecil.sql.web;

import com.cecil.sql.ai.Evaluator;
import com.cecil.sql.ai.Lexicon;
import com.cecil.sql.ai.TestQuestions;
import com.cecil.sql.db.Database;
import com.cecil.sql.db.SqlException;
import com.cecil.sql.model.*;
import com.cecil.sql.service.AuthService;
import com.cecil.sql.service.DatabaseService;
import com.cecil.sql.service.QueryService;
import com.cecil.sql.store.DemoDatabases;
import com.cecil.sql.util.Clock;
import com.cecil.sql.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The web server (JDK's com.sun.net.httpserver). Serves the website from the frontend folder and the JSON API:
 *
 *   POST /api/login  /api/logout          GET /api/me  /api/info
 *   GET  /api/databases                   GET /api/databases/{id}/schema       GET /api/databases/{id}/suggestions
 *   POST /api/ask      {db, question}     POST /api/run {db, sql}              POST /api/explain {db, sql}
 *   GET  /api/history  ?scope=all         POST /api/history/{id}/feedback      GET /api/history/{id}/csv
 *   POST /api/teach    {historyId, sql}   GET /api/examples?db=                DELETE /api/examples/{id}
 *   GET/POST /api/saved                   DELETE /api/saved/{id}
 *   POST /api/upload   {name, csv}        DELETE /api/databases/{id}/tables/{t}
 *   admin: GET /api/bench, GET /api/insights, GET/POST /api/users, POST/DELETE /api/users/{u}, GET/POST /api/settings
 */
public final class ApiServer {
    static final int SMALL = 64 * 1024;
    static final int UPLOAD = 2 * 1024 * 1024;

    private final AuthService auth;
    private final DatabaseService dbs;
    private final QueryService queries;
    private final Path frontend;
    private HttpServer server;

    private final Http.RateLimiter loginLimit = new Http.RateLimiter(10, 60_000);
    private final Http.RateLimiter askLimit = new Http.RateLimiter(90, 60_000);
    private final Http.RateLimiter uploadLimit = new Http.RateLimiter(10, 60_000);
    private final Http.RateLimiter benchLimit = new Http.RateLimiter(6, 60_000);

    public ApiServer(AuthService auth, DatabaseService dbs, QueryService queries, Path frontend) {
        this.auth = auth;
        this.dbs = dbs;
        this.queries = queries;
        this.frontend = frontend;
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }
    public void stop() { if (server != null) server.stop(0); }

    // ------------------------------------------------------------------ dispatch
    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            if (path.startsWith("/api/")) api(ex, path.substring(4), ex.getRequestMethod().toUpperCase());
            else staticFile(ex, path);
        } catch (Http.Fail f) {
            Http.json(ex, f.status, Json.map("error", f.getMessage()));
        } catch (AuthService.AuthException e) {
            Http.json(ex, 401, Json.map("error", e.getMessage()));
        } catch (SecurityException e) {
            Http.json(ex, 403, Json.map("error", e.getMessage()));
        } catch (SqlException e) {
            Http.json(ex, 400, Json.map("error", e.getMessage(), "errorAt", e.position));
        } catch (IllegalArgumentException | IllegalStateException e) {
            Http.json(ex, 400, Json.map("error", e.getMessage()));
        } catch (Exception e) {
            System.err.println("Server error on " + path + ": " + e);
            Http.json(ex, 500, Json.map("error", "Something went wrong on the server."));
        }
    }

    private static final Pattern DB_SCHEMA = Pattern.compile("/databases/([\\w-]+)/schema");
    private static final Pattern DB_SUGGEST = Pattern.compile("/databases/([\\w-]+)/suggestions");
    private static final Pattern DB_TABLE = Pattern.compile("/databases/([\\w-]+)/tables/([\\w-]+)");
    private static final Pattern HIST_FEEDBACK = Pattern.compile("/history/([\\w-]+)/feedback");
    private static final Pattern HIST_CSV = Pattern.compile("/history/([\\w-]+)/csv");
    private static final Pattern SAVED_ID = Pattern.compile("/saved/([\\w-]+)");
    private static final Pattern EXAMPLE_ID = Pattern.compile("/examples/([\\w-]+)");
    private static final Pattern USER_ID = Pattern.compile("/users/([\\w.]+)");

    private void api(HttpExchange ex, String p, String method) throws IOException {
        if (!method.equals("GET")) Http.checkSameOrigin(ex);
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();

        // ---------------- no sign-in needed
        if (p.equals("/info") && method.equals("GET")) {
            Http.json(ex, 200, Json.map("organisation", queries.settings().organisation, "name", "Prashna"));
            return;
        }
        if (p.equals("/login") && method.equals("POST")) {
            if (!loginLimit.allow(ip)) throw new Http.Fail(429, "Too many sign-in attempts. Wait a minute.");
            Map<String, Object> b = Http.jsonBody(ex, SMALL);
            AuthService.Session s = auth.login(Json.str(b, "username", ""), Json.str(b, "password", ""));
            Http.setSession(ex, s.token);
            Http.json(ex, 200, me(auth.find(s.username)));
            return;
        }
        if (p.equals("/logout") && method.equals("POST")) {
            auth.logout(Http.cookie(ex, Http.COOKIE));
            Http.clearSession(ex);
            Http.json(ex, 200, Json.map("ok", true));
            return;
        }

        // ---------------- signed in
        User u = auth.user(Http.cookie(ex, Http.COOKIE));
        if (u == null) throw new Http.Fail(401, "Please sign in.");
        Matcher m;

        if (p.equals("/me") && method.equals("GET")) { Http.json(ex, 200, me(u)); return; }

        if (p.equals("/databases") && method.equals("GET")) {
            List<Object> l = new ArrayList<>();
            for (Database d : dbs.visible(u)) l.add(dbs.summary(d));
            Http.json(ex, 200, l);
            return;
        }
        if ((m = DB_SCHEMA.matcher(p)).matches() && method.equals("GET")) {
            Http.json(ex, 200, dbs.schema(u, dbs.get(u, m.group(1))));
            return;
        }
        if ((m = DB_SUGGEST.matcher(p)).matches() && method.equals("GET")) {
            Http.json(ex, 200, QueryService.suggestions(dbs.get(u, m.group(1))));
            return;
        }
        if ((m = DB_TABLE.matcher(p)).matches() && method.equals("DELETE")) {
            dbs.deleteTable(u, m.group(1), m.group(2));
            Http.json(ex, 200, Json.map("ok", true));
            return;
        }

        if (p.equals("/ask") && method.equals("POST")) {
            if (!askLimit.allow(u.username)) throw new Http.Fail(429, "Too many questions in a minute. Please wait a little.");
            Map<String, Object> b = Http.jsonBody(ex, SMALL);
            Http.json(ex, 200, queries.ask(u, Json.str(b, "db"), Json.str(b, "question")));
            return;
        }
        if (p.equals("/run") && method.equals("POST")) {
            if (!askLimit.allow(u.username)) throw new Http.Fail(429, "Too many queries in a minute. Please wait a little.");
            Map<String, Object> b = Http.jsonBody(ex, SMALL);
            Http.json(ex, 200, queries.runSql(u, Json.str(b, "db"), Json.str(b, "sql"), Json.str(b, "question")));
            return;
        }
        if (p.equals("/explain") && method.equals("POST")) {
            Map<String, Object> b = Http.jsonBody(ex, SMALL);
            Http.json(ex, 200, queries.explain(u, Json.str(b, "db"), Json.str(b, "sql")));
            return;
        }

        if (p.equals("/history") && method.equals("GET")) {
            boolean all = "all".equals(Http.query(ex).get("scope"));
            Http.json(ex, 200, queries.history(u, all));
            return;
        }
        if (p.equals("/history") && method.equals("DELETE")) {
            queries.clearHistory(u);
            Http.json(ex, 200, Json.map("ok", true));
            return;
        }
        if ((m = HIST_FEEDBACK.matcher(p)).matches() && method.equals("POST")) {
            Map<String, Object> b = Http.jsonBody(ex, SMALL);
            queries.feedback(u, m.group(1), (int) Json.num(b, "value", 0));
            Http.json(ex, 200, Json.map("ok", true));
            return;
        }
        if ((m = HIST_CSV.matcher(p)).matches() && method.equals("GET")) {
            byte[] csv = queries.csv(u, m.group(1)).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"prashna-result.csv\"");
            Http.send(ex, 200, "text/csv; charset=utf-8", csv);
            return;
        }

        if (p.equals("/teach") && method.equals("POST")) {
            Map<String, Object> b = Http.jsonBody(ex, SMALL);
            Http.json(ex, 200, queries.teach(u, Json.str(b, "historyId"), Json.str(b, "sql")));
            return;
        }
        if (p.equals("/examples") && method.equals("GET")) {
            Http.json(ex, 200, queries.examples(u, Http.query(ex).get("db")));
            return;
        }
        if ((m = EXAMPLE_ID.matcher(p)).matches() && method.equals("DELETE")) {
            queries.forget(u, m.group(1));
            Http.json(ex, 200, Json.map("ok", true));
            return;
        }

        if (p.equals("/saved") && method.equals("GET")) { Http.json(ex, 200, queries.saved(u)); return; }
        if (p.equals("/saved") && method.equals("POST")) {
            Map<String, Object> b = Http.jsonBody(ex, SMALL);
            Http.json(ex, 200, queries.save(u, Json.str(b, "db"), Json.str(b, "name"), Json.str(b, "question"), Json.str(b, "sql")));
            return;
        }
        if ((m = SAVED_ID.matcher(p)).matches() && method.equals("DELETE")) {
            queries.unsave(u, m.group(1));
            Http.json(ex, 200, Json.map("ok", true));
            return;
        }

        if (p.equals("/upload") && method.equals("POST")) {
            if (!uploadLimit.allow(u.username)) throw new Http.Fail(429, "Too many uploads in a minute.");
            Map<String, Object> b = Http.jsonBody(ex, UPLOAD);
            Http.json(ex, 200, dbs.upload(u, Json.str(b, "name"), Json.str(b, "csv")));
            return;
        }

        // ---------------- admin only
        if (p.equals("/bench") || p.equals("/insights") || p.startsWith("/users") || p.equals("/settings")) {
            if (u.role != Role.ADMIN) throw new SecurityException("Only admins can open this page.");
        }
        if (p.equals("/bench") && method.equals("GET")) {
            if (!benchLimit.allow(u.username)) throw new Http.Fail(429, "Please wait a moment before running the test again.");
            Http.json(ex, 200, bench());
            return;
        }
        if (p.equals("/insights") && method.equals("GET")) { Http.json(ex, 200, queries.insights()); return; }
        if (p.equals("/users") && method.equals("GET")) {
            List<Object> l = new ArrayList<>();
            for (User x : auth.all()) l.add(x.publicView());
            Http.json(ex, 200, l);
            return;
        }
        if (p.equals("/users") && method.equals("POST")) {
            Map<String, Object> b = Http.jsonBody(ex, SMALL);
            User x = auth.create(Json.str(b, "username"), Json.str(b, "displayName"), role(Json.str(b, "role")), Json.str(b, "password"));
            Http.json(ex, 200, x.publicView());
            return;
        }
        if ((m = USER_ID.matcher(p)).matches() && method.equals("POST")) {
            Map<String, Object> b = Http.jsonBody(ex, SMALL);
            String r = Json.str(b, "role");
            auth.update(m.group(1), Json.str(b, "displayName"), r == null ? null : role(r), Json.str(b, "password"), u);
            Http.json(ex, 200, auth.find(m.group(1)).publicView());
            return;
        }
        if ((m = USER_ID.matcher(p)).matches() && method.equals("DELETE")) {
            auth.delete(m.group(1), u);
            Http.json(ex, 200, Json.map("ok", true));
            return;
        }
        if (p.equals("/settings") && method.equals("GET")) {
            Http.json(ex, 200, settingsView());
            return;
        }
        if (p.equals("/settings") && method.equals("POST")) {
            Map<String, Object> b = Http.jsonBody(ex, SMALL);
            Settings s = Settings.fromJson(Json.map("organisation", Json.str(b, "organisation", queries.settings().organisation),
                    "claudeMode", Json.str(b, "claudeMode", queries.settings().claudeMode.name()),
                    "viewersSeeSql", Json.bool(b, "viewersSeeSql", queries.settings().viewersSeeSql)));
            if (s.organisation.isBlank() || s.organisation.length() > 80) throw new IllegalArgumentException("Organisation name: 1–80 characters.");
            if (s.claudeMode != Settings.ClaudeMode.OFF && !queries.claudeReady())
                throw new IllegalArgumentException("Set ANTHROPIC_API_KEY and restart before switching Claude on.");
            queries.settings(s);
            Http.json(ex, 200, settingsView());
            return;
        }
        throw new Http.Fail(404, "Not found.");
    }

    private Map<String, Object> settingsView() {
        Map<String, Object> v = Json.map();
        v.putAll(Json.obj(queries.settings().toJson()));
        v.put("claudeReady", queries.claudeReady());
        return v;
    }

    private Map<String, Object> me(User u) {
        Map<String, Object> v = u.publicView();
        v.put("organisation", queries.settings().organisation);
        v.put("canWriteSql", u.can(Role.ANALYST));
        v.put("canUpload", u.can(Role.ANALYST));
        v.put("seesPrivate", u.role != Role.VIEWER);
        v.put("claude", queries.claudeReady() ? queries.settings().claudeMode.name() : "OFF");
        return v;
    }

    private static Role role(String r) {
        try { return Role.valueOf(r == null ? "" : r.toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Role must be ADMIN, ANALYST or VIEWER."); }
    }

    /** Runs both test sets on fresh copies of the demo databases (taught examples are not used). */
    private Map<String, Object> bench() {
        Database college = DemoDatabases.college(Clock.today()), shop = DemoDatabases.shop(Clock.today());
        Map<String, Database> map = Map.of(college.id, college, shop.id, shop);
        Map<String, Lexicon> lex = Map.of(college.id, new Lexicon(college), shop.id, new Lexicon(shop));
        long t0 = System.nanoTime();
        Evaluator.Report dev = Evaluator.run("Development set", TestQuestions.DEV, map::get, lex::get);
        Evaluator.Report fin = Evaluator.run("Final set", TestQuestions.FINAL, map::get, lex::get);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return Json.map("dev", dev.toJson(), "final", fin.toJson(), "millis", ms);
    }

    // ------------------------------------------------------------------ website files
    private void staticFile(HttpExchange ex, String path) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET") && !ex.getRequestMethod().equalsIgnoreCase("HEAD"))
            throw new Http.Fail(405, "Method not allowed.");
        String rel;
        if (path.equals("/") || path.equals("/index.html")) rel = "index.html";
        else if (path.startsWith("/static/")) rel = path.substring("/static/".length());
        else if (path.equals("/favicon.ico")) rel = "favicon.svg";
        else { notFound(ex); return; }
        if (!rel.matches("[A-Za-z0-9_.-]+") || rel.contains("..")) { notFound(ex); return; }
        Path f = frontend.resolve(rel).normalize();
        if (!f.startsWith(frontend) || !Files.isRegularFile(f)) { notFound(ex); return; }
        ex.getResponseHeaders().set("Cache-Control", rel.equals("index.html") ? "no-cache" : "max-age=300");
        Http.send(ex, 200, Http.contentType(rel), Files.readAllBytes(f));
    }

    private void notFound(HttpExchange ex) throws IOException {
        Http.send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
    }
}
