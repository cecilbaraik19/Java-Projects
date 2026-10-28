package com.cecil.edge.store;

import com.cecil.edge.model.Alert;
import com.cecil.edge.model.Command;
import com.cecil.edge.model.DayStat;
import com.cecil.edge.model.EdgeNode;
import com.cecil.edge.model.Enums.AlertStatus;
import com.cecil.edge.model.Enums.CommandStatus;
import com.cecil.edge.model.Enums.CommandType;
import com.cecil.edge.model.Enums.Condition;
import com.cecil.edge.model.Enums.NodeMode;
import com.cecil.edge.model.Enums.Role;
import com.cecil.edge.model.Enums.SensorType;
import com.cecil.edge.model.Enums.Severity;
import com.cecil.edge.model.HourAgg;
import com.cecil.edge.model.Rule;
import com.cecil.edge.model.Sensor;
import com.cecil.edge.model.Site;
import com.cecil.edge.model.User;
import com.cecil.edge.util.Num;
import com.cecil.edge.util.Passwords;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fills an empty data folder with a demo setup (all names are made up):
 * 4 sites, 8 edge nodes, 30 sensors, 10 rules, 14 days of history and some past alerts.
 */
public final class SeedData {
    private SeedData() { }

    public static void fill(DataStore db, long now, ZoneId zone) {
        Random rnd = new Random(42);
        users(db, now);
        sites(db);
        nodes(db, now);
        sensors(db);
        rules(db, now);
        history(db, now, zone, rnd);
        alerts(db, now, rnd);
        commands(db, now);
        db.addEvent(now - 60_000, "system", "setup", null, "Demo data created: 4 sites, 8 edge nodes, " + db.sensors.size() + " sensors");
    }

    public static User user(String username, String name, Role role, String password, long now) {
        User u = new User();
        u.username = username;
        u.displayName = name;
        u.role = role;
        u.salt = Passwords.newSalt();
        u.hash = Passwords.hash(password, u.salt);
        u.createdAt = now;
        return u;
    }

    private static void users(DataStore db, long now) {
        for (User u : List.of(
                user("admin", "Admin", Role.ADMIN, "admin123", now),
                user("priya", "Priya Sharma", Role.ENGINEER, "engineer123", now),
                user("kabir", "Kabir Singh", Role.ENGINEER, "engineer123", now),
                user("arjun", "Arjun Mehta", Role.VIEWER, "viewer123", now))) {
            db.users.put(u.username, u);
        }
    }

    private static void site(DataStore db, String id, String name, String location, String kind) {
        Site s = new Site();
        s.id = id;
        s.name = name;
        s.location = location;
        s.kind = kind;
        db.sites.put(id, s);
    }

    private static void sites(DataStore db) {
        site(db, "ST-101", "Press Shop - Line A", "Ranchi, Jharkhand", "Factory");
        site(db, "ST-102", "Cold Storage Hub", "Jamshedpur, Jharkhand", "Warehouse");
        site(db, "ST-103", "Green Acres Farm", "Hazaribagh, Jharkhand", "Farm");
        site(db, "ST-104", "City Water Pumping Station", "Dhanbad, Jharkhand", "Utility");
    }

    private static EdgeNode node(DataStore db, String id, String name, String site, String hw, String ip, NodeMode mode,
                                 int windowSec, double deadband, long now) {
        EdgeNode n = new EdgeNode();
        n.id = id;
        n.name = name;
        n.siteId = site;
        n.hardware = hw;
        n.ip = ip;
        n.mode = mode;
        n.windowSec = windowSec;
        n.deadbandPct = deadband;
        n.createdAt = now - 40L * 86_400_000L;
        String key = Passwords.nodeKey();
        n.keyHash = Passwords.sha256(key);
        n.keyHint = key.substring(key.length() - 4);
        db.nodes.put(id, n);
        return n;
    }

