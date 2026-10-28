package com.cecil.edge;

import com.cecil.edge.edge.EdgeEngine;
import com.cecil.edge.edge.ModeLogic;
import com.cecil.edge.edge.RollingStats;
import com.cecil.edge.model.Alert;
import com.cecil.edge.model.EdgeNode;
import com.cecil.edge.model.Enums.AlertStatus;
import com.cecil.edge.model.Enums.Condition;
import com.cecil.edge.model.Enums.FaultType;
import com.cecil.edge.model.Enums.NodeMode;
import com.cecil.edge.model.Enums.Role;
import com.cecil.edge.model.Enums.SensorType;
import com.cecil.edge.model.Enums.Severity;
import com.cecil.edge.model.Rule;
import com.cecil.edge.model.Sensor;
import com.cecil.edge.model.Site;
import com.cecil.edge.model.User;
import com.cecil.edge.service.Hub;
import com.cecil.edge.service.ServiceException;
import com.cecil.edge.store.DataStore;
import com.cecil.edge.store.SeedData;
import com.cecil.edge.util.Csv;
import com.cecil.edge.util.Json;
import com.cecil.edge.util.Passwords;
import com.cecil.edge.web.ApiServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * All backend tests in one file, with a tiny test runner (no JUnit download needed).
 * Run with run-tests.bat / run-tests.sh, or `mvn test`.
 */
public final class AllTests {
    private static int passed;
    private static final List<String> failed = new ArrayList<>();

    interface T { void run() throws Exception; }

    public static void main(String[] args) {
        test("JSON: write and read back, escaping, bad input", AllTests::json);
        test("JSON: model objects through reflection", AllTests::jsonModels);
        test("Passwords: PBKDF2 hash, salt, tokens, node keys", AllTests::passwords);
        test("CSV: formula injection is blocked", AllTests::csv);
        test("Mode RAW: every reading is sent", AllTests::modeRaw);
        test("Mode FILTER: dead-band drops small changes", AllTests::modeFilter);
        test("Mode AGGREGATE: one correct summary per window", AllTests::modeAggregate);
        test("Mode SMART: summaries + big jumps + urgent readings", AllTests::modeSmart);
        test("Rolling stats: mean, std and z-score", AllTests::rolling);
        test("Pipeline: impossible values are dropped at the edge", AllTests::invalidDropped);
        test("Rule ABOVE with 'must last' time", AllTests::ruleAboveDuration);
        test("Alerts: one per rule+sensor, cleared, repeats", AllTests::alertDedupe);
        test("Rule RATE_OF_CHANGE uses the reading a minute ago", AllTests::ruleRate);
        test("Rule ANOMALY uses the z-score", AllTests::ruleAnomaly);
        test("Store and forward: buffer, flush, lost when full", AllTests::storeAndForward);
        test("Health: offline after heartbeat timeout, back online", AllTests::offline);
        test("Rule NO_DATA fires for a silent sensor", AllTests::noData);
        test("Mode comparison: edge modes send less than RAW", AllTests::compare);
        test("Rule dry run (test button)", AllTests::ruleTest);
        test("Sign in: wrong password, lock-out, sessions", AllTests::auth);
        test("Permissions: viewer cannot change things", AllTests::permissions);
        test("Rules: input checks", AllTests::ruleValidation);
        test("Ingest API: key, readings, commands", AllTests::ingest);
        test("Data store: save and load again", AllTests::storeRoundTrip);
        test("Demo data and warm-up", AllTests::seed);
        test("Simulator: injected faults (spike, node down)", AllTests::simulatorFaults);
        test("HTTP API end to end", AllTests::http);

        System.out.println();
        System.out.println(passed + " passed, " + failed.size() + " failed");
        for (String f : failed) System.out.println("  FAILED: " + f);
        System.exit(failed.isEmpty() ? 0 : 1);
    }

    private static void test(String name, T t) {
        try {
            t.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable e) {
            failed.add(name + " -> " + e);
            System.out.println("FAIL  " + name + " -> " + e);
        }
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static void eq(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but got " + actual);
        }
    }

