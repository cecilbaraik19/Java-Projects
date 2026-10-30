package com.cecil.ngo.web;

import com.cecil.ngo.model.Enums;
import com.cecil.ngo.model.User;
import com.cecil.ngo.service.ApiError;
import com.cecil.ngo.service.AuthService;
import com.cecil.ngo.service.BeneficiaryService;
import com.cecil.ngo.service.DashboardService;
import com.cecil.ngo.service.FundService;
import com.cecil.ngo.service.ProgramService;
import com.cecil.ngo.service.UserService;
import com.cecil.ngo.service.VolunteerService;
import com.cecil.ngo.store.DataStore;
import com.cecil.ngo.util.Json;
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
    public final ProgramService programs;
    public final VolunteerService volunteers;
    public final FundService funds;
    public final BeneficiaryService beneficiaries;
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
        this.programs = new ProgramService(store);
        this.volunteers = new VolunteerService(store);
        this.funds = new FundService(store);
        this.beneficiaries = new BeneficiaryService(store);
        this.dash = new DashboardService(store, programs, volunteers);
        routes();
    }

    private void get(String p, Handler h) { routes.add(new Route("GET", Pattern.compile(p), false, h)); }
    private void post(String p, Handler h) { routes.add(new Route("POST", Pattern.compile(p), false, h)); }
    private void put(String p, Handler h) { routes.add(new Route("PUT", Pattern.compile(p), false, h)); }
    private void del(String p, Handler h) { routes.add(new Route("DELETE", Pattern.compile(p), false, h)); }

    private static final String ID = "([A-Za-z0-9_.-]{1,40})";
    private static final Map<String, Object> OK = Json.map("ok", true);

    private void routes() {
        // ---- sign in (the only routes that work without a token)
        routes.add(new Route("POST", Pattern.compile("/api/auth/login"), true, (ex, me, m) -> {
            Map<String, Object> b = Http.jsonBody(ex);
            AuthService.Session s = auth.login(Json.str(b, "username"), Json.str(b, "password"));
            return Json.map("token", s.token, "user", store.user(s.username).publicView());
        }));
        routes.add(new Route("POST", Pattern.compile("/api/auth/logout"), true, (ex, me, m) -> {
            auth.logout(Http.bearer(ex));
            return OK;
        }));
        get("/api/auth/me", (ex, me, m) -> me.publicView());
        post("/api/auth/password", (ex, me, m) -> {
            Map<String, Object> b = Http.jsonBody(ex);
            auth.changePassword(me, Json.str(b, "current"), Json.str(b, "next"), Http.bearer(ex));
            return OK;
        });

        // ---- lists for the screens
        get("/api/meta", (ex, me, m) -> {
            Map<String, Object> meta = Enums.meta();
            meta.put("programs", programs.minis());
            meta.put("users", users.list());
            meta.put("settings", dash.settings());
            return meta;
        });
        get("/api/dashboard", (ex, me, m) -> dash.dashboard(me));
        get("/api/report", (ex, me, m) -> dash.report(me, Http.query(ex)));
        get("/api/events", (ex, me, m) -> {
            AuthService.needStaff(me, "see the history");
            Map<String, String> q = Http.query(ex);
            int limit = 60;
            try { if (q.get("limit") != null) limit = Math.max(1, Math.min(500, Integer.parseInt(q.get("limit")))); }
            catch (NumberFormatException e) { throw ApiError.bad("limit must be a number"); }
            return dash.events(q.get("ref"), limit);
        });
        get("/api/settings", (ex, me, m) -> dash.settings());
        put("/api/settings", (ex, me, m) -> dash.saveSettings(me, Http.jsonBody(ex)));
        get("/api/funds", (ex, me, m) -> funds.position(me));

        // ---- team logins
        get("/api/users", (ex, me, m) -> { AuthService.needAdmin(me, "see logins"); return users.list(); });
        post("/api/users", (ex, me, m) -> users.create(me, Http.jsonBody(ex)));
        put("/api/users/" + ID, (ex, me, m) -> users.update(me, m.group(1), Http.jsonBody(ex)));

        // ---- programs
        get("/api/programs", (ex, me, m) -> programs.list(me, Http.query(ex)));
        post("/api/programs", (ex, me, m) -> programs.create(me, Http.jsonBody(ex)));
        get("/api/programs/" + ID, (ex, me, m) -> programs.get(me, m.group(1)));
        put("/api/programs/" + ID, (ex, me, m) -> programs.update(me, m.group(1), Http.jsonBody(ex)));
        del("/api/programs/" + ID, (ex, me, m) -> { programs.delete(me, m.group(1)); return OK; });

        // ---- activities
        get("/api/activities", (ex, me, m) -> programs.activities(me, Http.query(ex)));
        post("/api/activities", (ex, me, m) -> programs.createActivity(me, Http.jsonBody(ex)));
        get("/api/activities/" + ID, (ex, me, m) -> programs.activity(me, m.group(1)));
        put("/api/activities/" + ID, (ex, me, m) -> programs.updateActivity(me, m.group(1), Http.jsonBody(ex)));
        del("/api/activities/" + ID, (ex, me, m) -> { programs.deleteActivity(me, m.group(1)); return OK; });
        post("/api/activities/" + ID + "/signup", (ex, me, m) -> programs.signUp(me, m.group(1), Http.jsonBody(ex)));
        post("/api/activities/" + ID + "/withdraw", (ex, me, m) -> programs.withdraw(me, m.group(1), Http.jsonBody(ex)));
        post("/api/activities/" + ID + "/complete", (ex, me, m) -> programs.complete(me, m.group(1), Http.jsonBody(ex)));
        post("/api/activities/" + ID + "/cancel", (ex, me, m) -> programs.cancel(me, m.group(1), Http.jsonBody(ex)));

        // ---- volunteers and hours (fixed addresses before the id ones)
        get("/api/volunteers/export\\.csv", (ex, me, m) -> {
            Http.download(ex, "text/csv; charset=utf-8", "volunteers.csv", volunteers.exportCsv(me));
            return null;
        });
        get("/api/volunteers", (ex, me, m) -> volunteers.list(me, Http.query(ex)));
        post("/api/volunteers", (ex, me, m) -> volunteers.create(me, Http.jsonBody(ex)));
        get("/api/volunteers/" + ID, (ex, me, m) -> volunteers.get(me, m.group(1)));
        put("/api/volunteers/" + ID, (ex, me, m) -> volunteers.update(me, m.group(1), Http.jsonBody(ex)));
        del("/api/volunteers/" + ID, (ex, me, m) -> { volunteers.delete(me, m.group(1)); return OK; });
        get("/api/hours", (ex, me, m) -> volunteers.hours(me, Http.query(ex)));
        get("/api/hours/unlogged", (ex, me, m) -> volunteers.unlogged(me, Http.query(ex).get("volunteer")));
        post("/api/hours", (ex, me, m) -> volunteers.logHours(me, Http.jsonBody(ex)));
        post("/api/hours/" + ID + "/approve", (ex, me, m) -> volunteers.review(me, m.group(1), true));
        post("/api/hours/" + ID + "/reject", (ex, me, m) -> volunteers.review(me, m.group(1), false));

        // ---- donors and donations
        get("/api/donors", (ex, me, m) -> funds.donors(me, Http.query(ex)));
        post("/api/donors", (ex, me, m) -> funds.saveDonor(me, null, Http.jsonBody(ex)));
        get("/api/donors/" + ID, (ex, me, m) -> funds.donor(me, m.group(1)));
        put("/api/donors/" + ID, (ex, me, m) -> funds.saveDonor(me, m.group(1), Http.jsonBody(ex)));
        del("/api/donors/" + ID, (ex, me, m) -> { funds.deleteDonor(me, m.group(1)); return OK; });
        get("/api/donations/export\\.csv", (ex, me, m) -> {
            Http.download(ex, "text/csv; charset=utf-8", "donations.csv", funds.exportDonationsCsv(me, Http.query(ex)));
            return null;
        });
        get("/api/donations", (ex, me, m) -> funds.donations(me, Http.query(ex)));
        post("/api/donations", (ex, me, m) -> funds.recordDonation(me, Http.jsonBody(ex)));
        get("/api/donations/" + ID + "/receipt", (ex, me, m) -> funds.receipt(me, m.group(1)));
        del("/api/donations/" + ID, (ex, me, m) -> { funds.deleteDonation(me, m.group(1)); return OK; });

        // ---- expenses
        get("/api/expenses/export\\.csv", (ex, me, m) -> {
            Http.download(ex, "text/csv; charset=utf-8", "expenses.csv", funds.exportExpensesCsv(me, Http.query(ex)));
            return null;
        });
        get("/api/expenses", (ex, me, m) -> funds.expenses(me, Http.query(ex)));
        post("/api/expenses", (ex, me, m) -> funds.submitExpense(me, Http.jsonBody(ex)));
        post("/api/expenses/" + ID + "/review", (ex, me, m) -> funds.reviewExpense(me, m.group(1), Http.jsonBody(ex)));
        del("/api/expenses/" + ID, (ex, me, m) -> { funds.deleteExpense(me, m.group(1)); return OK; });

        // ---- beneficiaries
        get("/api/beneficiaries/export\\.csv", (ex, me, m) -> {
            Http.download(ex, "text/csv; charset=utf-8", "beneficiaries.csv", beneficiaries.exportCsv(me, Http.query(ex)));
            return null;
        });
        get("/api/beneficiaries", (ex, me, m) -> beneficiaries.list(me, Http.query(ex)));
        post("/api/beneficiaries", (ex, me, m) -> beneficiaries.save(me, null, Http.jsonBody(ex)));
        put("/api/beneficiaries/" + ID, (ex, me, m) -> beneficiaries.save(me, m.group(1), Http.jsonBody(ex)));
        del("/api/beneficiaries/" + ID, (ex, me, m) -> { beneficiaries.delete(me, m.group(1)); return OK; });
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

    /** Sends a file from the frontend folder. "/" gets index.html. */
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
