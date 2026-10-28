package com.cecil.energy;

import com.cecil.energy.insights.BillCalculator;
import com.cecil.energy.insights.EnergyMath;
import com.cecil.energy.insights.Insights;
import com.cecil.energy.model.Appliance;
import com.cecil.energy.model.Enums.ApplianceType;
import com.cecil.energy.model.Enums.Role;
import com.cecil.energy.model.Enums.UsageSource;
import com.cecil.energy.model.Room;
import com.cecil.energy.model.Settings;
import com.cecil.energy.model.UsageLog;
import com.cecil.energy.model.User;
import com.cecil.energy.service.AnalyticsService;
import com.cecil.energy.service.ApplianceService;
import com.cecil.energy.service.AuthService;
import com.cecil.energy.service.ServiceException;
import com.cecil.energy.service.SettingsService;
import com.cecil.energy.service.TipService;
import com.cecil.energy.service.UsageService;
import com.cecil.energy.store.DataStore;
import com.cecil.energy.store.SeedData;
import com.cecil.energy.util.Csv;
import com.cecil.energy.util.Json;
import com.cecil.energy.util.Passwords;
import com.cecil.energy.web.ApiServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All backend tests. Plain Java, no JUnit download needed.
 * Run with run-tests.bat / run-tests.sh (or "mvn test").
 */