    static void near(double expected, double actual, double tol, String what) {
        if (Math.abs(expected - actual) > tol) throw new AssertionError(what + ": expected " + expected + " but got " + actual);
    }

    static void throwsService(T t, int status, String what) {
        try {
            t.run();
        } catch (ServiceException e) {
            eq(status, e.status, what + " status");
            return;
        } catch (Exception e) {
            throw new AssertionError(what + ": wrong exception " + e);
        }
        throw new AssertionError(what + ": no error");
    }

    // ------------------------------------------------------------------ a small test world

    static final long T0 = 1_800_000_000_000L - Math.floorMod(1_800_000_000_000L, 3_600_000L); // on the hour

    static final class World {
        Hub hub;
        EdgeNode node;
        Sensor temp;
        long[] now = {T0};
        User admin;
        User viewer;
    }

    static World world(Path dir) {
        DataStore db = new DataStore(dir);
        Site site = new Site();
        site.id = "ST-1";
        site.name = "Test site";
        db.sites.put(site.id, site);
        EdgeNode n = new EdgeNode();
        n.id = "N-1";
        n.name = "test-gw";
        n.siteId = "ST-1";
        n.mode = NodeMode.SMART;
        n.windowSec = 60;
        n.deadbandPct = 2;
        n.bufferCapacity = 10;
        n.createdAt = T0 - 86_400_000L;
        db.nodes.put(n.id, n);
        Sensor s = new Sensor();
        s.id = "S-1";
        s.nodeId = "N-1";
        s.name = "Oven temperature";
        s.type = SensorType.TEMPERATURE;
        s.unit = "°C";
        s.low = 20;
        s.high = 70;
        s.sampleSec = 5;
        s.base = 50;
        s.noise = 0.1;
        db.sensors.put(s.id, s);
        World w = new World();
        w.admin = SeedData.user("admin", "Admin", Role.ADMIN, "admin123", T0);
        w.viewer = SeedData.user("vera", "Vera", Role.VIEWER, "viewer123", T0);
        db.users.put(w.admin.username, w.admin);
        db.users.put(w.viewer.username, w.viewer);
        w.hub = new Hub(db, ZoneId.of("UTC"), 7);
        w.hub.clock = () -> w.now[0];
        w.node = n;
        w.temp = s;
        return w;
    }

    static World world() throws IOException {
        return world(Files.createTempDirectory("edge-test"));
    }

    static Rule rule(World w, String id, Condition c, double th, int dur) {
        Rule r = new Rule();
        r.id = id;
        r.name = "Rule " + id;
        r.sensorId = w.temp.id;
        r.condition = c;
        r.threshold = th;
        r.durationSec = dur;
        r.severity = Severity.CRITICAL;
        r.action = "Turn heater off";
        w.hub.store.rules.put(id, r);
        return r;
    }

    /** Sends value v at time t through the pipeline (with a heartbeat first). */
    static EdgeEngine.Result feed(World w, long t, double v) {
        w.now[0] = t;
        w.hub.engine.heartbeat(w.node, t, 20, 40, 30, 50, w.node.uplinkUp);
        return w.hub.engine.process(w.node, w.temp, v, t);
    }

    // ------------------------------------------------------------------ tests

    static void json() {
        Map<String, Object> m = Json.obj("a", 1, "b", "x\"y\n<z>", "c", List.of(1.5, true), "d", null);
        String s = Json.write(m);
        check(!s.contains("<"), "angle brackets escaped");
        Map<String, Object> back = Json.parseObject(s);
        eq(1.0, back.get("a"), "number");
        eq("x\"y\n<z>", back.get("b"), "string");
        eq(List.of(1.5, true), back.get("c"), "list");
        check(back.containsKey("d") && back.get("d") == null, "null kept");
        eq("é", Json.parse("\"\\u00e9\""), "unicode escape");
        for (String bad : new String[] {"{", "[1,]", "{\"a\" 1}", "tru", "\"abc", "{} x"}) {
            try {
                Json.parse(bad);
                throw new AssertionError("should fail: " + bad);
            } catch (IllegalArgumentException expected) { /* good */ }
        }
        eq("null", Json.write(Double.NaN), "NaN written as null");
    }

