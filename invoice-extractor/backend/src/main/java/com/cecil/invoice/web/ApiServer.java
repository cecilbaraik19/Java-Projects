package com.cecil.invoice.web;

import com.cecil.invoice.extract.Classifier;
import com.cecil.invoice.extract.Validator;
import com.cecil.invoice.model.*;
import com.cecil.invoice.service.*;
import com.cecil.invoice.store.DataStore;
import com.cecil.invoice.store.SampleInvoices;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.cecil.invoice.model.Role.*;

/**
 * The web server. It serves the website from the frontend folder and answers the
 * JSON API under /api/. Every API route checks the user's role here, on the server -
 * the browser is never trusted for that.
 */
public class ApiServer {
    private final DataStore db;
    private final AuthService auth;
    private final LearningService learning;
    private final InvoiceService invoices;
    private final UserService users;
    private final DashboardService dash;
    private final Path frontend;
    private final List<Route> routes = new ArrayList<>();
    private final Random random;
    private HttpServer server;
    private ExecutorService pool;

    public ApiServer(DataStore db, Path frontend, Random random) {
        this.db = db;
        this.frontend = frontend;
        this.random = random;
        this.auth = new AuthService(db);
        this.learning = new LearningService(db);
        this.invoices = new InvoiceService(db, learning);
        this.users = new UserService(db, auth);
        this.dash = new DashboardService(db, learning, invoices);
        defineRoutes();
    }

    public InvoiceService invoices() { return invoices; }

    // ================================================================= routing

    @FunctionalInterface
    interface Handler { void handle(Ctx c) throws IOException; }

    /** Everything a route needs: the exchange, the signed-in user, path parts and query. */
    static final class Ctx {
        final HttpExchange ex;
        final User user;
        final Matcher path;
        final Map<String, String> query;
        Ctx(HttpExchange ex, User user, Matcher path, Map<String, String> query) {
            this.ex = ex; this.user = user; this.path = path; this.query = query;
        }
        String p(int i) { return path.group(i); }
        String q(String k) { String v = query.get(k); return v == null || v.isBlank() ? null : v; }
        Map<String, Object> body() throws IOException { return Http.jsonBody(ex); }
        void ok(Object v) throws IOException { Http.json(ex, 200, v); }
    }

    private record Route(String method, Pattern pattern, boolean needsLogin, Role[] roles, Handler handler) {}

    private void route(String method, String path, Handler h, Role... roles) {
        routes.add(new Route(method, Pattern.compile("^" + path + "$"), true, roles, h));
    }

    private void open(String method, String path, Handler h) {
        routes.add(new Route(method, Pattern.compile("^" + path + "$"), false, new Role[0], h));
    }

    private static final String ID = "([A-Za-z0-9]{1,20})";
    private static final Role[] ALL = {ADMIN, ACCOUNTANT, CLERK};

    private void defineRoutes() {
        // ---------------- sign in
        open("POST", "/api/login", c -> {
            Map<String, Object> b = c.body();
            AuthService.Session s = auth.login(String.valueOf(b.getOrDefault("username", "")), String.valueOf(b.getOrDefault("password", "")));
            Http.setSessionCookie(c.ex, s.token);
            c.ok(Map.of("user", UserService.view(db.user(s.userId))));
        });
        open("POST", "/api/logout", c -> {
            auth.logout(Http.cookie(c.ex, Http.COOKIE));
            Http.clearSessionCookie(c.ex);
            c.ok(Map.of("ok", true));
        });
        route("GET", "/api/me", c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("user", UserService.view(c.user));
            out.put("company", db.settings.companyName);
            out.put("companyGstin", db.settings.companyGstin);
            out.put("categories", Classifier.CATEGORIES);
            List<Map<String, String>> fields = new ArrayList<>();
            for (String f : Invoice.FIELDS) fields.add(Map.of("name", f, "label", Validator.label(f)));
            out.put("fields", fields);
            out.put("claude", invoices.claudeReady());
            out.put("autoApprove", db.settings.autoApprove);
            out.put("today", db.today().toString());
            int waiting = 0;
            for (Invoice i : db.invoices) if (i.status == Status.TO_REVIEW) waiting++;
            out.put("waiting", waiting);
            c.ok(out);
        }, ALL);
        route("POST", "/api/me/password", c -> {
            Map<String, Object> b = c.body();
            users.changeOwnPassword(c.user, String.valueOf(b.getOrDefault("current", "")), String.valueOf(b.getOrDefault("next", "")));
            c.ok(Map.of("ok", true));
        }, ALL);

        // ---------------- home
        route("GET", "/api/home", c -> c.ok(dash.home()), ALL);

