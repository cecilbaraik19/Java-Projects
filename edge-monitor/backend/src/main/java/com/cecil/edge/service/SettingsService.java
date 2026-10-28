package com.cecil.edge.service;

import com.cecil.edge.model.EdgeNode;
import com.cecil.edge.model.Enums.AlertStatus;
import com.cecil.edge.model.Enums.CommandType;
import com.cecil.edge.model.Enums.Condition;
import com.cecil.edge.model.Enums.FaultType;
import com.cecil.edge.model.Enums.NodeMode;
import com.cecil.edge.model.Enums.Role;
import com.cecil.edge.model.Enums.SensorType;
import com.cecil.edge.model.Enums.Severity;
import com.cecil.edge.model.Fault;
import com.cecil.edge.model.Sensor;
import com.cecil.edge.model.Settings;
import com.cecil.edge.model.Site;
import com.cecil.edge.model.User;
import com.cecil.edge.sim.Simulator;
import com.cecil.edge.util.Json;
import com.cecil.edge.util.Num;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Settings, the lists the pages need (meta), and the simulator's fault lab. */
public class SettingsService {
    private final Hub hub;

    SettingsService(Hub hub) {
        this.hub = hub;
    }

    public Settings get() {
        return hub.store.settings;
    }

    public Settings update(User me, Map<String, Object> b) {
        Check.role(me, Role.ADMIN);
        Settings s = Json.fromMap(Json.toMap(hub.store.settings), Settings.class); // change a copy first
        Double v;
        if ((v = Check.number(b, "heartbeatTimeoutSec", "Heartbeat timeout", 10, 3600, false)) != null) s.heartbeatTimeoutSec = (int) Math.round(v);
        if ((v = Check.number(b, "tickSec", "Simulator step", 1, 60, false)) != null) s.tickSec = (int) Math.round(v);
        if ((v = Check.number(b, "cloudRttMs", "Cloud round trip", 1, 5000, false)) != null) s.cloudRttMs = v;
        if ((v = Check.number(b, "localHopMs", "Local network hop", 0, 500, false)) != null) s.localHopMs = v;
        if ((v = Check.number(b, "bytesRaw", "Raw message size", 8, 10_000, false)) != null) s.bytesRaw = (int) Math.round(v);
        if ((v = Check.number(b, "bytesSummary", "Summary message size", 8, 10_000, false)) != null) s.bytesSummary = (int) Math.round(v);
        if ((v = Check.number(b, "costPerGb", "Data cost", 0, 10_000, false)) != null) s.costPerGb = v;
        if ((v = Check.number(b, "cpuWarnPct", "CPU warning", 10, 100, false)) != null) s.cpuWarnPct = v;
        if ((v = Check.number(b, "tempWarnC", "Temperature warning", 30, 120, false)) != null) s.tempWarnC = v;
        if ((v = Check.number(b, "bufferWarnPct", "Buffer warning", 10, 100, false)) != null) s.bufferWarnPct = v;
        Boolean on = Json.bool(b, "simulatorOn");
        if (on != null) s.simulatorOn = on;
        s.historyDays = hub.store.settings.historyDays;
        hub.store.settings = s;
        hub.store.markDirty("settings");
        hub.store.addEvent(hub.now(), me.username, "settings", null, "Changed settings");
        return s;
    }

    public Map<String, Object> setSimulator(User me, boolean on) {
        Check.canEdit(me);
        hub.store.settings.simulatorOn = on;
        hub.store.markDirty("settings");
        hub.store.addEvent(hub.now(), me.username, "simulator", null, on ? "Simulator started" : "Simulator paused (simulated nodes will go offline)");
        return Json.obj("simulatorOn", on);
    }