    static void jsonModels() {
        Rule r = new Rule();
        r.id = "R-9";
        r.condition = Condition.BELOW;
        r.threshold = -4.5;
        r.sensorType = SensorType.LEVEL;
        r.hits = 12;
        Rule back = Json.fromMap(Json.parseObject(Json.write(r)), Rule.class);
        eq("R-9", back.id, "id");
        eq(Condition.BELOW, back.condition, "enum");
        eq(-4.5, back.threshold, "double");
        eq(12L, back.hits, "long");
        eq(SensorType.LEVEL, back.sensorType, "enum 2");
    }

    static void passwords() {
        String salt = Passwords.newSalt();
        String h = Passwords.hash("secret123", salt);
        check(Passwords.matches("secret123", salt, h), "right password matches");
        check(!Passwords.matches("secret124", salt, h), "wrong password fails");
        check(!h.equals(Passwords.hash("secret123", Passwords.newSalt())), "salt changes the hash");
        check(!Passwords.token().equals(Passwords.token()), "tokens are random");
        String k = Passwords.nodeKey();
        check(k.startsWith("edge_") && k.length() == 53, "node key format");
        eq(64, Passwords.sha256(k).length(), "sha256 hex");
    }

    static void csv() {
        eq("'=SUM(A1)", Csv.cell("=SUM(A1)"), "formula");
        eq("'@cmd", Csv.cell("@cmd"), "at sign");
        eq("-12.5", Csv.cell("-12.5"), "negative number stays");
        eq("\"a,b\"", Csv.cell("a,b"), "comma quoted");
        eq("\"say \"\"hi\"\"\"", Csv.cell("say \"hi\""), "quotes doubled");
    }

    static void modeRaw() {
        ModeLogic m = new ModeLogic();
        int sent = 0;
        for (int i = 0; i < 20; i++) if (m.offer(NodeMode.RAW, T0 + i * 5000L, 10, 1, 60_000, false).rawSent) sent++;
        eq(20, sent, "all sent");
    }

    static void modeFilter() {
        ModeLogic m = new ModeLogic();
        double[] v = {10, 10.4, 10.9, 11.0, 11.2, 9.9, 9.95};
        boolean[] expect = {true, false, false, true, false, true, false};
        for (int i = 0; i < v.length; i++) {
            eq(expect[i], m.offer(NodeMode.FILTER, T0 + i * 5000L, v[i], 1.0, 60_000, false).rawSent, "reading " + i);
        }
    }

    static void modeAggregate() {
        ModeLogic m = new ModeLogic();
        int summaries = 0;
        double[] last = null;
        for (int i = 0; i < 13; i++) {           // 12 readings in the first minute, then one in the next
            ModeLogic.Out o = m.offer(NodeMode.AGGREGATE, T0 + i * 5000L, i, 1, 60_000, false);
            check(!o.rawSent, "never raw");
            summaries += o.summaries;
            if (o.summary != null) last = o.summary;
        }
        eq(1, summaries, "one summary");
        eq((double) T0, last[0], "window start");
        eq(0.0, last[1], "min");
        eq(11.0, last[2], "max");
        eq(5.5, last[3], "average");
        eq(12.0, last[4], "count");
    }

    static void modeSmart() {
        ModeLogic m = new ModeLogic();
        check(m.offer(NodeMode.SMART, T0, 10, 1, 60_000, false).rawSent, "first reading sent");
        check(!m.offer(NodeMode.SMART, T0 + 5000, 11.5, 1, 60_000, false).rawSent, "1.5 < 2 x dead-band");
        check(m.offer(NodeMode.SMART, T0 + 10_000, 12.5, 1, 60_000, false).rawSent, "2.5 >= 2 x dead-band");
        check(m.offer(NodeMode.SMART, T0 + 15_000, 12.6, 1, 60_000, true).rawSent, "urgent always sent");
        eq(1, m.offer(NodeMode.SMART, T0 + 60_000, 12.6, 1, 60_000, false).summaries, "summary at window end");
    }

