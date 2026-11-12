package com.cecil.sikho.web;

import com.cecil.sikho.ledger.TimeBank;
import com.cecil.sikho.match.Matcher;
import com.cecil.sikho.match.SwapFinder;
import com.cecil.sikho.match.Trust;
import com.cecil.sikho.model.AuditEntry;
import com.cecil.sikho.model.Circle;
import com.cecil.sikho.model.Exchange;
import com.cecil.sikho.model.LedgerEntry;
import com.cecil.sikho.model.Listing;
import com.cecil.sikho.model.Locality;
import com.cecil.sikho.model.Note;
import com.cecil.sikho.model.Report;
import com.cecil.sikho.model.Skill;
import com.cecil.sikho.model.User;
import com.cecil.sikho.service.AuthService;
import com.cecil.sikho.service.CircleService;
import com.cecil.sikho.service.ExchangeService;
import com.cecil.sikho.service.InsightService;
import com.cecil.sikho.service.ListingService;
import com.cecil.sikho.service.ModerationService;
import com.cecil.sikho.service.Problem;
import com.cecil.sikho.service.Views;
import com.cecil.sikho.store.DataStore;
import com.cecil.sikho.util.Clock;
import com.cecil.sikho.util.Json;
import com.cecil.sikho.util.Text;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The web server:
 *   /             the website (files from the frontend folder)
 *   /api/...      the JSON API (needs sign-in; every change needs the X-Sikho header = CSRF protection)
 *
 * Mistakes the person can fix (Problem) become friendly messages; anything else becomes "Something went wrong"
 * (the details go to the console, never to the browser).
 */
public class ApiServer {
    public static final String COOKIE = "sikho_session";
    public static final String CSRF_HEADER = "X-Sikho";

    /** All the parts the server talks to. */
    public record Parts(DataStore store, TimeBank bank, AuthService auth, ListingService listings, ExchangeService exchanges,
                        CircleService circles, ModerationService moderation, InsightService insights, Matcher matcher,
                        SwapFinder swaps, Views views) { }

    private final Path frontend;
    private final Parts p;
    private final DataStore store;
    private final List<Route> routes = new ArrayList<>();
    private final Map<String, Deque<Long>> tries = new ConcurrentHashMap<>();
    private HttpServer server;
    private java.util.concurrent.ExecutorService pool;

    /** What a route gets: the request, the signed-in member and the {id} from the path. */
    public static final class Ctx {
        public final HttpExchange ex;
        public final User user;
        public final String id;
        final String rawBody;

        Ctx(HttpExchange ex, User user, String id, String rawBody) {
            this.ex = ex; this.user = user; this.id = id; this.rawBody = rawBody;
        }

        public Map<String, Object> body() {
            if (rawBody == null || rawBody.isBlank()) return new LinkedHashMap<>();
            try { return Json.parseObject(rawBody); } catch (IllegalArgumentException e) { throw Problem.bad("The request was not valid JSON."); }
        }

        public Map<String, String> query() { return Http.query(ex); }
    }

    @FunctionalInterface
    interface Handler { Object handle(Ctx c) throws IOException; }

    record Route(String method, Pattern path, boolean needsLogin, Handler handler) { }

    public ApiServer(Path frontend, Parts parts) {
        this.frontend = frontend;
        this.p = parts;
        this.store = parts.store();
        defineRoutes();
    }

    // ------------------------------------------------------------------ routes

    private void get(String path, Handler h) { routes.add(new Route("GET", Pattern.compile(path), true, h)); }
    private void post(String path, Handler h) { routes.add(new Route("POST", Pattern.compile(path), true, h)); }
    private void put(String path, Handler h) { routes.add(new Route("PUT", Pattern.compile(path), true, h)); }

    private static final String ID = "([A-Za-z0-9-]{1,40})";

