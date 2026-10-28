package com.cecil.edge.edge;

import com.cecil.edge.model.Alert;
import com.cecil.edge.model.DayStat;
import com.cecil.edge.model.EdgeNode;
import com.cecil.edge.model.Enums.AlertStatus;
import com.cecil.edge.model.Enums.Condition;
import com.cecil.edge.model.Enums.NodeMode;
import com.cecil.edge.model.Enums.NodeStatus;
import com.cecil.edge.model.Enums.Severity;
import com.cecil.edge.model.HourAgg;
import com.cecil.edge.model.Rule;
import com.cecil.edge.model.Sensor;
import com.cecil.edge.model.Settings;
import com.cecil.edge.store.DataStore;
import com.cecil.edge.util.Num;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The edge processing pipeline. Every reading from a sensor goes through these steps on the node:
 *
 *   1. INGEST     count it (raw messages and bytes that WOULD go to the cloud without edge computing)
 *   2. VALIDATE   drop impossible values (outside the sensor's physical limits)
 *   3. ANALYSE    update rolling average / std (for z-score), rate of change, minute and hour summaries
 *   4. DETECT     check every rule locally and raise alerts at once (no cloud round trip)
 *   5. REDUCE     decide what to send with the node's mode (raw / filter / aggregate / smart)
 *   6. FORWARD    send to the cloud, or keep in the store-and-forward buffer when the link is down
 *
 * It also watches node health (heartbeats, CPU, temperature, buffer) and raises system alerts.
 * All methods must be called while holding the Hub lock.
 */
public final class EdgeEngine {
    public final DataStore store;
    public ZoneId zone = ZoneId.systemDefault();
    /** While true (warming up after start) readings fill the live charts but do not count or raise alerts. */
    public boolean quiet;

    private final Map<String, SensorRuntime> sensorRt = new HashMap<>();
    private final Map<String, NodeRuntime> nodeRt = new HashMap<>();
    /** "ruleId|sensorId or nodeId" -> alert that is not resolved yet. */
    private final Map<String, Alert> active = new HashMap<>();

    public static final String SYS_OFFLINE = "sys-offline";
    public static final String SYS_UPLINK = "sys-uplink";
    public static final String SYS_CPU = "sys-cpu";
    public static final String SYS_TEMP = "sys-temp";
    public static final String SYS_BUFFER = "sys-buffer";
    public static final String SYS_INVALID = "sys-invalid";

    public EdgeEngine(DataStore store) {
        this.store = store;
        rebuildIndex();
    }

    private Settings cfg() { return store.settings; }

    public SensorRuntime rt(Sensor s) { return sensorRt.computeIfAbsent(s.id, k -> new SensorRuntime()); }

    public NodeRuntime rt(EdgeNode n) { return nodeRt.computeIfAbsent(n.id, k -> new NodeRuntime()); }

    public void forget(String sensorOrNodeId) {
        sensorRt.remove(sensorOrNodeId);
        nodeRt.remove(sensorOrNodeId);
    }

    public void rebuildIndex() {
        active.clear();
        for (Alert a : store.alerts.values()) {
            if (a.status != AlertStatus.RESOLVED) active.put(alertKey(a.ruleId, a.sensorId != null ? a.sensorId : a.nodeId), a);
        }
    }

    public static String alertKey(String ruleId, String target) { return ruleId + "|" + target; }

    public String date(long ts) { return LocalDate.ofInstant(Instant.ofEpochMilli(ts), zone).toString(); }

    public DayStat day(EdgeNode n, long ts) {
        String d = date(ts);
        String key = d + "|" + n.id;
        DayStat s = store.days.get(key);
        if (s == null) {
            s = new DayStat();
            s.date = d;
            s.nodeId = n.id;
            store.days.put(key, s);
        }
        return s;
    }

    /** Absolute dead-band for a sensor: a % of its normal range (or of its physical range if not set). */
    public static double deadband(EdgeNode n, Sensor s) {
        double span = s.high - s.low;
        if (span <= 0) span = s.type.hi - s.type.lo;
        return Math.max(1e-6, span * n.deadbandPct / 100.0);
    }

    // ------------------------------------------------------------------ status

    public boolean isOnline(EdgeNode n, long now) {
        return n.enabled && n.lastSeen > 0 && now - n.lastSeen <= cfg().heartbeatTimeoutSec * 1000L;
    }

    public NodeStatus status(EdgeNode n, long now) {
        if (!n.enabled) return NodeStatus.DISABLED;
        if (!isOnline(n, now)) return NodeStatus.OFFLINE;
        if (!n.uplinkUp || n.cpu >= cfg().cpuWarnPct || n.temp >= cfg().tempWarnC || bufferPct(n) >= cfg().bufferWarnPct) {
            return NodeStatus.DEGRADED;
        }
        return NodeStatus.ONLINE;
    }

    public static double bufferPct(EdgeNode n) {
        return n.bufferCapacity <= 0 ? 0 : n.buffered * 100.0 / n.bufferCapacity;
    }

    /** "ok", "out" (outside normal range), "alert" (has an open alert), "silent" (no recent data), "off". */
    public String sensorState(Sensor s, EdgeNode n, long now) {
        if (!s.enabled || n == null || !n.enabled) return "off";
        if (s.lastValue == null || now - s.lastAt > Math.max(30_000L, s.sampleSec * 4000L)) return "silent";
        for (Alert a : active.values()) {
            if (s.id.equals(a.sensorId) && a.clearedAt == 0 && a.severity != Severity.INFO) return "alert";
        }
        if (s.lastValue < s.low || s.lastValue > s.high) return "out";
        return "ok";
    }

    // ------------------------------------------------------------------ heartbeat

    public void heartbeat(EdgeNode n, long ts, double cpu, double mem, double disk, double temp, boolean uplinkUp) {
        boolean wasOnline = isOnline(n, ts);
        n.lastSeen = ts;
        if (n.bootAt == 0) n.bootAt = ts;
        n.cpu = Num.round(Num.clamp(cpu, 0, 100), 1);
        n.mem = Num.round(Num.clamp(mem, 0, 100), 1);
        n.disk = Num.round(Num.clamp(disk, 0, 100), 1);
        n.temp = Num.round(temp, 1);
        boolean linkBack = !n.uplinkUp && uplinkUp;
        n.uplinkUp = uplinkUp;
        rt(n).healthPoint(ts, n.cpu, n.mem, n.temp, n.buffered);
        if (!quiet) {
            if (!wasOnline && rt(n).wasOnline) store.addEvent(ts, "system", "node", n.id, n.name + " is back online");
            if (linkBack) store.addEvent(ts, "system", "uplink", n.id, n.name + ": cloud link restored, sending " + n.buffered + " buffered messages");
        }
        rt(n).wasOnline = true;
        store.markDirty("nodes");
    }

    // ------------------------------------------------------------------ the pipeline

    /** What happened to one reading (used by the ingest API and the tests). */
    public static final class Result {
        public boolean valid;
        public boolean rawSent;
        public int summaries;
        public boolean buffered;
        public double edgeMs;
        public final List<Alert> newAlerts = new ArrayList<>();
    }

    public Result process(EdgeNode n, Sensor s, double value, long ts) {
        long t0 = System.nanoTime();
        Settings c = cfg();
        Result res = new Result();
        SensorRuntime sr = rt(s);
        NodeRuntime nr = rt(n);
        DayStat ds = quiet ? new DayStat() : day(n, ts);
        double[] tr = nr.trafficRow(ts);

        // 1. ingest: this is what a "no edge" system would send
        ds.raw++;
        ds.rawBytes += c.bytesRaw;
        tr[1]++;
        tr[3] += c.bytesRaw;

        // 2. validate
        if (Double.isNaN(value) || Double.isInfinite(value) || value < s.type.lo || value > s.type.hi) {
            ds.invalid++;
            tr[5]++;
            sr.raw.add(ts, Double.isFinite(value) ? value : 0, SensorRuntime.FLAG_INVALID);
            if (!quiet) {
                checkInvalidBurst(n, s, sr, ts);
                store.markDirty("days");
            }
            res.edgeMs = elapsedMs(t0) + c.localHopMs;
            return res;
        }
        res.valid = true;

        // 3. analyse
        Double z = sr.stats.z(value, 30);
        double[] old = sr.aboutOneMinuteAgo(ts);
        Double ratePerMin = old == null ? null : (value - old[1]) / ((ts - old[0]) / 60_000.0);
        sr.stats.add(value);
        sr.addMinute(ts, value);
        addHour(s, sr, ts, value);
        s.lastValue = Num.round(value, 3);
        s.lastAt = ts;

        // 4. detect (rules run here, on the node)
        boolean urgent = false;
        if (!quiet) {
            for (Rule r : store.rules.values()) {
                if (!applies(r, n, s) || r.condition == Condition.NO_DATA) continue;
                boolean bad = switch (r.condition) {
                    case ABOVE -> value > r.threshold;
                    case BELOW -> value < r.threshold;
                    case RATE_OF_CHANGE -> ratePerMin != null && Math.abs(ratePerMin) > r.threshold;
                    case ANOMALY -> z != null && Math.abs(z) >= r.threshold;
                    default -> false;
                };
                String key = alertKey(r.id, s.id);
                if (bad) {
                    long since = sr.since.computeIfAbsent(r.id, k -> ts);
                    if (ts - since >= r.durationSec * 1000L) {
                        Alert a = fire(r, n, s, value, ts, describe(r, s, value, ratePerMin, z));
                        if (a != null) { res.newAlerts.add(a); ds.alerts++; }
                        urgent = true;
                    }
                } else {
                    sr.since.remove(r.id);
                    Alert a = active.get(key);
                    if (a != null && a.clearedAt == 0) { a.clearedAt = ts; store.markDirty("alerts"); }
                }
            }
        }

        // 5. reduce
        ModeLogic.Out out = sr.logic.offer(n.mode, ts, value, deadband(n, s), n.windowSec * 1000L, urgent);
        res.rawSent = out.rawSent;
        res.summaries = out.summaries;
        if (out.summary != null) sr.summaries.add(out.summary);
        sr.raw.add(ts, value, out.rawSent ? SensorRuntime.FLAG_SENT : SensorRuntime.FLAG_KEPT);

        // 6. forward (or buffer)
        int msgs = (out.rawSent ? 1 : 0) + out.summaries;
        long bytes = (out.rawSent ? c.bytesRaw : 0) + (long) out.summaries * c.bytesSummary;
        if (msgs > 0) res.buffered = !deliver(n, ds, tr, msgs, bytes);

        res.edgeMs = elapsedMs(t0) + c.localHopMs;
        nr.edgeMsAvg = nr.edgeMsAvg == 0 ? res.edgeMs : nr.edgeMsAvg * 0.95 + res.edgeMs * 0.05;
        ds.edgeMsSum += res.edgeMs;
        ds.edgeMsN++;
        for (Alert a : res.newAlerts) {
            a.edgeMs = Num.round(res.edgeMs, 3);
            a.cloudMs = Num.round(c.localHopMs + c.cloudRttMs + (res.edgeMs - c.localHopMs), 1);
        }
        if (!quiet) store.markDirty("days", "sensors");
        return res;
    }

    private static double elapsedMs(long t0) { return (System.nanoTime() - t0) / 1_000_000.0; }

    /** true = sent to the cloud now; false = kept in the buffer (or lost when the buffer is full). */
    private boolean deliver(EdgeNode n, DayStat ds, double[] tr, int msgs, long bytes) {
        if (n.uplinkUp) {
            ds.forwarded += msgs;
            ds.fwdBytes += bytes;
            tr[2] += msgs;
            tr[4] += bytes;
            return true;
        }
        int space = Math.max(0, n.bufferCapacity - n.buffered);
        int keep = Math.min(space, msgs);
        if (keep > 0) {
            n.buffered += keep;
            n.bufferedBytes += bytes * keep / msgs;
            ds.buffered += keep;
        }
        if (keep < msgs) n.lost += msgs - keep;
        return false;
    }

    /** Store-and-forward: once the link is back, send the buffer in batches (up to `max` messages per call). */
    public int flushBuffer(EdgeNode n, long ts, int max) {
        if (!n.uplinkUp || n.buffered <= 0) return 0;
        int k = Math.min(max, n.buffered);
        long bytes = n.buffered == 0 ? 0 : n.bufferedBytes * k / n.buffered;
        n.buffered -= k;
        n.bufferedBytes -= bytes;
        if (n.buffered == 0) n.bufferedBytes = 0;
        if (!quiet) {
            DayStat ds = day(n, ts);
            ds.forwarded += k;
            ds.fwdBytes += bytes;
            store.markDirty("days", "nodes");
        }
        double[] tr = rt(n).trafficRow(ts);
        tr[2] += k;
        tr[4] += bytes;
        return k;
    }

    private void addHour(Sensor s, SensorRuntime sr, long ts, double v) {
        long h = ts - Math.floorMod(ts, 3_600_000L);
        if (sr.hourStart != h) {
            saveHour(s, sr);
            sr.hourStart = h;
            sr.hourN = 0;
            sr.hourMin = Double.MAX_VALUE;
            sr.hourMax = -Double.MAX_VALUE;
            sr.hourSum = 0;
        }
        sr.hourN++;
        sr.hourMin = Math.min(sr.hourMin, v);
        sr.hourMax = Math.max(sr.hourMax, v);
        sr.hourSum += v;
    }

    /** Merges the hour summed in memory into the saved hourly history. */
    public void saveHour(Sensor s, SensorRuntime sr) {
        if (quiet || sr.hourStart < 0 || sr.hourN == 0) return;
        String key = s.id + "|" + sr.hourStart;
        HourAgg h = store.hours.get(key);
        if (h == null) {
            h = new HourAgg();
            h.sensorId = s.id;
            h.hour = sr.hourStart;
            h.min = sr.hourMin;
            h.max = sr.hourMax;
            store.hours.put(key, h);
        }
        h.n += sr.hourN;
        h.sum = Num.round(h.sum + sr.hourSum, 4);
        h.min = Num.round(Math.min(h.min, sr.hourMin), 4);
        h.max = Num.round(Math.max(h.max, sr.hourMax), 4);
        sr.hourN = 0;
        sr.hourSum = 0;
        sr.hourMin = Double.MAX_VALUE;
        sr.hourMax = -Double.MAX_VALUE;
        store.markDirty("hours");
    }

    /** After the warm-up the hour sums start again, so warm-up readings are never saved twice. */
    public void resetHourSums() {
        for (SensorRuntime sr : sensorRt.values()) {
            sr.hourStart = -1;
            sr.hourN = 0;
        }
    }

    /** Hourly numbers for the current hour that are still in memory (shown with the saved history). */
    public HourAgg currentHour(Sensor s) {
        SensorRuntime sr = sensorRt.get(s.id);
        if (sr == null || sr.hourN == 0) return null;
        HourAgg h = new HourAgg();
        h.sensorId = s.id;
        h.hour = sr.hourStart;
        h.n = sr.hourN;
        h.min = sr.hourMin;
        h.max = sr.hourMax;
        h.sum = sr.hourSum;
        return h;
    }

    // ------------------------------------------------------------------ rules and alerts

    public static boolean applies(Rule r, EdgeNode n, Sensor s) {
        return r.enabled && targets(r, n, s);
    }

    /** Does the rule point at this sensor (whether or not the rule is turned on)? */
    public static boolean targets(Rule r, EdgeNode n, Sensor s) {
        if (r.sensorId != null && !r.sensorId.isEmpty()) return r.sensorId.equals(s.id);
        if (r.nodeId != null && !r.nodeId.isEmpty() && !r.nodeId.equals(n.id)) return false;
        return r.sensorType == null || r.sensorType == s.type;
    }

    private static String fmt(double v) {
        double a = Math.abs(v);
        return String.valueOf(a >= 100 ? Num.round(v, 0) : a >= 10 ? Num.round(v, 1) : Num.round(v, 2)).replaceAll("\\.0$", "");
    }

    static String describe(Rule r, Sensor s, double v, Double rate, Double z) {
        String u = s.unit == null ? "" : " " + s.unit;
        return switch (r.condition) {
            case ABOVE -> s.name + " is " + fmt(v) + u + " (limit " + fmt(r.threshold) + u + ")";
            case BELOW -> s.name + " is " + fmt(v) + u + " (minimum " + fmt(r.threshold) + u + ")";
            case RATE_OF_CHANGE -> s.name + " changing " + fmt(rate == null ? 0 : rate) + u + " per minute (limit " + fmt(r.threshold) + ")";
            case ANOMALY -> s.name + " unusual: " + fmt(v) + u + " (z-score " + fmt(z == null ? 0 : z) + ")";
            case NO_DATA -> s.name + " sent no data for over " + (int) r.threshold + " s";
        };
    }

    /** Raises an alert, or updates the one that is already open. Returns the alert only when it is new. */
    private Alert fire(Rule r, EdgeNode n, Sensor s, double value, long ts, String msg) {
        String key = alertKey(r.id, s.id);
        Alert a = active.get(key);
        if (a != null) {
            a.lastSeenAt = ts;
            a.value = Num.round(value, 3);
            if (a.clearedAt != 0) { a.repeats++; a.clearedAt = 0; a.message = msg; }
            store.markDirty("alerts");
            return null;
        }
        a = new Alert();
        a.id = store.nextId("A", store.alerts.keySet());
        a.ruleId = r.id;
        a.ruleName = r.name;
        a.nodeId = n.id;
        a.sensorId = s.id;
        a.severity = r.severity;
        a.message = msg;
        a.value = Num.round(value, 3);
        a.threshold = r.threshold;
        a.openedAt = ts;
        a.lastSeenAt = ts;
        a.action = r.action == null || r.action.isBlank() ? null : r.action;
        store.alerts.put(a.id, a);
        active.put(key, a);
        r.hits++;
        r.lastHit = ts;
        store.markDirty("alerts", "rules");
        store.addEvent(ts, "edge", "alert", n.id, r.severity + ": " + msg + (a.action != null ? " -> edge action: " + a.action : ""));
        store.trim();
        return a;
    }

    /** Built-in health alert for a node (offline, CPU, heat, buffer, link) or a sensor (garbage data). */
    public Alert raiseSystem(String code, String name, EdgeNode n, String sensorId, Severity sev, String msg,
                             double value, double threshold, long ts) {
        String target = sensorId != null ? sensorId : n.id;
        Alert a = active.get(alertKey(code, target));
        if (a != null) {
            a.lastSeenAt = ts;
            a.value = Num.round(value, 2);
            if (a.clearedAt != 0) { a.repeats++; a.clearedAt = 0; }
            a.message = msg;
            store.markDirty("alerts");
            return null;
        }
        a = new Alert();
        a.id = store.nextId("A", store.alerts.keySet());
        a.ruleId = code;
        a.ruleName = name;
        a.nodeId = n.id;
        a.sensorId = sensorId;
        a.severity = sev;
        a.message = msg;
        a.value = Num.round(value, 2);
        a.threshold = threshold;
        a.openedAt = ts;
        a.lastSeenAt = ts;
        a.edgeMs = 0;
        a.cloudMs = 0;
        store.alerts.put(a.id, a);
        active.put(alertKey(code, target), a);
        day(n, ts).alerts++;
        store.markDirty("alerts", "days");
        store.addEvent(ts, "system", "alert", n.id, sev + ": " + msg);
        store.trim();
        return a;
    }

    public void clearSystem(String code, String target, long ts) {
        Alert a = active.get(alertKey(code, target));
        if (a != null && a.clearedAt == 0) {
            a.clearedAt = ts;
            store.markDirty("alerts");
        }
    }

    /** Many garbage readings in a row usually means a broken sensor or loose wire. */
    private void checkInvalidBurst(EdgeNode n, Sensor s, SensorRuntime sr, long ts) {
        int bad = 0;
        int seen = 0;
        for (double[] r : sr.raw.since(ts - 60_000)) {
            seen++;
            if (r[2] == SensorRuntime.FLAG_INVALID) bad++;
        }
        if (bad >= 3 && bad * 2 >= seen) {
            raiseSystem(SYS_INVALID, "Garbage readings", n, s.id, Severity.WARNING,
                    s.name + " sent " + bad + " impossible values in the last minute (dropped at the edge)", bad, 3, ts);
        }
    }

    /** Called when someone resolves an alert. */
    public void resolved(Alert a) {
        active.remove(alertKey(a.ruleId, a.sensorId != null ? a.sensorId : a.nodeId));
    }

    public List<Alert> activeAlerts() {
        return new ArrayList<>(active.values());
    }

    // ------------------------------------------------------------------ periodic checks (every tick)

    public void checkHealth(long now, int tickSec) {
        Settings c = cfg();
        for (EdgeNode n : store.nodes.values()) {
            if (!n.enabled) continue;
            boolean online = isOnline(n, now);
            if (!quiet) {
                DayStat ds = day(n, now);
                ds.watchedSec += tickSec;
                if (online) ds.onlineSec += tickSec;
                store.markDirty("days");
            }
            if (quiet) continue;

            if (!online) {
                if (n.lastSeen > 0 || n.createdAt < now - c.heartbeatTimeoutSec * 1000L) {
                    long silent = n.lastSeen == 0 ? 0 : (now - n.lastSeen) / 1000;
                    raiseSystem(SYS_OFFLINE, "Node offline", n, null, Severity.CRITICAL,
                            n.name + " stopped sending heartbeats" + (silent > 0 ? " (" + silent + " s ago)" : " (never connected)"),
                            silent, c.heartbeatTimeoutSec, now);
                    if (rt(n).wasOnline) {
                        store.addEvent(now, "system", "node", n.id, n.name + " went offline");
                        rt(n).wasOnline = false;
                    }
                }
                continue;
            }
            clearSystem(SYS_OFFLINE, n.id, now);

            if (!n.uplinkUp) raiseSystem(SYS_UPLINK, "Cloud link down", n, null, Severity.WARNING,
                    n.name + " cannot reach the cloud; buffering data (" + n.buffered + " messages waiting)", n.buffered, 0, now);
            else clearSystem(SYS_UPLINK, n.id, now);

            if (n.cpu >= c.cpuWarnPct) raiseSystem(SYS_CPU, "High CPU", n, null, Severity.WARNING,
                    n.name + " CPU at " + Math.round(n.cpu) + "%", n.cpu, c.cpuWarnPct, now);
            else clearSystem(SYS_CPU, n.id, now);

            if (n.temp >= c.tempWarnC) raiseSystem(SYS_TEMP, "Node overheating", n, null, Severity.WARNING,
                    n.name + " board temperature " + Math.round(n.temp) + " °C", n.temp, c.tempWarnC, now);
            else clearSystem(SYS_TEMP, n.id, now);

            double bp = bufferPct(n);
            if (bp >= c.bufferWarnPct) raiseSystem(SYS_BUFFER, "Buffer almost full", n, null,
                    bp >= 100 ? Severity.CRITICAL : Severity.WARNING,
                    n.name + " buffer " + Math.round(bp) + "% full" + (n.lost > 0 ? ", " + n.lost + " messages lost" : ""), bp, c.bufferWarnPct, now);
            else clearSystem(SYS_BUFFER, n.id, now);

            // "no data" rules
            for (Sensor s : store.sensors.values()) {
                if (!s.enabled || !n.id.equals(s.nodeId)) continue;
                for (Rule r : store.rules.values()) {
                    if (r.condition != Condition.NO_DATA || !applies(r, n, s)) continue;
                    long silentMs = s.lastAt == 0 ? 0 : now - s.lastAt;
                    if (s.lastAt > 0 && silentMs > r.threshold * 1000) {
                        Alert a = fire(r, n, s, silentMs / 1000.0, now, describe(r, s, 0, null, null));
                        if (a != null) day(n, now).alerts++;
                    } else {
                        Alert a = active.get(alertKey(r.id, s.id));
                        if (a != null && a.clearedAt == 0) { a.clearedAt = now; store.markDirty("alerts"); }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ tools for the pages

    /** Replays a list of {time, value} readings through every mode, to compare how much each would send. */
    public static Map<String, Object> compareModes(List<double[]> readings, double deadband, long windowMs,
                                                   int bytesRaw, int bytesSummary) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (NodeMode m : NodeMode.values()) {
            ModeLogic logic = new ModeLogic();
            long msgs = 0;
            long bytes = 0;
            for (double[] r : readings) {
                ModeLogic.Out o = logic.offer(m, (long) r[0], r[1], deadband, windowMs, false);
                if (o.rawSent) { msgs++; bytes += bytesRaw; }
                msgs += o.summaries;
                bytes += (long) o.summaries * bytesSummary;
            }
            out.put(m.name(), new long[] {msgs, bytes});
        }
        return out;
    }

    /** How many times a rule would have fired on the readings still in memory (rule "dry run"). */
    public Map<String, Object> testRule(Rule r) {
        int sensors = 0;
        int fires = 0;
        int readings = 0;
        List<Map<String, Object>> samples = new ArrayList<>();
        for (Sensor s : store.sensors.values()) {
            EdgeNode n = store.nodes.get(s.nodeId);
            if (n == null || !applies(r, n, s)) continue;
            sensors++;
            SensorRuntime sr = sensorRt.get(s.id);
            if (sr == null || r.condition == Condition.NO_DATA) continue;
            RollingStats st = new RollingStats(120);
            Ring past = new Ring(720);
            Long since = null;
            boolean firing = false;
            for (double[] row : sr.raw.all()) {
                if (row[2] == SensorRuntime.FLAG_INVALID) continue;
                readings++;
                long ts = (long) row[0];
                double v = row[1];
                Double z = st.z(v, 30);
                double[] old = null;
                for (double[] p : past.all()) { if (ts - p[0] >= 55_000) old = p; else break; }
                Double rate = old == null ? null : (v - old[1]) / ((ts - old[0]) / 60_000.0);
                st.add(v);
                past.add(ts, v);
                boolean bad = switch (r.condition) {
                    case ABOVE -> v > r.threshold;
                    case BELOW -> v < r.threshold;
                    case RATE_OF_CHANGE -> rate != null && Math.abs(rate) > r.threshold;
                    case ANOMALY -> z != null && Math.abs(z) >= r.threshold;
                    default -> false;
                };
                if (bad) {
                    if (since == null) since = ts;
                    if (!firing && ts - since >= r.durationSec * 1000L) {
                        firing = true;
                        fires++;
                        if (samples.size() < 8) samples.add(Map.of("sensorId", s.id, "sensor", s.name, "at", ts,
                                "message", describe(r, s, v, rate, z)));
                    }
                } else {
                    since = null;
                    firing = false;
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sensors", sensors);
        m.put("readings", readings);
        m.put("fires", fires);
        m.put("samples", samples);
        return m;
    }
}
