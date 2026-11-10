package com.cecil.parisar.service;

import com.cecil.parisar.ai.EnergyForecaster;
import com.cecil.parisar.ai.RiskModel;
import com.cecil.parisar.iot.IotService;
import com.cecil.parisar.model.Alert;
import com.cecil.parisar.model.Building;
import com.cecil.parisar.model.ClassSession;
import com.cecil.parisar.model.Course;
import com.cecil.parisar.model.Device;
import com.cecil.parisar.model.Point;
import com.cecil.parisar.model.Role;
import com.cecil.parisar.model.Room;
import com.cecil.parisar.model.Student;
import com.cecil.parisar.model.User;
import com.cecil.parisar.store.DataStore;
import com.cecil.parisar.util.Clock;
import com.cecil.parisar.util.Json;
import com.cecil.parisar.util.Stats;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the raw data into what the pages show: the campus overview, the live map, the student list with the
 * AI risk score, one student's report, one room's history, and the analytics charts.
 */
public class AnalyticsService {
    private final DataStore store;
    private final AcademicService academic;
    private final RiskModel model;
    private final EnergyForecaster energy;

    public AnalyticsService(DataStore store, AcademicService academic, RiskModel model, EnergyForecaster energy) {
        this.store = store;
        this.academic = academic;
        this.model = model;
        this.energy = energy;
    }

    // ------------------------------------------------------------------ AI risk for students

    /** The 5 model inputs for one student, worked out from live data. */
    public double[] features(Student s, Map<String, Map<String, int[]>> table) {
        return new double[]{AcademicService.pct(table.get(s.id)), s.internalPct, s.assignmentPct, academic.libraryVisits30(s), s.lmsHours};
    }

    public Map<String, Object> students(User u, Map<String, String> q) {
        synchronized (store) {
            if (u.role == Role.STUDENT) throw Problem.forbidden("Students see only their own report.");
            Map<String, Map<String, int[]>> table = academic.attendanceTable();
            String text = q.getOrDefault("q", "").trim().toLowerCase();
            String risk = q.getOrDefault("risk", "");
            String program = q.getOrDefault("program", "");
            List<Map<String, Object>> rows = new ArrayList<>();
            int[] bands = new int[3];
            for (Student s : store.students.values()) {
                if (!academic.canSee(u, s)) continue;
                Map<String, Object> row = academic.studentRow(s, table);
                double p = model.probability(features(s, table));
                String band = RiskModel.band(p);
                bands[band.equals("HIGH") ? 2 : band.equals("MEDIUM") ? 1 : 0]++;
                if (!text.isEmpty() && !(s.name.toLowerCase().contains(text) || s.roll.toLowerCase().contains(text) || s.cardId.toLowerCase().contains(text))) continue;
                if (!risk.isEmpty() && !risk.equals(band)) continue;
                if (!program.isEmpty() && !program.equals(s.program)) continue;
                row.put("riskPct", Math.round(p * 100));
                row.put("band", band);
                rows.add(row);
            }
            rows.sort(Comparator.comparingLong(r -> -((Number) r.get("riskPct")).longValue()));
            List<String> programs = store.students.values().stream().map(s -> s.program).distinct().toList();
            return Json.obj("students", rows, "bands", Json.obj("LOW", bands[0], "MEDIUM", bands[1], "HIGH", bands[2]), "programs", programs);
        }
    }

    public Map<String, Object> studentDetail(User u, String id) {
        synchronized (store) {
            Student s = academic.student(u, id);
            Map<String, Map<String, int[]>> table = academic.attendanceTable();
            Map<String, Object> m = academic.studentRow(s, table);
            m.put("courses", academic.attendanceOf(s, table));
            m.put("weekly", academic.weekly(s.id, null));
            m.put("risk", model.explain(features(s, table)));
            // the last 10 library visits and class taps (what the IoT readers saw)
            List<Object> taps = new ArrayList<>();
            for (ClassSession cs : store.sessions.values()) {
                String how = cs.present.get(s.id);
                if (how == null) continue;
                taps.add(Json.obj("at", Long.parseLong(how.substring(how.indexOf('@') + 1)), "where", store.rooms.get(cs.roomId).name,
                        "what", cs.courseId + (how.startsWith("RFID") ? " · card tap" : " · marked by teacher")));
            }
            for (long t : s.libraryVisits) taps.add(Json.obj("at", t, "where", "Central Library", "what", "Library gate · card tap"));
            taps.sort(Comparator.comparingLong(x -> -((Number) ((Map<?, ?>) x).get("at")).longValue()));
            m.put("recentTaps", taps.subList(0, Math.min(10, taps.size())));
            return m;
        }
    }

    // ------------------------------------------------------------------ live map