    private static void nodes(DataStore db, long now) {
        node(db, "N-101", "press-line-gw-01", "ST-101", "Raspberry Pi 4 (4 GB)", "10.10.1.21", NodeMode.SMART, 60, 2, now);
        node(db, "N-102", "paint-booth-gw-02", "ST-101", "Intel NUC 11", "10.10.1.22", NodeMode.AGGREGATE, 60, 2, now);
        EdgeNode raw = node(db, "N-103", "compressor-gw-03", "ST-101", "NVIDIA Jetson Nano", "10.10.1.23", NodeMode.RAW, 60, 2, now);
        raw.notes = "Left in RAW mode on purpose, to compare against the other nodes.";
        node(db, "N-104", "cold-room-gw-01", "ST-102", "Raspberry Pi 4 (2 GB)", "10.20.4.11", NodeMode.SMART, 60, 3, now);
        node(db, "N-105", "dock-gw-02", "ST-102", "Raspberry Pi 3B+", "10.20.4.12", NodeMode.FILTER, 60, 2, now);
        EdgeNode field = node(db, "N-106", "field-gw-01", "ST-103", "LoRaWAN gateway (ARM)", "192.168.8.40", NodeMode.AGGREGATE, 300, 2, now);
        field.notes = "Solar powered, 4G uplink. Long 5-minute windows save mobile data.";
        node(db, "N-107", "pump-gw-01", "ST-104", "Siemens IOT2050", "172.16.0.31", NodeMode.SMART, 60, 1.5, now);
        EdgeNode real = node(db, "N-108", "pump-gw-02", "ST-104", "Raspberry Pi 4 (your real device)", "172.16.0.32", NodeMode.SMART, 60, 2, now);
        real.simulated = false;
        real.enabled = false;
        real.notes = "Slot for a real device. Turn it on, create a new key, then post readings to /api/ingest (see the README).";
    }

    private static int sensorSeq = 100;

    private static void sensor(DataStore db, String node, String name, SensorType t, double low, double high,
                               double base, double swing, double noise, int sample) {
        Sensor s = new Sensor();
        s.id = "S-" + (++sensorSeq);
        s.nodeId = node;
        s.name = name;
        s.type = t;
        s.unit = t.unit;
        s.low = low;
        s.high = high;
        s.base = base;
        s.swing = swing;
        s.noise = noise;
        s.sampleSec = sample;
        db.sensors.put(s.id, s);
    }