    private void defineRoutes() {
        // signing in, joining
        routes.add(new Route("POST", Pattern.compile("/api/login"), false, this::login));
        routes.add(new Route("POST", Pattern.compile("/api/join"), false, this::join));
        routes.add(new Route("POST", Pattern.compile("/api/logout"), false, c -> {
            p.auth().logout(Http.cookie(c.ex, COOKIE));
            c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            return Json.obj("ok", true);
        }));
        routes.add(new Route("GET", Pattern.compile("/api/me"), false, c -> Json.obj(
                "user", c.user == null ? null : p.insights().me(c.user), "meta", meta())));
        put("/api/me/profile", c -> { p.auth().updateProfile(c.user, c.body()); return p.insights().me(c.user); });
        post("/api/me/password", c -> {
            Map<String, Object> b = c.body();
            p.auth().changeOwnPassword(c.user, Json.str(b, "current"), Json.str(b, "next"));
            return Json.obj("ok", true);
        });

        // home, search, cards
        get("/api/home", c -> p.insights().home(c.user));
        get("/api/search", this::search);
        get("/api/listings/mine", c -> mine(c.user));
        post("/api/listings", c -> listing(c.user, p.listings().create(c.user, c.body())));
        get("/api/listings/" + ID, c -> listingDetail(c.user, c.id));
        put("/api/listings/" + ID, c -> listing(c.user, p.listings().update(c.user, c.id, c.body())));
        post("/api/listings/" + ID + "/delete", c -> Json.obj("result", p.listings().delete(c.user, c.id)));
        post("/api/listings/" + ID + "/request", c -> exchange(c.user, p.exchanges().request(c.user, c.id, c.body())));

        // exchanges
        get("/api/exchanges", c -> exchanges(c.user, c.query().getOrDefault("tab", "active")));
        get("/api/exchanges/" + ID, c -> exchange(c.user, p.exchanges().visible(c.user, c.id)));
        post("/api/exchanges/" + ID + "/accept", c -> exchange(c.user, p.exchanges().accept(c.user, c.id)));
        post("/api/exchanges/" + ID + "/decline", c -> exchange(c.user, p.exchanges().decline(c.user, c.id, Json.str(c.body(), "reason"))));
        post("/api/exchanges/" + ID + "/cancel", c -> exchange(c.user, p.exchanges().cancel(c.user, c.id, Json.str(c.body(), "reason"))));
        post("/api/exchanges/" + ID + "/message", c -> exchange(c.user, p.exchanges().message(c.user, c.id, Json.str(c.body(), "text"))));
        post("/api/exchanges/" + ID + "/done", c -> exchange(c.user, p.exchanges().done(c.user, c.id)));
        post("/api/exchanges/" + ID + "/dispute", c -> exchange(c.user, p.exchanges().dispute(c.user, c.id, Json.str(c.body(), "text"))));
        post("/api/exchanges/" + ID + "/review", c -> {
            p.exchanges().review(c.user, c.id, c.body());
            return exchange(c.user, p.exchanges().visible(c.user, c.id));
        });
        post("/api/exchanges/" + ID + "/resolve", c -> {
            Map<String, Object> b = c.body();
            return exchange(c.user, p.exchanges().resolve(c.user, c.id, Json.str(b, "result"), Json.str(b, "note")));
        });

        // members
        get("/api/members", c -> findMembers(c.user, c.query().getOrDefault("q", "")));
        get("/api/members/" + ID, c -> p.insights().profile(c.user, c.id));
        post("/api/members/" + ID + "/block", c -> {
            p.auth().block(c.user, c.id, Json.bool(c.body(), "block", true));
            return p.insights().profile(c.user, c.id);
        });
        post("/api/reports", c -> p.moderation().report(c.user, c.body()).toJson());

        // swaps, circles, wallet, map, community, notifications
        get("/api/swaps", c -> p.swaps().find(c.user.id));
        get("/api/circles", c -> circles(c.user));
        post("/api/circles", c -> circle(c.user, p.circles().create(c.user, c.body())));
        post("/api/circles/" + ID + "/join", c -> circle(c.user, p.circles().join(c.user, c.id)));
        post("/api/circles/" + ID + "/leave", c -> circle(c.user, p.circles().leave(c.user, c.id)));
        post("/api/circles/" + ID + "/cancel", c -> circle(c.user, p.circles().cancel(c.user, c.id, Json.str(c.body(), "reason"))));
        post("/api/circles/" + ID + "/done", c -> circle(c.user, p.circles().complete(c.user, c.id)));
        get("/api/wallet", c -> p.insights().wallet(c.user));
        post("/api/wallet/gift", c -> {
            Map<String, Object> b = c.body();
            User to;
            synchronized (store) { to = store.users.get(Json.str(b, "to")); }
            p.bank().gift(c.user, to, (int) Json.num(b, "amount", 0), Text.clean(Json.str(b, "note"), 120));
            return p.insights().wallet(c.user);
        });
        get("/api/map", c -> p.insights().map(c.user, c.query().get("category")));
        get("/api/community", c -> p.insights().community(c.user));
        get("/api/notes", c -> notes(c.user));
        post("/api/notes/read", c -> {
            synchronized (store) {
                for (Note n : store.notes.values()) if (c.user.id.equals(n.userId)) n.read = true;
                store.save("notes");
            }
            return Json.obj("ok", true);
        });

        // moderators
        get("/api/mod", c -> modOverview(c.user));
        post("/api/reports/" + ID + "/resolve", c -> {
            Map<String, Object> b = c.body();
            return p.moderation().resolve(c.user, c.id, Json.str(b, "action"), Json.str(b, "note")).toJson();
        });
        get("/api/mod/members", c -> modMembers(c.user, c.query().getOrDefault("q", "")));
        post("/api/members/" + ID + "/suspend", c -> { p.moderation().suspend(c.user, c.id, Json.str(c.body(), "reason")); return p.insights().profile(c.user, c.id); });
        post("/api/members/" + ID + "/reinstate", c -> { p.moderation().reinstate(c.user, c.id); return p.insights().profile(c.user, c.id); });
        post("/api/members/" + ID + "/role", c -> { p.moderation().setRole(c.user, c.id, Json.str(c.body(), "role")); return p.insights().profile(c.user, c.id); });
        post("/api/members/" + ID + "/adjust", c -> {
            Map<String, Object> b = c.body();
            return p.moderation().adjust(c.user, c.id, (int) Json.num(b, "amount", 0), Json.str(b, "reason"));
        });
        get("/api/ledger", c -> ledger(c.user));
        post("/api/skills", c -> p.moderation().addSkill(c.user, c.body()).toJson());
        get("/api/audit", c -> audit(c.user, c.query().getOrDefault("q", "")));
    }

