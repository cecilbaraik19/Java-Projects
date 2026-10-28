package com.cecil.edge.service;

import com.cecil.edge.edge.EdgeEngine;
import com.cecil.edge.edge.NodeRuntime;
import com.cecil.edge.edge.RollingStats;
import com.cecil.edge.edge.SensorRuntime;
import com.cecil.edge.model.Alert;
import com.cecil.edge.model.DayStat;
import com.cecil.edge.model.EdgeNode;
import com.cecil.edge.model.Enums.AlertStatus;
import com.cecil.edge.model.Enums.NodeMode;
import com.cecil.edge.model.Enums.NodeStatus;
import com.cecil.edge.model.Enums.Severity;
import com.cecil.edge.model.HourAgg;
import com.cecil.edge.model.Rule;
import com.cecil.edge.model.Sensor;
import com.cecil.edge.model.Settings;
import com.cecil.edge.model.Site;
import com.cecil.edge.util.Json;
import com.cecil.edge.util.Num;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Numbers for the dashboard, node and sensor pages, the mode comparison and the reports. */
public class AnalyticsService {
    private final Hub hub;

    AnalyticsService(Hub hub) {
        this.hub = hub;
    }

    private Settings cfg() { return hub.store.settings; }

    /** Rupees for a number of bytes of mobile data. */
    private double cost(double bytes) {
        return Num.round(bytes / 1_000_000_000.0 * cfg().costPerGb, 2);
    }

    // ------------------------------------------------------------------ live ticker (polled every few seconds)

    public Map<String, Object> live() {
        long now = hub.now();
        int online = 0;
        int total = 0;
        for (EdgeNode n : hub.store.nodes.values()) {
            if (!n.enabled) continue;
            total++;
            if (hub.engine.isOnline(n, now)) online++;
        }
        double[] last = lastMinuteTraffic(now);
        int open = 0;
        int crit = 0;
        Alert newest = null;
        for (Alert a : hub.engine.activeAlerts()) {
            if (a.status == AlertStatus.OPEN) open++;
            if (a.severity == Severity.CRITICAL && a.clearedAt == 0) crit++;
            if (newest == null || a.openedAt > newest.openedAt) newest = a;
        }
        return Json.obj("now", now, "nodesOnline", online, "nodesTotal", total,
                "msgsPerMin", (long) last[0], "fwdPerMin", (long) last[1],
                "reductionPct", last[2] == 0 ? 0 : Num.round(100 - Num.pct(last[3], last[2]), 1),
                "openAlerts", open, "critical", crit,
                "newestAlert", newest == null ? null : Json.obj("id", newest.id, "severity", newest.severity, "message", newest.message, "openedAt", newest.openedAt),
                "simulatorOn", cfg().simulatorOn, "faults", hub.sim.faults.size());
    }

    /** {raw msgs, fwd msgs, raw bytes, fwd bytes} in the last complete minute, all nodes. */
    private double[] lastMinuteTraffic(long now) {
        long m = now - Math.floorMod(now, 60_000L) - 60_000L;
        double[] t = new double[4];
        for (EdgeNode n : hub.store.nodes.values()) {
            for (double[] r : hub.engine.rt(n).traffic.since(m)) {
                if (r[0] != m) continue;
                t[0] += r[1];
                t[1] += r[2];
                t[2] += r[3];
                t[3] += r[4];
            }
        }
        return t;
    }

    // ------------------------------------------------------------------ dashboard

