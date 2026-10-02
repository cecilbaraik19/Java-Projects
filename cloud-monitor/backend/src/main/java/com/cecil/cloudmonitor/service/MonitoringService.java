package com.cecil.cloudmonitor.service;

import com.cecil.cloudmonitor.dto.LiveUpdate;
import com.cecil.cloudmonitor.dto.MetricPoint;
import com.cecil.cloudmonitor.dto.OverviewDto;
import com.cecil.cloudmonitor.model.*;
import com.cecil.cloudmonitor.repository.CloudResourceRepository;
import com.cecil.cloudmonitor.repository.MetricSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The heart of the app: collects metrics every tick, stores history and pushes live updates. */
@Service
public class MonitoringService {

    private static final int CHART_POINTS = 90;

    private final CloudResourceRepository resources;
    private final MetricSnapshotRepository snapshots;
    private final SimulationService simulation;
    private final LocalMetricsService local;
    private final AlertService alertService;
    private final SimpMessagingTemplate messaging;
    private final Object lock = new Object();

    @Value("${app.monitor.persist-every:5}")
    private int persistEvery;

    @Value("${app.monitor.retention-hours:24}")
    private int retentionHours;

    private long tick;
    private volatile boolean ready;

    public MonitoringService(CloudResourceRepository resources, MetricSnapshotRepository snapshots,
                             SimulationService simulation, LocalMetricsService local,
                             AlertService alertService, SimpMessagingTemplate messaging) {
        this.resources = resources;
        this.snapshots = snapshots;
        this.simulation = simulation;
        this.local = local;
        this.alertService = alertService;
        this.messaging = messaging;
    }

    /** Called by DataSeeder once the database is filled. */
    public void markReady() {
        this.ready = true;
    }

    // ------------------------------------------------------------------ live collection

    @Scheduled(fixedRateString = "${app.monitor.tick-ms:3000}", initialDelay = 2000)
    public void collect() {
        if (!ready) return;
        List<CloudResource> all;
        synchronized (lock) {
            all = resources.findAll();
            Instant now = Instant.now();
            for (CloudResource r : all) {
                if (r.isStopped()) {
                    r.setCpu(0);
                    r.setMemory(0);
                    r.setNetworkIn(0);
                    r.setNetworkOut(0);
                } else {
                    if (r.getType() == ResourceType.LOCAL_HOST) local.update(r);
                    else simulation.update(r);
                    r.setStatus(statusFor(r));
                }
                r.setLastUpdated(now);
            }
            all = resources.saveAll(all);

            if (++tick % Math.max(1, persistEvery) == 0) {
                snapshots.saveAll(all.stream().filter(r -> !r.isStopped())
                        .map(r -> MetricSnapshot.of(r, now)).toList());
            }
        }
        alertService.evaluate(all);
        messaging.convertAndSend("/topic/live", new LiveUpdate(buildOverview(all), sorted(all)));
    }

    @Scheduled(fixedRate = 600_000, initialDelay = 60_000)
    public void cleanupOldHistory() {
        snapshots.deleteOlderThan(Instant.now().minus(Duration.ofHours(retentionHours)));
    }

    static ResourceStatus statusFor(CloudResource r) {
        double worst = Math.max(r.getCpu(), Math.max(r.getMemory(), r.getDisk()));
        if (worst >= 90) return ResourceStatus.CRITICAL;
        if (worst >= 75) return ResourceStatus.WARNING;
        return ResourceStatus.RUNNING;
    }

    // ------------------------------------------------------------------ queries

    public List<CloudResource> all() {
        return sorted(resources.findAll());
    }

    public CloudResource get(Long id) {
        return resources.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found"));
    }

    public OverviewDto overview() {
        return buildOverview(resources.findAll());
    }

    public List<MetricPoint> resourceHistory(Long id, String range) {
        get(id);
        Duration d = parseRange(range);
        List<MetricSnapshot> list = snapshots.findByResourceIdAndTimestampAfterOrderByTimestampAsc(id, Instant.now().minus(d));
        return bucket(list, d, false);
    }

    public List<MetricPoint> overviewHistory(String range) {
        Duration d = parseRange(range);
        List<MetricSnapshot> list = snapshots.findByTimestampAfterOrderByTimestampAsc(Instant.now().minus(d));
        return bucket(list, d, true);
    }