    static void rolling() {
        RollingStats r = new RollingStats(4);
        for (double v : new double[] {100, 1, 2, 3, 4}) r.add(v);   // 100 falls out of the window
        near(2.5, r.mean(), 1e-9, "mean");
        near(1.2909944, r.std(), 1e-6, "sample std");
        near((10 - 2.5) / 1.2909944, r.z(10, 4), 1e-5, "z");
        check(r.z(10, 5) == null, "not enough history");
    }

    static void invalidDropped() throws Exception {
        World w = world();
        EdgeEngine.Result r = feed(w, T0, 999);
        check(!r.valid, "999 °C is impossible");
        check(!r.rawSent && r.summaries == 0, "nothing sent");
        var day = w.hub.engine.day(w.node, T0);
        eq(1L, day.raw, "counted as raw");
        eq(1L, day.invalid, "counted as invalid");
        eq(0L, day.forwarded, "not forwarded");
        check(feed(w, T0 + 5000, Double.NaN).valid == false, "NaN dropped");
        feed(w, T0 + 10_000, -500);
        check(w.hub.engine.activeAlerts().stream().anyMatch(a -> a.ruleId.equals(EdgeEngine.SYS_INVALID)), "garbage alert after 3 bad values");
    }

    static void ruleAboveDuration() throws Exception {
        World w = world();
        rule(w, "R-1", Condition.ABOVE, 60, 10);
        check(feed(w, T0, 65).newAlerts.isEmpty(), "not yet (0 s)");
        check(feed(w, T0 + 5000, 66).newAlerts.isEmpty(), "not yet (5 s)");
        EdgeEngine.Result r = feed(w, T0 + 10_000, 67);
        eq(1, r.newAlerts.size(), "fires after 10 s");
        Alert a = r.newAlerts.get(0);
        eq(Severity.CRITICAL, a.severity, "severity");
        eq("Turn heater off", a.action, "edge action");
        check(a.edgeMs > 0 && a.cloudMs > a.edgeMs, "edge faster than cloud");
        check(r.rawSent, "SMART sends the alert reading at once");
        World w2 = world();
        rule(w2, "R-1", Condition.ABOVE, 60, 10);
        feed(w2, T0, 65);
        feed(w2, T0 + 5000, 50);   // dips back: timer resets
        check(feed(w2, T0 + 10_000, 65).newAlerts.isEmpty(), "timer was reset");
    }

    static void alertDedupe() throws Exception {
        World w = world();
        rule(w, "R-1", Condition.ABOVE, 60, 0);
        eq(1, feed(w, T0, 65).newAlerts.size(), "first alert");
        eq(0, feed(w, T0 + 5000, 66).newAlerts.size(), "same alert, not a new one");
        Alert a = w.hub.engine.activeAlerts().get(0);
        feed(w, T0 + 10_000, 50);
        check(a.clearedAt == T0 + 10_000, "cleared when back to normal");
        feed(w, T0 + 15_000, 70);
        eq(1, a.repeats, "repeat counted");
        eq(0L, a.clearedAt, "active again");
        w.hub.alerts.ack(w.viewer, a.id);
        eq(AlertStatus.ACKNOWLEDGED, a.status, "viewer may acknowledge");
        w.hub.alerts.resolve(w.admin, a.id, Json.obj("note", "fixed"));
        eq(AlertStatus.RESOLVED, a.status, "resolved");
        eq(1, feed(w, T0 + 20_000, 70).newAlerts.size(), "new alert after resolve");
    }

