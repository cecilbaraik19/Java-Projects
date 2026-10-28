package com.cecil.edge.sim;

import com.cecil.edge.edge.EdgeEngine;
import com.cecil.edge.edge.SensorRuntime;
import com.cecil.edge.model.Command;
import com.cecil.edge.model.EdgeNode;
import com.cecil.edge.model.Enums.CommandStatus;
import com.cecil.edge.model.Enums.FaultType;
import com.cecil.edge.model.Enums.NodeMode;
import com.cecil.edge.model.Fault;
import com.cecil.edge.model.Sensor;
import com.cecil.edge.store.DataStore;
import com.cecil.edge.util.Num;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Pretends to be the edge nodes and their sensors, so the project works with no hardware.
 * Every tick (5 s by default) each simulated node:
 *   - sends a heartbeat with CPU, memory, disk and board temperature,
 *   - runs any commands waiting for it,
 *   - reads each sensor that is due and pushes the value through the edge pipeline.
 * Sensor values follow a daily wave plus noise, and the "fault lab" can inject problems.
 * Must be called while holding the Hub lock.
 */
public class Simulator {
    private final DataStore store;
    private final EdgeEngine engine;
    private final Random rnd;
    /** Faults currently injected (kept in memory; they end on their own). */
    public final Map<String, Fault> faults = new LinkedHashMap<>();
    private int faultSeq = 100;

    public Simulator(DataStore store, EdgeEngine engine, long seed) {
        this.store = store;
        this.engine = engine;
        this.rnd = new Random(seed);
    }

    // ------------------------------------------------------------------ faults

    public Fault inject(String nodeId, String sensorId, FaultType type, double magnitude, int minutes, String by, long now) {
        Fault f = new Fault();
        f.id = "F-" + (++faultSeq);
        f.nodeId = nodeId;
        f.sensorId = type.forSensor ? sensorId : null;
        f.type = type;
        f.magnitude = magnitude;
        f.startedAt = now;
        f.endsAt = now + minutes * 60_000L;
        f.by = by;
        faults.put(f.id, f);
        return f;
    }

    /** Default size of a fault for a sensor, based on its normal range. */
    public static double defaultMagnitude(FaultType t, Sensor s) {
        double span = s == null ? 10 : Math.max(1, s.high - s.low);
        return switch (t) {
            case SPIKE -> Num.round(span * 0.8, 2);
            case DRIFT -> Num.round(span * 0.15, 2);  // per minute
            case NOISE -> 6;                           // x normal noise
            default -> 0;
        };
    }

    private Fault find(String nodeId, String sensorId, FaultType type, long now) {
        for (Fault f : faults.values()) {
            if (f.type == type && f.nodeId.equals(nodeId) && now < f.endsAt
                    && (sensorId == null || sensorId.equals(f.sensorId))) return f;
        }
        return null;
    }

    private void expireFaults(long now) {
        List<String> done = new ArrayList<>();
        for (Fault f : faults.values()) if (now >= f.endsAt) done.add(f.id);
        for (String id : done) {
            Fault f = faults.remove(id);
            if (!engine.quiet) {
                EdgeNode n = store.nodes.get(f.nodeId);
                store.addEvent(now, "simulator", "fault", f.nodeId, "Fault ended: " + f.type.label + " on "
                        + (n == null ? f.nodeId : n.name) + sensorName(f.sensorId));
            }
        }
    }

    private String sensorName(String id) {
        Sensor s = id == null ? null : store.sensors.get(id);
        return s == null ? "" : " / " + s.name;
    }

    // ------------------------------------------------------------------ one tick

    public void tick(long now) {
        int tickSec = store.settings.tickSec;
        expireFaults(now);
        for (EdgeNode n : store.nodes.values()) {
            if (!n.simulated || !n.enabled) continue;
            if (find(n.id, null, FaultType.NODE_DOWN, now) != null) continue; // powered off: silence

            boolean uplink = find(n.id, null, FaultType.UPLINK_DOWN, now) == null;
            boolean hog = find(n.id, null, FaultType.CPU_HOG, now) != null;
            int sensors = 0;
            for (Sensor s : store.sensors.values()) if (s.enabled && n.id.equals(s.nodeId)) sensors++;

            // health: CPU follows the work the node does; the board warms up with the CPU
            double load = sensors * (5.0 / Math.max(1, tickSec)) * (n.mode == NodeMode.RAW ? 0.6 : 1.4);
            double cpu = 9 + load + rnd.nextGaussian() * 3 + (hog ? 72 : 0);
            double mem = 38 + sensors * 2.2 + (n.buffered * 20.0 / Math.max(1, n.bufferCapacity)) + rnd.nextGaussian() * 1.5;
            double disk = n.disk <= 0 ? 22 + rnd.nextDouble() * 20 : n.disk + (rnd.nextDouble() < 0.02 ? 0.1 : 0);
            double boardTemp = 41 + Num.clamp(cpu, 0, 100) * 0.38 + rnd.nextGaussian() * 0.8;
            engine.heartbeat(n, now, cpu, mem, Math.min(disk, 96), boardTemp, uplink);

            runCommands(n, now);
            if (uplink && n.buffered > 0) engine.flushBuffer(n, now, 400 * tickSec / 5);

            for (Sensor s : store.sensors.values()) {
                if (!s.enabled || !n.id.equals(s.nodeId)) continue;
                SensorRuntime sr = engine.rt(s);
                if (sr.lastSampleTs > 0 && now - sr.lastSampleTs < s.sampleSec * 1000L - 500) continue;
                sr.lastSampleTs = now;
                if (find(n.id, s.id, FaultType.DROPOUT, now) != null) continue;
                engine.process(n, s, read(n, s, sr, now), now);
            }
        }
        engine.checkHealth(now, tickSec);
    }