    // ------------------------------------------------------------------ handlers

    /** Lists for forms (localities, skills ...), so the browser does not hard-code them. Safe to show anyone. */
    private Map<String, Object> meta() {
        synchronized (store) {
            List<Object> locs = new ArrayList<>(), skills = new ArrayList<>();
            for (Locality l : store.localities.values()) locs.add(Json.obj("id", l.id, "name", l.name));
            TreeSet<String> cats = new TreeSet<>();
            List<Skill> sorted = new ArrayList<>(store.skills.values());
            sorted.sort(Comparator.comparing((Skill s) -> s.category).thenComparing(s -> s.name));
            for (Skill s : sorted) { skills.add(Json.obj("id", s.id, "name", s.name, "category", s.category)); cats.add(s.category); }
            return Json.obj("localities", locs, "skills", skills, "categories", new ArrayList<>(cats),
                    "reviewTags", new TreeSet<>(ExchangeService.REVIEW_TAGS), "reasons", new TreeSet<>(ModerationService.REASONS),
                    "today", Clock.today().toString(), "now", Clock.millis(), "welcome", TimeBank.WELCOME,
                    "city", "Ranchi", "maxHours", ExchangeService.MAX_HOURS);
        }
    }

    private Object search(Ctx c) {
        Map<String, String> q = c.query();
        Listing.Type type = Listing.parse(Listing.Type.class, q.get("type"), Listing.Type.OFFER);
        String skillId = q.get("skillId");
        if (skillId != null && skillId.isBlank()) skillId = null;
        String text = Text.clean(q.get("q"), 80);
        Listing.Level level = Listing.parse(Listing.Level.class, q.get("level"), null);
        double km = 0;
        try { km = Double.parseDouble(q.getOrDefault("km", "0")); } catch (NumberFormatException ignored) { }
        km = Math.max(0, Math.min(25, km));
        Listing.Mode mode = Listing.parse(Listing.Mode.class, q.get("mode"), null);
        Matcher.Want want = skillId == null && text == null && level == null ? null : new Matcher.Want(skillId, text, level);
        synchronized (store) {
            Matcher.Found f = p.matcher().search(c.user, type, want, q.get("category"), km, mode, 60);
            Trust trust = new Trust(store);
            List<Object> out = new ArrayList<>();
            for (Matcher.Result r : f.results()) out.add(p.views().result(r, c.user, trust));
            return Json.obj("results", out, "corrected", f.corrected(), "km", km > 0 ? km : c.user.maxKm);
        }
    }

