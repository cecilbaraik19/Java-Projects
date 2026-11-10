package com.cecil.parisar;

import com.cecil.parisar.ai.Assistant;
import com.cecil.parisar.ai.EnergyForecaster;
import com.cecil.parisar.ai.RiskModel;
import com.cecil.parisar.cloud.CloudService;
import com.cecil.parisar.iot.IotService;
import com.cecil.parisar.iot.Simulator;
import com.cecil.parisar.model.Alert;
import com.cecil.parisar.model.ClassSession;
import com.cecil.parisar.model.Course;
import com.cecil.parisar.model.Device;
import com.cecil.parisar.model.Point;
import com.cecil.parisar.model.Role;
import com.cecil.parisar.model.Room;
import com.cecil.parisar.model.Student;
import com.cecil.parisar.model.User;
import com.cecil.parisar.service.AcademicService;
import com.cecil.parisar.service.AnalyticsService;
import com.cecil.parisar.service.AuthService;
import com.cecil.parisar.service.Problem;
import com.cecil.parisar.store.DataStore;
import com.cecil.parisar.store.SeedData;
import com.cecil.parisar.util.Clock;
import com.cecil.parisar.util.Json;
import com.cecil.parisar.util.Passwords;
import com.cecil.parisar.util.Stats;
import com.cecil.parisar.util.Text;
import com.cecil.parisar.web.ApiServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All tests for Parisar, without JUnit (so nothing has to be downloaded).
 * Run with run-tests.bat / run-tests.sh. The clock is frozen at Tuesday 10 Nov 2026, 10:30 IST, so results never change.
 */
public final class AllTests {
    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    interface Body { void run() throws Exception; }

    static void test(String name, Body b) {
        try {
            b.run();
            passed++;
            System.out.println("  ok    " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + " -> " + t);
            System.out.println("  FAIL  " + name + "  (" + t + ")");
        }
    }

    static void check(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }
    static void eq(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }
    static int problem(Body b) {
        try { b.run(); } catch (Problem p) { return p.status; } catch (Exception e) { throw new AssertionError("wrong exception " + e); }
        throw new AssertionError("expected a Problem");
    }

    static final Instant START = ZonedDateTime.of(2026, 11, 10, 10, 30, 0, 0, Clock.ZONE).toInstant();

    // ------------------------------------------------------------------ a fresh world
    static DataStore store;
    static IotService iot;
    static AcademicService academic;
    static Simulator sim;
    static RiskModel model;
    static EnergyForecaster energy;
    static AnalyticsService analytics;
    static Assistant assistant;
    static AuthService auth;

    static void world(boolean history) {
        Clock.fix(START);
        store = new DataStore(null);
        iot = new IotService(store);
        academic = new AcademicService(store);
        iot.setAcademic(academic);
        sim = new Simulator(store, iot, academic, 42);
        model = new RiskModel();
        model.train(RiskModel.history(7), 1500, 0.3, 0.01);
        energy = new EnergyForecaster(store);
        analytics = new AnalyticsService(store, academic, model, energy);
        assistant = new Assistant(store, academic, analytics, energy);
        auth = new AuthService(store);
        SeedData.load(store, iot, academic, sim, history);
    }

    static User u(String username) { return store.userByName(username); }

    static Map<String, Object> m(Object... kv) { return Json.obj(kv); }