    public Map<String, Object> map() {
        synchronized (store) {
            long now = Clock.millis();
            List<Object> buildings = new ArrayList<>();
            for (Building b : store.buildings.values()) {
                List<Object> rooms = new ArrayList<>();
                double people = 0;
                for (Room r : store.rooms.values()) {
                    if (!b.id.equals(r.buildingId)) continue;
                    Map<String, Object> rm = roomLive(r, now);
                    people += ((Number) rm.getOrDefault("occupancy", 0)).doubleValue();
                    rooms.add(rm);
                }
                Device meter = store.deviceOf("ENERGY", b.id);
                Device tank = store.deviceOf("WATER", b.id);
                long alerts = store.alerts.values().stream().filter(a -> a.isOpen() && b.id.equals(a.buildingId)).count();
                Map<String, Object> bm = b.toJson();
                bm.put("rooms", rooms);
                bm.put("people", Math.round(people));
                bm.put("powerKw", meter == null ? null : Stats.round1(meter.latest.getOrDefault("power", 0.0)));
                bm.put("waterPct", tank == null ? null : Math.round(tank.latest.getOrDefault("level", 0.0)));
                bm.put("openAlerts", alerts);
                buildings.add(bm);
            }
            return Json.obj("buildings", buildings, "at", now);
        }
    }

    private Map<String, Object> roomLive(Room r, long now) {
        Map<String, Object> m = r.toJson();
        Device env = store.deviceOf("ENV", r.id);
        if (env != null) {
            m.put("sensorId", env.id);
            m.put("online", env.online(now, IotService.OFFLINE_MS));
            for (Map.Entry<String, Double> e : env.latest.entrySet()) m.put(e.getKey(), Stats.round1(e.getValue()));
        }
        ClassSession s = store.openSessionIn(r.id);
        m.put("classNow", s == null ? null : Json.obj("sessionId", s.id, "courseId", s.courseId, "present", s.present.size()));
        m.put("alerts", store.alerts.values().stream().filter(a -> a.isOpen() && r.id.equals(a.roomId)).map(a -> a.rule + ":" + a.severity).toList());
        return m;
    }

    public Map<String, Object> roomDetail(User u, String id) {
        synchronized (store) {
            Room r = store.rooms.get(id);
            if (r == null) throw Problem.notFound("Room");
            Map<String, Object> m = roomLive(r, Clock.millis());
            m.put("buildingName", store.buildings.get(r.buildingId).name);
            Device env = store.deviceOf("ENV", r.id);
            m.put("fine", env == null ? List.of() : store.fineOf(env.id));
            List<Object> timetable = new ArrayList<>();
            for (Course c : store.courses.values()) {
                if (!r.id.equals(c.roomId)) continue;
                for (int[] sl : c.slots) timetable.add(Json.obj("day", sl[0], "hour", sl[1], "courseId", c.id, "courseName", c.name));
            }
            m.put("timetable", timetable);
            m.put("canControl", u.role.atLeast(Role.FACULTY));
            return m;
        }
    }

    // ------------------------------------------------------------------ overview (the first page)

    public Map<String, Object> overview(User u) {
        synchronized (store) {
            long now = Clock.millis();
            Map<String, Map<String, int[]>> table = academic.attendanceTable();
            double people = 0, powerKw = 0, minWater = 100;
            int online = 0, active = 0;
            for (Device d : store.devices.values()) {
                if (!d.active) continue;
                active++;
                if (d.online(now, IotService.OFFLINE_MS)) online++;
                if ("ENV".equals(d.type)) people += d.latest.getOrDefault("occupancy", 0.0);
                if ("ENERGY".equals(d.type)) powerKw += d.latest.getOrDefault("power", 0.0);
                if ("WATER".equals(d.type)) minWater = Math.min(minWater, d.latest.getOrDefault("level", 100.0));
            }
            int crit = 0, warn = 0, info = 0;
            List<Object> recent = new ArrayList<>();
            List<Alert> open = new ArrayList<>();
            for (Alert a : store.alerts.values()) if (a.isOpen()) open.add(a);
            open.sort(Comparator.comparingLong(a -> -a.updatedAt));
            for (Alert a : open) {
                switch (a.severity) { case "CRITICAL" -> crit++; case "WARNING" -> warn++; default -> info++; }
                if (recent.size() < 6) recent.add(Json.obj("id", a.id, "rule", a.rule, "severity", a.severity, "message", a.message, "at", a.updatedAt, "roomId", a.roomId));
            }
            // attendance today = present / expected in classes held today
            String today = Clock.today().toString();
            int presentToday = 0, expectedToday = 0, liveClasses = 0;
            for (ClassSession s : store.sessions.values()) {
                if (s.isOpen()) liveClasses++;
                if (!today.equals(s.date)) continue;
                Course c = store.courses.get(s.courseId);
                if (c == null) continue;
                presentToday += s.present.size();
                expectedToday += c.studentIds.size();
            }
            int high = 0;
            for (Student s : store.students.values()) if (model.probability(features(s, table)) >= 0.6) high++;
            Map<String, Object> f = energy.forecast(null, 12);
            Map<String, Object> out = Json.obj(
                    "people", Math.round(people), "devicesOnline", online, "devices", active,
                    "alerts", Json.obj("CRITICAL", crit, "WARNING", warn, "INFO", info), "recentAlerts", recent,
                    "powerKw", Stats.round1(powerKw), "kwhToday", f.get("kwhToday"), "costToday", f.get("costToday"),
                    "peakKw", f.get("peakKw"), "peakAt", f.get("peakAt"), "forecast", f.get("forecast"), "past", f.get("past"),
                    "attendanceToday", expectedToday == 0 ? null : Stats.round1(100.0 * presentToday / expectedToday),
                    "liveClasses", liveClasses, "highRisk", high, "students", store.students.size(), "minWater", Math.round(minWater),
                    "campusAttendance", academic.weekly(null, null), "autoSaver", Json.bool(store.meta, "autoSaver", false));
            if (u.role == Role.STUDENT && u.studentId != null) out.put("me", studentDetail(u, u.studentId));
            return out;
        }
    }