    private Map<String, Object> listing(User u, Listing l) {
        synchronized (store) { return p.views().listing(l, u, new Trust(store)); }
    }

    private Map<String, Object> listingDetail(User u, String id) {
        Listing l = p.listings().get(id);
        synchronized (store) {
            Trust trust = new Trust(store);
            Map<String, Object> m = p.views().listing(l, u, trust);
            User owner = store.users.get(l.userId);
            if (!owner.id.equals(u.id)) {
                Matcher.Result r = p.matcher().score(u, owner, l, new Matcher.Want(l.skillId, null, null), 0,
                        p.matcher().km(u, owner), trust);
                m.put("score", r.score());
                m.put("parts", r.parts());
                m.put("sharedSlots", r.sharedSlots());
            }
            m.put("ownerAvailability", owner.availability);
            m.put("open", p.listings().openOn(l.id));
            List<Object> similar = new ArrayList<>();
            for (Matcher.Result r : p.matcher().search(u, l.type, new Matcher.Want(l.skillId, null, null), null, 0, null, 6).results())
                if (!r.listing().id.equals(l.id) && similar.size() < 3) similar.add(p.views().result(r, u, trust));
            m.put("similar", similar);
            List<Object> mineHere = new ArrayList<>();
            for (Exchange e : store.exchanges.values())
                if (l.id.equals(e.listingId) && e.involves(u.id) && e.isOpen()) mineHere.add(Json.obj("id", e.id, "status", e.status, "at", e.at));
            m.put("myOpen", mineHere);
            m.put("balance", p.bank().balance(u.id));
            return m;
        }
    }

    private Map<String, Object> mine(User u) {
        synchronized (store) {
            Trust trust = new Trust(store);
            List<Object> offers = new ArrayList<>(), wants = new ArrayList<>();
            for (Listing l : store.listings.values()) {
                if (!l.userId.equals(u.id)) continue;
                Map<String, Object> m = p.views().listing(l, u, trust);
                m.put("open", p.listings().openOn(l.id));
                (l.type == Listing.Type.OFFER ? offers : wants).add(m);
            }
            return Json.obj("offers", offers, "wants", wants, "max", ListingService.MAX_ACTIVE);
        }
    }

    private Map<String, Object> exchange(User u, Exchange e) {
        synchronized (store) { return p.views().exchange(e, u, new Trust(store), true); }
    }

    private Map<String, Object> exchanges(User u, String tab) {
        synchronized (store) {
            Trust trust = new Trust(store);
            long now = Clock.millis();
            List<Exchange> list = new ArrayList<>();
            int active = 0, requests = 0, past = 0;
            for (Exchange e : store.exchanges.values()) {
                if (!e.involves(u.id)) continue;
                boolean isReq = e.status == Exchange.Status.REQUESTED;
                boolean isActive = e.status == Exchange.Status.ACCEPTED || e.status == Exchange.Status.DISPUTED;
                if (isReq) requests++; else if (isActive) active++; else past++;
                if (("requests".equals(tab) && isReq) || ("active".equals(tab) && isActive) || ("past".equals(tab) && !isReq && !isActive)) list.add(e);
            }
            if ("past".equals(tab)) list.sort(Comparator.comparingLong((Exchange e) -> e.closedAt == 0 ? e.updatedAt : e.closedAt).reversed());
            else list.sort(Comparator.comparingLong(e -> e.at));
            List<Object> out = new ArrayList<>();
            for (Exchange e : list) {
                Map<String, Object> m = p.views().exchange(e, u, trust, false);
                m.put("needsMe", ExchangeService.actionsFor(e, u, now).stream().anyMatch(a -> a.equals("accept") || a.equals("done") || a.equals("review")));
                Object last = e.messages.isEmpty() ? null : e.messages.get(e.messages.size() - 1).text;
                m.put("last", last);
                out.add(m);
            }
            return Json.obj("tab", tab, "items", out, "counts", Json.obj("active", active, "requests", requests, "past", past));
        }
    }