    static void ruleRate() throws Exception {
        World w = world();
        rule(w, "R-1", Condition.RATE_OF_CHANGE, 3, 0);
        for (int i = 0; i <= 12; i++) check(feed(w, T0 + i * 5000L, 50 + i * 0.1).newAlerts.isEmpty(), "slow change ok");
        EdgeEngine.Result r = feed(w, T0 + 65_000, 56);   // ~5 °C in a minute
        eq(1, r.newAlerts.size(), "fast change fires");
        check(r.newAlerts.get(0).message.contains("per minute"), "message");
    }

    static void ruleAnomaly() throws Exception {
        World w = world();
        rule(w, "R-1", Condition.ANOMALY, 4, 0);
        for (int i = 0; i < 40; i++) check(feed(w, T0 + i * 5000L, 50 + (i % 2 == 0 ? 0.2 : -0.2)).newAlerts.isEmpty(), "normal wobble");
        eq(1, feed(w, T0 + 200_000, 53).newAlerts.size(), "big jump is unusual");
    }

    static void storeAndForward() throws Exception {
        World w = world();
        w.node.mode = NodeMode.RAW;
        w.node.uplinkUp = false;
        for (int i = 0; i < 12; i++) feed(w, T0 + i * 5000L, 50);
        eq(10, w.node.buffered, "buffer holds 10");
        eq(2L, w.node.lost, "2 lost when full");
        eq(0L, w.hub.engine.day(w.node, T0).forwarded, "nothing forwarded");
        w.node.uplinkUp = true;
        eq(4, w.hub.engine.flushBuffer(w.node, T0 + 60_000, 4), "batch of 4");
        eq(6, w.node.buffered, "6 left");
        w.hub.engine.flushBuffer(w.node, T0 + 65_000, 100);
        eq(0, w.node.buffered, "empty");
        eq(10L, w.hub.engine.day(w.node, T0).forwarded, "all 10 forwarded later");
        eq(10L * w.hub.store.settings.bytesRaw, w.hub.engine.day(w.node, T0).fwdBytes, "bytes forwarded");
    }

    static void offline() throws Exception {
        World w = world();
        w.hub.engine.heartbeat(w.node, T0, 10, 10, 10, 40, true);
        w.hub.engine.checkHealth(T0 + 5000, 5);
        eq("ONLINE", w.hub.engine.status(w.node, T0 + 5000).name(), "online");
        long late = T0 + (w.hub.store.settings.heartbeatTimeoutSec + 5) * 1000L;
        w.hub.engine.checkHealth(late, 5);
        eq("OFFLINE", w.hub.engine.status(w.node, late).name(), "offline");
        check(w.hub.engine.activeAlerts().stream().anyMatch(a -> a.ruleId.equals(EdgeEngine.SYS_OFFLINE) && a.clearedAt == 0), "offline alert");
        w.hub.engine.heartbeat(w.node, late + 1000, 10, 10, 10, 40, true);
        w.hub.engine.checkHealth(late + 1000, 5);
        check(w.hub.engine.activeAlerts().stream().anyMatch(a -> a.ruleId.equals(EdgeEngine.SYS_OFFLINE) && a.clearedAt > 0), "cleared when back");
        w.hub.engine.heartbeat(w.node, late + 2000, 95, 10, 10, 40, true);
        eq("DEGRADED", w.hub.engine.status(w.node, late + 2000).name(), "high CPU = degraded");
    }

    static void noData() throws Exception {
        World w = world();
        rule(w, "R-1", Condition.NO_DATA, 30, 0);
        feed(w, T0, 50);
        w.hub.engine.heartbeat(w.node, T0 + 20_000, 10, 10, 10, 40, true);
        w.hub.engine.checkHealth(T0 + 20_000, 5);
        check(w.hub.engine.activeAlerts().isEmpty(), "20 s is fine");
        w.hub.engine.heartbeat(w.node, T0 + 40_000, 10, 10, 10, 40, true);
        w.hub.engine.checkHealth(T0 + 40_000, 5);
        eq(1, w.hub.engine.activeAlerts().size(), "silent sensor alert");
    }

