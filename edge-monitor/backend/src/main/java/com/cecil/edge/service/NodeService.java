package com.cecil.edge.service;

import com.cecil.edge.edge.EdgeEngine;
import com.cecil.edge.model.Alert;
import com.cecil.edge.model.Command;
import com.cecil.edge.model.DayStat;
import com.cecil.edge.model.EdgeNode;
import com.cecil.edge.model.Enums.AlertStatus;
import com.cecil.edge.model.Enums.CommandStatus;
import com.cecil.edge.model.Enums.CommandType;
import com.cecil.edge.model.Enums.NodeMode;
import com.cecil.edge.model.Enums.Role;
import com.cecil.edge.model.Enums.SensorType;
import com.cecil.edge.model.Rule;
import com.cecil.edge.model.Sensor;
import com.cecil.edge.model.Site;
import com.cecil.edge.model.User;
import com.cecil.edge.util.Json;
import com.cecil.edge.util.Num;
import com.cecil.edge.util.Passwords;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Sites, edge nodes, sensors, node commands, node API keys and the ingest endpoint for real devices. */
public class NodeService {
    private final Hub hub;

    NodeService(Hub hub) {
        this.hub = hub;
    }

    // ------------------------------------------------------------------ views

    public Map<String, Object> nodeView(EdgeNode n) {
        long now = hub.now();
        Map<String, Object> m = Json.toMap(n);
        m.remove("keyHash");
        m.put("status", hub.engine.status(n, now));
        m.put("modeLabel", n.mode.label);
        Site site = hub.store.sites.get(n.siteId);
        m.put("siteName", site == null ? "-" : site.name);
        int count = 0;
        for (Sensor s : hub.store.sensors.values()) if (n.id.equals(s.nodeId)) count++;
        m.put("sensorCount", count);
        int open = 0;
        for (Alert a : hub.engine.activeAlerts()) if (n.id.equals(a.nodeId) && a.clearedAt == 0) open++;
        m.put("openAlerts", open);
        m.put("bufferPct", Num.round(EdgeEngine.bufferPct(n), 1));
        m.put("uptimeSec", n.bootAt > 0 && hub.engine.isOnline(n, now) ? (now - n.bootAt) / 1000 : 0);
        m.put("edgeMs", Num.round(hub.engine.rt(n).edgeMsAvg, 3));
        DayStat d = hub.store.days.get(hub.engine.date(now) + "|" + n.id);
        m.put("todayRaw", d == null ? 0 : d.raw);
        m.put("todayForwarded", d == null ? 0 : d.forwarded);
        m.put("todayRawBytes", d == null ? 0 : d.rawBytes);
        m.put("todayFwdBytes", d == null ? 0 : d.fwdBytes);
        m.put("reductionPct", d == null || d.rawBytes == 0 ? 0 : Num.round(100 - Num.pct(d.fwdBytes, d.rawBytes), 1));
        int pending = 0;
        for (Command c : hub.store.commands.values()) if (n.id.equals(c.nodeId) && c.status == CommandStatus.PENDING) pending++;
        m.put("pendingCommands", pending);
        return m;
    }

    public Map<String, Object> sensorView(Sensor s) {
        long now = hub.now();
        Map<String, Object> m = Json.toMap(s);
        EdgeNode n = hub.store.nodes.get(s.nodeId);
        m.put("nodeName", n == null ? "-" : n.name);
        m.put("siteId", n == null ? null : n.siteId);
        m.put("typeLabel", s.type.label);
        m.put("state", hub.engine.sensorState(s, n, now));
        // last 30 minutes, one average per minute (for the small trend line)
        List<Double> spark = new ArrayList<>();
        for (double[] r : hub.engine.rt(s).minutes.since(now - 30 * 60_000L)) spark.add(Num.round(r[4] / r[1], 3));
        m.put("spark", spark);
        return m;
    }