    private Map<String, Object> circles(User u) {
        synchronized (store) {
            Trust trust = new Trust(store);
            List<Circle> all = new ArrayList<>(store.circles.values());
            all.sort(Comparator.comparingLong(c -> c.at));
            List<Object> upcoming = new ArrayList<>(), past = new ArrayList<>();
            long now = Clock.millis();
            for (Circle c : all) {
                User host = store.users.get(c.hostId);
                if (host == null || Matcher.blocked(u, host)) continue;
                boolean mineOrJoined = c.hostId.equals(u.id) || c.attendees.contains(u.id);
                if (c.status == Circle.Status.OPEN) upcoming.add(p.views().circle(c, u, trust));
                else if (mineOrJoined || now - c.closedAt < 30L * 86_400_000L) past.add(0, p.views().circle(c, u, trust));
            }
            return Json.obj("upcoming", upcoming, "past", past.size() > 12 ? past.subList(0, 12) : past, "balance", p.bank().balance(u.id));
        }
    }

    private Map<String, Object> circle(User u, Circle c) {
        synchronized (store) { return p.views().circle(c, u, new Trust(store)); }
    }

    private Map<String, Object> notes(User u) {
        synchronized (store) {
            List<Note> ns = new ArrayList<>();
            for (Note n : store.notes.values()) if (u.id.equals(n.userId)) ns.add(n);
            List<Object> out = new ArrayList<>();
            for (int i = ns.size() - 1; i >= 0; i--) out.add(ns.get(i).toJson());
            return Json.obj("notes", out);
        }
    }

    private Map<String, Object> findMembers(User u, String q) {
        String t = q.trim().toLowerCase();
        synchronized (store) {
            List<Object> out = new ArrayList<>();
            if (t.length() < 2) return Json.obj("members", out);
            for (User x : store.users.values()) {
                if (x.id.equals(u.id) || !store.canAct(x) || Matcher.blocked(u, x)) continue;
                if (x.name.toLowerCase().contains(t) && out.size() < 10)
                    out.add(Json.obj("id", x.id, "name", x.name, "initials", x.initials(), "locality", store.localityName(x.localityId)));
            }
            return Json.obj("members", out);
        }
    }

    private static void mod(User u) {
        if (!u.isModerator()) throw Problem.forbidden("This page is for moderators.");
    }

    private Map<String, Object> modOverview(User u) {
        mod(u);
        synchronized (store) {
            Trust trust = new Trust(store);
            List<Object> open = new ArrayList<>(), closed = new ArrayList<>(), disputes = new ArrayList<>();
            List<Report> rs = new ArrayList<>(store.reports.values());
            rs.sort(Comparator.comparingLong((Report r) -> r.createdAt).reversed());
            for (Report r : rs) {
                Map<String, Object> m = r.toJson();
                m.put("reporter", store.userName(r.reporterId));
                m.put("target", store.userName(r.targetId));
                User t = store.users.get(r.targetId);
                m.put("targetWarnings", t == null ? 0 : t.warnings);
                m.put("targetSuspended", t != null && t.suspended);
                m.put("resolver", r.resolvedBy == null ? null : store.userName(r.resolvedBy));
                long others = store.reports.values().stream().filter(x -> x.targetId.equals(r.targetId)).count() - 1;
                m.put("otherReports", others);
                if ("OPEN".equals(r.status)) open.add(m); else if (closed.size() < 15) closed.add(m);
            }
            for (Exchange e : store.exchanges.values()) if (e.status == Exchange.Status.DISPUTED) disputes.add(p.views().exchange(e, u, trust, true));
            int moneyFlags = 0;
            for (AuditEntry a : store.audit) if ("safety.money".equals(a.action) && Clock.millis() - a.at < 30L * 86_400_000L) moneyFlags++;
            long paused = store.users.values().stream().filter(x -> x.suspended).count();
            return Json.obj("open", open, "closed", closed, "disputes", disputes,
                    "stats", Json.obj("openReports", open.size(), "disputes", disputes.size(), "moneyFlags", moneyFlags, "paused", paused));
        }
    }