    static void compare() {
        List<double[]> rows = new ArrayList<>();
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < 720; i++) rows.add(new double[] {T0 + i * 5000L, 50 + r.nextGaussian() * 0.3});
        Map<String, Object> c = EdgeEngine.compareModes(rows, 1.0, 60_000, 512, 640);
        long raw = ((long[]) c.get("RAW"))[1];
        eq(720L * 512, raw, "raw bytes");
        for (String m : new String[] {"FILTER", "AGGREGATE", "SMART"}) check(((long[]) c.get(m))[1] < raw / 2, m + " saves more than half");
        eq(59L, ((long[]) c.get("AGGREGATE"))[0], "59 closed windows in an hour");
    }

    static void ruleTest() throws Exception {
        World w = world();
        for (int i = 0; i < 30; i++) feed(w, T0 + i * 5000L, i < 20 ? 50 : 65);
        Map<String, Object> res = w.hub.alerts.testRule(Json.obj("condition", "ABOVE", "sensorId", "S-1", "threshold", 60.0, "durationSec", 0.0));
        eq(1, res.get("fires"), "fires once for one run above 60");
        check(w.hub.store.rules.isEmpty(), "dry run saves nothing");
    }

    static void auth() throws Exception {
        World w = world();
        Map<String, Object> ok = w.hub.auth.login("admin", "admin123");
        String token = (String) ok.get("token");
        eq("admin", w.hub.auth.userFor(token).username, "session works");
        throwsService(() -> w.hub.auth.login("admin", "nope"), 401, "wrong password");
        for (int i = 0; i < 4; i++) {
            try { w.hub.auth.login("vera", "bad"); } catch (ServiceException ignored) { /* expected */ }
        }
        throwsService(() -> w.hub.auth.login("vera", "bad"), 401, "5th wrong");
        throwsService(() -> w.hub.auth.login("vera", "viewer123"), 429, "locked even with right password");
        w.now[0] += 6 * 60_000L;
        check(w.hub.auth.login("vera", "viewer123").get("token") != null, "unlocked after 5 min");
        w.now[0] += 13 * 3_600_000L;
        check(w.hub.auth.userFor(token) == null, "session expired after 12 h idle");
        w.hub.auth.logout(token);
    }

    static void permissions() throws Exception {
        World w = world();
        throwsService(() -> w.hub.nodes.updateNode(w.viewer, "N-1", Json.obj("mode", "RAW")), 403, "viewer edit node");
        throwsService(() -> w.hub.alerts.saveRule(w.viewer, null, Json.obj("name", "x")), 403, "viewer add rule");
        throwsService(() -> w.hub.settings.update(w.viewer, Json.obj()), 403, "viewer settings");
        throwsService(() -> w.hub.settings.inject(w.viewer, Json.obj("type", "SPIKE")), 403, "viewer fault");
        throwsService(() -> w.hub.auth.update(w.admin, "admin", Json.obj("role", "VIEWER")), 400, "last admin keeps admin");
        eq(NodeMode.RAW.name(), String.valueOf(w.hub.nodes.updateNode(w.admin, "N-1", Json.obj("mode", "RAW")).get("mode")), "admin can");
    }

    static void ruleValidation() throws Exception {
        World w = world();
        throwsService(() -> w.hub.alerts.saveRule(w.admin, null, Json.obj("name", "z", "condition", "ANOMALY", "threshold", 50.0, "severity", "INFO")), 400, "z too big");
        throwsService(() -> w.hub.alerts.saveRule(w.admin, null, Json.obj("name", "n", "condition", "NO_DATA", "threshold", 2.0, "severity", "INFO")), 400, "no-data too short");
        throwsService(() -> w.hub.alerts.saveRule(w.admin, null, Json.obj("name", "s", "condition", "ABOVE", "threshold", 1.0, "severity", "INFO", "sensorId", "S-404")), 400, "unknown sensor");
        throwsService(() -> w.hub.alerts.saveRule(w.admin, null, Json.obj("condition", "ABOVE", "threshold", 1.0, "severity", "INFO")), 400, "name required");
        Map<String, Object> r = w.hub.alerts.saveRule(w.admin, null, Json.obj("name", "Hot", "condition", "ABOVE", "threshold", 60.0, "severity", "WARNING", "sensorId", "S-1"));
        eq("N-1", r.get("nodeId"), "node filled from the sensor");
        throwsService(() -> w.hub.nodes.saveSensor(w.admin, null, Json.obj("name", "x", "nodeId", "N-1", "type", "HUMIDITY", "low", 80.0, "high", 20.0)), 400, "low >= high");
        throwsService(() -> w.hub.nodes.saveSensor(w.admin, null, Json.obj("name", "x", "nodeId", "N-1", "type", "HUMIDITY", "low", 0.0, "high", 120.0)), 400, "above sensor limit");
    }

    @SuppressWarnings("unchecked")
    static void ingest() throws Exception {
        World w = world();
        String key = (String) w.hub.nodes.rotateKey(w.admin, "N-1").get("apiKey");
        throwsService(() -> w.hub.nodes.ingest("edge_wrong", Json.obj()), 401, "bad key");
        throwsService(() -> w.hub.nodes.ingest(null, Json.obj()), 401, "no key");
        w.hub.nodes.queueCommand(w.admin, "N-1", Json.obj("type", "SET_MODE", "arg", "FILTER"));
        Map<String, Object> res = w.hub.nodes.ingest(key, Json.obj(
                "health", Json.obj("cpu", 33.0, "temp", 51.0),
                "readings", List.of(Json.obj("sensorId", "S-1", "value", 49.0), Json.obj("sensorId", "S-1", "value", 9999.0))));
        eq(1, res.get("accepted"), "accepted");
        eq(1, res.get("invalid"), "invalid");
        eq(1, ((List<Object>) res.get("commands")).size(), "command handed over");
        eq(NodeMode.FILTER, w.node.mode, "mode applied before processing");
        eq(33.0, w.node.cpu, "health stored");
        check(w.hub.engine.isOnline(w.node, w.now[0]), "online after ingest");
        throwsService(() -> w.hub.nodes.ingest(key, Json.obj("readings", List.of(Json.obj("sensorId", "S-999", "value", 1.0)))), 400, "unknown sensor");
        w.node.enabled = false;
        throwsService(() -> w.hub.nodes.ingest(key, Json.obj()), 403, "node off");
    }

    static void storeRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("edge-store");
        World w = world(dir);
        rule(w, "R-1", Condition.ABOVE, 60, 0);
        feed(w, T0, 70);
        w.hub.store.saveAll();
        DataStore again = new DataStore(dir);
        again.load();
        eq(1, again.nodes.size(), "nodes");
        eq("Oven temperature", again.sensors.get("S-1").name, "sensor");
        eq(1, again.alerts.size(), "alert");
        eq(Condition.ABOVE, again.rules.get("R-1").condition, "rule");
        check(!again.events.isEmpty(), "events");
        check(Files.list(dir).noneMatch(p -> p.toString().endsWith(".tmp")), "no temp files left");
    }

    static void seed() throws Exception {
        Path dir = Files.createTempDirectory("edge-seed");
        Hub hub = Hub.open(dir, ZoneId.of("Asia/Kolkata"), 10);
        eq(8, hub.store.nodes.size(), "8 nodes");
        eq(30, hub.store.sensors.size(), "30 sensors");
        eq(10, hub.store.rules.size(), "10 rules");
        check(hub.store.hours.size() > 4000, "hourly history");
        check(hub.engine.isOnline(hub.store.nodes.get("N-101"), System.currentTimeMillis()), "simulated node online after warm-up");
        check(!hub.engine.isOnline(hub.store.nodes.get("N-108"), System.currentTimeMillis()), "real-device slot not online");
        check(hub.store.sensors.get("S-101").lastValue != null, "live values after warm-up");
        Map<String, Object> d = hub.analytics.dashboard();
        check(d.containsKey("topology") && d.containsKey("today"), "dashboard");
        Map<String, Object> rep = hub.analytics.reports(7);
        eq(7, ((List<?>) rep.get("perDay")).size(), "7 days in report");
    }

    static void simulatorFaults() throws Exception {
        World w = world();
        rule(w, "R-1", Condition.ABOVE, 60, 0);
        w.hub.sim.inject("N-1", "S-1", FaultType.SPIKE, 30, 1, "admin", T0);
        w.now[0] = T0;
        w.hub.sim.tick(T0);
        check(w.temp.lastValue > 70, "spike applied: " + w.temp.lastValue);
        eq(1, w.hub.engine.activeAlerts().size(), "rule fired");
        w.hub.sim.inject("N-1", null, FaultType.NODE_DOWN, 0, 2, "admin", T0);
        long t = T0;
        for (int i = 1; i <= 10; i++) { t = T0 + i * 5000L; w.hub.sim.tick(t); }
        eq("OFFLINE", w.hub.engine.status(w.node, t).name(), "node down -> offline");
        w.hub.sim.tick(T0 + 3 * 60_000L);
        check(w.hub.sim.faults.isEmpty(), "faults ended by themselves");
        eq("ONLINE", w.hub.engine.status(w.node, T0 + 3 * 60_000L).name(), "back online");
    }

    static void http() throws Exception {
        Path root = Files.createTempDirectory("edge-http");
        Path front = Files.createDirectories(root.resolve("frontend"));
        Files.writeString(front.resolve("index.html"), "<!doctype html><title>t</title>");
        Files.writeString(root.resolve("secret.txt"), "top secret");
        World w = world(root.resolve("data"));
        w.hub.clock = System::currentTimeMillis;
        ApiServer server = new ApiServer(w.hub, front);
        server.start("127.0.0.1", 0);
        String base = "http://127.0.0.1:" + server.port();
        HttpClient c = HttpClient.newHttpClient();
        try {
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, r.statusCode(), "index");
            check(r.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"), "CSP header");
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/static/..%2Fsecret.txt")).build(), HttpResponse.BodyHandlers.ofString());
            eq(404, r.statusCode(), "path traversal blocked");
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/dashboard")).build(), HttpResponse.BodyHandlers.ofString());
            eq(401, r.statusCode(), "needs sign in");
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"admin123\"}")).build(), HttpResponse.BodyHandlers.ofString());
            eq(200, r.statusCode(), "login");
            String token = (String) Json.parseObject(r.body()).get("token");
            for (String p : new String[] {"/api/dashboard", "/api/meta", "/api/live", "/api/nodes", "/api/nodes/N-1", "/api/nodes/N-1/compare",
                    "/api/sensors", "/api/sensors/S-1", "/api/alerts", "/api/rules", "/api/reports?days=7", "/api/events", "/api/settings",
                    "/api/faults", "/api/users", "/api/commands", "/api/sites"}) {
                r = c.send(HttpRequest.newBuilder(URI.create(base + p)).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
                eq(200, r.statusCode(), "GET " + p);
            }
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/alerts/export.csv")).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
            check(r.body().contains("Severity") && r.headers().firstValue("Content-Disposition").isPresent(), "CSV download");
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/nodes/N-1")).header("Authorization", "Bearer " + token)
                    .header("Origin", "http://evil.example").PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            eq(403, r.statusCode(), "cross-site write blocked");
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/rules")).header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString("{not json")).build(), HttpResponse.BodyHandlers.ofString());
            eq(400, r.statusCode(), "bad JSON");
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/nodes/N-404")).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
            eq(404, r.statusCode(), "unknown node");
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/ingest")).header("X-Node-Key", "edge_nope")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            eq(401, r.statusCode(), "ingest with a bad key");
        } finally {
            server.stop();
        }
    }
}