    public Map<String, Object> dashboard() {
        long now = hub.now();
        Map<String, Object> out = new LinkedHashMap<>();
        String today = hub.engine.date(now);

        int total = 0;
        int online = 0;
        int degraded = 0;
        int offline = 0;
        for (EdgeNode n : hub.store.nodes.values()) {
            NodeStatus st = hub.engine.status(n, now);
            if (st == NodeStatus.DISABLED) continue;
            total++;
            if (st == NodeStatus.ONLINE) online++;
            else if (st == NodeStatus.DEGRADED) { online++; degraded++; }
            else offline++;
        }
        out.put("nodes", Json.obj("total", total, "online", online, "degraded", degraded, "offline", offline));

        DayStat t = new DayStat();
        List<Map<String, Object>> byNode = new ArrayList<>();
        for (EdgeNode n : hub.store.nodes.values()) {
            DayStat d = hub.store.days.get(today + "|" + n.id);
            if (d == null) continue;
            add(t, d);
            byNode.add(Json.obj("id", n.id, "name", n.name, "mode", n.mode, "rawBytes", d.rawBytes, "fwdBytes", d.fwdBytes,
                    "raw", d.raw, "forwarded", d.forwarded,
                    "reductionPct", d.rawBytes == 0 ? 0 : Num.round(100 - Num.pct(d.fwdBytes, d.rawBytes), 1)));
        }
        byNode.sort(Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.get("rawBytes")).doubleValue()).reversed());
        out.put("byNode", byNode);
        out.put("today", totals(t));

        double[] last = lastMinuteTraffic(now);
        out.put("msgsPerMin", (long) last[0]);
        out.put("fwdPerMin", (long) last[1]);

        int sensors = 0;
        int out1 = 0;
        int silent = 0;
        for (Sensor s : hub.store.sensors.values()) {
            EdgeNode n = hub.store.nodes.get(s.nodeId);
            if (n == null || !n.enabled || !s.enabled) continue;
            sensors++;
            String st = hub.engine.sensorState(s, n, now);
            if (st.equals("out") || st.equals("alert")) out1++;
            if (st.equals("silent")) silent++;
        }
        out.put("sensors", Json.obj("total", sensors, "outOfRange", out1, "silent", silent));

        int[] sev = new int[3];
        List<Alert> active = hub.engine.activeAlerts();
        for (Alert a : active) sev[a.severity.ordinal()]++;
        out.put("alerts", Json.obj("info", sev[0], "warning", sev[1], "critical", sev[2], "total", active.size()));
        active.sort(Comparator.comparing((Alert a) -> a.clearedAt != 0)
                .thenComparing((Alert a) -> -a.severity.ordinal())
                .thenComparing((Alert a) -> -a.openedAt));
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Alert a : active.subList(0, Math.min(6, active.size()))) recent.add(hub.alerts.view(a));
        out.put("activeAlerts", recent);

        out.put("traffic", traffic(null, now, 60));
        out.put("topology", topology(now));
        out.put("events", hub.alerts.events(null, null, 8));
        out.put("faults", hub.sim.faults.size());
        out.put("simulatorOn", cfg().simulatorOn);
        out.put("costPerGb", cfg().costPerGb);
        return out;
    }

    private static void add(DayStat t, DayStat d) {
        t.raw += d.raw;
        t.invalid += d.invalid;
        t.forwarded += d.forwarded;
        t.rawBytes += d.rawBytes;
        t.fwdBytes += d.fwdBytes;
        t.buffered += d.buffered;
        t.alerts += d.alerts;
        t.edgeMsSum += d.edgeMsSum;
        t.edgeMsN += d.edgeMsN;
        t.onlineSec += d.onlineSec;
        t.watchedSec += d.watchedSec;
    }

    private Map<String, Object> totals(DayStat t) {
        long saved = Math.max(0, t.rawBytes - t.fwdBytes);
        return Json.obj("raw", t.raw, "invalid", t.invalid, "valid", t.raw - t.invalid, "forwarded", t.forwarded,
                "buffered", t.buffered, "rawBytes", t.rawBytes, "fwdBytes", t.fwdBytes, "savedBytes", saved,
                "reductionPct", t.rawBytes == 0 ? 0 : Num.round(100 - Num.pct(t.fwdBytes, t.rawBytes), 1),
                "savedCost", cost(saved), "rawCost", cost(t.rawBytes), "fwdCost", cost(t.fwdBytes),
                "alerts", t.alerts,
                "edgeMs", t.edgeMsN == 0 ? 0 : Num.round(t.edgeMsSum / t.edgeMsN, 3),
                "cloudMs", Num.round(cfg().localHopMs + cfg().cloudRttMs, 1),
                "uptimePct", t.watchedSec == 0 ? 100 : Num.round(Num.pct(t.onlineSec, t.watchedSec), 2));
    }

    /** Messages and bytes per minute for one node (or all when nodeId is null). */
    private List<double[]> traffic(String nodeId, long now, int minutes) {
        long end = now - Math.floorMod(now, 60_000L);
        long start = end - (minutes - 1) * 60_000L;
        List<double[]> out = new ArrayList<>();
        for (long m = start; m <= end; m += 60_000L) out.add(new double[] {m, 0, 0, 0, 0, 0});
        for (EdgeNode n : hub.store.nodes.values()) {
            if (nodeId != null && !nodeId.equals(n.id)) continue;
            for (double[] r : hub.engine.rt(n).traffic.since(start)) {
                int i = (int) ((r[0] - start) / 60_000L);
                if (i < 0 || i >= out.size()) continue;
                for (int k = 1; k < 6; k++) out.get(i)[k] += r[k];
            }
        }
        return out;
    }

    private List<Map<String, Object>> topology(long now) {
        List<Map<String, Object>> sites = new ArrayList<>();
        for (Site site : hub.store.sites.values()) {
            List<Map<String, Object>> nodes = new ArrayList<>();
            for (EdgeNode n : hub.store.nodes.values()) {
                if (!site.id.equals(n.siteId)) continue;
                List<Map<String, Object>> ss = new ArrayList<>();
                for (Sensor s : hub.store.sensors.values()) {
                    if (!n.id.equals(s.nodeId)) continue;
                    ss.add(Json.obj("id", s.id, "name", s.name, "type", s.type, "state", hub.engine.sensorState(s, n, now),
                            "value", s.lastValue, "unit", s.unit));
                }
                nodes.add(Json.obj("id", n.id, "name", n.name, "status", hub.engine.status(n, now), "mode", n.mode,
                        "uplinkUp", n.uplinkUp, "buffered", n.buffered, "cpu", n.cpu, "sensors", ss));
            }
            sites.add(Json.obj("id", site.id, "name", site.name, "location", site.location, "kind", site.kind, "nodes", nodes));
        }
        return sites;
    }

    // ------------------------------------------------------------------ node page

    public Map<String, Object> node(String id) {
        long now = hub.now();
        EdgeNode n = hub.nodes.node(id);
        NodeRuntime nr = hub.engine.rt(n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", hub.nodes.nodeView(n));
        out.put("sensors", hub.nodes.sensors(id, null, null, null));
        out.put("health", nr.health.since(now - 120 * 60_000L));
        out.put("traffic", traffic(id, now, 60));
        out.put("commands", hub.nodes.commands(id, 10));
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (Alert a : hub.alerts.find("ACTIVE", null, id, null, null, null, null)) alerts.add(hub.alerts.view(a));
        out.put("alerts", alerts);
        out.put("events", hub.alerts.events(id, null, 12));
        List<Map<String, Object>> days = new ArrayList<>();
        LocalDate today = LocalDate.parse(hub.engine.date(now));
        for (int d = 6; d >= 0; d--) {
            String date = today.minusDays(d).toString();
            DayStat s = hub.store.days.get(date + "|" + id);
            days.add(Json.obj("date", date, "raw", s == null ? 0 : s.raw, "forwarded", s == null ? 0 : s.forwarded,
                    "rawBytes", s == null ? 0 : s.rawBytes, "fwdBytes", s == null ? 0 : s.fwdBytes,
                    "uptimePct", s == null || s.watchedSec == 0 ? null : Num.round(Num.pct(s.onlineSec, s.watchedSec), 1)));
        }
        out.put("days", days);
        List<Map<String, Object>> faults = new ArrayList<>();
        for (var f : hub.sim.faults.values()) if (id.equals(f.nodeId)) faults.add(faultView(f));
        out.put("faults", faults);
        return out;
    }

    public Map<String, Object> faultView(com.cecil.edge.model.Fault f) {
        Map<String, Object> m = Json.toMap(f);
        m.put("typeLabel", f.type.label);
        EdgeNode n = hub.store.nodes.get(f.nodeId);
        Sensor s = f.sensorId == null ? null : hub.store.sensors.get(f.sensorId);
        m.put("nodeName", n == null ? f.nodeId : n.name);
        m.put("sensorName", s == null ? null : s.name);
        m.put("unit", s == null ? null : s.unit);
        return m;
    }

    // ------------------------------------------------------------------ "what if" mode comparison

    public Map<String, Object> compare(String id, Double deadbandPct, Double windowSec) {
        EdgeNode n = hub.nodes.node(id);
        double dbPct = deadbandPct != null ? Num.clamp(deadbandPct, 0, 50) : n.deadbandPct;
        long windowMs = (long) (windowSec != null ? Num.clamp(windowSec, 10, 3600) : n.windowSec) * 1000L;
        long[][] totals = new long[NodeMode.values().length][2];
        long readings = 0;
        long first = Long.MAX_VALUE;
        long last = 0;
        List<Map<String, Object>> perSensor = new ArrayList<>();
        for (Sensor s : hub.store.sensors.values()) {
            if (!id.equals(s.nodeId)) continue;
            List<double[]> rows = new ArrayList<>();
            for (double[] r : hub.engine.rt(s).raw.all()) {
                if (r[2] == SensorRuntime.FLAG_INVALID) continue;
                rows.add(new double[] {r[0], r[1]});
            }
            if (rows.isEmpty()) continue;
            readings += rows.size();
            first = Math.min(first, (long) rows.get(0)[0]);
            last = Math.max(last, (long) rows.get(rows.size() - 1)[0]);
            double span = s.high - s.low;
            double db = Math.max(1e-6, span * dbPct / 100.0);
            Map<String, Object> res = EdgeEngine.compareModes(rows, db, windowMs, cfg().bytesRaw, cfg().bytesSummary);
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.id);
            sm.put("name", s.name);
            sm.put("readings", rows.size());
            for (NodeMode m : NodeMode.values()) {
                long[] v = (long[]) res.get(m.name());
                totals[m.ordinal()][0] += v[0];
                totals[m.ordinal()][1] += v[1];
                sm.put(m.name(), v[0]);
            }
            perSensor.add(sm);
        }
        List<Map<String, Object>> modes = new ArrayList<>();
        long rawBytes = totals[NodeMode.RAW.ordinal()][1];
        double minutes = last > first ? (last - first) / 60_000.0 : 0;
        for (NodeMode m : NodeMode.values()) {
            long[] v = totals[m.ordinal()];
            double perDay = minutes > 0 ? v[1] * (1440.0 / minutes) : 0;
            modes.add(Json.obj("mode", m, "label", m.label, "help", m.help, "messages", v[0], "bytes", v[1],
                    "reductionPct", rawBytes == 0 ? 0 : Num.round(100 - Num.pct(v[1], rawBytes), 1),
                    "perDayBytes", Math.round(perDay), "perMonthCost", cost(perDay * 30), "current", m == n.mode));
        }
        return Json.obj("node", n.id, "name", n.name, "deadbandPct", dbPct, "windowSec", windowMs / 1000,
                "readings", readings, "minutes", Num.round(minutes, 1), "modes", modes, "sensors", perSensor);
    }

    // ------------------------------------------------------------------ sensor page

    public Map<String, Object> sensor(String id) {
        long now = hub.now();
        Sensor s = hub.nodes.sensor(id);
        EdgeNode n = hub.store.nodes.get(s.nodeId);
        SensorRuntime sr = hub.engine.rt(s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sensor", hub.nodes.sensorView(s));
        out.put("node", n == null ? null : Json.obj("id", n.id, "name", n.name, "mode", n.mode, "modeLabel", n.mode.label,
                "windowSec", n.windowSec, "deadbandPct", n.deadbandPct, "status", hub.engine.status(n, now),
                "deadband", n == null ? 0 : Num.round(EdgeEngine.deadband(n, s), 4)));
        out.put("raw", sr.raw.since(now - 15 * 60_000L));
        out.put("summaries", sr.summaries.since(now - 60 * 60_000L));
        List<double[]> minutes = new ArrayList<>();
        for (double[] r : sr.minutes.all()) minutes.add(new double[] {r[0], r[2], r[3], Num.round(r[4] / r[1], 4), r[1]});
        out.put("minutes", minutes);

        long since = now - 7 * 86_400_000L;
        List<double[]> hours = new ArrayList<>();
        for (HourAgg h : hub.store.hours.values()) {
            if (id.equals(h.sensorId) && h.hour >= since) hours.add(new double[] {h.hour, h.min, h.max, Num.round(h.avg(), 4), h.n});
        }
        HourAgg cur = hub.engine.currentHour(s);
        if (cur != null) {
            HourAgg saved = hub.store.hours.get(id + "|" + cur.hour);
            if (saved == null) hours.add(new double[] {cur.hour, cur.min, cur.max, Num.round(cur.avg(), 4), cur.n});
        }
        hours.sort(Comparator.comparingDouble(r -> r[0]));
        out.put("hours", hours);

        long kept = 0;
        long sent = 0;
        long bad = 0;
        RollingStats st = new RollingStats(720);
        for (double[] r : sr.raw.since(now - 60 * 60_000L)) {
            if (r[2] == SensorRuntime.FLAG_INVALID) { bad++; continue; }
            if (r[2] == SensorRuntime.FLAG_SENT) sent++; else kept++;
            st.add(r[1]);
        }
        out.put("lastHour", Json.obj("readings", kept + sent + bad, "sentRaw", sent, "keptAtEdge", kept, "invalid", bad,
                "summaries", sr.summaries.since(now - 60 * 60_000L).size(),
                "mean", Num.round(st.mean(), 3), "std", Num.round(st.std(), 3)));

        List<Map<String, Object>> rules = new ArrayList<>();
        for (Rule r : hub.nodes.rulesFor(s)) rules.add(hub.alerts.ruleView(r));
        out.put("rules", rules);
        List<Map<String, Object>> alerts = new ArrayList<>();
        List<Alert> list = hub.alerts.find(null, null, null, id, null, null, null);
        for (Alert a : list.subList(0, Math.min(10, list.size()))) alerts.add(hub.alerts.view(a));
        out.put("alerts", alerts);
        List<Map<String, Object>> faults = new ArrayList<>();
        for (var f : hub.sim.faults.values()) if (id.equals(f.sensorId)) faults.add(faultView(f));
        out.put("faults", faults);
        return out;
    }

    // ------------------------------------------------------------------ reports

    public Map<String, Object> reports(int days) {
        long now = hub.now();
        days = Math.max(1, Math.min(days, cfg().historyDays));
        LocalDate today = LocalDate.parse(hub.engine.date(now));
        LocalDate first = today.minusDays(days - 1);
        long fromMs = first.atStartOfDay(hub.engine.zone).toInstant().toEpochMilli();

        List<Map<String, Object>> perDay = new ArrayList<>();
        Map<String, Integer> dayIndex = new LinkedHashMap<>();
        DayStat all = new DayStat();
        Map<String, DayStat> perNode = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            String date = first.plusDays(i).toString();
            DayStat t = new DayStat();
            for (EdgeNode n : hub.store.nodes.values()) {
                DayStat d = hub.store.days.get(date + "|" + n.id);
                if (d == null) continue;
                add(t, d);
                add(all, d);
                add(perNode.computeIfAbsent(n.id, k -> new DayStat()), d);
            }
            dayIndex.put(date, i);
            perDay.add(Json.obj("date", date, "raw", t.raw, "forwarded", t.forwarded, "invalid", t.invalid,
                    "rawBytes", t.rawBytes, "fwdBytes", t.fwdBytes,
                    "reductionPct", t.rawBytes == 0 ? 0 : Num.round(100 - Num.pct(t.fwdBytes, t.rawBytes), 1),
                    "info", 0, "warning", 0, "critical", 0));
        }

        List<Alert> alerts = hub.alerts.find(null, null, null, null, null, fromMs, null);
        Map<String, Integer> byRule = new LinkedHashMap<>();
        Map<String, Integer> bySensor = new LinkedHashMap<>();
        Map<String, Integer> byNodeAlerts = new LinkedHashMap<>();
        for (Alert a : alerts) {
            Integer i = dayIndex.get(hub.engine.date(a.openedAt));
            if (i != null) {
                Map<String, Object> d = perDay.get(i);
                String k = a.severity.name().toLowerCase();
                d.put(k, ((Integer) d.get(k)) + 1);
            }
            byRule.merge(a.ruleName == null ? a.ruleId : a.ruleName, 1, Integer::sum);
            if (a.sensorId != null) bySensor.merge(a.sensorId, 1, Integer::sum);
            byNodeAlerts.merge(a.nodeId, 1, Integer::sum);
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (EdgeNode n : hub.store.nodes.values()) {
            DayStat d = perNode.get(n.id);
            if (d == null) continue;
            nodes.add(Json.obj("id", n.id, "name", n.name, "mode", n.mode, "modeLabel", n.mode.label,
                    "raw", d.raw, "forwarded", d.forwarded, "invalid", d.invalid, "rawBytes", d.rawBytes, "fwdBytes", d.fwdBytes,
                    "reductionPct", d.rawBytes == 0 ? 0 : Num.round(100 - Num.pct(d.fwdBytes, d.rawBytes), 1),
                    "uptimePct", d.watchedSec == 0 ? 0 : Num.round(Num.pct(d.onlineSec, d.watchedSec), 2),
                    "edgeMs", d.edgeMsN == 0 ? 0 : Num.round(d.edgeMsSum / d.edgeMsN, 3),
                    "alerts", byNodeAlerts.getOrDefault(n.id, 0),
                    "savedCost", cost(Math.max(0, d.rawBytes - d.fwdBytes))));
        }

        List<Map<String, Object>> rules = new ArrayList<>();
        byRule.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                .forEach(e -> rules.add(Json.obj("name", e.getKey(), "count", e.getValue())));
        List<Map<String, Object>> sensors = new ArrayList<>();
        bySensor.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8).forEach(e -> {
            Sensor s = hub.store.sensors.get(e.getKey());
            EdgeNode n = s == null ? null : hub.store.nodes.get(s.nodeId);
            sensors.add(Json.obj("id", e.getKey(), "name", s == null ? e.getKey() : s.name, "node", n == null ? "-" : n.name, "count", e.getValue()));
        });

        double[] mt = AlertService.mtta(alerts);
        int[] sev = new int[3];
        for (Alert a : alerts) sev[a.severity.ordinal()]++;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("from", first.toString());
        out.put("to", today.toString());
        out.put("totals", totals(all));
        out.put("perDay", perDay);
        out.put("nodes", nodes);
        out.put("topRules", rules);
        out.put("topSensors", sensors);
        out.put("alerts", Json.obj("total", alerts.size(), "info", sev[0], "warning", sev[1], "critical", sev[2],
                "mttaMin", mt[0], "mttrMin", mt[1]));
        out.put("costPerGb", cfg().costPerGb);
        return out;
    }
}