    private Map<String, Object> modMembers(User u, String q) {
        mod(u);
        String t = q.trim().toLowerCase();
        synchronized (store) {
            Trust trust = new Trust(store);
            Map<String, Integer> reports = new HashMap<>();
            for (Report r : store.reports.values()) reports.merge(r.targetId, 1, Integer::sum);
            List<Object> out = new ArrayList<>();
            for (User x : store.users.values()) {
                if (!t.isEmpty() && !(x.name.toLowerCase().contains(t) || x.username.contains(t) || store.localityName(x.localityId).toLowerCase().contains(t))) continue;
                Trust.Score s = trust.of(x.id);
                out.add(Json.obj("id", x.id, "name", x.name, "username", x.username, "initials", x.initials(), "role", x.role,
                        "locality", store.localityName(x.localityId), "balance", p.bank().balance(x.id),
                        "rating", Math.round(s.rating() * 10) / 10.0, "reviews", s.reviews(), "reliability", Math.round(s.reliability() * 100),
                        "completed", s.completed(), "warnings", x.warnings, "reports", reports.getOrDefault(x.id, 0),
                        "suspended", x.suspended, "joined", x.createdAt));
            }
            return Json.obj("members", out);
        }
    }

    private Map<String, Object> ledger(User u) {
        mod(u);
        Map<String, Integer> expected = new HashMap<>();
        p.exchanges().expectedEscrow(expected);
        p.circles().expectedEscrow(expected);
        synchronized (store) {
            Map<String, Object> v = p.bank().verify(expected);
            List<Object> lines = new ArrayList<>();
            for (int i = store.ledger.size() - 1; i >= 0 && lines.size() < 200; i--) {
                LedgerEntry e = store.ledger.get(i);
                Map<String, Object> m = e.toJson();
                m.put("fromName", store.userName(e.from));
                m.put("toName", store.userName(e.to));
                lines.add(m);
            }
            v.put("entries", lines);
            Map<String, Integer> kinds = new LinkedHashMap<>();
            for (LedgerEntry e : store.ledger) kinds.merge(e.kind, e.amount, Integer::sum);
            v.put("byKind", kinds);
            store.log(u.id, "ledger.verify", "ledger", Boolean.TRUE.equals(v.get("ok")) ? "ok" : ((List<?>) v.get("problems")).size() + " problem(s)");
            store.save("audit");
            return v;
        }
    }

    private Map<String, Object> audit(User u, String q) {
        mod(u);
        String t = q.toLowerCase();
        synchronized (store) {
            List<Object> out = new ArrayList<>();
            for (int i = store.audit.size() - 1; i >= 0 && out.size() < 300; i--) {
                AuditEntry a = store.audit.get(i);
                String who = a.userId == null ? "system" : store.userName(a.userId);
                String line = (who + " " + a.action + " " + a.target + " " + (a.detail == null ? "" : a.detail)).toLowerCase();
                if (!t.isEmpty() && !line.contains(t)) continue;
                Map<String, Object> m = a.toJson();
                m.put("who", who);
                out.add(m);
            }
            return Json.obj("entries", out);
        }
    }

    // ------------------------------------------------------------------ sign in and join (rate limited)

