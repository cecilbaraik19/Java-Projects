package com.cecil.edge.service;

import com.cecil.edge.model.Alert;
import com.cecil.edge.model.EdgeNode;
import com.cecil.edge.model.Enums.AlertStatus;
import com.cecil.edge.model.Enums.Condition;
import com.cecil.edge.model.Enums.SensorType;
import com.cecil.edge.model.Enums.Severity;
import com.cecil.edge.model.Event;
import com.cecil.edge.model.Rule;
import com.cecil.edge.model.Sensor;
import com.cecil.edge.model.User;
import com.cecil.edge.util.Csv;
import com.cecil.edge.util.Json;
import com.cecil.edge.util.Num;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Alerts (acknowledge, resolve, export), rules (add, edit, test) and the activity history. */
public class AlertService {
    private final Hub hub;

    AlertService(Hub hub) {
        this.hub = hub;
    }

    // ------------------------------------------------------------------ alerts

    public Map<String, Object> view(Alert a) {
        Map<String, Object> m = Json.toMap(a);
        EdgeNode n = hub.store.nodes.get(a.nodeId);
        Sensor s = a.sensorId == null ? null : hub.store.sensors.get(a.sensorId);
        m.put("nodeName", n == null ? a.nodeId : n.name);
        m.put("sensorName", s == null ? null : s.name);
        m.put("unit", s == null ? null : s.unit);
        m.put("system", a.ruleId != null && a.ruleId.startsWith("sys-"));
        return m;
    }

    public Alert alert(String id) {
        Alert a = hub.store.alerts.get(id);
        if (a == null) throw ServiceException.notFound("Alert");
        return a;
    }

    /** status: OPEN, ACKNOWLEDGED, RESOLVED or ACTIVE (= not resolved). */
    public List<Alert> find(String status, String severity, String node, String sensor, String q, Long from, Long to) {
        List<Alert> out = new ArrayList<>();
        String qq = q == null ? null : q.toLowerCase();
        for (Alert a : hub.store.alerts.values()) {
            if (status != null) {
                if (status.equals("ACTIVE")) { if (a.status == AlertStatus.RESOLVED) continue; }
                else if (!status.equals(a.status.name())) continue;
            }
            if (severity != null && !severity.equals(a.severity.name())) continue;
            if (node != null && !node.equals(a.nodeId)) continue;
            if (sensor != null && !sensor.equals(a.sensorId)) continue;
            if (from != null && a.openedAt < from) continue;
            if (to != null && a.openedAt > to) continue;
            if (qq != null && !(a.message.toLowerCase().contains(qq) || (a.ruleName != null && a.ruleName.toLowerCase().contains(qq)))) continue;
            out.add(a);
        }
        out.sort(Comparator.comparingLong((Alert a) -> a.openedAt).reversed());
        return out;
    }

    public Map<String, Object> list(String status, String severity, String node, String sensor, String q, int limit) {
        List<Alert> all = find(status, severity, node, sensor, q, null, null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Alert a : all.subList(0, Math.min(limit, all.size()))) rows.add(view(a));
        int open = 0;
        int ack = 0;
        int crit = 0;
        for (Alert a : hub.store.alerts.values()) {
            if (a.status == AlertStatus.OPEN) open++;
            if (a.status == AlertStatus.ACKNOWLEDGED) ack++;
            if (a.status != AlertStatus.RESOLVED && a.severity == Severity.CRITICAL) crit++;
        }
        return Json.obj("alerts", rows, "total", all.size(), "open", open, "acknowledged", ack, "critical", crit);
    }

    public Map<String, Object> ack(User me, String id) {
        Alert a = alert(id);
        if (a.status != AlertStatus.OPEN) throw ServiceException.bad("Only open alerts can be acknowledged");
        a.status = AlertStatus.ACKNOWLEDGED;
        a.ackBy = me.username;
        a.ackAt = hub.now();
        hub.store.markDirty("alerts");
        hub.store.addEvent(a.ackAt, me.username, "alert", a.nodeId, "Acknowledged: " + a.message);
        return view(a);
    }

    public Map<String, Object> resolve(User me, String id, Map<String, Object> b) {
        Check.canEdit(me);
        Alert a = alert(id);
        if (a.status == AlertStatus.RESOLVED) throw ServiceException.bad("This alert is already resolved");
        String note = Check.text(b, "note", "Note", 300, false);
        long now = hub.now();
        if (a.ackAt == 0) {
            a.ackAt = now;
            a.ackBy = me.username;
        }
        a.status = AlertStatus.RESOLVED;
        a.resolvedBy = me.username;
        a.resolvedAt = now;
        a.note = note;
        hub.engine.resolved(a);
        hub.store.markDirty("alerts");
        hub.store.addEvent(now, me.username, "alert", a.nodeId, "Resolved: " + a.message + (note != null ? " - " + note : ""));
        return view(a);
    }