    private static void sensors(DataStore db) {
        sensorSeq = 100;
        sensor(db, "N-101", "Motor vibration", SensorType.VIBRATION, 0.5, 7.1, 3.0, 0.6, 0.25, 5);
        sensor(db, "N-101", "Bearing temperature", SensorType.TEMPERATURE, 30, 80, 58, 4, 0.3, 5);
        sensor(db, "N-101", "Hydraulic pressure", SensorType.PRESSURE, 6, 10, 8.2, 0.3, 0.08, 5);
        sensor(db, "N-101", "Line power", SensorType.POWER, 20, 90, 56, 12, 2.0, 5);

        sensor(db, "N-102", "Booth temperature", SensorType.TEMPERATURE, 22, 28, 25, 1.2, 0.15, 5);
        sensor(db, "N-102", "Booth humidity", SensorType.HUMIDITY, 40, 60, 50, 4, 0.6, 5);
        sensor(db, "N-102", "Booth air quality", SensorType.CO2, 400, 1000, 640, 80, 15, 5);
        sensor(db, "N-102", "Exhaust fan power", SensorType.POWER, 2, 8, 5.2, 0.6, 0.15, 5);

        sensor(db, "N-103", "Compressor vibration", SensorType.VIBRATION, 0.5, 7.1, 3.6, 0.4, 0.3, 5);
        sensor(db, "N-103", "Outlet pressure", SensorType.PRESSURE, 6.5, 9, 7.8, 0.2, 0.06, 5);
        sensor(db, "N-103", "Compressor power", SensorType.POWER, 10, 45, 30, 5, 1.5, 5);

        sensor(db, "N-104", "Freezer room temperature", SensorType.TEMPERATURE, -24, -16, -19.5, 0.6, 0.12, 5);
        sensor(db, "N-104", "Chiller room temperature", SensorType.TEMPERATURE, 2, 6, 3.8, 0.4, 0.1, 5);
        sensor(db, "N-104", "Cold room humidity", SensorType.HUMIDITY, 70, 90, 82, 2, 0.5, 5);
        sensor(db, "N-104", "Refrigeration power", SensorType.POWER, 15, 60, 38, 8, 1.2, 5);

        sensor(db, "N-105", "Dock temperature", SensorType.TEMPERATURE, 12, 40, 27, 6, 0.25, 5);
        sensor(db, "N-105", "Dock humidity", SensorType.HUMIDITY, 30, 80, 58, 10, 0.8, 5);
        sensor(db, "N-105", "Forklift charger power", SensorType.POWER, 0, 25, 11, 4, 0.6, 5);

        sensor(db, "N-106", "Soil moisture - zone 1", SensorType.SOIL, 25, 45, 35, 2, 0.3, 15);
        sensor(db, "N-106", "Soil moisture - zone 2", SensorType.SOIL, 25, 45, 31, 2, 0.3, 15);
        sensor(db, "N-106", "Greenhouse temperature", SensorType.TEMPERATURE, 18, 38, 28, 6, 0.2, 15);
        sensor(db, "N-106", "Greenhouse humidity", SensorType.HUMIDITY, 45, 85, 66, 8, 0.7, 15);
        sensor(db, "N-106", "Drip line flow", SensorType.FLOW, 0, 60, 24, 6, 1.0, 15);

        sensor(db, "N-107", "Inlet pressure", SensorType.PRESSURE, 1, 3, 2.0, 0.15, 0.03, 5);
        sensor(db, "N-107", "Outlet pressure", SensorType.PRESSURE, 4, 7, 5.6, 0.3, 0.06, 5);
        sensor(db, "N-107", "Pump vibration", SensorType.VIBRATION, 0.5, 7.1, 2.4, 0.3, 0.2, 5);
        sensor(db, "N-107", "Main line flow", SensorType.FLOW, 400, 800, 610, 60, 8, 5);
        sensor(db, "N-107", "Reservoir level", SensorType.LEVEL, 30, 95, 68, 10, 0.4, 5);

        sensor(db, "N-108", "Backup pump pressure", SensorType.PRESSURE, 4, 7, 5.5, 0.3, 0.06, 5);
        sensor(db, "N-108", "Backup pump power", SensorType.POWER, 5, 40, 22, 4, 1.0, 5);
    }

    private static Rule rule(DataStore db, String id, String name, SensorType type, String sensorId, Condition c,
                             double th, int dur, Severity sev, String action, long now) {
        Rule r = new Rule();
        r.id = id;
        r.name = name;
        r.sensorType = type;
        r.sensorId = sensorId;
        r.condition = c;
        r.threshold = th;
        r.durationSec = dur;
        r.severity = sev;
        r.action = action;
        r.createdBy = "admin";
        r.createdAt = now - 30L * 86_400_000L;
        db.rules.put(id, r);
        return r;
    }

    private static void rules(DataStore db, long now) {
        rule(db, "R-101", "Bearing overheating", null, "S-102", Condition.ABOVE, 75, 20, Severity.CRITICAL, "Slow press line to 50%", now);
        rule(db, "R-102", "High vibration (ISO 10816 zone C)", SensorType.VIBRATION, null, Condition.ABOVE, 7.1, 15, Severity.WARNING, "Notify maintenance team", now);
        rule(db, "R-103", "Freezer too warm", null, "S-112", Condition.ABOVE, -15, 60, Severity.CRITICAL, "Start backup compressor", now);
        rule(db, "R-104", "Fast temperature change", SensorType.TEMPERATURE, null, Condition.RATE_OF_CHANGE, 3, 0, Severity.WARNING, null, now);
        rule(db, "R-105", "Unusual reading", null, null, Condition.ANOMALY, 4.5, 0, Severity.INFO, "Mark for review", now);
        rule(db, "R-106", "Reservoir low", SensorType.LEVEL, null, Condition.BELOW, 25, 30, Severity.CRITICAL, "Start fill pump", now);
        rule(db, "R-107", "Dry soil", SensorType.SOIL, null, Condition.BELOW, 22, 120, Severity.WARNING, "Open irrigation valve", now);
        rule(db, "R-108", "Sensor silent", null, null, Condition.NO_DATA, 60, 0, Severity.WARNING, null, now);
        rule(db, "R-109", "Poor air quality", SensorType.CO2, null, Condition.ABOVE, 1200, 30, Severity.WARNING, "Boost exhaust fan", now);
        rule(db, "R-110", "Pump outlet pressure low", null, "S-125", Condition.BELOW, 4, 30, Severity.CRITICAL, "Switch to standby pump", now);
    }