public class AllTests {

    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        test("JSON read and write", AllTests::json);
        test("Password hashing", AllTests::passwords);
        test("Login, wrong password and lock-out", AllTests::loginLockout);
        test("Members cannot change appliances or rates", AllTests::permissions);
        test("Slab bill: 150 units = Rs 875", AllTests::slabBill);
        test("Full bill: duty, fixed charge, peak and off-peak", AllTests::fullBill);
        test("Hours in peak, also across midnight", AllTests::hoursInRange);
        test("Running time split by day and peak hours", AllTests::splitByDay);
        test("Live switch on/off records usage", AllTests::liveSwitch);
        test("Auto-off timer switches off the geyser", AllTests::autoOff);
        test("Typed usage record replaces the day's record", AllTests::manualUsage);
        test("Missing days are filled with estimates only", AllTests::autofill);
        test("Meter reading can only go up", AllTests::meter);
        test("AC temperature tip: 6% per degree, apply sets 24 C", AllTests::acTip);
        test("Standby and LED tips", AllTests::standbyAndLed);
        test("Peak-shift tip suggests an off-peak start", AllTests::peakShift);
        test("Spike detection (last week vs 4 weeks before)", AllTests::spike);
        test("Savings planner", AllTests::planner);
        test("Energy score is 0-100 with 6 parts", AllTests::score);
        test("Rates validation", AllTests::settingsValidation);
        test("CSV blocks spreadsheet formulas", AllTests::csv);
        test("Data is saved and loaded again", AllTests::persistence);
        test("Demo data looks right", AllTests::seed);
        test("HTTP API end to end", AllTests::http);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        for (String f : failures) System.out.println("  FAILED: " + f);
        if (failed > 0) System.exit(1);
    }

    // ================================================================== tiny test framework

    interface Body { void run() throws Exception; }

    static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  ok    " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + " -> " + t);
            System.out.println("  FAIL  " + name + " -> " + t);
        }
    }

    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static void near(double expected, double actual, double tol, String msg) {
        if (Math.abs(expected - actual) > tol) throw new AssertionError(msg + ": expected " + expected + " but got " + actual);
    }

    static void fails(int status, Body body) throws Exception {
        try {
            body.run();
        } catch (ServiceException e) {
            check(e.status == status, "expected status " + status + " but got " + e.status + " (" + e.getMessage() + ")");
            return;
        }
        throw new AssertionError("expected an error " + status);
    }

    /** A clock the tests can move forward. */
    static class TestClock extends Clock {
        Instant now;
        TestClock(LocalDateTime t) { now = t.toInstant(ZoneOffset.UTC); }
        void plusMinutes(long m) { now = now.plusSeconds(m * 60); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    static Path tempDir() throws IOException {
        return Files.createTempDirectory("energy-test");
    }

    /** An empty home with one manager, one member, one room. */
    static DataStore emptyStore(TestClock clock) throws IOException {
        DataStore s = new DataStore(tempDir(), clock);
        s.users.add(user("boss", Role.MANAGER));
        s.users.add(user("kid", Role.MEMBER));
        s.users.add(user("root", Role.ADMIN));
        Room r = new Room();
        r.id = "r1";
        r.name = "Hall";
        s.rooms.add(r);
        return s;
    }

    static User user(String name, Role role) {
        User u = new User();
        u.username = name;
        u.displayName = name;
        u.role = role;
        u.passwordHash = Passwords.hash("password1");
        return u;
    }

    static Appliance add(DataStore s, String id, ApplianceType type, double watts, double standby, double hours, int start) {
        Appliance a = new Appliance();
        a.id = id;
        a.name = id;
        a.type = type;
        a.roomId = "r1";
        a.watts = watts;
        a.standbyWatts = standby;
        a.hoursPerDay = hours;
        a.usualStartHour = start;
        a.createdAt = "2026-01-01T00:00:00";
        s.appliances.add(a);
        return a;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 28, 12, 0);

    // ================================================================== tests

    static void json() {
        String text = "{\"a\":1,\"b\":[true,null,\"x\\n\\u00e9\"],\"c\":{\"d\":2.5}}";
        Map<String, Object> m = Json.parseObject(text);
        check(m.get("a").equals(1L), "whole numbers become Long");
        check(((Map<?, ?>) m.get("c")).get("d").equals(2.5), "decimals become Double");
        check(Json.parse(Json.write(m)).equals(m), "write then read gives the same data");
        check(Json.write("</script>").contains("\\u003c"), "< is escaped");
        for (String bad : new String[] {"{", "[1,]", "{\"a\" 1}", "tru", "\"abc"}) {
            try {
                Json.parse(bad);
                throw new AssertionError("should fail: " + bad);
            } catch (IllegalArgumentException expected) { /* good */ }
        }
    }

    static void passwords() {
        String h = Passwords.hash("secret123");
        check(!h.contains("secret123"), "hash must not contain the password");
        check(Passwords.verify("secret123", h), "right password works");
        check(!Passwords.verify("secret124", h), "wrong password fails");
        check(!h.equals(Passwords.hash("secret123")), "random salt: same password, different hash");
    }

    static void loginLockout() throws Exception {
        DataStore s = emptyStore(new TestClock(NOON));
        AuthService auth = new AuthService(s);
        Map<String, Object> r = auth.login("boss", "password1");
        String token = (String) r.get("token");
        check(auth.require(token).username.equals("boss"), "token finds the user");
        for (int i = 0; i < 5; i++) fails(401, () -> auth.login("kid", "nope"));
        fails(429, () -> auth.login("kid", "password1")); // locked now, even with the right password
        auth.logout(token);
        fails(401, () -> auth.require(token));
    }

    static void permissions() throws Exception {
        DataStore s = emptyStore(new TestClock(NOON));
        ApplianceService ap = new ApplianceService(s);
        User kid = s.user("kid"), boss = s.user("boss");
        Map<String, Object> body = map("name", "Fan", "type", "FAN", "roomId", "r1", "watts", 70, "hoursPerDay", 5);
        fails(403, () -> ap.create(kid, body));
        Map<String, Object> made = ap.create(boss, body);
        // a member can still switch it on and off
        ap.setPower(kid, (String) made.get("id"), true);
        check(s.appliance((String) made.get("id")).on, "member can switch on");
        fails(403, () -> new SettingsService(s).update(kid, new Settings().toMap()));
        fails(403, () -> new AuthService(s).createUser(boss, map("username", "new1", "displayName", "N", "password", "password1")));
        fails(400, () -> ap.create(boss, map("name", "Bad", "type", "FAN", "roomId", "r1", "watts", -5, "hoursPerDay", 5)));
    }

    static void slabBill() {
        List<Settings.Slab> slabs = List.of(new Settings.Slab(100, 5.5), new Settings.Slab(200, 6.5), new Settings.Slab(0, 7.5));
        near(875, BillCalculator.slabCharge(150, slabs), 0.001, "100 x 5.5 + 50 x 6.5");
        near(550, BillCalculator.slabCharge(100, slabs), 0.001, "exactly first slab");
        near(550 + 650 + 7.5 * 50, BillCalculator.slabCharge(250, slabs), 0.001, "third slab");
        near(0, BillCalculator.slabCharge(0, slabs), 0.001, "zero units");
    }

    static void fullBill() {
        Settings s = new Settings();
        s.slabs = new ArrayList<>(List.of(new Settings.Slab(0, 5)));
        s.fixedCharge = 100;
        s.dutyPercent = 10;
        s.peakSurchargePercent = 20;
        s.offPeakRebatePercent = 10;
        BillCalculator.Bill b = BillCalculator.bill(100, 10, 20, s);
        // energy 500, peak +10*5*0.2=10, off-peak -20*5*0.1=10, duty 10% of 500 = 50, fixed 100
        near(500, b.energyCharge, 0.001, "energy");
        near(10, b.peakExtra, 0.001, "peak extra");
        near(10, b.offPeakDiscount, 0.001, "off-peak discount");
        near(50, b.duty, 0.001, "duty");
        near(650, b.total, 0.001, "total");
    }

    static void hoursInRange() {
        near(2, EnergyMath.hoursInRange(20, 3, 18, 22), 1e-9, "20:00-23:00 has 2 h of 18-22 peak");
        near(0, EnergyMath.hoursInRange(9, 3, 18, 22), 1e-9, "morning is not peak");
        near(3, EnergyMath.hoursInRange(23, 3, 22, 6), 1e-9, "23:00-02:00 is all inside 22-06");
        near(1.5, EnergyMath.hoursInRange(4.5, 3, 22, 6), 1e-9, "04:30-07:30 has 1.5 h of night");
        near(0, EnergyMath.hoursInRange(0, 24, 5, 5), 1e-9, "an empty range has no hours");
    }

    static void splitByDay() {
        Settings s = new Settings(); // peak 18-22, off-peak 22-06
        Map<LocalDate, double[]> m = EnergyMath.splitByDay(LocalDateTime.of(2026, 10, 1, 21, 30), LocalDateTime.of(2026, 10, 2, 1, 0), s);
        double[] d1 = m.get(LocalDate.of(2026, 10, 1)), d2 = m.get(LocalDate.of(2026, 10, 2));
        near(2.5, d1[0], 1e-9, "day 1 hours");
        near(0.5, d1[1], 1e-9, "day 1 peak (21:30-22:00)");
        near(2.0, d1[2], 1e-9, "day 1 off-peak (22-24)");
        near(1.0, d2[0], 1e-9, "day 2 hours");
        near(1.0, d2[2], 1e-9, "day 2 off-peak");
    }

    static void liveSwitch() throws Exception {
        TestClock c = new TestClock(LocalDateTime.of(2026, 10, 28, 17, 0));
        DataStore s = emptyStore(c);
        Appliance tv = add(s, "tv", ApplianceType.TELEVISION, 100, 2, 4, 19);
        ApplianceService ap = new ApplianceService(s);
        ap.setPower(s.user("kid"), "tv", true);
        c.plusMinutes(150); // 17:00 -> 19:30
        ap.setPower(s.user("kid"), "tv", false);
        check(!tv.on, "is off");
        UsageLog u = s.usage.get(0);
        near(2.5, u.hours, 1e-6, "ran 2.5 h");
        near(1.5, u.peakHours, 1e-6, "18:00-19:30 is peak");
        near(0.25, u.kwh, 1e-6, "100 W x 2.5 h = 0.25 kWh");
        near(2 * 21.5 / 1000, u.standbyKwh, 1e-6, "standby for the other 21.5 h");
        check(u.source == UsageSource.LIVE, "source is LIVE");
        // switching on again the same day adds to the same record
        ap.setPower(s.user("kid"), "tv", true);
        c.plusMinutes(30);
        ap.setPower(s.user("kid"), "tv", false);
        check(s.usage.size() == 1, "still one record for the day");
        near(3.0, s.usage.get(0).hours, 1e-6, "2.5 + 0.5 h");
    }

    static void autoOff() throws Exception {
        TestClock c = new TestClock(LocalDateTime.of(2026, 10, 28, 6, 0));
        DataStore s = emptyStore(c);
        Appliance g = add(s, "geyser", ApplianceType.WATER_HEATER, 2000, 0, 0.5, 6);
        g.autoOffMinutes = 30;
        ApplianceService ap = new ApplianceService(s);
        ap.setPower(s.user("kid"), "geyser", true);
        c.plusMinutes(20);
        check(ap.autoOffSweep().isEmpty(), "not yet after 20 minutes");
        c.plusMinutes(25); // 45 minutes, sweep runs late
        check(ap.autoOffSweep().size() == 1, "switched off");
        check(!g.on, "geyser is off");
        near(0.5, s.usage.get(0).hours, 1e-6, "counted only up to the 30-minute timer");
        near(1.0, s.usage.get(0).kwh, 1e-6, "2000 W x 0.5 h = 1 kWh");
    }

    static void manualUsage() throws Exception {
        DataStore s = emptyStore(new TestClock(NOON));
        add(s, "fan", ApplianceType.FAN, 75, 0, 8, 13);
        UsageService us = new UsageService(s);
        us.saveManual(s.user("kid"), null, map("applianceId", "fan", "date", "2026-10-27", "hours", 4));
        us.saveManual(s.user("kid"), null, map("applianceId", "fan", "date", "2026-10-27", "hours", 6));
        check(s.usage.size() == 1, "one record per appliance per day");
        near(0.45, s.usage.get(0).kwh, 1e-6, "75 W x 6 h");
        fails(400, () -> us.saveManual(s.user("kid"), null, map("applianceId", "fan", "date", "2026-10-29", "hours", 1)));
        fails(400, () -> us.saveManual(s.user("kid"), null, map("applianceId", "fan", "date", "2026-10-20", "hours", 25)));
        // a member cannot edit someone else's record
        String id = s.usage.get(0).id;
        s.usage.get(0).by = "boss";
        fails(403, () -> us.saveManual(s.user("kid"), id, map("hours", 2)));
    }

    static void autofill() throws Exception {
        DataStore s = emptyStore(new TestClock(NOON));
        add(s, "fan", ApplianceType.FAN, 100, 0, 5, 13);
        UsageService us = new UsageService(s);
        us.saveManual(s.user("kid"), null, map("applianceId", "fan", "date", "2026-10-25", "hours", 1));
        int made = us.autofill(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 27), "test");
        check(made == 3, "3 empty days filled, got " + made);
        for (UsageLog u : s.usage) {
            if (u.date.equals("2026-10-25")) check(u.source == UsageSource.MANUAL && u.hours == 1, "real record kept");
            else check(u.source == UsageSource.ESTIMATE && u.hours == 5, "estimate uses normal hours");
        }
    }

    static void meter() throws Exception {
        DataStore s = emptyStore(new TestClock(NOON));
        UsageService us = new UsageService(s);
        us.addMeter(s.user("kid"), map("date", "2026-10-01", "reading", 1000));
        us.addMeter(s.user("kid"), map("date", "2026-10-15", "reading", 1200));
        fails(400, () -> us.addMeter(s.user("kid"), map("date", "2026-10-20", "reading", 1100)));
        fails(400, () -> us.addMeter(s.user("kid"), map("date", "2026-10-10", "reading", 1300)));
        fails(409, () -> us.addMeter(s.user("kid"), map("date", "2026-10-15", "reading", 1250)));
        us.addMeter(s.user("kid"), map("date", "2026-10-08", "reading", 1100));
        List<Map<String, Object>> list = us.meterReadings();
        check(list.size() == 3, "3 readings");
        near(100, ((Number) list.get(0).get("unitsSincePrevious")).doubleValue(), 1e-6, "1200 - 1100");
    }

    static void acTip() throws Exception {
        DataStore s = emptyStore(new TestClock(NOON));
        Appliance ac = add(s, "ac", ApplianceType.AIR_CONDITIONER, 1500, 0, 4, 23);
        ac.setTemp = 20;
        Insights in = new Insights(s);
        Insights.Tip t = in.tipsFor(ac).stream().filter(x -> x.rule.equals("AC_TEMP")).findFirst().orElseThrow();
        // 1.5 kW x 4 h x 30 days = 180 kWh; 4 degrees x 6% = 24% -> 43.2 kWh
        near(43.2, t.kwhPerMonth, 0.01, "AC saving");
        check(t.rupeesPerMonth > 0 && t.cost == 0 && t.paybackMonths == 0, "free tip with rupee saving");
        TipService tips = new TipService(s);
        fails(403, () -> tips.apply(s.user("kid"), t.key));
        tips.apply(s.user("boss"), t.key);
        check(ac.setTemp == 24, "temperature changed to 24");
        check(new Insights(s).openTips().stream().noneMatch(x -> x.key.equals(t.key)), "tip is gone");
        fails(409, () -> tips.dismiss(s.user("boss"), t.key));
    }

    static void standbyAndLed() throws Exception {
        DataStore s = emptyStore(new TestClock(NOON));
        add(s, "stb", ApplianceType.SET_TOP_BOX, 15, 12, 4, 19);
        add(s, "tiny", ApplianceType.LAPTOP, 65, 0.5, 4, 14);
        Appliance tube = add(s, "tube", ApplianceType.LIGHT, 80, 0, 5, 18);
        tube.quantity = 2;
        List<Insights.Tip> all = new Insights(s).openTips();
        check(all.stream().anyMatch(t -> t.key.equals("STANDBY:stb")), "12 W standby gets a tip");
        check(all.stream().noneMatch(t -> t.key.equals("STANDBY:tiny")), "0.5 W standby is too small for a tip");
        Insights.Tip led = all.stream().filter(t -> t.key.equals("LED:tube")).findFirst().orElseThrow();
        // 80 W -> 2 x 20 W: half of 80 W x 5 h x 30 = 6 kWh
        near(6, led.kwhPerMonth, 0.01, "LED saving");
        near(700, led.cost, 0.01, "2 LED tubes cost");
    }

    static void peakShift() throws Exception {
        DataStore s = emptyStore(new TestClock(NOON));
        Appliance wm = add(s, "wm", ApplianceType.WASHING_MACHINE, 500, 0, 1, 19);
        Insights in = new Insights(s);
        check(in.suggestedStart(wm) == 5, "1-hour job: start at 05:00 to end when off-peak ends");
        Insights.Tip t = in.tipsFor(wm).stream().filter(x -> x.rule.equals("PEAK_SHIFT")).findFirst().orElseThrow();
        check(t.kwhPerMonth == 0 && t.rupeesPerMonth > 0, "same units, lower bill");
        Appliance tv = add(s, "tv", ApplianceType.TELEVISION, 100, 0, 3, 19);
        check(in.tipsFor(tv).stream().noneMatch(x -> x.rule.equals("PEAK_SHIFT")), "TV is not shiftable");
    }

    static void spike() throws Exception {
        TestClock c = new TestClock(NOON);
        DataStore s = emptyStore(c);
        add(s, "fridge", ApplianceType.REFRIGERATOR, 45, 0, 24, 0);
        for (int i = 1; i <= 35; i++) {
            UsageLog u = new UsageLog();
            u.id = "u" + i;
            u.applianceId = "fridge";
            u.date = LocalDate.of(2026, 10, 28).minusDays(i).toString();
            u.hours = 24;
            u.kwh = i <= 7 ? 1.6 : 1.0;
            s.usage.add(u);
        }
        Insights in = new Insights(s);
        Insights.ApplianceStats st = in.stats("fridge");
        near(1.6, st.last7Avg, 1e-9, "last week");
        near(1.0, st.prev28Avg, 1e-9, "weeks before");
        check(Insights.isSpike(st), "60% jump is a spike");
        check(in.alerts(0, 0).stream().anyMatch(a -> "SPIKE".equals(a.get("kind"))), "spike alert shown");
    }

    static void planner() throws Exception {
        DataStore s = emptyStore(new TestClock(NOON));
        Appliance ac = add(s, "ac", ApplianceType.AIR_CONDITIONER, 1000, 0, 5, 23);
        ac.setTemp = 22;
        AnalyticsService an = new AnalyticsService(s, new ApplianceService(s));
        Map<String, Object> r = an.plan(map("changes", List.of(map("applianceId", "ac", "setTemp", 24, "hoursPerDay", 4))));
        // before 1 kW x 5 h x 30 = 150; after 1 kW x 0.88 x 4 h x 30 = 105.6
        near(44.4, ((Number) r.get("savedKwh")).doubleValue(), 0.11, "planner units saved");
        check(((Number) r.get("savedRupees")).doubleValue() > 0, "planner rupees saved");
        check(ac.hoursPerDay == 5 && ac.setTemp == 22, "planner does not change anything");
    }

    @SuppressWarnings("unchecked")
    static void score() throws Exception {
        DataStore s = new DataStore(tempDir(), new TestClock(NOON));
        SeedData.fill(s);
        Map<String, Object> sc = new Insights(s).score(500);
        int v = ((Number) sc.get("score")).intValue();
        check(v >= 0 && v <= 100, "score in range");
        List<Map<String, Object>> f = (List<Map<String, Object>>) sc.get("factors");
        check(f.size() == 6, "6 parts");
        int sum = 0, max = 0;
        for (Map<String, Object> x : f) {
            sum += ((Number) x.get("points")).intValue();
            max += ((Number) x.get("max")).intValue();
        }
        check(sum == v && max == 100, "points add up to the score, maximum 100");
    }

    static void settingsValidation() throws Exception {
        DataStore s = emptyStore(new TestClock(NOON));
        SettingsService ss = new SettingsService(s);
        User boss = s.user("boss");
        Map<String, Object> ok = new Settings().toMap();
        ss.update(boss, ok);
        Map<String, Object> overlap = new Settings().toMap();
        overlap.put("offPeakStart", 20);
        fails(400, () -> ss.update(boss, overlap));
        Map<String, Object> badSlabs = new Settings().toMap();
        badSlabs.put("slabs", List.of(map("upTo", 200, "rate", 5), map("upTo", 100, "rate", 6), map("upTo", 0, "rate", 7)));
        fails(400, () -> ss.update(boss, badSlabs));
        Map<String, Object> bill = ss.billPreview(150, 0);
        near(100 + 875 * 1.05 + (150 - 100) * 0 + 0, ((Number) bill.get("total")).doubleValue(), 0.01, "bill preview, 5% duty");
    }

    static void csv() {
        check(Csv.cell("=CMD()").equals("'=CMD()"), "formula is made harmless");
        check(Csv.cell("-5").equals("-5"), "negative numbers stay numbers");
        check(Csv.cell("a,b").equals("\"a,b\""), "comma is quoted");
        check(Csv.cell("say \"hi\"").equals("\"say \"\"hi\"\"\""), "quotes are doubled");
    }

    static void persistence() throws Exception {
        TestClock c = new TestClock(NOON);
        DataStore s = emptyStore(c);
        add(s, "fan", ApplianceType.FAN, 75, 0, 8, 13).notes = "test \"note\" ₹";
        s.saveAll();
        DataStore again = new DataStore(s.dir(), c);
        check(again.load(), "loads");
        check(again.appliances.size() == 1 && again.appliance("fan").notes.equals("test \"note\" ₹"), "appliance and text come back");
        check(again.users.size() == 3 && Passwords.verify("password1", again.user("boss").passwordHash), "users come back");
        try (var files = Files.list(s.dir())) {
            check(files.noneMatch(p -> p.toString().endsWith(".tmp")), "no temp files left");
        }
    }

    static void seed() throws Exception {
        DataStore s = new DataStore(tempDir(), new TestClock(NOON));
        SeedData.fill(s);
        check(s.users.size() == 4 && s.rooms.size() == 7 && s.appliances.size() == 28, "users, rooms, appliances");
        check(s.usage.size() > 28 * 180, "6 months of usage");
        Insights in = new Insights(s);
        check(in.openTips().size() >= 10, "plenty of tips");
        check(in.last30().kwh > 300 && in.last30().kwh < 900, "realistic monthly use: " + in.last30().kwh);
        check(Insights.isSpike(in.stats("ap07")), "the fridge problem is detected");
    }

    @SuppressWarnings("unchecked")
    static void http() throws Exception {
        Path root = Files.createTempDirectory("energy-web");
        Path fe = Files.createDirectories(root.resolve("frontend"));
        Files.writeString(fe.resolve("index.html"), "<!doctype html><title>t</title>");
        Files.writeString(root.resolve("secret.txt"), "no");
        DataStore s = new DataStore(root.resolve("data"), Clock.systemUTC());
        SeedData.fill(s);
        ApiServer server = new ApiServer(s, fe);
        server.start("127.0.0.1", 0);
        try {
            String base = "http://127.0.0.1:" + server.port();
            HttpClient hc = HttpClient.newHttpClient();
            check(get(hc, base + "/", null).statusCode() == 200, "home page");
            check(get(hc, base + "/static/..%2fsecret.txt", null).statusCode() == 404, "no files outside frontend");
            check(get(hc, base + "/api/dashboard", null).statusCode() == 401, "needs sign-in");
            HttpResponse<String> login = post(hc, base + "/api/auth/login", null, "{\"username\":\"meera\",\"password\":\"manager123\"}", null);
            check(login.statusCode() == 200, "login works");
            String token = (String) Json.parseObject(login.body()).get("token");
            HttpResponse<String> dash = get(hc, base + "/api/dashboard", token);
            check(dash.statusCode() == 200 && dash.body().contains("\"kpis\""), "dashboard");
            check(dash.headers().firstValue("Content-Security-Policy").isPresent(), "security headers");
            HttpResponse<String> on = post(hc, base + "/api/appliances/ap02/power", token, "{\"on\":true}", null);
            check(on.statusCode() == 200 && Json.parseObject(on.body()).get("on").equals(true), "switch on");
            check(post(hc, base + "/api/appliances/ap02/power", token, "{\"on\":false}", "http://evil.example").statusCode() == 403, "cross-site blocked");
            check(post(hc, base + "/api/tips/AC_TEMP:ap13/apply", token, "{}", null).statusCode() == 200, "apply tip");
            HttpResponse<String> csv = get(hc, base + "/api/usage/export.csv?appliance=ap13", token);
            check(csv.statusCode() == 200 && csv.body().contains("Window AC"), "CSV export");
            HttpResponse<String> rep = get(hc, base + "/api/reports?months=12", token);
            check(((List<Object>) Json.parseObject(rep.body()).get("monthly")).size() == 12, "12-month report");
            check(post(hc, base + "/api/planner", token, "not json", null).statusCode() == 400, "bad JSON is rejected");
        } finally {
            server.stop();
        }
    }

    static HttpResponse<String> get(HttpClient hc, String url, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return hc.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(HttpClient hc, String url, String token, String body, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (origin != null) b.header("Origin", origin);
        return hc.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