    public Map<String, Object> siteView(Site s) {
        Map<String, Object> m = Json.toMap(s);
        long now = hub.now();
        int nodes = 0;
        int online = 0;
        for (EdgeNode n : hub.store.nodes.values()) {
            if (!s.id.equals(n.siteId)) continue;
            nodes++;
            if (hub.engine.isOnline(n, now)) online++;
        }
        m.put("nodes", nodes);
        m.put("online", online);
        return m;
    }

    public EdgeNode node(String id) {
        EdgeNode n = hub.store.nodes.get(id);
        if (n == null) throw ServiceException.notFound("Edge node");
        return n;
    }

    public Sensor sensor(String id) {
        Sensor s = hub.store.sensors.get(id);
        if (s == null) throw ServiceException.notFound("Sensor");
        return s;
    }

    // ------------------------------------------------------------------ lists

    public List<Map<String, Object>> sites() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Site s : hub.store.sites.values()) out.add(siteView(s));
        return out;
    }

    public List<Map<String, Object>> nodes(String site, String status, String q) {
        long now = hub.now();
        List<Map<String, Object>> out = new ArrayList<>();
        String qq = q == null ? null : q.toLowerCase();
        for (EdgeNode n : hub.store.nodes.values()) {
            if (site != null && !site.equals(n.siteId)) continue;
            if (status != null && !status.equals(hub.engine.status(n, now).name())) continue;
            if (qq != null && !(n.name.toLowerCase().contains(qq) || n.id.toLowerCase().contains(qq)
                    || (n.hardware != null && n.hardware.toLowerCase().contains(qq)))) continue;
            out.add(nodeView(n));
        }
        return out;
    }

    public List<Map<String, Object>> sensors(String node, String type, String state, String q) {
        List<Map<String, Object>> out = new ArrayList<>();
        String qq = q == null ? null : q.toLowerCase();
        for (Sensor s : hub.store.sensors.values()) {
            if (node != null && !node.equals(s.nodeId)) continue;
            if (type != null && !type.equals(s.type.name())) continue;
            if (qq != null && !(s.name.toLowerCase().contains(qq) || s.id.toLowerCase().contains(qq))) continue;
            Map<String, Object> v = sensorView(s);
            if (state != null && !state.equals(v.get("state"))) continue;
            out.add(v);
        }
        return out;
    }

    // ------------------------------------------------------------------ sites

    public Map<String, Object> saveSite(User me, String id, Map<String, Object> b) {
        Check.canEdit(me);
        Site s;
        if (id == null) {
            s = new Site();
            s.id = hub.store.nextId("ST", hub.store.sites.keySet());
        } else {
            s = hub.store.sites.get(id);
            if (s == null) throw ServiceException.notFound("Site");
        }
        String name = Check.text(b, "name", "Site name", 60, id == null);
        if (name != null) s.name = name;
        if (b.containsKey("location")) s.location = Check.text(b, "location", "Location", 80, false);
        if (b.containsKey("kind")) s.kind = Check.text(b, "kind", "Kind", 40, false);
        hub.store.sites.put(s.id, s);
        hub.store.markDirty("sites");
        hub.store.addEvent(hub.now(), me.username, "site", null, (id == null ? "Added site " : "Updated site ") + s.name);
        return siteView(s);
    }

    public void deleteSite(User me, String id) {
        Check.role(me, Role.ADMIN);
        Site s = hub.store.sites.get(id);
        if (s == null) throw ServiceException.notFound("Site");
        for (EdgeNode n : hub.store.nodes.values()) {
            if (id.equals(n.siteId)) throw ServiceException.bad("Move or delete the site's edge nodes first");
        }
        hub.store.sites.remove(id);
        hub.store.markDirty("sites");
        hub.store.addEvent(hub.now(), me.username, "site", null, "Deleted site " + s.name);
    }

    // ------------------------------------------------------------------ nodes

    /** Creates a node. The answer includes "apiKey" - the only time the key is ever shown. */
    public Map<String, Object> createNode(User me, Map<String, Object> b) {
        Check.canEdit(me);
        EdgeNode n = new EdgeNode();
        n.id = hub.store.nextId("N", hub.store.nodes.keySet());
        n.createdAt = hub.now();
        n.simulated = !Boolean.FALSE.equals(Json.bool(b, "simulated"));
        applyNode(n, b, true);
        String key = newKey(n);
        hub.store.nodes.put(n.id, n);
        hub.store.markDirty("nodes");
        hub.store.addEvent(hub.now(), me.username, "node", n.id, "Added edge node " + n.name + " (" + n.mode.label + ")");
        Map<String, Object> out = nodeView(n);
        out.put("apiKey", key);
        return out;
    }

    private String newKey(EdgeNode n) {
        String key = Passwords.nodeKey();
        n.keyHash = Passwords.sha256(key);
        n.keyHint = key.substring(key.length() - 4);
        return key;
    }

    public Map<String, Object> rotateKey(User me, String id) {
        Check.canEdit(me);
        EdgeNode n = node(id);
        String key = newKey(n);
        hub.store.markDirty("nodes");
        hub.store.addEvent(hub.now(), me.username, "security", n.id, "New API key made for " + n.name + " (old key stops working)");
        return Json.obj("apiKey", key, "keyHint", n.keyHint);
    }

    public Map<String, Object> updateNode(User me, String id, Map<String, Object> b) {
        Check.canEdit(me);
        EdgeNode n = node(id);
        NodeMode oldMode = n.mode;
        int oldWindow = n.windowSec;
        applyNode(n, b, false);
        if (oldMode != n.mode || oldWindow != n.windowSec) {
            for (Sensor s : hub.store.sensors.values()) if (n.id.equals(s.nodeId)) hub.engine.rt(s).logic.reset();
        }
        hub.store.markDirty("nodes");
        hub.store.addEvent(hub.now(), me.username, "node", n.id, "Updated " + n.name
                + (oldMode != n.mode ? ": mode " + oldMode.label + " -> " + n.mode.label : ""));
        return nodeView(n);
    }

    private void applyNode(EdgeNode n, Map<String, Object> b, boolean create) {
        String name = Check.text(b, "name", "Node name", 40, create);
        if (name != null) {
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9 ._-]*")) throw ServiceException.bad("Node name: letters, numbers, space . _ - only");
            for (EdgeNode o : hub.store.nodes.values()) {
                if (o != n && o.name.equalsIgnoreCase(name)) throw ServiceException.conflict("Another node already has that name");
            }
            n.name = name;
        }
        String site = Check.text(b, "siteId", "Site", 20, create);
        if (site != null) {
            if (!hub.store.sites.containsKey(site)) throw ServiceException.bad("Choose a site");
            n.siteId = site;
        }
        if (b.containsKey("hardware")) n.hardware = Check.text(b, "hardware", "Hardware", 60, false);
        if (b.containsKey("ip")) {
            String ip = Check.text(b, "ip", "IP address", 45, false);
            if (ip != null && !ip.matches("[0-9a-fA-F.:]{2,45}")) throw ServiceException.bad("IP address is not valid");
            n.ip = ip;
        }
        if (b.containsKey("notes")) n.notes = Check.text(b, "notes", "Notes", 300, false);
        NodeMode mode = Check.choice(b, "mode", NodeMode.class, "Mode", false);
        if (mode != null) n.mode = mode;
        Double w = Check.number(b, "windowSec", "Window", 10, 3600, false);
        if (w != null) n.windowSec = (int) Math.round(w);
        Double d = Check.number(b, "deadbandPct", "Dead-band", 0, 50, false);
        if (d != null) n.deadbandPct = Num.round(d, 2);
        Double cap = Check.number(b, "bufferCapacity", "Buffer size", 100, 1_000_000, false);
        if (cap != null) n.bufferCapacity = (int) Math.round(cap);
        Boolean en = Json.bool(b, "enabled");
        if (en != null) n.enabled = en;
        Boolean sim = Json.bool(b, "simulated");
        if (sim != null) n.simulated = sim;
    }

    public void deleteNode(User me, String id) {
        Check.role(me, Role.ADMIN);
        EdgeNode n = node(id);
        List<String> sensorIds = new ArrayList<>();
        for (Sensor s : hub.store.sensors.values()) if (id.equals(s.nodeId)) sensorIds.add(s.id);
        for (String sid : sensorIds) removeSensor(sid);
        hub.store.rules.values().removeIf(r -> id.equals(r.nodeId));
        for (Alert a : hub.engine.activeAlerts()) {
            if (id.equals(a.nodeId)) {
                a.status = AlertStatus.RESOLVED;
                a.resolvedAt = hub.now();
                a.resolvedBy = me.username;
                a.note = "Node deleted";
                hub.engine.resolved(a);
            }
        }
        hub.store.commands.values().removeIf(c -> id.equals(c.nodeId) && c.status == CommandStatus.PENDING);
        hub.store.nodes.remove(id);
        hub.engine.forget(id);
        hub.store.markDirty("nodes", "sensors", "rules", "alerts", "commands");
        hub.store.addEvent(hub.now(), me.username, "node", null, "Deleted edge node " + n.name + " and its " + sensorIds.size() + " sensors");
    }

    // ------------------------------------------------------------------ sensors

    public Map<String, Object> saveSensor(User me, String id, Map<String, Object> b) {
        Check.canEdit(me);
        Sensor s;
        boolean create = id == null;
        if (create) {
            s = new Sensor();
            s.id = hub.store.nextId("S", hub.store.sensors.keySet());
        } else {
            s = sensor(id);
        }
        String name = Check.text(b, "name", "Sensor name", 60, create);
        if (name != null) s.name = name;
        String nodeId = Check.text(b, "nodeId", "Edge node", 20, create);
        if (nodeId != null) {
            node(nodeId);
            if (!create && !nodeId.equals(s.nodeId)) hub.engine.forget(s.id);
            s.nodeId = nodeId;
        }
        SensorType type = Check.choice(b, "type", SensorType.class, "Sensor type", create);
        if (type != null) {
            s.type = type;
            s.unit = type.unit;
        }
        Double low = Check.number(b, "low", "Normal low", s.type.lo, s.type.hi, create);
        Double high = Check.number(b, "high", "Normal high", s.type.lo, s.type.hi, create);
        double lo = low != null ? low : s.low;
        double hi = high != null ? high : s.high;
        if (lo >= hi) throw ServiceException.bad("Normal low must be less than normal high");
        s.low = lo;
        s.high = hi;
        Double every = Check.number(b, "sampleSec", "Reading every", 1, 3600, false);
        if (every != null) s.sampleSec = (int) Math.round(every);
        Boolean en = Json.bool(b, "enabled");
        if (en != null) s.enabled = en;
        // simulator shape: by default sit in the middle of the normal range
        Double base = Check.number(b, "base", "Simulated average", s.type.lo, s.type.hi, false);
        Double swing = Check.number(b, "swing", "Daily swing", 0, s.type.hi - s.type.lo, false);
        Double noise = Check.number(b, "noise", "Noise", 0, s.type.hi - s.type.lo, false);
        if (base != null) s.base = base; else if (create) s.base = Num.round((lo + hi) / 2, 3);
        if (swing != null) s.swing = swing; else if (create) s.swing = Num.round((hi - lo) * 0.12, 3);
        if (noise != null) s.noise = noise; else if (create) s.noise = Num.round((hi - lo) * 0.015, 3);
        hub.store.sensors.put(s.id, s);
        hub.store.markDirty("sensors");
        hub.store.addEvent(hub.now(), me.username, "sensor", s.nodeId, (create ? "Added sensor " : "Updated sensor ") + s.name);
        return sensorView(s);
    }

    public void deleteSensor(User me, String id) {
        Check.canEdit(me);
        Sensor s = sensor(id);
        removeSensor(id);
        hub.store.markDirty("sensors", "rules", "hours", "alerts");
        hub.store.addEvent(hub.now(), me.username, "sensor", s.nodeId, "Deleted sensor " + s.name);
    }

    private void removeSensor(String id) {
        hub.store.sensors.remove(id);
        hub.store.rules.values().removeIf(r -> id.equals(r.sensorId));
        hub.store.hours.values().removeIf(h -> id.equals(h.sensorId));
        for (Alert a : hub.engine.activeAlerts()) {
            if (id.equals(a.sensorId)) {
                a.status = AlertStatus.RESOLVED;
                a.resolvedAt = hub.now();
                a.note = "Sensor deleted";
                hub.engine.resolved(a);
            }
        }
        hub.engine.forget(id);
    }

    // ------------------------------------------------------------------ commands

    public List<Map<String, Object>> commands(String nodeId, int limit) {
        List<Command> list = new ArrayList<>();
        for (Command c : hub.store.commands.values()) if (nodeId == null || nodeId.equals(c.nodeId)) list.add(c);
        list.sort(Comparator.comparingLong((Command c) -> c.createdAt).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Command c : list.subList(0, Math.min(limit, list.size()))) {
            Map<String, Object> m = Json.toMap(c);
            m.put("typeLabel", c.type.label);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> queueCommand(User me, String nodeId, Map<String, Object> b) {
        Check.canEdit(me);
        EdgeNode n = node(nodeId);
        if (!n.enabled) throw ServiceException.bad("Turn the node on first");
        CommandType type = Check.choice(b, "type", CommandType.class, "Command", true);
        String arg = null;
        if (type == CommandType.SET_MODE) {
            NodeMode m = Check.choice(b, "arg", NodeMode.class, "Mode", true);
            arg = m.name();
        }
        int pending = 0;
        for (Command c : hub.store.commands.values()) if (nodeId.equals(c.nodeId) && c.status == CommandStatus.PENDING) pending++;
        if (pending >= 20) throw ServiceException.bad("This node already has 20 commands waiting");
        Command c = new Command();
        c.id = hub.store.nextId("C", hub.store.commands.keySet());
        c.nodeId = nodeId;
        c.type = type;
        c.arg = arg;
        c.by = me.username;
        c.createdAt = hub.now();
        hub.store.commands.put(c.id, c);
        hub.store.markDirty("commands");
        hub.store.trim();
        hub.store.addEvent(hub.now(), me.username, "command", nodeId, "Sent " + type.label + (arg != null ? " (" + NodeMode.valueOf(arg).label + ")" : "")
                + " to " + n.name + (hub.engine.isOnline(n, hub.now()) ? "" : " - it will run when the node is back online"));
        Map<String, Object> m = Json.toMap(c);
        m.put("typeLabel", type.label);
        return m;
    }

    // ------------------------------------------------------------------ ingest (real devices)

    /**
     * A real edge device posts its readings here with its API key in the X-Node-Key header.
     * Body: {"health": {"cpu":12,"mem":40,"disk":30,"temp":48},
     *        "readings": [{"sensorId":"S-129","value":5.4,"ts":1730000000000}, ...]}
     * The answer lists what happened and hands over any commands waiting for the device.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> ingest(String key, Map<String, Object> b) {
        if (key == null || key.isBlank()) throw new ServiceException(401, "Missing X-Node-Key header");
        String h = Passwords.sha256(key.trim());
        EdgeNode n = null;
        for (EdgeNode x : hub.store.nodes.values()) if (Passwords.sameHash(h, x.keyHash)) n = x;
        if (n == null) throw new ServiceException(401, "Unknown node key");
        if (!n.enabled) throw ServiceException.forbidden("This node is turned off in the app");
        long now = hub.now();

        Map<String, Object> health = b.get("health") instanceof Map ? (Map<String, Object>) b.get("health") : Map.of();
        double cpu = val(health, "cpu", n.cpu, 0, 100);
        double mem = val(health, "mem", n.mem, 0, 100);
        double disk = val(health, "disk", n.disk, 0, 100);
        double temp = val(health, "temp", n.temp, -40, 150);
        hub.engine.heartbeat(n, now, cpu, mem, disk, temp, true);
        if (n.buffered > 0) hub.engine.flushBuffer(n, now, Integer.MAX_VALUE);

        int accepted = 0;
        int invalid = 0;
        int sent = 0;
        List<Map<String, Object>> alerts = new ArrayList<>();
        Object rs = b.get("readings");
        if (rs != null && !(rs instanceof List)) throw ServiceException.bad("readings must be a list");
        List<Object> readings = rs == null ? List.of() : (List<Object>) rs;
        if (readings.size() > 500) throw ServiceException.bad("At most 500 readings per request");
        List<Object[]> rows = new ArrayList<>();
        for (Object o : readings) {
            if (!(o instanceof Map)) throw ServiceException.bad("Each reading must be an object");
            Map<String, Object> r = (Map<String, Object>) o;
            String sid = Json.str(r, "sensorId");
            Sensor s = sid == null ? null : hub.store.sensors.get(sid);
            if (s == null || !n.id.equals(s.nodeId)) throw ServiceException.bad("Unknown sensor for this node: " + sid);
            Double v;
            try {
                v = Json.num(r, "value");
            } catch (IllegalArgumentException e) {
                v = Double.NaN;
            }
            Double ts = Json.num(r, "ts");
            long t = ts == null || Math.abs(ts - now) > 10 * 60_000L ? now : ts.longValue();
            rows.add(new Object[] {s, v == null ? Double.NaN : v, t});
        }
        rows.sort(Comparator.comparingLong(x -> (Long) x[2]));

        // waiting commands are handed over before processing, so a mode change applies to this batch
        List<Map<String, Object>> cmds = new ArrayList<>();
        for (Command c : hub.store.commands.values()) {
            if (!n.id.equals(c.nodeId) || c.status != CommandStatus.PENDING) continue;
            if (c.type == CommandType.SET_MODE) {
                n.mode = NodeMode.valueOf(c.arg);
                for (Sensor s : hub.store.sensors.values()) if (n.id.equals(s.nodeId)) hub.engine.rt(s).logic.reset();
            }
            c.status = CommandStatus.DONE;
            c.doneAt = now;
            c.result = "Delivered to the device";
            cmds.add(Json.obj("id", c.id, "type", c.type, "arg", c.arg));
        }
        if (!cmds.isEmpty()) hub.store.markDirty("commands", "nodes");

        for (Object[] r : rows) {
            Sensor s = (Sensor) r[0];
            if (!s.enabled) continue;
            EdgeEngine.Result res = hub.engine.process(n, s, (Double) r[1], (Long) r[2]);
            if (res.valid) accepted++; else invalid++;
            if (res.rawSent || res.summaries > 0) sent++;
            for (Alert a : res.newAlerts) alerts.add(Json.obj("id", a.id, "severity", a.severity, "message", a.message, "action", a.action));
        }

        return Json.obj("node", n.id, "accepted", accepted, "invalid", invalid, "sentToCloud", sent,
                "mode", n.mode, "alerts", alerts, "commands", cmds);
    }

    private static double val(Map<String, Object> m, String k, double dflt, double lo, double hi) {
        try {
            Double v = Json.num(m, k);
            return v == null || v.isNaN() ? dflt : Num.clamp(v, lo, hi);
        } catch (IllegalArgumentException e) {
            return dflt;
        }
    }

    /** Rules that check this sensor. */
    public List<Rule> rulesFor(Sensor s) {
        List<Rule> out = new ArrayList<>();
        EdgeNode n = hub.store.nodes.get(s.nodeId);
        for (Rule r : hub.store.rules.values()) if (n != null && EdgeEngine.targets(r, n, s)) out.add(r);
        return out;
    }
}