    /** Lists for drop-downs and labels. */
    public Map<String, Object> meta() {
        List<Map<String, Object>> modes = new ArrayList<>();
        for (NodeMode m : NodeMode.values()) modes.add(Json.obj("id", m, "label", m.label, "help", m.help));
        List<Map<String, Object>> types = new ArrayList<>();
        for (SensorType t : SensorType.values()) types.add(Json.obj("id", t, "label", t.label, "unit", t.unit, "lo", t.lo, "hi", t.hi));
        List<Map<String, Object>> conds = new ArrayList<>();
        for (Condition c : Condition.values()) conds.add(Json.obj("id", c, "label", c.label));
        List<Map<String, Object>> faults = new ArrayList<>();
        for (FaultType f : FaultType.values()) faults.add(Json.obj("id", f, "label", f.label, "forSensor", f.forSensor, "help", f.help));
        List<Map<String, Object>> cmds = new ArrayList<>();
        for (CommandType c : CommandType.values()) cmds.add(Json.obj("id", c, "label", c.label));
        List<Map<String, Object>> roles = new ArrayList<>();
        for (Role r : Role.values()) roles.add(Json.obj("id", r, "label", r.label));
        List<Map<String, Object>> sites = new ArrayList<>();
        for (Site s : hub.store.sites.values()) sites.add(Json.obj("id", s.id, "name", s.name));
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (EdgeNode n : hub.store.nodes.values()) nodes.add(Json.obj("id", n.id, "name", n.name, "siteId", n.siteId, "simulated", n.simulated));
        List<Map<String, Object>> sensors = new ArrayList<>();
        for (Sensor s : hub.store.sensors.values()) sensors.add(Json.obj("id", s.id, "name", s.name, "nodeId", s.nodeId,
                "type", s.type, "unit", s.unit, "low", s.low, "high", s.high));
        return Json.obj("modes", modes, "sensorTypes", types, "conditions", conds, "faultTypes", faults, "commandTypes", cmds,
                "roles", roles, "severities", Severity.values(), "alertStatuses", AlertStatus.values(),
                "sites", sites, "nodes", nodes, "sensors", sensors, "settings", hub.store.settings);
    }

    // ------------------------------------------------------------------ fault lab

    public List<Map<String, Object>> faults() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Fault f : hub.sim.faults.values()) out.add(hub.analytics.faultView(f));
        return out;
    }

    public Map<String, Object> inject(User me, Map<String, Object> b) {
        Check.canEdit(me);
        FaultType type = Check.choice(b, "type", FaultType.class, "Fault", true);
        String nodeId = Check.text(b, "nodeId", "Edge node", 20, true);
        EdgeNode n = hub.nodes.node(nodeId);
        if (!n.simulated) throw ServiceException.bad("Faults can only be injected into simulated nodes");
        Sensor s = null;
        if (type.forSensor) {
            String sid = Check.text(b, "sensorId", "Sensor", 20, true);
            s = hub.nodes.sensor(sid);
            if (!nodeId.equals(s.nodeId)) throw ServiceException.bad("That sensor is not on this node");
        }
        int minutes = (int) Math.round(Check.number(b, "minutes", "Minutes", 1, 120, true));
        Double mag = Check.number(b, "magnitude", "Size", -100_000, 100_000, false);
        double magnitude = mag != null ? mag : Simulator.defaultMagnitude(type, s);
        if (hub.sim.faults.size() >= 30) throw ServiceException.bad("Too many faults running; stop some first");
        Fault f = hub.sim.inject(nodeId, s == null ? null : s.id, type, Num.round(magnitude, 3), minutes, me.username, hub.now());
        hub.store.addEvent(hub.now(), me.username, "fault", nodeId, "Injected fault: " + type.label + " on " + n.name
                + (s != null ? " / " + s.name : "") + " for " + minutes + " min");
        return hub.analytics.faultView(f);
    }

    public void stopFault(User me, String id) {
        Check.canEdit(me);
        Fault f = hub.sim.faults.remove(id);
        if (f == null) throw ServiceException.notFound("Fault");
        hub.store.addEvent(hub.now(), me.username, "fault", f.nodeId, "Stopped fault early: " + f.type.label);
    }
}