    public static void main(String[] args) throws Exception {
        System.out.println("Parisar Smart Campus - tests");

        System.out.println("\n[helpers]");
        test("JSON writes and reads back text with quotes, unicode and HTML characters", () -> {
            Map<String, Object> o = m("a", "say \"hi\" <b> & ₹", "n", 42L, "d", 2.5, "list", List.of(1L, true));
            Map<String, Object> back = Json.parseObject(Json.write(o));
            eq("say \"hi\" <b> & ₹", back.get("a"));
            eq(42L, back.get("n"));
            eq(2.5, back.get("d"));
            check(!Json.write(o).contains("<"), "< must be escaped");
        });
        test("JSON rejects broken input", () -> {
            for (String bad : List.of("{", "{\"a\":}", "[1,]", "{\"a\":1} x", "nul"))
                try { Json.parse(bad); throw new AssertionError("accepted " + bad); } catch (IllegalArgumentException expected) { }
        });
        test("passwords are salted PBKDF2 hashes", () -> {
            String h1 = Passwords.hash("secret123"), h2 = Passwords.hash("secret123");
            check(!h1.equals(h2), "same password must give different hashes (salt)");
            check(Passwords.verify("secret123", h1) && !Passwords.verify("secret124", h1), "verify");
            check(Passwords.problem("short1") != null && Passwords.problem("longenough1") == null, "rules");
        });
        test("words: lower case, no stop words, simple stemming", () -> {
            eq(List.of("library", "timing"), Text.words("What are the Library timings?"));
            eq(List.of("free", "room"), Text.words("free rooms"));
        });
        test("statistics: mean, std, z-score, Pearson", () -> {
            List<Double> xs = List.of(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0);
            eq(5.0, Stats.mean(xs));
            check(Math.abs(Stats.std(xs) - 2.138) < 0.01, "std " + Stats.std(xs));
            eq(2.0, Stats.z(9, 5, 2));
            check(Math.abs(AnalyticsService.pearson(List.of(1.0, 2.0, 3.0), List.of(2.0, 4.0, 6.0)) - 1) < 1e-9, "pearson");
        });

        System.out.println("\n[storage]");
        test("readings are averaged into 10-minute and 1-hour buckets", () -> {
            DataStore s = new DataStore(null);
            long t = START.toEpochMilli() - Math.floorMod(START.toEpochMilli(), DataStore.HOUR_MS);
            s.record("X", t + 1000, Map.of("temp", 20.0));
            s.record("X", t + 2000, Map.of("temp", 22.0));
            s.record("X", t + 11 * 60_000, Map.of("temp", 30.0));
            eq(2, s.fineOf("X").size());
            eq(21.0, s.fineOf("X").get(0).get("temp"));
            eq(1, s.hourlyOf("X").size());
            check(Math.abs(s.hourlyOf("X").get(0).get("temp") - 24.0) < 1e-9, "hourly average");
        });
        test("fine history keeps only the last 24 hours", () -> {
            DataStore s = new DataStore(null);
            for (int i = 0; i < 300; i++) s.record("X", i * DataStore.FINE_MS, Map.of("v", (double) i));
            eq(DataStore.FINE_KEEP, s.fineOf("X").size());
            eq(299.0, s.fineOf("X").get(s.fineOf("X").size() - 1).get("v"));
        });

        world(false);
        System.out.println("\n[sign-in]");
        test("sign in works, wrong password gives the same message as unknown user", () -> {
            check(auth.login("admin", "admin123") != null, "token");
            Problem a = null, b = null;
            try { auth.login("admin", "nope"); } catch (Problem p) { a = p; }
            try { auth.login("nobody", "nope"); } catch (Problem p) { b = p; }
            eq(a.getMessage(), b.getMessage());
        });
        test("five wrong passwords lock the account for 5 minutes", () -> {
            for (int i = 0; i < 5; i++) problem(() -> auth.login("rkumar", "wrong"));
            eq(429, problem(() -> auth.login("rkumar", "faculty123")));
            Clock.advanceMillis(5 * 60_000 + 1000);
            check(auth.login("rkumar", "faculty123") != null, "unlocked after 5 minutes");
            Clock.fix(START);
        });
        test("a student account must belong to a student record", () -> {
            eq(400, problem(() -> auth.createUser(u("admin"), m("username", "newkid", "name", "New Kid", "role", "STUDENT", "password", "student123"))));
            User k = auth.createUser(u("admin"), m("username", "newkid", "name", "New Kid", "role", "STUDENT", "password", "student123", "studentId", "S-1001"));
            eq("S-1001", k.studentId);
            eq(409, problem(() -> auth.createUser(u("admin"), m("username", "newkid2", "name", "X", "role", "STUDENT", "password", "student123", "studentId", "S-1001"))));
            eq(403, problem(() -> auth.createUser(u("rkumar"), m("username", "zz", "name", "Z", "role", "FACULTY", "password", "faculty123"))));
        });

        System.out.println("\n[IoT hub]");
        test("a device with the wrong key is refused; the right key works", () -> {
            eq(401, problem(() -> iot.ingest(SeedData.DEMO_DEVICE, "wrong", m("temp", 22.0))));
            eq(401, problem(() -> iot.ingest("D-NOPE", SeedData.DEMO_KEY, m("temp", 22.0))));
            Map<String, Object> r = iot.ingest(SeedData.DEMO_DEVICE, SeedData.DEMO_KEY, m("temp", 22.5, "co2", 650L, "occupancy", 5L));
            eq(true, r.get("ok"));
            eq(22.5, store.devices.get(SeedData.DEMO_DEVICE).latest.get("temp"));
        });
        test("impossible values are rejected, good ones kept", () -> {
            Map<String, Object> r = iot.ingest(SeedData.DEMO_DEVICE, SeedData.DEMO_KEY, m("temp", 999.0, "humidity", 50.0));
            eq(List.of("temp"), r.get("rejected"));
            eq(400, problem(() -> iot.ingest(SeedData.DEMO_DEVICE, SeedData.DEMO_KEY, m("temp", "hot"))));
        });
        test("more than 120 messages a minute from one device are refused", () -> {
            Device d = store.devices.get("D-ENV-L02");
            String key = (String) iot.rotateKey(u("admin"), d.id).get("key");
            int refused = 0;
            for (int i = 0; i < 125; i++) {
                try { iot.ingest(d.id, key, m("temp", 22.0)); } catch (Problem p) { if (p.status == 429) refused++; }
            }
            eq(5, refused);
        });
        test("heat alert: raised at 32 C, made critical at 36 C, closed by itself below 30 C", () -> {
            Device d = store.devices.get("D-ENV-A201");
            iot.process(d, Clock.millis(), m("temp", 33.0));
            Alert a = openAlert(d.id, "HEAT");
            eq("WARNING", a.severity);
            iot.process(d, Clock.millis(), m("temp", 37.0));
            eq("CRITICAL", a.severity);
            eq(1L, store.alerts.values().stream().filter(x -> x.isOpen() && d.id.equals(x.deviceId) && "HEAT".equals(x.rule)).count());
            iot.process(d, Clock.millis(), m("temp", 25.0));
            eq("RESOLVED", a.status);
            eq("auto", a.resolvedBy);
        });
        test("CO2 and low-water alerts", () -> {
            iot.process(store.devices.get("D-ENV-B101"), Clock.millis(), m("co2", 1500.0));
            check(openAlert("D-ENV-B101", "CO2") != null, "co2 alert");
            iot.process(store.devices.get("D-WT-A"), Clock.millis(), m("level", 8.0));
            eq("CRITICAL", openAlert("D-WT-A", "WATER_LOW").severity);
        });
        test("a silent device gets an OFFLINE alert, which closes when it talks again", () -> {
            Device d = store.devices.get("D-ENV-C01");
            iot.process(d, Clock.millis() - 20 * 60_000, m("temp", 24.0));
            iot.checkOffline();
            Alert a = openAlert(d.id, "OFFLINE");
            check(a != null, "offline alert");
            iot.process(d, Clock.millis(), m("temp", 24.0));
            eq("RESOLVED", a.status);
        });
        test("card readers send a heartbeat so they are not reported offline", () -> {
            Device g = store.devices.get("D-GT-A102");
            eq("HEARTBEAT", iot.process(g, Clock.millis(), m("heartbeat", true)).get("result"));
            eq(Clock.millis(), g.lastSeen);
        });
        test("lights left on in an empty room for 30 minutes -> energy-waste alert; auto saver switches them off", () -> {
            Room r = store.rooms.get("R-B102");
            Device d = store.devices.get("D-ENV-B102");
            r.lightsOn = true;
            r.acOn = true;
            iot.process(d, Clock.millis() - 31 * 60_000, m("occupancy", 0.0));
            iot.process(d, Clock.millis(), m("occupancy", 0.0));
            Alert a = openAlert(d.id, "ENERGY_WASTE");
            check(a != null && a.message.contains("AC and lights"), "waste alert");
            iot.control(u("admin"), r.id, m("ac", false, "lights", false));
            eq("RESOLVED", a.status);
            store.meta.put("autoSaver", true);
            r.lightsOn = true;
            iot.process(d, Clock.millis() + 60_000, m("occupancy", 0.0));
            iot.process(d, Clock.millis() + 32 * 60_000, m("occupancy", 0.0));
            check(!r.lightsOn, "auto saver switched the lights off");
            store.meta.put("autoSaver", false);
        });
        test("anomaly maths: a big jump is ~z standard deviations away, a small one is not", () -> {
            List<Point> h = new ArrayList<>();
            for (int i = 0; i < 25; i++) { Point p = new Point(i * DataStore.FINE_MS); p.add(Map.of("power", 20.0 + (i % 3))); h.add(p); }
            IotService.Anomaly big = IotService.anomaly(h, "power", 90, 25);
            check(big.z() > 4, "z " + big.z());
            check(IotService.anomaly(h, "power", 23, 25).z() < 2, "small change is normal");
            eq(null, IotService.anomaly(h.subList(0, 5), "power", 90, 25));
        });
        test("only staff can switch rooms; a student cannot", () -> {
            eq(403, problem(() -> iot.control(u("aditi"), "R-A101", m("lights", true))));
            eq(400, problem(() -> iot.control(u("admin"), "R-A102", m("ac", true))));      // no AC there
        });
        test("registering a device gives a key once; a new key makes the old one stop working", () -> {
            Map<String, Object> r = iot.register(u("admin"), m("type", "env", "name", "Test sensor", "roomId", "R-C02"));
            String id = (String) ((Map<?, ?>) r.get("device")).get("id");
            String key = (String) r.get("key");
            eq(true, iot.ingest(id, key, m("temp", 25.0)).get("ok"));
            String key2 = (String) iot.rotateKey(u("admin"), id).get("key");
            eq(401, problem(() -> iot.ingest(id, key, m("temp", 25.0))));
            eq(true, iot.ingest(id, key2, m("temp", 25.0)).get("ok"));
            eq(403, problem(() -> iot.register(u("rkumar"), m("type", "ENV", "name", "x", "roomId", "R-C02"))));
        });

        System.out.println("\n[classes and RFID attendance]");
        test("the timetable class opens by itself and closes after the hour", () -> {
            Clock.fix(ZonedDateTime.of(2026, 11, 10, 14, 0, 5, 0, Clock.ZONE).toInstant());     // Tuesday 14:00 = CSE305
            List<ClassSession> opened = academic.autoSessions(Clock.millis());
            eq(1, opened.size());
            eq("CSE305", opened.get(0).courseId);
            check(store.rooms.get("R-A201").lightsOn, "smart room switched the lights on");
            academic.autoSessions(Clock.millis() + 61 * 60_000);
            check(!opened.get(0).isOpen(), "closed after one hour");
            Clock.fix(START);
        });
        test("RFID taps: present, already present, not in the course, no class", () -> {
            Clock.fix(ZonedDateTime.of(2026, 11, 10, 14, 0, 5, 0, Clock.ZONE).toInstant());
            store.sessions.remove(AcademicService.sessionId("CSE305", "2026-11-10", 14));
            academic.autoSessions(Clock.millis());
            Device gate = store.devices.get("D-GT-A201");
            Course c = store.courses.get("CSE305");
            Student in = store.students.get(c.studentIds.get(0));
            Student out = store.students.get("S-1001");                   // a B.Sc. IT student
            eq("PRESENT", iot.process(gate, Clock.millis(), m("cardId", in.cardId)).get("result"));
            eq("ALREADY", iot.process(gate, Clock.millis(), m("cardId", in.cardId)).get("result"));
            eq("NOT_ENROLLED", iot.process(gate, Clock.millis(), m("cardId", out.cardId)).get("result"));
            eq("UNKNOWN_CARD", iot.process(gate, Clock.millis(), m("cardId", "RF-000000")).get("result"));
            eq("NO_CLASS", academic.tap(store.devices.get("D-GT-B101"), in, Clock.millis()));
            Clock.fix(START);
        });
        test("library gate: one visit per 30 minutes", () -> {
            Student s = store.students.get("S-1010");
            int before = s.libraryVisits.size();
            Device lib = store.devices.get("D-GT-L01");
            eq("LIBRARY", academic.tap(lib, s, Clock.millis()));
            academic.tap(lib, s, Clock.millis() + 10 * 60_000);
            academic.tap(lib, s, Clock.millis() + 45 * 60_000);
            eq(before + 2, s.libraryVisits.size());
        });
        test("teachers correct attendance only in their own course", () -> {
            ClassSession s = store.sessions.values().stream().filter(x -> x.courseId.equals("CSIT131") && !x.isOpen()
                    && Clock.millis() - x.endedAt < 7L * 24 * DataStore.HOUR_MS).findFirst().orElseThrow();
            String sid = store.courses.get("CSIT131").studentIds.get(3);
            academic.mark(u("rkumar"), s.id, sid, true);
            check(s.present.get(sid).startsWith("MANUAL@") || s.present.get(sid).startsWith("RFID@"), "marked");
            academic.mark(u("rkumar"), s.id, sid, false);
            check(!s.present.containsKey(sid), "unmarked");
            eq(403, problem(() -> academic.mark(u("soraon"), s.id, sid, true)));
            eq(403, problem(() -> academic.mark(u("aditi"), s.id, sid, true)));
        });
        test("attendance %, and how many classes are needed to reach 75%", () -> {
            Student s = store.students.get("S-1009");
            List<Map<String, Object>> rows = academic.attendanceOf(s, academic.attendanceTable());
            for (Map<String, Object> r : rows) {
                int att = (int) r.get("attended"), held = (int) r.get("held"), need = (int) r.get("classesNeeded");
                if (need > 0) {
                    check((att + need) * 4 >= (held + need) * 3, "enough after " + need);
                    check((att + need - 1) * 4 < (held + need - 1) * 3, "not enough after " + (need - 1));
                }
            }
        });
        test("who can see a student: the student, their teachers and mentor, the admin", () -> {
            Student rohit = store.students.get("S-1009");
            check(academic.canSee(u("rohit"), rohit) && !academic.canSee(u("aditi"), rohit), "students see only themselves");
            check(academic.canSee(u("rkumar"), rohit) && academic.canSee(u("admin"), rohit), "teacher and admin");
            check(!academic.canSee(u("aekka"), rohit), "a BCA teacher does not see a B.Sc. IT student");
            eq(404, problem(() -> analytics.studentDetail(u("aditi"), "S-1009")));
            eq(403, problem(() -> analytics.students(u("aditi"), Map.of())));
        });

        System.out.println("\n[AI]");
        test("risk model: accuracy over 80% and AUC over 0.85 on students it never saw", () -> {
            Map<String, Object> r = model.report();
            check(((Number) r.get("accuracy")).doubleValue() > 80, "accuracy " + r.get("accuracy"));
            check(((Number) r.get("auc")).doubleValue() > 85, "auc " + r.get("auc"));
        });
        test("risk model learned that more attendance and marks mean less risk", () -> {
            for (Object o : (List<?>) model.report().get("weights")) {
                Map<?, ?> w = (Map<?, ?>) o;
                check(((Number) w.get("weight")).doubleValue() < 0, w.get("feature") + " weight should be negative");
            }
        });
        test("risk explanation names the weak areas", () -> {
            Map<String, Object> bad = model.explain(new double[]{45, 30, 40, 0, 0.5});
            eq("HIGH", bad.get("band"));
            check(((List<?>) bad.get("reasons")).get(0).toString().toLowerCase().contains("attendance"), "attendance first: " + bad.get("reasons"));
            eq("LOW", model.explain(new double[]{97, 90, 98, 12, 8}).get("band"));
        });
        test("the at-risk demo student is HIGH, the good one LOW", () -> {
            eq("HIGH", ((Map<?, ?>) analytics.studentDetail(u("admin"), "S-1009").get("risk")).get("band"));
            eq("LOW", ((Map<?, ?>) analytics.studentDetail(u("admin"), "S-1004").get("risk")).get("band"));
        });
        test("assistant finds the right FAQ with TF-IDF", () -> {
            eq("Library timings", assistant.rank(Text.words("library timings on Saturday")).get(0).get("title"));
            eq("Library open till 11 pm during exams", assistant.rank(Text.words("is the library open late during exams")).get(0).get("title"));
            eq("Lost ID card", assistant.rank(Text.words("I lost my card")).get(0).get("title"));
            eq("SEARCH", assistant.ask(u("aditi"), "What is the meaning of life?").get("intent"));
        });
        test("assistant answers live questions and respects roles", () -> {
            eq("ATTENDANCE", assistant.ask(u("rohit"), "What is my attendance?").get("intent"));
            check(assistant.ask(u("rohit"), "What is my attendance?").get("answer").toString().startsWith("Your attendance"), "own attendance");
            check(assistant.ask(u("aditi"), "any open alerts?").get("answer").toString().contains("for staff"), "students do not see alerts");
            eq("ROOM_CLIMATE", assistant.ask(u("admin"), "temperature in the server room").get("intent"));
            check(assistant.ask(u("admin"), "temperature in the server room").get("answer").toString().startsWith("Server Room"), "server room");
            eq("FREE_ROOM", assistant.ask(u("aditi"), "Where can I study now?").get("intent"));
        });

        System.out.println("\n[cloud]");
        test("cloud sync uploads finished points once, with checksums; tampering is detected", () -> {
            Path dir = Files.createTempDirectory("parisar-cloud");
            Device d = store.devices.get("D-ENV-C02");
            long t0 = Clock.millis() - 3 * DataStore.FINE_MS;
            for (int i = 0; i < 3; i++) store.record(d.id, t0 + i * DataStore.FINE_MS + 1000, Map.of("temp", 24.0 + i));
            CloudService cloud = new CloudService(store, iot, dir);
            Map<String, Object> first = cloud.sync(null);
            check(((Number) first.get("records")).intValue() >= 3, "records " + first.get("records"));
            eq(0, ((Number) cloud.sync(null).get("records")).intValue());
            eq(0, ((List<?>) cloud.verify(null).get("bad")).size());
            Path obj = Files.list(dir.resolve("telemetry")).findFirst().orElseThrow();
            check(Files.readAllLines(obj).get(0).startsWith("{\"device\""), "NDJSON lines");
            Files.writeString(obj, "tampered\n", java.nio.file.StandardOpenOption.APPEND);
            eq(1, ((List<?>) cloud.verify(null).get("bad")).size());
        });
        test("backups are full snapshots; object keys cannot escape the bucket", () -> {
            Path dir = Files.createTempDirectory("parisar-cloud");
            CloudService cloud = new CloudService(store, iot, dir);
            String key = (String) cloud.backup(null).get("key");
            check(cloud.read(u("admin"), key).length() > 1000, "admin can open it");
            Map<String, Object> snap = Json.parseObject(Files.readString(dir.resolve(key)));
            eq(120, ((List<?>) snap.get("students")).size());
            check(!Json.write(snap).contains("passwordHash\":null"), "users included");
            eq(404, problem(() -> cloud.read(u("admin"), "../users.json")));
            eq(403, problem(() -> cloud.read(u("rkumar"), key)));
        });

        System.out.println("\n[simulator + history]");
        world(true);
        test("demo start: 14 days of history and the planted problems are on the alert board", () -> {
            check(store.hourlyOf("D-EM-A").size() > 300, "hourly history " + store.hourlyOf("D-EM-A").size());
            List<String> rules = new ArrayList<>();
            for (Alert a : store.alerts.values()) if (a.isOpen()) rules.add(a.rule);
            for (String r : List.of("HEAT", "WATER_LOW", "OFFLINE", "ENERGY_WASTE")) check(rules.contains(r), "missing " + r + " in " + rules);
        });
        test("energy forecast: 12 hours ahead, back-test error under 15%", () -> {
            Map<String, Object> f = energy.forecast(null, 12);
            eq(12, ((List<?>) f.get("forecast")).size());
            check(f.get("mape") != null && ((Number) f.get("mape")).doubleValue() < 15, "mape " + f.get("mape"));
            check(((Number) f.get("kwhYesterday")).doubleValue() > 100, "yesterday kWh");
        });
        test("a live tick sends one reading per room sensor and fills the class from RFID taps", () -> {
            long before = store.devices.get("D-ENV-A101").messages;
            sim.tick(Clock.millis() + 15_000);
            check(store.devices.get("D-ENV-A101").messages == before + 1, "one message");
            ClassSession live = store.openSessionIn("R-A101");      // Tuesday 10:00 = CSIT135
            check(live != null && live.courseId.equals("CSIT135"), "CSIT135 is live");
            sim.tick(Clock.millis() + 20 * 60_000);
            check(live.present.size() > 15, "students tapped in: " + live.present.size());
        });
        test("injected AC failure heats the room until a HEAT alert appears", () -> {
            sim.inject(u("admin"), "R-L02", "AC_FAIL", 120);
            store.rooms.get("R-L02").acOn = true;
            long t = Clock.millis() + 21 * 60_000;
            for (int i = 0; i < 40 && openAlert("D-ENV-L02", "HEAT") == null; i++) sim.tick(t += 60_000);
            check(openAlert("D-ENV-L02", "HEAT") != null, "heat alert");
            eq(403, problem(() -> sim.inject(u("rkumar"), "R-L02", "VENT", 10)));
        });
        test("overview and map add up the live sensors", () -> {
            Map<String, Object> o = analytics.overview(u("admin"));
            eq(120, o.get("students"));
            check(((Number) o.get("powerKw")).doubleValue() > 20, "campus power");
            eq(6, ((List<?>) analytics.map().get("buildings")).size());
            check(analytics.overview(u("aditi")).containsKey("me"), "student overview has their report");
        });

        System.out.println("\n[web server]");
        test("HTTP: website, sign-in, CSRF header, roles, device ingest", () -> {
            Path front = Files.createTempDirectory("parisar-front");
            Files.writeString(front.resolve("index.html"), "<!doctype html><title>Parisar</title>");
            CloudService cloud = new CloudService(store, iot, Files.createTempDirectory("parisar-cloud"));
            ApiServer server = new ApiServer(front, new ApiServer.Parts(store, auth, iot, academic, analytics, assistant, energy, cloud, sim, model));
            server.start(0);
            try {
                String base = "http://127.0.0.1:" + server.port();
                HttpClient http = HttpClient.newHttpClient();
                eq(200, http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                eq(404, http.send(HttpRequest.newBuilder(URI.create(base + "/static/..%2fbackend")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                eq(401, http.send(HttpRequest.newBuilder(URI.create(base + "/api/overview")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                String login = "{\"username\":\"aditi\",\"password\":\"student123\"}";
                HttpResponse<String> noHeader = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString());
                eq(403, noHeader.statusCode());
                HttpResponse<String> ok = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login")).header("Content-Type", "application/json")
                        .header("X-Parisar", "1").POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString());
                eq(200, ok.statusCode());
                String cookie = ok.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
                check(ok.headers().firstValue("Set-Cookie").get().contains("HttpOnly"), "HttpOnly cookie");
                eq(200, http.send(HttpRequest.newBuilder(URI.create(base + "/api/overview")).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                eq(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/devices")).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                HttpResponse<String> dev = http.send(HttpRequest.newBuilder(URI.create(base + "/iot/ingest")).header("Content-Type", "application/json")
                        .header("X-Device-Id", SeedData.DEMO_DEVICE).header("X-Device-Key", SeedData.DEMO_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"temp\":23.4,\"occupancy\":3}")).build(), HttpResponse.BodyHandlers.ofString());
                eq(200, dev.statusCode());
                check(http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString())
                        .headers().firstValue("Content-Security-Policy").isPresent(), "CSP header");
            } finally {
                server.stop();
            }
        });

        Clock.fix(null);
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
    }

    static Alert openAlert(String deviceId, String rule) {
        for (Alert a : store.alerts.values()) if (a.isOpen() && deviceId.equals(a.deviceId) && rule.equals(a.rule)) return a;
        return null;
    }
}