        // ---------------- invoices
        route("GET", "/api/invoices", c -> c.ok(invoices.list(c.query)), ALL);
        route("POST", "/api/invoices", c -> c.ok(Map.of("id", invoices.upload(c.user, c.body()).id)), ALL);
        route("GET", "/api/invoices/" + ID, c -> c.ok(invoices.detail(c.p(1))), ALL);
        route("PUT", "/api/invoices/" + ID, c -> { invoices.correct(c.user, c.p(1), c.body()); c.ok(invoices.detail(c.p(1))); }, ALL);
        route("POST", "/api/invoices/" + ID + "/reread", c -> { invoices.reread(c.user, c.p(1)); c.ok(invoices.detail(c.p(1))); }, ALL);
        route("POST", "/api/invoices/" + ID + "/approve", c -> { invoices.approve(c.user, c.p(1), c.body()); c.ok(invoices.detail(c.p(1))); }, ADMIN, ACCOUNTANT);
        route("POST", "/api/invoices/" + ID + "/reject", c -> {
            invoices.reject(c.user, c.p(1), String.valueOf(c.body().getOrDefault("reason", "")));
            c.ok(invoices.detail(c.p(1)));
        }, ADMIN, ACCOUNTANT);
        route("POST", "/api/invoices/" + ID + "/reopen", c -> { invoices.reopen(c.user, c.p(1)); c.ok(invoices.detail(c.p(1))); }, ADMIN, ACCOUNTANT);

        // ---------------- sample invoices for trying the reader
        route("GET", "/api/samples", c -> c.ok(sampleList()), ALL);
        route("GET", "/api/samples/([a-z-]{2,20})", c -> c.ok(sample(c.p(1))), ALL);

        // ---------------- ledger, vendors
        route("GET", "/api/ledger", c -> c.ok(dash.ledger(c.query)), ALL);
        route("GET", "/api/vendors", c -> c.ok(dash.vendors()), ALL);
        route("PUT", "/api/vendors/" + ID, c -> c.ok(dash.saveVendor(c.user, c.p(1), c.body())), ADMIN, ACCOUNTANT);

        // ---------------- the AI model
        route("GET", "/api/model", c -> c.ok(dash.model()), ALL);
        route("POST", "/api/model/test", c -> c.ok(dash.testCategory(String.valueOf(c.body().getOrDefault("text", "")))), ALL);
        route("POST", "/api/model/retrain", c -> c.ok(dash.retrain(c.user)), ADMIN);

