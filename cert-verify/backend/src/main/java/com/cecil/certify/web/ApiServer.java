package com.cecil.certify.web;

import com.cecil.certify.cert.CertFields;
import com.cecil.certify.cert.CertificateService;
import com.cecil.certify.cert.ForgeryBench;
import com.cecil.certify.cert.Verifier;
import com.cecil.certify.chain.Block;
import com.cecil.certify.chain.ChainState;
import com.cecil.certify.chain.Network;
import com.cecil.certify.chain.Node;
import com.cecil.certify.chain.Transaction;
import com.cecil.certify.model.CertificateRecord;
import com.cecil.certify.model.Issuer;
import com.cecil.certify.model.Role;
import com.cecil.certify.model.User;
import com.cecil.certify.model.VerifyLogEntry;
import com.cecil.certify.qr.QrCode;
import com.cecil.certify.service.AuthService;
import com.cecil.certify.service.LabService;
import com.cecil.certify.store.DataStore;
import com.cecil.certify.store.SeedData;
import com.cecil.certify.util.Clock;
import com.cecil.certify.util.Crypto;
import com.cecil.certify.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All web routes. The same server also sends the website (frontend folder) so there is only one thing to start.
 *
 * Public (no sign-in):  verify a certificate, look at the ledger, list institutions.
 * Student:              my certificates, share links, download my certificate file.
 * Institution staff:    issue (one or a CSV batch), revoke, see who checked our certificates.
 * Registrar:            approve / suspend institutions, nodes, Tamper lab, Forgery test, people, activity.
 */
public final class ApiServer {

    public static final String COOKIE = "pramaan_sid";

    private final AuthService auth;
    private final CertificateService certs;
    private final Verifier verifier;
    private final LabService lab;
    private final ForgeryBench bench;
    private final Network network;
    private final DataStore store;
    private final Path frontend;
    private final String publicUrl;
    private HttpServer server;
    private java.util.concurrent.ExecutorService pool;

    private final Http.RateLimiter loginLimit = new Http.RateLimiter(10);
    private final Http.RateLimiter verifyLimit = new Http.RateLimiter(30);
    private final Http.RateLimiter writeLimit = new Http.RateLimiter(60);
    private final Http.RateLimiter heavyLimit = new Http.RateLimiter(6);

    private final List<Route> routes = new ArrayList<>();

    public ApiServer(AuthService auth, CertificateService certs, Verifier verifier, Path frontend, String publicUrl) {
        this.auth = auth;
        this.certs = certs;
        this.verifier = verifier;
        this.network = certs.network();
        this.store = certs.store();
        this.lab = new LabService(network, store);
        this.bench = new ForgeryBench(certs, verifier);
        this.frontend = frontend;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        defineRoutes();
    }

    // ------------------------------------------------------------------ routing

    @FunctionalInterface
    interface Handler {
        Object handle(Ctx c) throws Exception;
    }

    record Route(String method, Pattern path, Role[] roles, boolean signedIn, Handler handler) {}

    static final class Ctx {
        HttpExchange ex;
        User user;
        Matcher m;
        Map<String, Object> body;

        String p(int i) { return m.group(i); }
        String s(String k) { return Json.str(body, k, ""); }
    }

    private void get(String path, Handler h) { routes.add(new Route("GET", Pattern.compile(path), null, false, h)); }
    private void post(String path, Handler h) { routes.add(new Route("POST", Pattern.compile(path), null, false, h)); }
    private void get(String path, Role[] roles, Handler h) { routes.add(new Route("GET", Pattern.compile(path), roles, true, h)); }
    private void post(String path, Role[] roles, Handler h) { routes.add(new Route("POST", Pattern.compile(path), roles, true, h)); }