    /** Averages raw history into at most CHART_POINTS points so charts stay fast. */
    private List<MetricPoint> bucket(List<MetricSnapshot> list, Duration range, boolean sumNetworkAcrossResources) {
        long start = Instant.now().minus(range).toEpochMilli();
        long size = Math.max(1000, range.toMillis() / CHART_POINTS);
        Map<Long, List<MetricSnapshot>> groups = list.stream()
                .collect(Collectors.groupingBy(s -> (s.getTimestamp().toEpochMilli() - start) / size,
                        TreeMap::new, Collectors.toList()));
        List<MetricPoint> points = new ArrayList<>();
        groups.forEach((bucket, items) -> {
            double netScale = 1;
            if (sumNetworkAcrossResources) {
                netScale = items.stream().map(MetricSnapshot::getResourceId).distinct().count();
            }
            points.add(new MetricPoint(
                    start + bucket * size + size / 2,
                    avg(items, MetricSnapshot::getCpu),
                    avg(items, MetricSnapshot::getMemory),
                    avg(items, MetricSnapshot::getDisk),
                    round(avg(items, MetricSnapshot::getNetworkIn) * netScale),
                    round(avg(items, MetricSnapshot::getNetworkOut) * netScale)));
        });
        return points;
    }

    private static Duration parseRange(String range) {
        return switch (range == null ? "1h" : range) {
            case "15m" -> Duration.ofMinutes(15);
            case "6h" -> Duration.ofHours(6);
            case "24h" -> Duration.ofHours(24);
            default -> Duration.ofHours(1);
        };
    }

    private OverviewDto buildOverview(List<CloudResource> all) {
        List<CloudResource> on = all.stream().filter(r -> !r.isStopped()).toList();
        Map<ResourceStatus, Long> byStatus = all.stream()
                .collect(Collectors.groupingBy(CloudResource::getStatus, Collectors.counting()));
        double hourly = on.stream().mapToDouble(CloudResource::getHourlyCost).sum();
        return new OverviewDto(
                all.size(),
                byStatus.getOrDefault(ResourceStatus.RUNNING, 0L).intValue(),
                byStatus.getOrDefault(ResourceStatus.WARNING, 0L).intValue(),
                byStatus.getOrDefault(ResourceStatus.CRITICAL, 0L).intValue(),
                byStatus.getOrDefault(ResourceStatus.STOPPED, 0L).intValue(),
                avgR(on, CloudResource::getCpu),
                avgR(on, CloudResource::getMemory),
                avgR(on, CloudResource::getDisk),
                round(on.stream().mapToDouble(CloudResource::getNetworkIn).sum()),
                round(on.stream().mapToDouble(CloudResource::getNetworkOut).sum()),
                alertService.activeCount(),
                round2(hourly),
                round2(hourly * 730),
                all.stream().collect(Collectors.groupingBy(r -> r.getProvider().name(), TreeMap::new, Collectors.counting())),
                all.stream().collect(Collectors.groupingBy(r -> r.getType().name(), TreeMap::new, Collectors.counting())),
                System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ actions

    public CloudResource performAction(Long id, String action, String username) {
        CloudResource r;
        synchronized (lock) {
            r = get(id);
            String act = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
            if (r.getType() == ResourceType.LOCAL_HOST && !act.equals("REFRESH")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The local machine can't be controlled from the dashboard");
            }
            switch (act) {
                case "START" -> {
                    if (!r.isStopped()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Already running");
                    r.setStatus(ResourceStatus.RUNNING);
                    r.setStartedAt(Instant.now());
                    simulation.reset(r);
                }
                case "STOP" -> {
                    if (r.isStopped()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Already stopped");
                    r.setStatus(ResourceStatus.STOPPED);
                    r.setCpu(0);
                    r.setMemory(0);
                    r.setNetworkIn(0);
                    r.setNetworkOut(0);
                }
                case "RESTART" -> {
                    r.setStatus(ResourceStatus.RUNNING);
                    r.setStartedAt(Instant.now());
                    simulation.reset(r);
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown action: " + action);
            }
            r.setLastUpdated(Instant.now());
            r = resources.save(r);
            alertService.raiseEvent(r, act.equals("STOP") ? Severity.WARNING : Severity.INFO,
                    r.getName() + " was " + switch (act) {
                        case "START" -> "started";
                        case "STOP" -> "stopped";
                        default -> "restarted";
                    } + " by " + username);
        }
        return r;
    }

    // ------------------------------------------------------------------ helpers

    private static List<CloudResource> sorted(List<CloudResource> list) {
        return list.stream().sorted(Comparator.comparing(CloudResource::getId)).toList();
    }

    private static double avg(List<MetricSnapshot> l, Function<MetricSnapshot, Double> f) {
        return round(l.stream().mapToDouble(f::apply).average().orElse(0));
    }

    private static double avgR(List<CloudResource> l, Function<CloudResource, Double> f) {
        return round(l.stream().mapToDouble(f::apply).average().orElse(0));
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