        // ---------------- admin
        route("GET", "/api/users", c -> c.ok(users.list(null)), ADMIN);
        route("POST", "/api/users", c -> c.ok(UserService.view(users.save(c.user, null, c.body()))), ADMIN);
        route("PUT", "/api/users/" + ID, c -> c.ok(UserService.view(users.save(c.user, c.p(1), c.body()))), ADMIN);
        route("POST", "/api/users/" + ID + "/active", c -> c.ok(UserService.view(users.setActive(c.user, c.p(1), Boolean.TRUE.equals(c.body().get("active"))))), ADMIN);
        route("GET", "/api/settings", c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("settings", db.settings);
            out.put("claudeKey", invoices.claudeKeyPresent());
            c.ok(out);
        }, ADMIN);
        route("PUT", "/api/settings", c -> c.ok(dash.saveSettings(c.user, c.body())), ADMIN);
        route("GET", "/api/events", c -> {
            List<Event> list = new ArrayList<>(db.events);
            Collections.reverse(list);
            c.ok(list.subList(0, Math.min(400, list.size())));
        }, ADMIN, ACCOUNTANT);

        // ---------------- exports (approved / exported invoices)
        route("GET", "/api/export/register.csv", c -> export(c, "register"), ADMIN, ACCOUNTANT);
        route("GET", "/api/export/items.csv", c -> export(c, "items"), ADMIN, ACCOUNTANT);
        route("GET", "/api/export/invoices.json", c -> export(c, "json"), ADMIN, ACCOUNTANT);
    }

    private void export(Ctx c, String kind) throws IOException {
        List<Invoice> list;
        synchronized (db) {
            list = new ArrayList<>();
            for (Invoice i : db.invoices) {
                if (i.status != Status.APPROVED && i.status != Status.EXPORTED) continue;
                String d = i.v("invoiceDate");
                if (c.q("from") != null && d.compareTo(c.q("from")) < 0) continue;
                if (c.q("to") != null && d.compareTo(c.q("to")) > 0) continue;
                if (c.q("status") != null && !c.q("status").equals(i.status.name())) continue;
                list.add(i);
            }
            list.sort(Comparator.comparing((Invoice i) -> i.v("invoiceDate")).thenComparing(i -> i.id));
        }
        String stamp = db.today().toString();
        switch (kind) {
            case "register" -> Http.download(c.ex, "invoice-register-" + stamp + ".csv", "text/csv", dash.registerCsv(list));
            case "items" -> Http.download(c.ex, "invoice-items-" + stamp + ".csv", "text/csv", dash.itemsCsv(list));
            default -> Http.download(c.ex, "invoices-" + stamp + ".json", "application/json", dash.json(list));
        }
        if ("1".equals(c.q("mark"))) invoices.markExported(c.user, list);
    }

    private static final String[][] SAMPLES = {
            {"kanchan", "Stationery - classic GST invoice", "Known vendor, same state (CGST + SGST)"},
            {"cloudnest", "Cloud software - labels in a row", "Values sit under their labels, other state (IGST)"},
            {"mehta", "CA firm - \"Bill Ref\" label", "The app learned what \"Bill Ref\" means from a correction"},
            {"skyroute", "Travel agent - short receipt", "Few labels, terms written as \"pay within 7 days\""},
            {"greenleaf", "Office rent", "Big amount, so it always needs a person"},
            {"pixelprint", "Print shop - a new vendor", "Never seen before, so the vendor is created on approval"},
            {"ocr", "Poor scan (letters misread)", "\"lnvoice N0\", \"Grand Tota1\", O instead of 0"},
            {"wrong-total", "Total does not add up", "The checks must catch the mistake"},
            {"bad-gstin", "Wrong GSTIN check digit", "A misprinted or fake GSTIN"},
    };

    private List<Map<String, String>> sampleList() {
        List<Map<String, String>> out = new ArrayList<>();
        for (String[] s : SAMPLES) out.add(Map.of("key", s[0], "label", s[1], "note", s[2]));
        return out;
    }

    private Map<String, Object> sample(String key) {
        SampleInvoices.Flaw flaw = SampleInvoices.Flaw.NONE;
        String supplier = key;
        switch (key) {
            case "ocr" -> { flaw = SampleInvoices.Flaw.OCR_NOISE; supplier = "kanchan"; }
            case "wrong-total" -> { flaw = SampleInvoices.Flaw.WRONG_TOTAL; supplier = "byteline"; }
            case "bad-gstin" -> { flaw = SampleInvoices.Flaw.BAD_GSTIN; supplier = "fixright"; }
            default -> { }
        }
        SampleInvoices.Supplier s;
        try { s = SampleInvoices.supplier(supplier); } catch (IllegalArgumentException e) { throw ApiError.notFound("Sample"); }
        int number;
        synchronized (random) { number = 100 + random.nextInt(800); }
        SampleInvoices.Sample sm = SampleInvoices.make(s, number, db.today().minusDays(1), new Random(number * 31L + key.hashCode()), flaw);
        return Map.of("text", sm.text(), "fileName", key + "-sample-" + number + ".txt");
    }

    // ================================================================= server

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        pool = Executors.newFixedThreadPool(8);
        server.setExecutor(pool);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    void handle(HttpExchange ex) throws IOException {
        try {
            Http.secure(ex.getResponseHeaders());
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) api(ex, path);
            else staticFile(ex, path);
        } catch (ApiError e) {
            Http.error(ex, e.status, e.getMessage());
        } catch (Exception e) {
            System.err.println("Error on " + ex.getRequestURI() + ": " + e);
            e.printStackTrace();
            Http.error(ex, 500, "Something went wrong on the server.");
        } finally {
            ex.close();
        }
    }

    private void api(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) checkOrigin(ex);
        boolean pathMatched = false;
        for (Route r : routes) {
            Matcher m = r.pattern.matcher(path);
            if (!m.matches()) continue;
            pathMatched = true;
            if (!r.method.equals(method) && !(method.equals("HEAD") && r.method.equals("GET"))) continue;
            User u = auth.check(Http.cookie(ex, Http.COOKIE));
            if (r.needsLogin) AuthService.require(u, r.roles);
            r.handler.handle(new Ctx(ex, u, m, Http.query(ex)));
            return;
        }
        if (pathMatched) throw new ApiError(405, "This method is not allowed here.");
        throw new ApiError(404, "No such API address.");
    }

    /** Refuse changes coming from other websites (a simple CSRF guard on top of SameSite cookies). */
    private static void checkOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return;
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || !(origin.equals("http://" + host) || origin.equals("https://" + host)))
            throw new ApiError(403, "Requests from other websites are not allowed.");
    }

    private void staticFile(HttpExchange ex, String path) throws IOException {
        if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) throw new ApiError(405, "Not allowed.");
        String rel;
        if (path.equals("/") || path.equals("/index.html")) rel = "index.html";
        else if (path.startsWith("/static/")) rel = path.substring("/static/".length());
        else if (path.equals("/favicon.ico") || path.equals("/favicon.svg")) rel = "favicon.svg";
        else { ex.getResponseHeaders().set("Location", "/"); Http.send(ex, 302, new byte[0]); return; }
        Path file = frontend.resolve(rel).normalize();
        if (!file.startsWith(frontend) || !Files.isRegularFile(file)) {   // blocks ../ tricks
            Http.send(ex, 404, "Not found".getBytes());
            return;
        }
        ex.getResponseHeaders().set("Content-Type", Http.contentType(rel));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        Http.send(ex, 200, Files.readAllBytes(file));
    }
}
