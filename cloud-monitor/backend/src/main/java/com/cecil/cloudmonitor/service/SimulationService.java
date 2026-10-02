package com.cecil.cloudmonitor.service;

import com.cecil.cloudmonitor.model.CloudResource;
import com.cecil.cloudmonitor.model.MetricSnapshot;
import com.cecil.cloudmonitor.model.ResourceType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates realistic-looking metrics for the simulated cloud resources:
 * a daily usage pattern, random noise, and occasional load spikes (which trigger alerts).
 */
@Service
public class SimulationService {

    private final Random random = new Random();
    private final Map<Long, Profile> profiles = new ConcurrentHashMap<>();

    /** The "personality" of one resource. */
    private static class Profile {
        double cpuBase, memBase, netBase;
        int spikeTicksLeft;
        double spikeCpu;
    }

    private Profile profile(CloudResource r) {
        return profiles.computeIfAbsent(r.getId(), id -> {
            Random seeded = new Random(id * 7919L);
            Profile p = new Profile();
            ResourceType t = r.getType();
            switch (t) {
                case DATABASE -> { p.cpuBase = 30 + seeded.nextDouble() * 20; p.memBase = 62 + seeded.nextDouble() * 12; }
                case CONTAINER -> { p.cpuBase = 22 + seeded.nextDouble() * 18; p.memBase = 40 + seeded.nextDouble() * 15; }
                case STORAGE -> { p.cpuBase = 3 + seeded.nextDouble() * 5; p.memBase = 15 + seeded.nextDouble() * 10; }
                case FUNCTION -> { p.cpuBase = 10 + seeded.nextDouble() * 15; p.memBase = 25 + seeded.nextDouble() * 15; }
                default -> { p.cpuBase = 25 + seeded.nextDouble() * 20; p.memBase = 45 + seeded.nextDouble() * 20; }
            }
            if (r.getName().toLowerCase().contains("ml")) { p.cpuBase = 68; p.memBase = 72; }
            p.netBase = 150 + seeded.nextDouble() * 2500;
            return p;
        });
    }

    /** Busier during the day, quieter at night (peaks around 3 PM). */
    private static double dailyFactor(Instant at) {
        int minuteOfDay = at.atZone(ZoneId.systemDefault()).getHour() * 60
                + at.atZone(ZoneId.systemDefault()).getMinute();
        return 1 + 0.30 * Math.sin(2 * Math.PI * (minuteOfDay - 540) / 1440.0);
    }

    /** Moves the resource's metrics one step forward. Called every tick. */
    public void update(CloudResource r) {
        Profile p = profile(r);
        double factor = dailyFactor(Instant.now());

        // ~0.6% chance per tick that a resource starts a load spike lasting 30-90 seconds
        if (p.spikeTicksLeft == 0 && random.nextDouble() < 0.006) {
            p.spikeTicksLeft = 10 + random.nextInt(20);
            p.spikeCpu = 86 + random.nextDouble() * 13;
        }
        double cpuTarget = p.spikeTicksLeft > 0 ? p.spikeCpu : p.cpuBase * factor;
        if (p.spikeTicksLeft > 0) p.spikeTicksLeft--;
        double memTarget = p.memBase * (0.95 + 0.1 * factor) + (p.spikeTicksLeft > 0 ? 12 : 0);

        r.setCpu(clamp(r.getCpu() + (cpuTarget - r.getCpu()) * 0.35 + gaussian(3)));
        r.setMemory(clamp(r.getMemory() + (memTarget - r.getMemory()) * 0.2 + gaussian(1.2)));

        // Disk fills slowly; a "cleanup job" frees space once it gets close to full
        double disk = r.getDisk() + 0.004 + Math.max(0, gaussian(0.01));
        if (disk > 96) disk = 55 + random.nextDouble() * 10;
        r.setDisk(clamp(disk));

        double net = p.netBase * factor * (p.spikeTicksLeft > 0 ? 2.5 : 1);
        r.setNetworkIn(Math.max(0, round(net * (0.85 + random.nextDouble() * 0.3))));
        r.setNetworkOut(Math.max(0, round(net * 0.6 * (0.85 + random.nextDouble() * 0.3))));
    }

    /** Clears any spike, e.g. after a restart. */
    public void reset(CloudResource r) {
        Profile p = profile(r);
        p.spikeTicksLeft = 0;
        r.setCpu(p.cpuBase * 0.5);
        r.setMemory(p.memBase * 0.6);
    }

    /** Builds a believable past data point, used once at startup to fill the last 24h of charts. */
    public MetricSnapshot historicalPoint(CloudResource r, Instant at, double disk, Random rnd) {
        Profile p = profile(r);
        double f = dailyFactor(at);
        boolean spike = rnd.nextDouble() < 0.02;
        MetricSnapshot s = new MetricSnapshot();
        s.setResourceId(r.getId());
        s.setTimestamp(at);
        s.setCpu(clamp(spike ? 85 + rnd.nextDouble() * 12 : p.cpuBase * f + rnd.nextGaussian() * 4));
        s.setMemory(clamp(p.memBase * (0.95 + 0.1 * f) + rnd.nextGaussian() * 1.5));
        s.setDisk(clamp(disk));
        double net = p.netBase * f;
        s.setNetworkIn(round(net * (0.85 + rnd.nextDouble() * 0.3)));
        s.setNetworkOut(round(net * 0.6 * (0.85 + rnd.nextDouble() * 0.3)));
        return s;
    }

    private double gaussian(double sd) {
        return random.nextGaussian() * sd;
    }

    private static double clamp(double v) {
        return round(Math.max(0, Math.min(100, v)));
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