    /** Sensor value = base + daily wave + slow wander + noise, then any injected fault. */
    double read(EdgeNode n, Sensor s, SensorRuntime sr, long now) {
        double hour = LocalTime.ofInstant(Instant.ofEpochMilli(now), engine.zone).toSecondOfDay() / 3600.0;
        double wave = Math.sin(2 * Math.PI * (hour - 9) / 24.0);   // lowest around 03:00, highest around 15:00
        sr.walk = sr.walk * 0.98 + rnd.nextGaussian() * s.noise * 0.15;
        double noise = s.noise;
        Fault fz = find(n.id, s.id, FaultType.NOISE, now);
        if (fz != null) noise *= Math.max(1, fz.magnitude);
        double v = s.base + s.swing * wave + sr.walk + rnd.nextGaussian() * noise;

        Fault spike = find(n.id, s.id, FaultType.SPIKE, now);
        if (spike != null) v += spike.magnitude;
        Fault drift = find(n.id, s.id, FaultType.DRIFT, now);
        if (drift != null) v += drift.magnitude * (now - drift.startedAt) / 60_000.0;
        Fault stuck = find(n.id, s.id, FaultType.STUCK, now);
        if (stuck != null) {
            if (stuck.stuckValue == null) stuck.stuckValue = s.lastValue == null ? v : s.lastValue;
            v = stuck.stuckValue;
        }
        if (find(n.id, s.id, FaultType.BAD_DATA, now) != null && rnd.nextDouble() < 0.7) {
            v = rnd.nextBoolean() ? s.type.hi * 10 + 999 : s.type.lo - 999;
        }
        // physical sensors cannot go past their own limits (except garbage data)
        if (stuck == null && find(n.id, s.id, FaultType.BAD_DATA, now) == null) v = Num.clamp(v, s.type.lo, s.type.hi);
        return Num.round(v, 3);
    }

    /** A simulated node runs its waiting commands right away. */
    private void runCommands(EdgeNode n, long now) {
        boolean changed = false;
        for (Command c : store.commands.values()) {
            if (c.status != CommandStatus.PENDING || !n.id.equals(c.nodeId)) continue;
            switch (c.type) {
                case PING -> c.result = "Pong in " + (2 + rnd.nextInt(6)) + " ms";
                case REBOOT -> {
                    n.bootAt = now;
                    c.result = "Rebooted in " + Num.round(2.5 + rnd.nextDouble() * 2, 1) + " s";
                }
                case SET_MODE -> {
                    NodeMode m = NodeMode.valueOf(c.arg);
                    n.mode = m;
                    for (Sensor s : store.sensors.values()) if (n.id.equals(s.nodeId)) engine.rt(s).logic.reset();
                    c.result = "Mode is now " + m.label;
                }
                case FLUSH_BUFFER -> {
                    if (!n.uplinkUp) {
                        c.status = CommandStatus.FAILED;
                        c.result = "Cloud link is down; data stays in the buffer";
                        c.doneAt = now;
                        changed = true;
                        continue;
                    }
                    int k = engine.flushBuffer(n, now, Integer.MAX_VALUE);
                    c.result = "Sent " + k + " buffered messages";
                }
            }
            c.status = CommandStatus.DONE;
            c.doneAt = now;
            changed = true;
            if (!engine.quiet) store.addEvent(now, c.by, "command", n.id, n.name + ": " + c.type.label + " done - " + c.result);
        }
        if (changed) store.markDirty("commands", "nodes");
    }

    /**
     * Fills the live charts with the last `minutes` of data when the app starts,
     * so the pages are not empty. Nothing is counted or alerted during this warm-up.
     */
    public void warmUp(long now, int minutes) {
        boolean was = engine.quiet;
        engine.quiet = true;
        long step = store.settings.tickSec * 1000L;
        long start = now - minutes * 60_000L;
        start -= Math.floorMod(start, step);
        for (long t = start; t < now; t += step) tick(t);
        engine.resetHourSums();
        engine.quiet = was;
    }
}