    /** At most `max` tries per `windowMs` from one computer. */
    private void limit(HttpExchange ex, String what, int max, long windowMs, String message) {
        String key = what + ":" + Http.clientIp(ex);
        long now = Clock.millis();
        Deque<Long> d = tries.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) {
            while (!d.isEmpty() && now - d.peekFirst() > windowMs) d.pollFirst();
            if (d.size() >= max) throw new Problem(429, message);
            d.addLast(now);
        }
    }

    private Object login(Ctx c) {
        limit(c.ex, "login", 10, 60_000, "Too many sign-in attempts from this computer. Wait a minute.");
        Map<String, Object> b = c.body();
        String token = p.auth().login(Json.str(b, "username"), Json.str(b, "password"));
        c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200");
        User u = p.auth().userFor(token);
        return Json.obj("user", p.insights().me(u), "meta", meta());
    }

    private Object join(Ctx c) {
        limit(c.ex, "join", 5, 3_600_000, "Too many new accounts from this computer. Try again in an hour.");
        User u = p.auth().register(c.body());
        String token = p.auth().login(u.username, Json.str(c.body(), "password"));
        c.ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200");
        return Json.obj("user", p.insights().me(u), "meta", meta());
    }

    // ------------------------------------------------------------------ the server

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
            Http.securityHeaders(ex);
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) api(ex, path);
            else staticFile(ex, path);
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e);
            e.printStackTrace();
            try { Http.error(ex, 500, "Something went wrong on the server.", null); } catch (Exception ignored) { }
        } finally {
            ex.close();
        }
    }

    private void api(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod();
        try {
            Route route = null;
            java.util.regex.Matcher found = null;
            boolean pathKnown = false;
            for (Route r : routes) {
                java.util.regex.Matcher m = r.path().matcher(path);
                if (!m.matches()) continue;
                pathKnown = true;
                if (r.method().equals(method) || ("HEAD".equals(method) && "GET".equals(r.method()))) { route = r; found = m; break; }
            }
            if (route == null) {
                Http.error(ex, pathKnown ? 405 : 404, pathKnown ? "Method not allowed." : "No such API.", null);
                return;
            }
            boolean changes = !"GET".equals(method) && !"HEAD".equals(method);
            if (changes) {
                // CSRF protection: a page on another website cannot send this header without our permission,
                // and if the browser says where the request came from it must be this site.
                if (!"1".equals(ex.getRequestHeaders().getFirst(CSRF_HEADER))) {
                    Http.error(ex, 403, "Request blocked (missing app header).", null);
                    return;
                }
                String origin = ex.getRequestHeaders().getFirst("Origin");
                String host = ex.getRequestHeaders().getFirst("Host");
                if (origin != null && !origin.equals("null") && host != null
                        && !origin.equals("http://" + host) && !origin.equals("https://" + host)) {
                    Http.error(ex, 403, "Request blocked (different website).", null);
                    return;
                }
                String type = ex.getRequestHeaders().getFirst("Content-Type");
                if (type == null || !type.toLowerCase().startsWith("application/json")) {
                    Http.error(ex, 415, "Send JSON.", null);
                    return;
                }
            }
            User user = p.auth().userFor(Http.cookie(ex, COOKIE));
            if (route.needsLogin() && user == null) {
                Http.error(ex, 401, "Please sign in.", null);
                return;
            }
            String body = changes ? Http.body(ex) : null;
            String id = found.groupCount() >= 1 ? found.group(1) : null;
            Object result = route.handler().handle(new Ctx(ex, user, id, body));
            Http.json(ex, 200, result);
        } catch (Problem pr) {
            Http.error(ex, pr.status, pr.getMessage(), pr.data);
        } catch (Http.TooBig t) {
            Http.error(ex, 413, "That is too much text in one go.", null);
        }
    }

    private void staticFile(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            Http.send(ex, 405, new byte[0]);
            return;
        }
        String rel;
        if (path.equals("/") || path.equals("/index.html")) rel = "index.html";
        else if (path.startsWith("/static/")) rel = path.substring("/static/".length());
        else if (path.equals("/favicon.ico")) rel = "favicon.svg";
        else { notFound(ex); return; }
        // only plain file names inside the frontend folder: no "..", no sub-folders, no hidden files
        if (!rel.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || rel.contains("..")) { notFound(ex); return; }
        Path file = frontend.resolve(rel).normalize();
        if (!file.startsWith(frontend) || !Files.isRegularFile(file)) { notFound(ex); return; }
        ex.getResponseHeaders().set("Content-Type", Http.contentType(rel));
        ex.getResponseHeaders().set("Cache-Control", rel.equals("index.html") ? "no-cache" : "max-age=300");
        Http.send(ex, 200, Files.readAllBytes(file));
    }

    private static void notFound(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        Http.send(ex, 404, "Not found".getBytes(StandardCharsets.UTF_8));
    }
}