    /** 14 days of hourly sensor history and daily traffic numbers per node. */
    private static void history(DataStore db, long now, ZoneId zone, Random rnd) {
        int days = db.settings.historyDays;
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone);
        long hourNow = now - Math.floorMod(now, 3_600_000L);
        long start = today.minusDays(days - 1).atStartOfDay(zone).toInstant().toEpochMilli();

        for (Sensor s : db.sensors.values()) {
            EdgeNode n = db.nodes.get(s.nodeId);
            if (!n.simulated) continue;
            double walk = 0;
            for (long h = start; h < hourNow; h += 3_600_000L) {
                double hour = LocalTime.ofInstant(Instant.ofEpochMilli(h + 1_800_000L), zone).toSecondOfDay() / 3600.0;
                double wave = Math.sin(2 * Math.PI * (hour - 9) / 24.0);
                walk = walk * 0.8 + rnd.nextGaussian() * s.noise * 0.4;
                double avg = s.base + s.swing * wave + walk;
                double spread = 2.5 * s.noise + Math.abs(s.swing) * 0.13;
                HourAgg a = new HourAgg();
                a.sensorId = s.id;
                a.hour = h;
                a.n = 3600 / s.sampleSec;
                a.min = Num.round(avg - spread * (0.8 + rnd.nextDouble() * 0.4), 3);
                a.max = Num.round(avg + spread * (0.8 + rnd.nextDouble() * 0.4), 3);
                a.sum = Num.round(avg * a.n, 3);
                db.hours.put(s.id + "|" + h, a);
            }
        }