    private static final Role[] ANY = Role.values();
    private static final Role[] STUDENT = {Role.STUDENT};
    private static final Role[] ISSUER = {Role.ISSUER};
    private static final Role[] REGISTRAR = {Role.REGISTRAR};
    private static final Role[] HOLDER_OR_ISSUER = {Role.STUDENT, Role.ISSUER};

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        server.createContext("/", this::handle);
        pool = Executors.newFixedThreadPool(8);
        server.setExecutor(pool);
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            if (!path.startsWith("/api/")) {
                serveStatic(ex, path);
                return;
            }
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            if (method.equals("POST")) checkCsrf(ex);
            for (Route r : routes) {
                if (!r.method.equals(method)) continue;
                Matcher m = r.path.matcher(path);
                if (!m.matches()) continue;
                Ctx c = new Ctx();
                c.ex = ex;
                c.m = m;
                c.user = auth.userFor(Http.cookie(ex, COOKIE));
                if (r.signedIn) {
                    if (c.user == null) throw new Http.Error(401, "Please sign in");
                    boolean ok = false;
                    for (Role role : r.roles) if (role == c.user.role) ok = true;
                    if (!ok) throw new Http.Error(403, "Your account cannot do that");
                }
                c.body = method.equals("POST") ? Http.jsonBody(ex) : new LinkedHashMap<>();
                Object out = r.handler.handle(c);
                if (out != null) Http.json(ex, 200, out);
                return;
            }
            throw new Http.Error(404, "Not found");
        } catch (Http.Error e) {
            safeError(ex, e.status, e.getMessage());
        } catch (SecurityException e) {
            safeError(ex, 403, e.getMessage());
        } catch (IllegalArgumentException e) {
            safeError(ex, 400, e.getMessage());
        } catch (IllegalStateException e) {
            safeError(ex, 409, e.getMessage());
        } catch (Exception e) {
            System.err.println("Server error: " + e);
            safeError(ex, 500, "Something went wrong on the server");
        }
    }

    private static void safeError(HttpExchange ex, int status, String msg) {
        try {
            Http.json(ex, status, Json.map("error", msg == null ? "Error" : msg));
        } catch (IOException ignored) {
            ex.close();
        }
    }

    /**
     * CSRF protection: every POST must be JSON (a plain HTML form cannot send that) and, if the browser says where
     * the request came from, it must be this same site.
     */
    private static void checkCsrf(HttpExchange ex) {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("application/json"))
            throw new Http.Error(415, "Requests must be sent as JSON");
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String host = ex.getRequestHeaders().getFirst("Host");
        if (origin != null && host != null) {
            try {
                URI o = URI.create(origin);
                String oh = o.getHost() + (o.getPort() > 0 ? ":" + o.getPort() : "");
                if (!oh.equalsIgnoreCase(host)) throw new Http.Error(403, "Request came from another website");
            } catch (IllegalArgumentException e) {
                throw new Http.Error(403, "Bad Origin header");
            }
        }
    }

    private static void limit(Http.RateLimiter l, HttpExchange ex, String what) {
        if (!l.allow(Http.clientIp(ex) + "|" + what)) throw new Http.Error(429, "Too many requests. Please wait a minute and try again.");
    }

    // ------------------------------------------------------------------ static files

    private static final Pattern STATIC_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,60}");

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        String name;
        if (path.equals("/") || path.equals("/index.html")) name = "index.html";
        else if (path.startsWith("/static/")) name = path.substring(8);
        else if (path.equals("/favicon.ico")) name = "favicon.svg";
        else { Http.json(ex, 404, Json.map("error", "Not found")); return; }
        if (!STATIC_NAME.matcher(name).matches() || name.contains("..")) { Http.json(ex, 404, Json.map("error", "Not found")); return; }
        Path f = frontend.resolve(name).normalize();
        if (!f.startsWith(frontend) || !Files.isRegularFile(f)) { Http.json(ex, 404, Json.map("error", "Not found")); return; }
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : name.endsWith(".png") ? "image/png"
                : name.endsWith(".csv") ? "text/csv; charset=utf-8"
                : "application/octet-stream";
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        Http.send(ex, 200, type, Files.readAllBytes(f));
    }

    // ------------------------------------------------------------------ routes

    private void defineRoutes() {
        // ---------- public
        get("/api/public/info", c -> publicInfo());
        post("/api/public/verify", c -> {
            limit(verifyLimit, c.ex, "verify");
            String method = c.s("method").toUpperCase(Locale.ROOT);
            return switch (method) {
                case "LINK" -> verifier.verifyLink(c.s("certId"), c.s("token"), true);
                case "FILE" -> verifier.verifyFile(Json.obj(c.body, "file"), true);
                case "TYPED" -> {
                    Map<String, String> f = new LinkedHashMap<>();
                    Map<String, Object> in = Json.obj(c.body, "fields");
                    if (in != null) in.forEach((k, v) -> {
                        if (CertFields.position(k) >= 0 && v != null) f.put(k, limitText(String.valueOf(v), 200));
                    });
                    yield verifier.verifyTyped(c.s("certId"), f, true);
                }
                default -> throw new Http.Error(400, "Choose how to check: LINK, FILE or TYPED");
            };
        });
        get("/api/public/ledger", c -> ledger(Http.query(c.ex)));
        get("/api/public/block/(\\d{1,7})", c -> blockDetail(Integer.parseInt(c.p(1))));
        get("/api/public/samples", c -> Json.map("samples", SeedData.samples(certs)));

        // ---------- sign in
        post("/api/login", c -> {
            limit(loginLimit, c.ex, "login");
            AuthService.Session s = auth.login(c.s("username"), c.s("password"));
            Http.setSessionCookie(c.ex, COOKIE, s.id, AuthService.IDLE_MS / 1000);
            return me(store.users.get(s.username));
        });
        post("/api/logout", c -> {
            auth.logout(Http.cookie(c.ex, COOKIE));
            Http.setSessionCookie(c.ex, COOKIE, "", 0);
            return Json.map("ok", true);
        });
        get("/api/me", c -> c.user == null ? Json.map("user", null) : me(c.user));

        // ---------- student
        get("/api/my/certificates", STUDENT, c -> {
            List<Object> out = new ArrayList<>();
            for (CertificateRecord r : sortedNewest(store.certificates.values()))
                if (r.holderUsername.equals(c.user.username)) out.add(certView(r, c.user));
            return Json.map("certificates", out);
        });
        post("/api/my/certificates/(PRM-[0-9A-Z-]{11})/links", STUDENT, c -> {
            limit(writeLimit, c.ex, "write");
            Map<String, Object> link = certs.createShareLink(c.user, c.p(1), c.s("label"), Json.num(c.body, "days", 30));
            link.put("url", publicUrl + "/#/v/" + c.p(1) + "/" + link.get("token"));
            return link;
        });
        post("/api/my/certificates/(PRM-[0-9A-Z-]{11})/links/([0-9a-f]{8})/off", STUDENT, c -> {
            certs.disableShareLink(c.user, c.p(1), c.p(2));
            return Json.map("ok", true);
        });

        // ---------- holder or issuer: one certificate, its file and its QR code
        get("/api/certificates/(PRM-[0-9A-Z-]{11})", HOLDER_OR_ISSUER, c -> certView(mayView(c.user, c.p(1)), c.user));
        get("/api/certificates/(PRM-[0-9A-Z-]{11})/file", HOLDER_OR_ISSUER, c -> {
            CertificateRecord r = mayView(c.user, c.p(1));
            c.ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + r.certId + ".pramaan.json\"");
            Http.send(c.ex, 200, "application/json; charset=utf-8", Json.pretty(certs.certificateFile(r)).getBytes(StandardCharsets.UTF_8));
            return null;
        });

        // ---------- institution staff
        get("/api/issuer/overview", ISSUER, c -> issuerOverview(c.user));
        post("/api/issuer/issue", ISSUER, c -> {
            limit(writeLimit, c.ex, "write");
            Map<String, String> f = new LinkedHashMap<>();
            Map<String, Object> in = Json.obj(c.body, "fields");
            if (in != null) in.forEach((k, v) -> f.put(k, v == null ? "" : String.valueOf(v)));
            CertificateRecord r = certs.issue(c.user, f, c.s("username"));
            Map<String, Object> view = certView(r, c.user);
            view.put("mineMillis", network.node("A").chain.isEmpty() ? 0 : lastBlock().mineMillis);
            return view;
        });
        post("/api/issuer/batch", ISSUER, c -> {
            limit(heavyLimit, c.ex, "batch");
            CertificateService.BatchResult r = certs.issueBatch(c.user, c.s("csv"));
            List<Object> issued = new ArrayList<>();
            for (CertificateRecord rec : r.issued)
                issued.add(Json.map("certId", rec.certId, "holder", rec.fields.get("holder"), "rollNo", rec.fields.get("rollNo"),
                        "holderUsername", rec.holderUsername));
            return Json.map("issued", issued, "errors", r.errors, "block", r.block, "mineMillis", r.mineMillis);
        });
        post("/api/issuer/revoke", ISSUER, c -> {
            limit(writeLimit, c.ex, "write");
            CertificateRecord r = certs.revoke(c.user, c.s("certId"), c.s("reason"));
            return certView(r, c.user);
        });

        // ---------- registrar
        get("/api/admin/institutions", REGISTRAR, c -> institutions());
        post("/api/admin/institutions", REGISTRAR, c -> {
            Issuer i = certs.registerIssuer(c.s("code"), c.s("name"), c.s("city"));
            return Json.map("ok", true, "id", i.id, "keyFingerprint", Crypto.keyFingerprint(i.publicKey));
        });
        post("/api/admin/institutions/([A-Z0-9]{2,8})/suspend", REGISTRAR, c -> {
            certs.suspendIssuer(c.p(1), c.s("reason"));
            return Json.map("ok", true);
        });
        get("/api/admin/network", REGISTRAR, c -> networkView());
        post("/api/admin/tamper", REGISTRAR, c -> {
            limit(heavyLimit, c.ex, "tamper");
            LabService.Attack a;
            try { a = LabService.Attack.valueOf(c.s("attack")); } catch (IllegalArgumentException e) { throw new Http.Error(400, "Choose an attack"); }
            String msg = lab.attack(c.s("node"), a, c.s("certId"), c.s("field"), limitText(c.s("value"), 120));
            Map<String, Object> out = networkView();
            out.put("message", msg);
            if (a != LabService.Attack.DELETE_REVOCATION) out.put("forgedFile", lab.forgedFile(c.s("certId"), c.s("field"), c.s("value")));
            return out;
        });
        post("/api/admin/repair", REGISTRAR, c -> {
            network.repair(c.s("node"));
            Map<String, Object> out = networkView();
            out.put("message", "Node " + c.s("node") + " now holds a fresh copy of the majority chain.");
            return out;
        });
        post("/api/admin/undo", REGISTRAR, c -> {
            network.undoAllAttacks();
            Map<String, Object> out = networkView();
            out.put("message", "All three nodes are back to the chain the network itself committed.");
            return out;
        });
        post("/api/admin/reload", REGISTRAR, c -> {
            network.reloadFromDisk();
            Map<String, Object> out = networkView();
            out.put("message", "Re-read the three node files from the data/nodes folder.");
            return out;
        });
        post("/api/admin/bench", REGISTRAR, c -> {
            limit(heavyLimit, c.ex, "bench");
            return bench.run(Json.num(c.body, "seed", Clock.now() % 100000));
        });
        get("/api/admin/users", REGISTRAR, c -> {
            List<Object> out = new ArrayList<>();
            for (User u : store.users.values()) {
                Map<String, Object> m = u.publicView();
                m.put("locked", u.lockedUntil > Clock.now());
                m.put("certificates", store.certificates.values().stream().filter(r -> r.holderUsername.equals(u.username)).count());
                out.add(m);
            }
            return Json.map("users", out, "activeSessions", auth.activeSessions());
        });
        post("/api/admin/users", REGISTRAR, c -> {
            Role role;
            try { role = Role.valueOf(c.s("role")); } catch (IllegalArgumentException e) { throw new Http.Error(400, "Choose a role"); }
            User u = auth.createUser(c.s("username"), c.s("displayName"), role, c.s("issuerId"), c.s("password"));
            return u.publicView();
        });
        get("/api/admin/activity", REGISTRAR, c -> activity(null));
    }

    // ------------------------------------------------------------------ views

    private Map<String, Object> me(User u) {
        Map<String, Object> m = u.publicView();
        Issuer i = store.issuers.get(u.issuerId);
        if (i != null) m.put("issuerName", i.name);
        return Json.map("user", m);
    }

    private Map<String, Object> publicInfo() {
        Network.Consensus c = network.consensus();
        List<Object> inst = new ArrayList<>();
        if (c.state != null) {
            for (ChainState.IssuerEntry e : c.state.issuers.values()) {
                Issuer i = store.issuers.get(e.id);
                inst.add(Json.map("id", e.id, "name", e.name, "city", i == null ? "" : i.city,
                        "keyFingerprint", Crypto.keyFingerprint(e.publicKey), "approvedInBlock", e.registeredBlock,
                        "suspended", e.suspendedAt != 0, "suspendReason", e.suspendReason));
            }
        }
        long revoked = c.state == null ? 0 : c.state.certs.values().stream().filter(x -> x.revokeTx != null).count();
        return Json.map("network", c.state == null ? "" : c.state.networkName, "genesisHash", network.genesisHash,
                "height", c.state == null ? -1 : c.state.height, "consensus", c.ok, "consensusMessage", c.message,
                "agreeing", c.agreeing, "nodes", network.nodes.size(), "difficulty", network.difficulty,
                "certificates", c.state == null ? 0 : c.state.certs.size(), "revoked", revoked,
                "institutions", inst, "fields", fieldList(), "publicUrl", publicUrl, "today", Clock.today().toString());
    }

    private static List<Object> fieldList() {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < CertFields.NAMES.length; i++)
            out.add(Json.map("name", CertFields.NAMES[i], "label", CertFields.LABELS[i], "max", CertFields.MAX_LENGTH[i]));
        return out;
    }

    private CertificateRecord mayView(User u, String certId) {
        CertificateRecord r = store.certificates.get(certId);
        if (r == null) throw new Http.Error(404, "No such certificate");
        boolean ok = (u.role == Role.STUDENT && u.username.equals(r.holderUsername))
                || (u.role == Role.ISSUER && u.issuerId.equals(r.issuerId));
        if (!ok) throw new Http.Error(404, "No such certificate"); // same answer as "does not exist"
        return r;
    }

    private Map<String, Object> certView(CertificateRecord r, User viewer) {
        Issuer i = store.issuers.get(r.issuerId);
        Network.Consensus c = network.consensus();
        ChainState.CertEntry e = c.state == null ? null : c.state.certs.get(r.certId);
        String url = publicUrl + "/#/v/" + r.certId + "/" + certs.qrToken(r.certId);
        List<Object> links = new ArrayList<>();
        if (viewer.role == Role.STUDENT) {
            for (CertificateRecord.ShareLink s : r.shareLinks) {
                links.add(Json.map("id", s.id, "label", s.label, "createdAt", s.createdAt, "expiresAt", s.expiresAt,
                        "views", s.views, "active", !s.disabled && s.expiresAt > Clock.now()));
            }
        }
        long checks = store.verifications.stream().filter(v -> r.certId.equals(v.certId)).count();
        List<Object> fields = new ArrayList<>();
        for (int k = 0; k < CertFields.NAMES.length; k++)
            fields.add(Json.map("name", CertFields.NAMES[k], "label", CertFields.LABELS[k], "value", r.fields.get(CertFields.NAMES[k])));
        return Json.map("certId", r.certId, "issuerId", r.issuerId, "issuerName", i == null ? "" : i.name,
                "issuerCity", i == null ? "" : i.city, "keyFingerprint", i == null ? "" : Crypto.keyFingerprint(i.publicKey),
                "fields", fields, "values", r.fields, "holderUsername", r.holderUsername, "issuedAt", r.issuedAt, "issuedBy", r.issuedBy,
                "block", r.block, "txId", r.txId, "root", e == null ? "" : e.issueTx.get("root"),
                "confirmations", e == null || c.state == null ? 0 : c.state.height - e.block,
                "onChain", e != null, "revoked", r.revoked, "revokeReason", r.revokeReason, "revokedAt", r.revokedAt,
                "verifyUrl", url, "qrSvg", QrCode.encode(url).toSvg(2, "#1d1a2e"), "shareLinks", links, "timesChecked", checks);
    }

    private Block lastBlock() {
        Network.Consensus c = network.consensus();
        List<Block> chain = c.ok ? c.leader.chain : network.node("A").chain;
        return chain.get(chain.size() - 1);
    }

    private Map<String, Object> issuerOverview(User u) {
        Issuer me = store.issuers.get(u.issuerId);
        Network.Consensus c = network.consensus();
        ChainState.IssuerEntry e = c.state == null ? null : c.state.issuers.get(u.issuerId);
        List<Object> list = new ArrayList<>();
        int revoked = 0;
        for (CertificateRecord r : sortedNewest(store.certificates.values())) {
            if (!r.issuerId.equals(u.issuerId)) continue;
            if (r.revoked) revoked++;
            list.add(Json.map("certId", r.certId, "holder", r.fields.get("holder"), "rollNo", r.fields.get("rollNo"),
                    "programme", r.fields.get("programme"), "result", r.fields.get("result"), "issueDate", r.fields.get("issueDate"),
                    "holderUsername", r.holderUsername, "issuedAt", r.issuedAt, "block", r.block, "revoked", r.revoked,
                    "revokeReason", r.revokeReason, "batchId", r.batchId));
        }
        List<Object> students = new ArrayList<>();
        for (User s : store.users.values()) if (s.role == Role.STUDENT) students.add(Json.map("username", s.username, "displayName", s.displayName));
        Map<String, Object> out = Json.map("issuer", Json.map("id", me.id, "name", me.name, "city", me.city,
                        "keyFingerprint", Crypto.keyFingerprint(me.publicKey),
                        "approvedInBlock", e == null ? -1 : e.registeredBlock,
                        "suspended", e != null && e.suspendedAt != 0, "suspendReason", e == null ? "" : e.suspendReason),
                "certificates", list, "issuedCount", list.size(), "revokedCount", revoked, "students", students,
                "fields", fieldList(), "today", Clock.today().toString());
        out.putAll(activity(u.issuerId));
        return out;
    }

    /** Verification counts for the last 30 days (all institutions, or one). */
    private Map<String, Object> activity(String issuerId) {
        long since = Clock.now() - 30L * 86_400_000L;
        Map<String, Integer> byVerdict = new LinkedHashMap<>();
        for (String v : List.of("GENUINE", "TAMPERED", "NOT_FOUND", "REVOKED", "UNTRUSTED_ISSUER", "NETWORK_UNSAFE", "BAD_INPUT")) byVerdict.put(v, 0);
        int[] perDay = new int[30];
        int[] forgedPerDay = new int[30];
        List<Object> recent = new ArrayList<>();
        List<VerifyLogEntry> log = new ArrayList<>(store.verifications);
        for (int k = log.size() - 1; k >= 0; k--) {
            VerifyLogEntry v = log.get(k);
            if (issuerId != null && !issuerId.equals(v.issuerId)) continue;
            if (v.time >= since) {
                byVerdict.merge(v.verdict, 1, Integer::sum);
                int day = (int) Math.min(29, (Clock.now() - v.time) / 86_400_000L);
                perDay[29 - day]++;
                if (!v.verdict.equals("GENUINE")) forgedPerDay[29 - day]++;
            }
            if (recent.size() < 25) recent.add(v.toJson());
        }
        List<Integer> pd = new ArrayList<>(), fd = new ArrayList<>();
        for (int i = 0; i < 30; i++) { pd.add(perDay[i]); fd.add(forgedPerDay[i]); }
        return Json.map("checks30", byVerdict, "perDay", pd, "badPerDay", fd, "recentChecks", recent);
    }

    private Map<String, Object> institutions() {
        Network.Consensus c = network.consensus();
        List<Object> out = new ArrayList<>();
        for (Issuer i : store.issuers.values()) {
            ChainState.IssuerEntry e = c.state == null ? null : c.state.issuers.get(i.id);
            long issued = store.certificates.values().stream().filter(r -> r.issuerId.equals(i.id)).count();
            long staff = store.users.values().stream().filter(u -> i.id.equals(u.issuerId)).count();
            out.add(Json.map("id", i.id, "name", i.name, "city", i.city, "keyFingerprint", Crypto.keyFingerprint(i.publicKey),
                    "approvedInBlock", e == null ? -1 : e.registeredBlock, "suspended", e != null && e.suspendedAt != 0,
                    "suspendReason", e == null ? "" : e.suspendReason, "issued", issued, "staff", staff));
        }
        return Json.map("institutions", out);
    }

    private Map<String, Object> ledger(Map<String, String> q) {
        Network.Consensus c = network.consensus();
        Node source = c.ok ? c.leader : network.node("A");
        List<Block> chain = source.chain;
        int limit = (int) Math.max(1, Math.min(50, parseInt(q.get("limit"), 12)));
        int before = (int) Math.min(chain.size(), parseInt(q.get("before"), chain.size()));
        List<Object> blocks = new ArrayList<>();
        for (int i = before - 1; i >= 0 && blocks.size() < limit; i--) blocks.add(blockSummary(chain.get(i)));
        return Json.map("height", chain.size() - 1, "blocks", blocks, "consensus", c.ok, "consensusMessage", c.message,
                "agreeing", c.agreeing, "nodes", nodeStatuses(c), "difficulty", network.difficulty, "genesisHash", network.genesisHash);
    }

    private static long parseInt(String s, long fallback) {
        try { return s == null ? fallback : Long.parseLong(s); } catch (NumberFormatException e) { return fallback; }
    }

    private Map<String, Object> blockSummary(Block b) {
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (Transaction t : b.transactions) kinds.merge(t.type.name(), 1, Integer::sum);
        return Json.map("index", b.index, "time", b.time, "hash", b.hash, "previousHash", b.previousHash,
                "merkleRoot", b.merkleRoot, "nonce", b.nonce, "difficulty", b.difficulty, "sealedBy", b.sealedBy,
                "mineMillis", b.mineMillis, "txCount", b.transactions.size(), "kinds", kinds,
                "signers", b.transactions.stream().map(t -> t.signer).distinct().toList());
    }

    private Map<String, Object> blockDetail(int index) {
        Network.Consensus c = network.consensus();
        Node source = c.ok ? c.leader : network.node("A");
        if (index < 0 || index >= source.chain.size()) throw new Http.Error(404, "No such block");
        Block b = source.chain.get(index);
        Map<String, Object> out = blockSummary(b);
        List<Object> txs = new ArrayList<>();
        int shown = 0;
        for (Transaction t : b.transactions) {
            if (shown++ >= 200) break;
            Map<String, Object> m = Json.map("id", t.id(), "type", t.type.name(), "label", t.type.label, "time", t.time,
                    "signer", t.signer, "signature", t.signature.length() > 24 ? t.signature.substring(0, 24) + "…" : t.signature);
            // only public, non-personal values are shown: IDs, hashes, institution names, reasons
            for (String k : List.of("certId", "issuerId", "name", "root", "reason", "network")) if (t.get(k) != null) m.put(k, t.get(k));
            if (t.get("fieldHashes") != null) m.put("fieldHashes", List.of(t.get("fieldHashes").split(",")));
            if (t.get("publicKey") != null) m.put("keyFingerprint", Crypto.keyFingerprint(t.get("publicKey")));
            if (t.get("registrarKey") != null) m.put("keyFingerprint", Crypto.keyFingerprint(t.get("registrarKey")));
            txs.add(m);
        }
        out.put("transactions", txs);
        out.put("header", b.header());
        out.put("height", source.chain.size() - 1);
        return out;
    }

    private List<Object> nodeStatuses(Network.Consensus c) {
        List<Object> out = new ArrayList<>();
        for (Node n : network.nodes) {
            Network.NodeStatus s = c.status.get(n.id);
            out.add(Json.map("id", n.id, "name", n.name, "keeper", n.keeper, "state", s.state, "detail", s.detail,
                    "height", s.height, "badBlock", s.badBlock, "problemKind", s.problemKind, "tipHash", s.tipHash));
        }
        return out;
    }

    private Map<String, Object> networkView() {
        Network.Consensus c = network.consensus();
        List<Object> nodes = new ArrayList<>();
        for (Node n : network.nodes) {
            Network.NodeStatus s = c.status.get(n.id);
            List<Object> blocks = new ArrayList<>();
            for (Block b : n.chain) {
                String st = "ok";
                if (s.state.equals("BROKEN") && s.badBlock >= 0) st = b.index == s.badBlock ? "bad" : b.index > s.badBlock ? "after" : "ok";
                if (s.state.equals("OUTVOTED") && b.index >= s.badBlock) st = "fork";
                blocks.add(Json.map("index", b.index, "hash", b.hash, "previousHash", b.previousHash, "nonce", b.nonce,
                        "txCount", b.transactions.size(), "state", st));
            }
            Map<String, Object> m = Json.map("id", n.id, "name", n.name, "keeper", n.keeper, "state", s.state,
                    "detail", s.detail, "height", s.height, "badBlock", s.badBlock, "problemKind", s.problemKind,
                    "tipHash", s.tipHash, "blocks", blocks);
            nodes.add(m);
        }
        List<Object> live = new ArrayList<>(), revoked = new ArrayList<>();
        for (CertificateRecord r : sortedNewest(store.certificates.values())) {
            Map<String, Object> m = Json.map("certId", r.certId, "holder", r.fields.get("holder"), "issuerId", r.issuerId,
                    "result", r.fields.get("result"), "values", r.fields);
            (r.revoked ? revoked : live).add(m);
        }
        return Json.map("consensus", c.ok, "consensusMessage", c.message, "agreeing", c.agreeing, "nodes", nodes,
                "difficulty", network.difficulty, "certificates", live.subList(0, Math.min(live.size(), 80)), "revokedCertificates", revoked,
                "fields", fieldList());
    }

    private static List<CertificateRecord> sortedNewest(java.util.Collection<CertificateRecord> all) {
        List<CertificateRecord> l = new ArrayList<>(all);
        l.sort(Comparator.comparingLong((CertificateRecord r) -> r.issuedAt).reversed()
                .thenComparing(r -> r.fields.getOrDefault("holder", "")));
        return l;
    }

    private static String limitText(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
