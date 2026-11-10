package com.cecil.parisar.iot;

import com.cecil.parisar.model.Building;
import com.cecil.parisar.model.ClassSession;
import com.cecil.parisar.model.Course;
import com.cecil.parisar.model.Device;
import com.cecil.parisar.model.Role;
import com.cecil.parisar.model.Room;
import com.cecil.parisar.model.Student;
import com.cecil.parisar.model.User;
import com.cecil.parisar.service.AcademicService;
import com.cecil.parisar.service.Problem;
import com.cecil.parisar.store.DataStore;
import com.cecil.parisar.util.Clock;
import com.cecil.parisar.util.Json;
import com.cecil.parisar.util.Stats;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Pretends to be the campus hardware, so the platform works without buying sensors.
 * Every 15 seconds it sends one message per device - exactly what a real ESP32 / Raspberry Pi would send to
 * POST /iot/ingest - through the same IotService.process() path, so alerts, history and attendance behave the same.
 *
 * How the made-up campus behaves:
 *   - people: classes fill rooms (from the real RFID attendance), the library is busy in the afternoon,
 *     the hostel common room in the evening, the cafeteria at lunch
 *   - temperature follows a Ranchi November day (about 15 °C at night, 27 °C in the afternoon); AC cools to ~24 °C;
 *     people warm a room up; CO2 rises with people (ventilation is better when the AC runs)
 *   - when a room empties, people usually switch off the lights and AC - but not always (energy waste!)
 *   - each building's smart meter adds up base load + lights + AC + people's devices
 *   - water tanks drain with use (hostel mornings and evenings) and a pump refills them below 35%
 *
 * Faults can be injected from the website to demonstrate the alerts: AC failure, poor ventilation, water leak,
 * a device going silent, an energy spike, lights left on.
 */
public class Simulator {
    public static final List<String> FAULTS = List.of("AC_FAIL", "VENT", "LEAK", "SILENT", "SPIKE", "LEFT_ON");

    private final DataStore store;
    private final IotService iot;
    private final AcademicService academic;
    private final Random rnd;
    private final List<long[]> pendingTaps = new ArrayList<>();      // {at, gate index, student index}
    private final List<String> gateIds = new ArrayList<>();
    private final List<String> studentIds = new ArrayList<>();
    private long lastTick;

    public Simulator(DataStore store, IotService iot, AcademicService academic, long seed) {
        this.store = store;
        this.iot = iot;
        this.academic = academic;
        this.rnd = new Random(seed);
    }

    // ------------------------------------------------------------------ live