        long dayStartToday = today.atStartOfDay(zone).toInstant().toEpochMilli();
        for (EdgeNode n : db.nodes.values()) {
            if (!n.simulated) continue;
            List<Sensor> ss = new ArrayList<>();
            for (Sensor s : db.sensors.values()) if (n.id.equals(s.nodeId)) ss.add(s);
            for (int d = days - 1; d >= 0; d--) {
                LocalDate date = today.minusDays(d);
                double frac = d == 0 ? (now - dayStartToday) / 86_400_000.0 : 1.0;
                double up = 1.0;
                if (n.id.equals("N-106") && d == 5) up = 1 - 2.6 / 24;      // field node lost power one night
                if (n.id.equals("N-103") && d == 9) up = 1 - 0.4 / 24;      // reboot after firmware update
                DayStat st = new DayStat();
                st.date = date.toString();
                st.nodeId = n.id;
                long raw = 0;
                for (Sensor s : ss) raw += Math.round(86_400.0 / s.sampleSec * frac * up);
                st.raw = raw;
                st.invalid = Math.round(raw * (0.0006 + rnd.nextDouble() * 0.0012));
                long valid = raw - st.invalid;
                int b = db.settings.bytesRaw;
                int bs = db.settings.bytesSummary;
                double perWindow = n.windowSec / 5.0 * (ss.isEmpty() ? 1 : 5.0 / ss.get(0).sampleSec);
                switch (n.mode) {
                    case RAW -> { st.forwarded = valid; st.fwdBytes = valid * b; }
                    case FILTER -> {
                        long k = Math.round(valid * (0.28 + rnd.nextDouble() * 0.08));
                        st.forwarded = k;
                        st.fwdBytes = k * b;
                    }
                    case AGGREGATE -> {
                        long k = Math.round(valid / perWindow);
                        st.forwarded = k;
                        st.fwdBytes = k * bs;
                    }
                    case SMART -> {
                        long sum = Math.round(valid / perWindow);
                        long events = Math.round(valid * (0.025 + rnd.nextDouble() * 0.02));
                        st.forwarded = sum + events;
                        st.fwdBytes = sum * bs + events * b;
                    }
                }
                st.rawBytes = raw * b;
                if (n.id.equals("N-104") && d == 3) st.buffered = 1840;
                st.edgeMsN = valid;
                st.edgeMsSum = Num.round(valid * (db.settings.localHopMs + 0.03 + rnd.nextDouble() * 0.04), 2);
                st.watchedSec = Math.round(86_400 * frac);
                st.onlineSec = Math.round(86_400 * frac * up);
                db.days.put(st.key(), st);
            }
        }
    }

    private static void pastAlert(DataStore db, String ruleId, String ruleName, String node, String sensor, Severity sev,
                                  String msg, double value, double th, long at, int ackMin, int resolveMin,
                                  String ackBy, String resolvedBy, String note, String action, Random rnd) {
        Alert a = new Alert();
        a.id = db.nextId("A", db.alerts.keySet());
        a.ruleId = ruleId;
        a.ruleName = ruleName;
        a.nodeId = node;
        a.sensorId = sensor;
        a.severity = sev;
        a.message = msg;
        a.value = value;
        a.threshold = th;
        a.openedAt = at;
        a.lastSeenAt = at + 60_000L * Math.max(1, resolveMin / 3);
        a.clearedAt = a.lastSeenAt + 30_000;
        a.action = action;
        if (!ruleId.startsWith("sys-")) {
            a.edgeMs = Num.round(db.settings.localHopMs + 0.02 + rnd.nextDouble() * 0.05, 3);
            a.cloudMs = Num.round(db.settings.localHopMs + db.settings.cloudRttMs + rnd.nextDouble() * 40, 1);
        }
        if (ackMin >= 0) {
            a.status = AlertStatus.ACKNOWLEDGED;
            a.ackBy = ackBy;
            a.ackAt = at + ackMin * 60_000L;
        }
        if (resolveMin >= 0) {
            a.status = AlertStatus.RESOLVED;
            a.resolvedBy = resolvedBy;
            a.resolvedAt = at + resolveMin * 60_000L;
            a.note = note;
        }
        db.alerts.put(a.id, a);
        Rule r = db.rules.get(ruleId);
        if (r != null) { r.hits++; r.lastHit = Math.max(r.lastHit, at); }
    }

    private static void alerts(DataStore db, long now, Random rnd) {
        long D = 86_400_000L;
        long H = 3_600_000L;
        pastAlert(db, "R-101", "Bearing overheating", "N-101", "S-102", Severity.CRITICAL, "Bearing temperature is 77.4 °C (limit 75 °C)", 77.4, 75,
                now - 12 * D - 5 * H, 4, 52, "priya", "priya", "Bearing re-greased; back to 60 °C.", "Slow press line to 50%", rnd);
        pastAlert(db, "R-102", "High vibration (ISO 10816 zone C)", "N-103", "S-109", Severity.WARNING, "Compressor vibration is 7.6 mm/s (limit 7.1 mm/s)", 7.6, 7.1,
                now - 11 * D - 2 * H, 12, 140, "kabir", "kabir", "Loose mounting bolt tightened.", "Notify maintenance team", rnd);
        pastAlert(db, "R-103", "Freezer too warm", "N-104", "S-112", Severity.CRITICAL, "Freezer room temperature is -14.2 °C (limit -15 °C)", -14.2, -15,
                now - 10 * D - 7 * H, 3, 35, "priya", "priya", "Loading door was left open. Staff reminded.", "Start backup compressor", rnd);
        pastAlert(db, "sys-cpu", "High CPU", "N-103", null, Severity.WARNING, "compressor-gw-03 CPU at 91%", 91, 85,
                now - 9 * D - 3 * H, 20, 75, "kabir", "kabir", "Firmware update made RAW mode heavier; rebooted.", null, rnd);
        pastAlert(db, "R-109", "Poor air quality", "N-102", "S-107", Severity.WARNING, "Booth air quality is 1,310 ppm (limit 1,200 ppm)", 1310, 1200,
                now - 8 * D - 6 * H, 6, 24, "priya", "priya", "Exhaust filter replaced.", "Boost exhaust fan", rnd);
        pastAlert(db, "R-105", "Unusual reading", "N-107", "S-126", Severity.INFO, "Pump vibration unusual: 4.1 mm/s (z-score 4.8)", 4.1, 4.5,
                now - 7 * D - 9 * H, 45, 180, "arjun", "kabir", "Debris in intake strainer cleaned.", "Mark for review", rnd);
        pastAlert(db, "sys-offline", "Node offline", "N-106", null, Severity.CRITICAL, "field-gw-01 stopped sending heartbeats (95 s ago)", 95, 30,
                now - 5 * D - 20 * H, 9, 160, "priya", "priya", "Solar battery ran flat at night; battery replaced.", null, rnd);
        pastAlert(db, "R-107", "Dry soil", "N-106", "S-120", Severity.WARNING, "Soil moisture - zone 2 is 21.4 % (minimum 22 %)", 21.4, 22,
                now - 5 * D - 13 * H, 15, 90, "kabir", "kabir", "Drip line valve was stuck closed.", "Open irrigation valve", rnd);
        pastAlert(db, "sys-uplink", "Cloud link down", "N-104", null, Severity.WARNING, "cold-room-gw-01 cannot reach the cloud; buffering data (1840 messages waiting)", 1840, 0,
                now - 3 * D - 4 * H, 7, 62, "priya", "priya", "ISP outage. All 1,840 buffered messages synced after the link came back.", null, rnd);
        pastAlert(db, "R-104", "Fast temperature change", "N-104", "S-113", Severity.WARNING, "Chiller room temperature changing 3.6 °C per minute (limit 3)", 3.6, 3,
                now - 2 * D - 8 * H, 5, 30, "kabir", "kabir", "Defrost cycle; normal.", null, rnd);
        pastAlert(db, "sys-invalid", "Garbage readings", "N-105", "S-117", Severity.WARNING, "Dock humidity sent 9 impossible values in the last minute (dropped at the edge)", 9, 3,
                now - D - 15 * H, 10, 48, "priya", "priya", "Loose connector re-seated.", null, rnd);
        pastAlert(db, "R-106", "Reservoir low", "N-107", "S-128", Severity.CRITICAL, "Reservoir level is 23.8 % (minimum 25 %)", 23.8, 25,
                now - D - 3 * H, 2, 41, "kabir", "kabir", "Fill pump started automatically by the edge; level recovered.", "Start fill pump", rnd);
        // still being looked at
        pastAlert(db, "R-102", "High vibration (ISO 10816 zone C)", "N-101", "S-101", Severity.WARNING, "Motor vibration is 7.3 mm/s (limit 7.1 mm/s)", 7.3, 7.1,
                now - 5 * H, 18, -1, "priya", null, null, "Notify maintenance team", rnd);
        pastAlert(db, "R-105", "Unusual reading", "N-102", "S-106", Severity.INFO, "Booth humidity unusual: 57.9 % (z-score 4.6)", 57.9, 4.5,
                now - 2 * H, -1, -1, null, null, null, "Mark for review", rnd);
    }

    private static void commands(DataStore db, long now) {
        Object[][] list = {
            {"N-103", CommandType.REBOOT, null, "kabir", 9 * 86_400_000L + 2 * 3_600_000L, "Rebooted in 3.1 s"},
            {"N-104", CommandType.FLUSH_BUFFER, null, "priya", 3 * 86_400_000L + 3 * 3_600_000L, "Sent 1840 buffered messages"},
            {"N-106", CommandType.SET_MODE, "AGGREGATE", "priya", 6 * 86_400_000L, "Mode is now Aggregate (window)"},
            {"N-101", CommandType.PING, null, "admin", 3_600_000L, "Pong in 4 ms"},
        };
        for (Object[] r : list) {
            Command c = new Command();
            c.id = db.nextId("C", db.commands.keySet());
            c.nodeId = (String) r[0];
            c.type = (CommandType) r[1];
            c.arg = (String) r[2];
            c.by = (String) r[3];
            c.createdAt = now - (Long) r[4];
            c.doneAt = c.createdAt + 4000;
            c.status = CommandStatus.DONE;
            c.result = (String) r[5];
            db.commands.put(c.id, c);
        }
    }
}