    /** Acknowledges every open alert (optionally of one node). */
    public int ackAll(User me, String node) {
        int k = 0;
        long now = hub.now();
        for (Alert a : hub.store.alerts.values()) {
            if (a.status != AlertStatus.OPEN || (node != null && !node.equals(a.nodeId))) continue;
            a.status = AlertStatus.ACKNOWLEDGED;
            a.ackBy = me.username;
            a.ackAt = now;
            k++;
        }
        if (k > 0) {
            hub.store.markDirty("alerts");
            hub.store.addEvent(now, me.username, "alert", node, "Acknowledged " + k + " open alerts");
        }
        return k;
    }

    public String csv(String status, String severity, String node) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        sb.append(Csv.row(List.of("Alert", "Opened", "Severity", "Status", "Node", "Sensor", "Rule", "Message", "Value",
                "Edge decision (ms)", "Cloud round trip (ms)", "Edge action", "Acknowledged by", "Acknowledged",
                "Resolved by", "Resolved", "Note")));
        for (Alert a : find(status, severity, node, null, null, null, null)) {
            Map<String, Object> v = view(a);
            sb.append(Csv.row(List.of(a.id, time(a.openedAt, f), a.severity, a.status, v.get("nodeName"),
                    v.get("sensorName") == null ? "" : v.get("sensorName"), a.ruleName == null ? "" : a.ruleName, a.message, a.value,
                    a.edgeMs == 0 ? "" : a.edgeMs, a.cloudMs == 0 ? "" : a.cloudMs, a.action == null ? "" : a.action,
                    a.ackBy == null ? "" : a.ackBy, time(a.ackAt, f), a.resolvedBy == null ? "" : a.resolvedBy,
                    time(a.resolvedAt, f), a.note == null ? "" : a.note)));
        }
        return sb.toString();
    }

    private String time(long ms, DateTimeFormatter f) {
        return ms == 0 ? "" : LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), hub.engine.zone).format(f);
    }

    // ------------------------------------------------------------------ rules

    public Map<String, Object> ruleView(Rule r) {
        Map<String, Object> m = Json.toMap(r);
        m.put("conditionLabel", r.condition.label);
        Sensor s = r.sensorId == null ? null : hub.store.sensors.get(r.sensorId);
        EdgeNode n = r.nodeId == null ? null : hub.store.nodes.get(r.nodeId);
        String target;
        if (s != null) target = s.name + " (" + nodeName(s.nodeId) + ")";
        else {
            target = r.sensorType == null ? "All sensors" : "All " + r.sensorType.label.toLowerCase() + " sensors";
            if (n != null) target += " on " + n.name;
        }
        m.put("target", target);
        m.put("unit", s != null ? s.unit : r.sensorType != null ? r.sensorType.unit : null);
        int matched = 0;
        for (Sensor x : hub.store.sensors.values()) {
            EdgeNode xn = hub.store.nodes.get(x.nodeId);
            if (xn != null && com.cecil.edge.edge.EdgeEngine.targets(r, xn, x)) matched++;
        }
        m.put("sensorCount", matched);
        int active = 0;
        for (Alert a : hub.engine.activeAlerts()) if (r.id.equals(a.ruleId)) active++;
        m.put("activeAlerts", active);
        return m;
    }

    private String nodeName(String id) {
        EdgeNode n = hub.store.nodes.get(id);
        return n == null ? id : n.name;
    }

    public List<Map<String, Object>> rules() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Rule r : hub.store.rules.values()) out.add(ruleView(r));
        return out;
    }

    /** Reads a rule from a form (used for saving and for the "test" button). */
    Rule readRule(Rule r, Map<String, Object> b, boolean create) {
        String name = Check.text(b, "name", "Rule name", 60, create);
        if (name != null) r.name = name;
        Condition c = Check.choice(b, "condition", Condition.class, "Condition", create);
        if (c != null) r.condition = c;
        if (b.containsKey("sensorId") || create) {
            String sid = Check.text(b, "sensorId", "Sensor", 20, false);
            if (sid != null && !hub.store.sensors.containsKey(sid)) throw ServiceException.bad("Unknown sensor");
            r.sensorId = sid;
        }
        if (b.containsKey("sensorType") || create) r.sensorType = Check.choice(b, "sensorType", SensorType.class, "Sensor type", false);
        if (b.containsKey("nodeId") || create) {
            String nid = Check.text(b, "nodeId", "Edge node", 20, false);
            if (nid != null && !hub.store.nodes.containsKey(nid)) throw ServiceException.bad("Unknown edge node");
            r.nodeId = nid;
        }
        if (r.sensorId != null) {
            Sensor s = hub.store.sensors.get(r.sensorId);
            r.sensorType = s.type;
            r.nodeId = s.nodeId;
        }
        Double th = Check.number(b, "threshold", "Threshold", -100_000, 100_000, create);
        if (th != null) r.threshold = th;
        if (r.condition == Condition.ANOMALY && (r.threshold < 1 || r.threshold > 10)) throw ServiceException.bad("z-score must be between 1 and 10");
        if (r.condition == Condition.NO_DATA && (r.threshold < 10 || r.threshold > 86_400)) throw ServiceException.bad("No-data time must be 10 to 86400 seconds");
        if (r.condition == Condition.RATE_OF_CHANGE && r.threshold <= 0) throw ServiceException.bad("Rate must be more than 0");
        Double dur = Check.number(b, "durationSec", "Must last", 0, 86_400, false);
        if (dur != null) r.durationSec = (int) Math.round(dur);
        Severity sev = Check.choice(b, "severity", Severity.class, "Severity", create);
        if (sev != null) r.severity = sev;
        if (b.containsKey("action")) r.action = Check.text(b, "action", "Edge action", 80, false);
        Boolean en = Json.bool(b, "enabled");
        if (en != null) r.enabled = en;
        return r;
    }

    public Map<String, Object> saveRule(User me, String id, Map<String, Object> b) {
        Check.canEdit(me);
        Rule r;
        if (id == null) {
            r = new Rule();
            r.id = hub.store.nextId("R", hub.store.rules.keySet());
            r.createdBy = me.username;
            r.createdAt = hub.now();
        } else {
            Rule old = hub.store.rules.get(id);
            if (old == null) throw ServiceException.notFound("Rule");
            r = Json.fromMap(Json.toMap(old), Rule.class); // edit a copy, so a bad form changes nothing
        }
        readRule(r, b, id == null);
        hub.store.rules.put(r.id, r);
        hub.store.markDirty("rules");
        hub.store.addEvent(hub.now(), me.username, "rule", null, (id == null ? "Added rule " : "Updated rule ") + r.name);
        return ruleView(r);
    }

    public void deleteRule(User me, String id) {
        Check.canEdit(me);
        Rule r = hub.store.rules.remove(id);
        if (r == null) throw ServiceException.notFound("Rule");
        hub.store.markDirty("rules");
        hub.store.addEvent(hub.now(), me.username, "rule", null, "Deleted rule " + r.name);
    }

    /** Dry run: how often would this rule have fired on the last hour of readings? Nothing is saved. */
    public Map<String, Object> testRule(Map<String, Object> b) {
        Rule r = readRule(new Rule(), b, false);
        if (r.condition == null) throw ServiceException.bad("Choose a condition");
        if (r.name == null) r.name = "Test";
        r.enabled = true;
        Map<String, Object> m = hub.engine.testRule(r);
        if (r.condition == Condition.NO_DATA) m.put("note", "No-data rules depend on time passing, so they cannot be replayed.");
        return m;
    }

    // ------------------------------------------------------------------ activity

    public List<Map<String, Object>> events(String node, String kind, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Event> ev = hub.store.events;
        for (int i = ev.size() - 1; i >= 0 && out.size() < limit; i--) {
            Event e = ev.get(i);
            if (node != null && !node.equals(e.nodeId)) continue;
            if (kind != null && !kind.equals(e.kind)) continue;
            Map<String, Object> m = Json.toMap(e);
            m.put("nodeName", e.nodeId == null ? null : nodeName(e.nodeId));
            out.add(m);
        }
        return out;
    }

    /** Mean time to acknowledge / resolve, in minutes, over the given alerts. */
    public static double[] mtta(List<Alert> list) {
        double ackSum = 0;
        int ackN = 0;
        double resSum = 0;
        int resN = 0;
        for (Alert a : list) {
            if (a.ackAt > 0) { ackSum += (a.ackAt - a.openedAt) / 60_000.0; ackN++; }
            if (a.resolvedAt > 0) { resSum += (a.resolvedAt - a.openedAt) / 60_000.0; resN++; }
        }
        return new double[] {ackN == 0 ? 0 : Num.round(ackSum / ackN, 1), resN == 0 ? 0 : Num.round(resSum / resN, 1)};
    }
}