    /** One live step: open/close timetable classes, deliver card taps, send one reading per device. */
    public synchronized void tick(long now) {
        double dtMin = lastTick == 0 ? 0.25 : Stats.clamp((now - lastTick) / 60_000.0, 0.05, 10);
        lastTick = now;
        if (!Json.bool(store.meta, "sim.running", true)) return;
        for (ClassSession s : academic.autoSessions(now)) scheduleTaps(s);
        synchronized (store) {
            expireFaults(now);
            deliverTaps(now);
            libraryTaps(now, dtMin);
            ZonedDateTime z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), Clock.ZONE);
            for (Room r : store.rooms.values()) {
                Device env = store.deviceOf("ENV", r.id);
                if (env == null || !env.active) continue;
                double before = env.latest.getOrDefault("occupancy", 0.0);
                Map<String, Double> v = roomValues(r, env.latest, z, occupancyLive(r, z), 0.35);
                humans(r, before, v.get("occupancy"), v.get("temp"), z.getHour());
                if (!silent(env.id)) iot.process(env, now, new LinkedHashMap<>(v));
            }
            for (Device d : store.devices.values()) {
                if (!d.active || silent(d.id)) continue;
                if ("ENERGY".equals(d.type)) iot.process(d, now, new LinkedHashMap<>(Map.of("power", power(d, z, true))));
                if ("WATER".equals(d.type)) iot.process(d, now, new LinkedHashMap<>(Map.of("level", water(d, z, dtMin))));
                if ("GATE".equals(d.type) && now - d.lastSeen >= 60_000) iot.process(d, now, new LinkedHashMap<>(Map.of("heartbeat", true)));
            }
        }
    }

    /** People switching lights / AC on and off (and sometimes forgetting). */
    private void humans(Room r, double before, double after, double temp, int hour) {
        if ("SERVER".equals(r.kind)) { r.acOn = true; return; }                     // server room AC never stops
        if (fault(r.id, "LEFT_ON")) { r.lightsOn = true; r.acOn = r.hasAC; return; }
        if (before < 1 && after >= 1 && hour >= 6 && hour <= 23) {
            r.lightsOn = true;
            if (r.hasAC && temp > 26) r.acOn = true;
        } else if (before >= 1 && after < 1) {
            if (rnd.nextDouble() < 0.8) { r.lightsOn = false; r.acOn = false; }
        } else if (after >= 1 && r.hasAC && !r.acOn && temp > 27) r.acOn = true;
    }

    // ------------------------------------------------------------------ history (first start only)

    /** Fills in the past (no alerts): one reading per device every 10 minutes from 'from' to 'to'. */
    public synchronized void history(long from, long to) {
        Map<String, Integer> present = new HashMap<>();
        for (ClassSession s : store.sessions.values()) present.put(s.roomId + "|" + s.date + "|" + s.hour, s.present.size());
        iot.setRules(false);
        Map<String, Map<String, Double>> state = new HashMap<>();
        for (long t = from; t < to; t += DataStore.FINE_MS) {
            ZonedDateTime z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(t), Clock.ZONE);
            for (Room r : store.rooms.values()) {
                Device env = store.deviceOf("ENV", r.id);
                if (env == null) continue;
                Integer cls = present.get(r.id + "|" + z.toLocalDate() + "|" + z.getHour());
                double occ = cls != null ? cls + 1 : occupancyProfile(r, z);
                Map<String, Double> prev = state.computeIfAbsent(env.id, k -> new LinkedHashMap<>());
                r.lightsOn = occ >= 1 && z.getHour() >= 6;
                r.acOn = "SERVER".equals(r.kind) || (r.hasAC && occ >= 1 && prev.getOrDefault("temp", 22.0) > 26);
                Map<String, Double> v = roomValues(r, prev, z, occ, 0.6);
                prev.putAll(v);
                iot.process(env, t + rnd.nextInt(60_000), new LinkedHashMap<>(v));
            }
            for (Device d : store.devices.values()) {
                if ("ENERGY".equals(d.type)) iot.process(d, t + rnd.nextInt(60_000), new LinkedHashMap<>(Map.of("power", power(d, z, false))));
                if ("WATER".equals(d.type)) iot.process(d, t + rnd.nextInt(60_000), new LinkedHashMap<>(Map.of("level", water(d, z, 10))));
            }
            libraryTaps(t, 10);
        }
        // the class taps at the doors (from the attendance that was generated), oldest first
        List<long[]> taps = new ArrayList<>();
        List<String> gates = new ArrayList<>();
        for (ClassSession s : store.sessions.values()) {
            Device gate = store.deviceOf("GATE", s.roomId);
            if (gate == null) continue;
            if (!gates.contains(gate.id)) gates.add(gate.id);
            for (String how : s.present.values())
                if (how.startsWith("RFID@")) taps.add(new long[]{Long.parseLong(how.substring(5)), gates.indexOf(gate.id)});
        }
        taps.sort((a, b) -> Long.compare(a[0], b[0]));
        for (long[] t : taps) {
            if (t[0] < from) continue;
            Device gate = store.devices.get(gates.get((int) t[1]));
            store.record(gate.id, t[0], Map.of("taps", 1.0));
            gate.messages++;
        }
        for (Device g : store.devices.values()) if ("GATE".equals(g.type)) g.lastSeen = to;
        for (Room r : store.rooms.values()) { r.lightsOn = false; r.acOn = "SERVER".equals(r.kind); }
        iot.setRules(true);
        lastTick = to;
    }

    // ------------------------------------------------------------------ the physics of a room

    /** temp, humidity, CO2 and people for one room. alpha = how fast values move towards their target (1 = at once). */
    Map<String, Double> roomValues(Room r, Map<String, Double> prev, ZonedDateTime z, double occTarget, double alpha) {
        double h = z.getHour() + z.getMinute() / 60.0;
        double outdoor = 21 + 6 * Math.sin(2 * Math.PI * (h - 9) / 24);
        double share = Stats.clamp(occTarget / Math.max(1, r.capacity), 0, 1.5);
        double temp;
        if (fault(r.id, "AC_FAIL")) temp = 39;
        else if (r.hasAC && r.acOn) temp = 23.5 + share * 1.5;
        else temp = outdoor * 0.55 + 22 * 0.45 + share * 3.5 + ("CAFE".equals(r.kind) ? 1.5 : 0);
        if ("SERVER".equals(r.kind) && !fault(r.id, "AC_FAIL")) temp = 20.5;
        double co2 = 420 + share * ("LIBRARY".equals(r.kind) ? 650 : 900) * (r.acOn ? 0.8 : 1.0);
        if (fault(r.id, "VENT")) co2 = co2 * 1.9 + 450;
        double hum = Stats.clamp(72 - (temp - 18) * 1.8 - (r.acOn ? 8 : 0), 30, 90);
        double pOcc = prev.getOrDefault("occupancy", occTarget), pTemp = prev.getOrDefault("temp", temp), pCo2 = prev.getOrDefault("co2", co2);
        double pHum = prev.getOrDefault("humidity", hum);
        Map<String, Double> v = new LinkedHashMap<>();
        double tempAlpha = fault(r.id, "AC_FAIL") ? Math.max(alpha * 0.4, 0.08) : alpha * 0.5;
        v.put("temp", Stats.round1(pTemp + (temp - pTemp) * tempAlpha + rnd.nextGaussian() * 0.12));
        v.put("humidity", Stats.round1(Stats.clamp(pHum + (hum - pHum) * alpha * 0.5 + rnd.nextGaussian() * 0.4, 20, 95)));
        v.put("co2", (double) Math.round(Math.max(400, pCo2 + (co2 - pCo2) * alpha + rnd.nextGaussian() * 12)));
        v.put("occupancy", (double) Math.max(0, Math.round(pOcc + (occTarget - pOcc) * Math.min(1, alpha * 1.6))));
        return v;
    }

    /** People in a room now: the class attendance (from RFID) if a class is running, otherwise the usual pattern. */
    private double occupancyLive(Room r, ZonedDateTime z) {
        if (fault(r.id, "LEFT_ON")) return 0;
        ClassSession s = store.openSessionIn(r.id);
        if (s != null) return s.present.size() + 1;
        return occupancyProfile(r, z);
    }

    /** Typical number of people in a room by hour, when no class is running there. */
    double occupancyProfile(Room r, ZonedDateTime z) {
        int h = z.getHour(), day = z.getDayOfWeek().getValue();
        boolean weekday = day <= 5;
        double f = switch (r.kind) {
            case "LIBRARY" -> {
                double[] p = {0, 0, 0, 0, 0, 0, 0, 0, .05, .1, .15, .2, .25, .3, .35, .4, .45, .4, .3, .25, .2, .1, 0, 0};
                yield p[h] * (weekday ? 1 : day == 6 && h >= 9 && h < 17 ? 0.4 : 0) * (r.capacity < 60 ? 0.6 : 1);
            }
            case "LAB" -> weekday && h >= 9 && h < 17 ? 0.08 : 0;
            case "CLASSROOM", "SEMINAR" -> weekday && h >= 9 && h < 17 && rnd.nextDouble() < 0.3 ? 2.0 / r.capacity : 0;
            case "SERVER" -> weekday && h >= 10 && h < 17 && rnd.nextDouble() < 0.2 ? 1.0 / r.capacity : 0;
            case "OFFICE" -> weekday && h >= 9 && h < 17 ? 0.6 : 0;
            case "CAFE" -> "R-H02".equals(r.id)
                    ? (h >= 7 && h < 9 ? .5 : h >= 12 && h < 14 ? .6 : h == 17 ? .2 : h >= 20 && h < 22 ? .7 : 0)
                    : (h >= 8 && h < 19 ? (h == 13 ? .7 : h == 16 ? .4 : .15) * (weekday ? 1 : .3) : 0);
            case "HOSTEL" -> h >= 17 && h <= 23 ? (h == 20 ? .6 : .4) : (!weekday && h >= 10 && h < 17 ? .3 : h >= 6 && h < 9 ? .15 : 0);
            default -> 0;
        };
        double occ = r.capacity * f * (0.85 + rnd.nextDouble() * 0.3);
        return Math.round(occ);
    }

    /** Building power in kW from what is switched on inside it. */
    private double power(Device meter, ZonedDateTime z, boolean live) {
        Building b = store.buildings.get(meter.buildingId);
        int h = z.getHour();
        double kw = switch (b.kind) { case "LAB" -> 9; case "HOSTEL" -> 6; case "ADMIN" -> 5; case "LIBRARY" -> 5; default -> 6; };
        for (Room r : store.rooms.values()) {
            if (!b.id.equals(r.buildingId)) continue;
            Device env = store.deviceOf("ENV", r.id);
            double occ = env == null ? 0 : env.latest.getOrDefault("occupancy", 0.0);
            if (r.lightsOn) kw += 0.6 + r.capacity * 0.012;
            if (r.hasAC && r.acOn) kw += 1.2 + r.capacity * 0.035;
            kw += occ * ("LAB".equals(r.kind) ? 0.12 : 0.05);
            if ("CAFE".equals(r.kind) && h >= 7 && h < 20) kw += 6;
        }
        if ("HOSTEL".equals(b.kind) && h >= 5 && h < 8) kw += 8;      // water heaters
        if (fault(meter.id, "SPIKE")) kw += 70;
        kw *= dayFactor(z.toLocalDate(), meter.id);
        kw *= 1 + rnd.nextGaussian() * 0.03;
        return Stats.round1(Math.max(0.5, kw));
    }

    /** Some days are simply busier than others (events, weather): a fixed factor 0.85 - 1.15 per day and meter. */
    static double dayFactor(java.time.LocalDate d, String meterId) {
        long h = (d.toEpochDay() * 2654435761L) ^ meterId.hashCode();
        return 0.85 + Math.floorMod(h, 1000) / 1000.0 * 0.30;
    }

    /** Water tank level in %: use by the hour, the pump refills below 35% up to 95%. */
    private double water(Device tank, ZonedDateTime z, double dtMin) {
        double level = tank.latest.getOrDefault("level", 80.0);
        int h = z.getHour();
        boolean hostel = "B-H".equals(tank.buildingId);
        double use = hostel ? (h >= 6 && h < 9 ? 2.5 : h >= 12 && h < 14 ? 1.5 : h >= 18 && h < 22 ? 2.0 : h >= 23 || h < 5 ? 0.1 : 0.5)
                : (h >= 9 && h < 17 ? 0.8 : 0.1);
        if (fault(tank.id, "LEAK")) use += 7;
        String pumpKey = "sim.pump." + tank.id;
        boolean pump = Json.bool(store.meta, pumpKey, false);
        if (level < 35) pump = true;
        if (level >= 95) pump = false;
        store.meta.put(pumpKey, pump);
        level += (-use + (pump ? 6 : 0)) * dtMin / 10.0 + rnd.nextGaussian() * 0.1;
        return Stats.round1(Stats.clamp(level, 0, 100));
    }

    // ------------------------------------------------------------------ RFID taps

    /** When a class opens, decide who comes (by each student's habit) and when they tap (first 12 minutes). */
    public synchronized void scheduleTaps(ClassSession s) {
        synchronized (store) {
            Device gate = store.deviceOf("GATE", s.roomId);
            Course c = store.courses.get(s.courseId);
            if (gate == null || c == null) return;
            if (!gateIds.contains(gate.id)) gateIds.add(gate.id);
            for (String sid : c.studentIds) {
                Student st = store.students.get(sid);
                if (st == null || rnd.nextDouble() > st.attendHabit) continue;
                if (!studentIds.contains(sid)) studentIds.add(sid);
                long at = s.startedAt + (long) (Math.abs(rnd.nextGaussian()) * 4 * 60_000);
                if (rnd.nextDouble() < 0.12) at += 8 * 60_000;                                   // a few latecomers
                pendingTaps.add(new long[]{at, gateIds.indexOf(gate.id), studentIds.indexOf(sid)});
            }
        }
    }

    private void deliverTaps(long now) {
        List<long[]> due = new ArrayList<>();
        for (long[] t : pendingTaps) if (t[0] <= now) due.add(t);
        pendingTaps.removeAll(due);
        for (long[] t : due) {
            Device gate = store.devices.get(gateIds.get((int) t[1]));
            Student st = store.students.get(studentIds.get((int) t[2]));
            if (gate == null || st == null || !gate.active || silent(gate.id)) continue;
            iot.process(gate, Math.min(now, Math.max(t[0], now - 60_000)), new LinkedHashMap<>(Map.of("cardId", st.cardId)));
        }
    }

    public synchronized int pendingTaps() { return pendingTaps.size(); }

    private void libraryTaps(long now, double dtMin) {
        Device gate = null;
        for (Device d : store.devices.values()) if ("GATE".equals(d.type) && "R-L01".equals(d.roomId)) gate = d;
        if (gate == null || !gate.active || silent(gate.id)) return;
        ZonedDateTime z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), Clock.ZONE);
        int h = z.getHour(), day = z.getDayOfWeek().getValue();
        if (h < 8 || h >= 22 || day == 7 || (day == 6 && (h < 9 || h >= 17))) return;
        double perHour = (day == 6 ? 1.0 : 3.2) * (h >= 13 && h < 18 ? 1.6 : 0.8);
        double expected = perHour * dtMin / 60;
        List<Student> all = new ArrayList<>(store.students.values());
        double total = 0;
        for (Student s : all) total += s.libraryHabit;
        while (expected > 0) {
            if (rnd.nextDouble() < Math.min(1, expected)) {
                double pick = rnd.nextDouble() * total;
                for (Student s : all) {
                    pick -= s.libraryHabit;
                    if (pick <= 0) { iot.process(gate, now - rnd.nextInt((int) Math.max(1, dtMin * 60_000)), new LinkedHashMap<>(Map.of("cardId", s.cardId))); break; }
                }
            }
            expected -= 1;
        }
    }

    // ------------------------------------------------------------------ faults (for demos)

    @SuppressWarnings("unchecked")
    private Map<String, Object> faults() {
        Object f = store.meta.get("sim.faults");
        if (!(f instanceof Map)) { f = new LinkedHashMap<String, Object>(); store.meta.put("sim.faults", f); }
        return (Map<String, Object>) f;
    }

    boolean fault(String target, String kind) {
        Object f = faults().get(target + "|" + kind);
        return f instanceof Number n && n.longValue() > Clock.millis();
    }

    boolean silent(String deviceId) { return fault(deviceId, "SILENT"); }

    private void expireFaults(long now) {
        faults().values().removeIf(v -> !(v instanceof Number n) || n.longValue() <= now);
    }

    /** Admin injects a fault for 'minutes' minutes. target = a room id (AC_FAIL, VENT, LEFT_ON) or a device id. */
    public Map<String, Object> inject(User u, String target, String kind, int minutes) {
        if (u.role != Role.ADMIN) throw Problem.forbidden("Only the campus admin can use the simulator.");
        if (kind == null || !FAULTS.contains(kind)) throw Problem.bad("Unknown fault.");
        synchronized (store) {
            boolean roomFault = List.of("AC_FAIL", "VENT", "LEFT_ON").contains(kind);
            if (roomFault) {
                Room r = store.rooms.get(target);
                if (r == null) throw Problem.bad("Choose a room for this fault.");
                if ("AC_FAIL".equals(kind) && !r.hasAC) throw Problem.bad(r.name + " has no AC.");
            } else {
                Device d = store.devices.get(target);
                if (d == null) throw Problem.bad("Choose a device for this fault.");
                if ("LEAK".equals(kind) && !"WATER".equals(d.type)) throw Problem.bad("A leak needs a water tank sensor.");
                if ("SPIKE".equals(kind) && !"ENERGY".equals(d.type)) throw Problem.bad("A power spike needs an energy meter.");
            }
            long until = Clock.millis() + Math.max(1, Math.min(240, minutes)) * 60_000L;
            faults().put(target + "|" + kind, until);
            store.log(u.id, "sim.fault", target, kind + " for " + minutes + " min");
            store.save("audit");
            return status();
        }
    }

    public Map<String, Object> clear(User u, String key) {
        if (u.role != Role.ADMIN) throw Problem.forbidden("Only the campus admin can use the simulator.");
        synchronized (store) {
            if (faults().remove(key) == null) throw Problem.notFound("Fault");
            store.log(u.id, "sim.clear", key, null);
            store.save("audit");
            return status();
        }
    }

    /** A device that went silent comes back after a "reboot" command. */
    public Map<String, Object> reboot(User u, String deviceId) {
        if (u.role != Role.ADMIN) throw Problem.forbidden("Only the campus admin can reboot devices.");
        synchronized (store) {
            Device d = store.devices.get(deviceId);
            if (d == null) throw Problem.notFound("Device");
            faults().remove(deviceId + "|SILENT");
            store.log(u.id, "device.reboot", d.id, d.name);
            store.save("audit");
            return Json.obj("ok", true, "note", d.name + " restarted. It should report within 15 seconds.");
        }
    }

    public Map<String, Object> settings(User u, Map<String, Object> body) {
        if (u.role != Role.ADMIN) throw Problem.forbidden("Only the campus admin can change this.");
        synchronized (store) {
            if (body.containsKey("running")) store.meta.put("sim.running", Json.bool(body, "running", true));
            if (body.containsKey("autoSaver")) store.meta.put("autoSaver", Json.bool(body, "autoSaver", false));
            store.log(u.id, "sim.settings", "simulator", "running=" + store.meta.get("sim.running") + ", autoSaver=" + store.meta.get("autoSaver"));
            store.save("audit");
            return status();
        }
    }

    public Map<String, Object> status() {
        synchronized (store) {
            List<Object> list = new ArrayList<>();
            for (Map.Entry<String, Object> e : faults().entrySet()) {
                String[] p = e.getKey().split("\\|");
                String name = store.rooms.containsKey(p[0]) ? store.rooms.get(p[0]).name : store.devices.containsKey(p[0]) ? store.devices.get(p[0]).name : p[0];
                list.add(Json.obj("key", e.getKey(), "target", p[0], "targetName", name, "kind", p[1], "until", e.getValue()));
            }
            return Json.obj("running", Json.bool(store.meta, "sim.running", true), "autoSaver", Json.bool(store.meta, "autoSaver", false),
                    "faults", list, "kinds", FAULTS, "pendingTaps", pendingTaps.size());
        }
    }

    /** Used by SeedData to set up the demo problems without an admin user. */
    public void seedFault(String target, String kind, long until) {
        synchronized (store) { faults().put(target + "|" + kind, until); }
    }
}
