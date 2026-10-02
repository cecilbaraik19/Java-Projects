package com.cecil.cloudmonitor.service;

import com.cecil.cloudmonitor.dto.CostReport;
import com.cecil.cloudmonitor.dto.CostReport.*;
import com.cecil.cloudmonitor.model.AppSetting;
import com.cecil.cloudmonitor.model.CloudResource;
import com.cecil.cloudmonitor.model.ResourceType;
import com.cecil.cloudmonitor.repository.AppSettingRepository;
import com.cecil.cloudmonitor.repository.CloudResourceRepository;
import com.cecil.cloudmonitor.repository.MetricSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Works out what the infrastructure costs, where the money goes and how to save some. */
@Service
public class CostService {

    /** Cloud providers bill a month as 730 hours (365 x 24 / 12). */
    public static final double HOURS_PER_MONTH = 730;
    private static final String BUDGET_KEY = "monthly-budget";

    /** Below this average CPU (last 24h) a server is considered over-sized. */
    private static final double IDLE_CPU = 25;
    /** A single resource above this share of the bill gets a "schedule it" suggestion. */
    private static final double BIG_SHARE_PCT = 40;

    private final CloudResourceRepository resources;
    private final MetricSnapshotRepository snapshots;
    private final AppSettingRepository settings;

    @Value("${app.cost.monthly-budget:3000}")
    private double defaultBudget;

    public CostService(CloudResourceRepository resources, MetricSnapshotRepository snapshots,
                       AppSettingRepository settings) {
        this.resources = resources;
        this.snapshots = snapshots;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ budget

    public double budget() {
        return settings.findById(BUDGET_KEY)
                .map(s -> parseOr(s.getValue(), defaultBudget))
                .orElse(defaultBudget);
    }

    public double setBudget(double value) {
        settings.save(new AppSetting(BUDGET_KEY, String.valueOf(value)));
        return value;
    }

    // ------------------------------------------------------------------ report

    public CostReport report() {
        List<CloudResource> all = resources.findAll();
        Instant now = Instant.now();
        Instant dayAgo = now.minus(Duration.ofHours(24));

        List<CloudResource> running = all.stream().filter(r -> !r.isStopped()).toList();
        double hourlyBurn = running.stream().mapToDouble(CloudResource::getHourlyCost).sum();
        double projected = hourlyBurn * HOURS_PER_MONTH;
        double stoppedSavings = all.stream().filter(CloudResource::isStopped)
                .mapToDouble(r -> r.getHourlyCost() * HOURS_PER_MONTH).sum();

        // CPU usage over the last 24h, per resource
        Map<Long, double[]> cpu = new HashMap<>();
        for (Object[] row : snapshots.cpuUsageSince(dayAgo)) {
            cpu.put(((Number) row[0]).longValue(), new double[]{((Number) row[1]).doubleValue(), ((Number) row[2]).doubleValue()});
        }

        // Per-resource table, most expensive first
        List<ResourceCost> rows = all.stream()
                .sorted(Comparator.comparingDouble(CloudResource::getHourlyCost).reversed()
                        .thenComparing(CloudResource::getName))
                .map(r -> {
                    double monthly = r.isStopped() ? 0 : r.getHourlyCost() * HOURS_PER_MONTH;
                    double[] u = cpu.get(r.getId());
                    return new ResourceCost(r.getId(), r.getName(), r.getType().name(), r.getProvider().name(),
                            r.getInstanceType(), r.getRegion(), r.getStatus().name(),
                            r.getHourlyCost(), round2(monthly), projected > 0 ? round1(monthly / projected * 100) : 0,
                            u == null ? null : round1(u[0]), u == null ? null : round1(u[1]));
                })
                .toList();

        List<Slice> byProvider = slices(running, r -> r.getProvider().name());
        List<Slice> byType = slices(running, r -> r.getType().name());
        List<HourCost> hourly = hourly(all, now);
        double last24h = hourly.stream().filter(h -> h.cost() != null).mapToDouble(HourCost::cost).sum();

        List<Recommendation> recs = recommendations(running, cpu, projected);
        double potential = recs.stream().mapToDouble(Recommendation::monthlySaving).sum();
        double budget = budget();

        return new CostReport(round2(hourlyBurn), round2(projected), round2(last24h), budget,
                budget > 0 ? round1(projected / budget * 100) : 0, round2(potential), round2(stoppedSavings),
                byProvider, byType, hourly, rows, recs, now.toEpochMilli());
    }

    /** Monthly cost of running resources, grouped. Free resources (like this PC) are left out. */
    private static List<Slice> slices(List<CloudResource> running, Function<CloudResource, String> key) {
        return running.stream()
                .filter(r -> r.getHourlyCost() > 0)
                .collect(Collectors.groupingBy(key))
                .entrySet().stream()
                .map(e -> new Slice(e.getKey(),
                        round2(e.getValue().stream().mapToDouble(CloudResource::getHourlyCost).sum() * HOURS_PER_MONTH),
                        e.getValue().size()))
                .sorted(Comparator.comparingDouble(Slice::monthly).reversed())
                .toList();
    }

    /**
     * Spend per clock hour for the last 24 hours. A resource is charged for an hour if it has
     * any history point in that hour (history is only saved while a resource is running).
     * Hours with no history at all mean the app itself was off, so they are returned as null.
     */
    private List<HourCost> hourly(List<CloudResource> all, Instant now) {
        Map<Long, Double> price = all.stream().collect(Collectors.toMap(CloudResource::getId, CloudResource::getHourlyCost));
        // Hours follow the local clock (e.g. India is UTC+5:30, so UTC hours would show as "01:30")
        Instant firstHour = hourStart(now).minus(Duration.ofHours(23));

        Map<Long, Set<Long>> activeByHour = new HashMap<>();
        for (Object[] row : snapshots.activitySince(firstHour)) {
            long id = ((Number) row[0]).longValue();
            long hour = hourStart((Instant) row[1]).toEpochMilli();
            activeByHour.computeIfAbsent(hour, h -> new HashSet<>()).add(id);
        }

        List<HourCost> out = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            long hour = firstHour.plus(Duration.ofHours(i)).toEpochMilli();
            Set<Long> ids = activeByHour.get(hour);
            if (ids == null || ids.isEmpty()) {
                out.add(new HourCost(hour, null, 0));
            } else {
                double cost = ids.stream().mapToDouble(id -> price.getOrDefault(id, 0.0)).sum();
                out.add(new HourCost(hour, round2(cost), ids.size()));
            }
        }
        return out;
    }