    // ------------------------------------------------------------------ analytics page

    public Map<String, Object> analytics(User u) {
        if (u.role == Role.STUDENT) throw Problem.forbidden("Analytics is for staff.");
        synchronized (store) {
            Map<String, Map<String, int[]>> table = academic.attendanceTable();
            // 1. attendance per course per week
            List<Object> courseWeeks = new ArrayList<>();
            for (Course c : store.courses.values()) {
                if (u.role == Role.FACULTY && !u.id.equals(c.facultyId)) continue;
                courseWeeks.add(Json.obj("courseId", c.id, "name", c.name, "weeks", academic.weekly(null, c.id)));
            }
            // 2. room use heat-map: average share of seats used, by room and hour (8:00-21:00), weekdays of the last 14 days
            List<Object> heat = new ArrayList<>();
            for (Room r : store.rooms.values()) {
                Device env = store.deviceOf("ENV", r.id);
                if (env == null) continue;
                double[] sum = new double[24];
                int[] n = new int[24];
                for (Point p : store.hourlyOf(env.id)) {
                    ZonedDateTime z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(p.t), Clock.ZONE);
                    if (z.getDayOfWeek().getValue() > 5) continue;
                    double occ = p.get("occupancy");
                    if (Double.isNaN(occ)) continue;
                    sum[z.getHour()] += occ / Math.max(1, r.capacity);
                    n[z.getHour()]++;
                }
                List<Object> cells = new ArrayList<>();
                double total = 0;
                int cnt = 0;
                for (int h = 8; h <= 21; h++) {
                    double v = n[h] == 0 ? 0 : 100 * sum[h] / n[h];
                    cells.add(Math.round(v));
                    if (h >= 9 && h <= 17) { total += v; cnt++; }
                }
                heat.add(Json.obj("roomId", r.id, "room", r.name, "kind", r.kind, "cells", cells, "dayAvg", Math.round(total / Math.max(1, cnt))));
            }
            // 3. risk by program and the attendance-vs-marks scatter
            Map<String, int[]> byProgram = new LinkedHashMap<>();
            List<Object> scatter = new ArrayList<>();
            List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
            for (Student s : store.students.values()) {
                if (!academic.canSee(u, s)) continue;
                double[] f = features(s, table);
                String band = RiskModel.band(model.probability(f));
                int[] b = byProgram.computeIfAbsent(s.program, k -> new int[3]);
                b[band.equals("HIGH") ? 2 : band.equals("MEDIUM") ? 1 : 0]++;
                scatter.add(Json.obj("id", s.id, "name", s.name, "x", Stats.round1(f[0]), "y", Stats.round1(f[1]), "band", band));
                xs.add(f[0]);
                ys.add(f[1]);
            }
            List<Object> risk = new ArrayList<>();
            for (Map.Entry<String, int[]> e : byProgram.entrySet())
                risk.add(Json.obj("program", e.getKey(), "LOW", e.getValue()[0], "MEDIUM", e.getValue()[1], "HIGH", e.getValue()[2]));
            // 4. alerts by rule in the last 14 days
            Map<String, Integer> alertRules = new LinkedHashMap<>();
            for (String r : List.of("HEAT", "CO2", "WATER_LOW", "OFFLINE", "ENERGY_WASTE", "ANOMALY")) alertRules.put(r, 0);
            long since = Clock.millis() - 14L * 24 * DataStore.HOUR_MS;
            for (Alert a : store.alerts.values()) if (a.createdAt >= since) alertRules.merge(a.rule, 1, Integer::sum);
            return Json.obj("courseWeeks", courseWeeks, "heat", heat, "heatHours", List.of(8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21),
                    "risk", risk, "scatter", scatter, "pearson", Stats.round2(pearson(xs, ys)),
                    "energyDaily", energy.dailyByBuilding(), "alertRules", alertRules);
        }
    }

    /** Pearson correlation: +1 = when one goes up the other always goes up; 0 = no link. */
    public static double pearson(List<Double> xs, List<Double> ys) {
        if (xs.size() < 3) return 0;
        double mx = Stats.mean(xs), my = Stats.mean(ys), sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < xs.size(); i++) {
            double dx = xs.get(i) - mx, dy = ys.get(i) - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        return sxx == 0 || syy == 0 ? 0 : sxy / Math.sqrt(sxx * syy);
    }

    public RiskModel model() { return model; }
}