    private static List<Recommendation> recommendations(List<CloudResource> running, Map<Long, double[]> cpu, double projected) {
        List<Recommendation> recs = new ArrayList<>();
        for (CloudResource r : running) {
            double monthly = r.getHourlyCost() * HOURS_PER_MONTH;
            if (monthly <= 0) continue;
            double share = projected > 0 ? monthly / projected * 100 : 0;
            double[] u = cpu.get(r.getId());

            // One resource eats a big part of the bill: run it only during working hours
            if (share >= BIG_SHARE_PCT) {
                recs.add(new Recommendation(r.getId(), r.getName(), "SCHEDULE",
                        "Run " + r.getName() + " only during working hours",
                        String.format("It is %.0f%% of your monthly bill. Stopping it for 12 hours every night would halve its cost.", share),
                        round2(monthly * 0.5)));
                continue;
            }

            // Busy-type resources that barely use their CPU are probably too big
            boolean sizable = r.getType() == ResourceType.VM || r.getType() == ResourceType.DATABASE
                    || r.getType() == ResourceType.CONTAINER;
            if (sizable && u != null && u[0] < IDLE_CPU && u[1] < 80) {
                recs.add(new Recommendation(r.getId(), r.getName(), "DOWNSIZE",
                        "Downsize " + r.getName(),
                        String.format("Average CPU was only %.1f%% over the last 24 hours (peak %.1f%%). The next smaller instance size should be enough.", u[0], u[1]),
                        round2(monthly * 0.5)));
            }
        }
        recs.sort(Comparator.comparingDouble(Recommendation::monthlySaving).reversed());
        return recs;
    }

    private static Instant hourStart(Instant t) {
        return t.atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS).toInstant();
    }

    private static double parseOr(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
