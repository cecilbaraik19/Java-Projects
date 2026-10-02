package com.cecil.cloudmonitor.service;

import com.cecil.cloudmonitor.dto.LogEntry;
import com.cecil.cloudmonitor.model.CloudResource;
import com.cecil.cloudmonitor.model.ResourceType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the most recent log lines in memory and streams new ones to the browser (/topic/logs).
 *
 * The simulated cloud resources don't have real log files, so this class writes believable lines
 * for them every tick (web requests, slow queries, training progress ...). Lines get more serious
 * when a resource is under load, so logs line up with the charts and alerts.
 * The local PC gets real lines built from its actual CPU and memory readings.
 */
@Service
public class LogService {

    public static final List<String> LEVELS = List.of("DEBUG", "INFO", "WARN", "ERROR");
    private static final int CAPACITY = 5000;
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final Deque<LogEntry> buffer = new ArrayDeque<>(CAPACITY);
    private final AtomicLong ids = new AtomicLong();
    private final SimpMessagingTemplate messaging;
    private final Random random = new Random();
    private long tick;

    public LogService(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    // ------------------------------------------------------------------ writing

    public LogEntry log(String level, CloudResource r, String source, String message) {
        return add(level, r == null ? null : r.getId(), r == null ? null : r.getName(), source, message, System.currentTimeMillis(), true);
    }

    /** A line from CloudPulse itself (not tied to one resource). */
    public LogEntry system(String level, String message) {
        return add(level, null, null, "cloudpulse", message, System.currentTimeMillis(), true);
    }

    private LogEntry add(String level, Long resourceId, String name, String source, String message, long at, boolean publish) {
        LogEntry e = new LogEntry(ids.incrementAndGet(), at, level, resourceId, name, source, message);
        synchronized (buffer) {
            if (buffer.size() >= CAPACITY) buffer.pollFirst();
            buffer.addLast(e);
        }
        if (publish) messaging.convertAndSend("/topic/logs", e);
        return e;
    }

    // ------------------------------------------------------------------ reading

    /**
     * Newest-last list of lines that match the filters.
     * minLevel: show this level and above. q: case-insensitive text search.
     */
    public List<LogEntry> query(Long resourceId, String minLevel, String q, int limit) {
        int min = Math.max(0, LEVELS.indexOf(minLevel == null ? "DEBUG" : minLevel.toUpperCase(Locale.ROOT)));
        String needle = q == null || q.isBlank() ? null : q.toLowerCase(Locale.ROOT);
        int max = Math.min(Math.max(limit, 1), CAPACITY);
        List<LogEntry> snapshot;
        synchronized (buffer) {
            snapshot = new ArrayList<>(buffer);
        }
        LinkedList<LogEntry> out = new LinkedList<>();
        for (int i = snapshot.size() - 1; i >= 0 && out.size() < max; i--) {
            LogEntry e = snapshot.get(i);
            if (resourceId != null && !resourceId.equals(e.resourceId())) continue;
            if (LEVELS.indexOf(e.level()) < min) continue;
            if (needle != null && !(e.message().toLowerCase(Locale.ROOT).contains(needle)
                    || (e.resourceName() != null && e.resourceName().toLowerCase(Locale.ROOT).contains(needle))
                    || e.source().toLowerCase(Locale.ROOT).contains(needle))) continue;
            out.addFirst(e);
        }
        return out;
    }

    /** Plain-text version for download, one line per entry. */
    public String asText(List<LogEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (LogEntry e : entries) {
            sb.append(FILE_TIME.format(Instant.ofEpochMilli(e.timestamp()))).append(' ')
                    .append(String.format("%-5s", e.level())).append(' ')
                    .append('[').append(e.resourceName() == null ? "cloudpulse" : e.resourceName()).append(']').append(' ')
                    .append(e.source()).append(": ").append(e.message()).append(System.lineSeparator());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ simulated lines

    /** Called by MonitoringService every tick with the fresh metrics. */
    public void generate(List<CloudResource> resources) {
        tick++;
        for (CloudResource r : resources) {
            if (r.isStopped()) continue;
            if (r.getType() == ResourceType.LOCAL_HOST) {
                localLines(r);
                continue;
            }
            // Busier resources write more lines
            double chance = 0.12 + r.getCpu() / 400.0;
            if (random.nextDouble() < chance) {
                Line l = lineFor(r);
                add(l.level, r.getId(), r.getName(), l.source, l.message, System.currentTimeMillis(), true);
            }
        }
    }

    /** Fills the buffer with the last few minutes of lines so the page isn't empty after a restart. */
    public void backfill(List<CloudResource> resources, int minutes) {
        long now = System.currentTimeMillis();
        List<LogEntry> lines = new ArrayList<>();
        for (CloudResource r : resources) {
            if (r.isStopped() || r.getType() == ResourceType.LOCAL_HOST) continue;
            int count = 6 + random.nextInt(10);
            for (int i = 0; i < count; i++) {
                long at = now - (long) (random.nextDouble() * minutes * 60_000L);
                Line l = lineFor(r);
                lines.add(new LogEntry(0, at, l.level, r.getId(), r.getName(), l.source, l.message));
            }
        }
        lines.sort(Comparator.comparingLong(LogEntry::timestamp));
        for (LogEntry e : lines) add(e.level(), e.resourceId(), e.resourceName(), e.source(), e.message(), e.timestamp(), false);
        add("INFO", null, null, "cloudpulse", "CloudPulse started, monitoring " + resources.size() + " resources", now, false);
    }

    /** Real readings from this computer, every ~30 seconds (10 ticks). */
    private void localLines(CloudResource r) {
        if (tick % 10 != 0) return;
        String level = r.getMemory() >= 90 || r.getCpu() >= 90 ? "WARN" : "INFO";
        add(level, r.getId(), r.getName(), "oshi",
                String.format(Locale.ENGLISH, "sampled cpu=%.1f%% memory=%.1f%% disk_used=%.1f%% net_in=%.0fKB/s net_out=%.0fKB/s",
                        r.getCpu(), r.getMemory(), r.getDisk(), r.getNetworkIn(), r.getNetworkOut()),
                System.currentTimeMillis(), true);
    }

    private record Line(String level, String source, String message) {}

    private Line lineFor(CloudResource r) {
        String name = r.getName().toLowerCase(Locale.ROOT);
        boolean hot = r.getCpu() >= 85;
        if (name.contains("ml")) return mlWorker(r, hot);
        if (name.contains("redis")) return redis(hot);
        if (name.contains("analytics")) return analytics(hot);
        if (name.contains("backup")) return backup();
        return switch (r.getType()) {
            case DATABASE -> postgres(hot);
            case CONTAINER -> gateway(hot);
            case STORAGE -> storage(hot);
            case FUNCTION -> function(hot);
            default -> nginx(r, hot);
        };
    }

    private static final String[] PATHS = {"/", "/api/products", "/api/cart", "/api/orders", "/api/login", "/static/app.js", "/api/search?q=shoes", "/health"};
    private static final String[] METHODS = {"GET", "GET", "GET", "POST", "GET", "PUT"};

    private Line nginx(CloudResource r, boolean hot) {
        String path = pick(PATHS);
        String ip = "203.0.113." + (2 + random.nextInt(250));
        if (hot && random.nextDouble() < 0.35) {
            return new Line("ERROR", "nginx", String.format("upstream timed out (110: Connection timed out) while reading response header, client: %s, request: \"GET %s\"", ip, path));
        }
        double roll = random.nextDouble();
        if (roll < 0.06) return new Line("WARN", "nginx", String.format("%s \"%s %s\" 404 0.00%ds", ip, pick(METHODS), "/wp-login.php", random.nextInt(9)));
        if (roll < 0.09) return new Line("ERROR", "nginx", String.format("%s \"POST %s\" 502 bad gateway", ip, path));
        int ms = (int) (8 + random.nextInt(60) + r.getCpu() * (hot ? 12 : 1.5));
        return new Line(ms > 600 ? "WARN" : "INFO", "nginx", String.format("%s \"%s %s\" 200 %dms", ip, pick(METHODS), path, ms));
    }

    private static final String[] QUERIES = {
            "SELECT * FROM orders WHERE customer_id = $1", "UPDATE inventory SET qty = qty - 1 WHERE sku = $1",
            "SELECT count(*) FROM events WHERE created_at > now() - interval '1 day'", "INSERT INTO payments (...) VALUES (...)"};

    private Line postgres(boolean hot) {
        double roll = random.nextDouble();
        if (hot || roll < 0.08) {
            return new Line("WARN", "postgres", String.format("duration: %d ms  statement: %s", 900 + random.nextInt(hot ? 4000 : 900), pick(QUERIES)));
        }
        if (roll < 0.12) return new Line("ERROR", "postgres", "deadlock detected: process " + (4000 + random.nextInt(900)) + " waits for ShareLock on transaction " + (880000 + random.nextInt(9999)));
        if (roll < 0.35) return new Line("INFO", "postgres", String.format("checkpoint complete: wrote %d buffers (%.1f%%); write=%.3f s", 50 + random.nextInt(900), random.nextDouble() * 6, random.nextDouble() * 4));
        if (roll < 0.55) return new Line("DEBUG", "postgres", "autovacuum: processing table \"public.events\"");
        return new Line("INFO", "postgres", String.format("connection authorized: user=app database=shop (%d active)", 10 + random.nextInt(60)));
    }

    private Line redis(boolean hot) {
        if (hot && random.nextDouble() < 0.5) return new Line("WARN", "redis", "Client id=" + random.nextInt(9999) + " closed for overcoming of output buffer limits");
        double roll = random.nextDouble();
        if (roll < 0.3) return new Line("INFO", "redis", (1 + random.nextInt(900)) + " changes in 60 seconds. Saving...");
        if (roll < 0.55) return new Line("INFO", "redis", "Background saving terminated with success");
        if (roll < 0.62) return new Line("WARN", "redis", "Evicted " + (100 + random.nextInt(4000)) + " keys (maxmemory reached)");
        return new Line("DEBUG", "redis", String.format("keyspace hits=%d misses=%d", 9000 + random.nextInt(4000), random.nextInt(700)));
    }

    private Line gateway(boolean hot) {
        String[] svc = {"orders-svc", "payments-svc", "users-svc", "catalog-svc"};
        String[] routes = {"POST /v1/payments", "GET /v1/users/me", "GET /v1/catalog", "POST /v1/orders"};
        int i = random.nextInt(svc.length);
        if (hot && random.nextDouble() < 0.4) return new Line("ERROR", "gateway", "circuit breaker OPEN for " + svc[i] + " after 5 failures");
        if (random.nextDouble() < 0.07) return new Line("WARN", "gateway", "rate limit hit for api key ak_" + Integer.toHexString(random.nextInt(0xffff)) + " (429)");
        return new Line("INFO", "gateway", String.format("routed %s -> %s 200 (%dms)", routes[i], svc[i], 15 + random.nextInt(hot ? 900 : 120)));
    }

    private Line storage(boolean hot) {
        String[] ops = {"PUT", "GET", "GET", "DELETE"};
        String op = pick(ops);
        if (random.nextDouble() < 0.05) return new Line("WARN", "s3", "403 AccessDenied for " + op + " private/invoices/inv_" + random.nextInt(9999) + ".pdf");
        return new Line("INFO", "s3", String.format("%s uploads/img_%d.jpg (%.1f MB) %dms", op, random.nextInt(99999), 0.1 + random.nextDouble() * 6, 20 + random.nextInt(200)));
    }

    private Line function(boolean hot) {
        if (hot || random.nextDouble() < 0.05) return new Line("WARN", "cloud-run", "Cold start took " + (1200 + random.nextInt(2500)) + " ms");
        if (random.nextDouble() < 0.03) return new Line("ERROR", "cloud-run", "Invocation failed: image too large (max 20 MB)");
        return new Line("INFO", "cloud-run", String.format("resized image to 800x600 in %d ms (memory %d MB)", 60 + random.nextInt(300), 120 + random.nextInt(250)));
    }

    private int epoch = 1;

    private Line mlWorker(CloudResource r, boolean hot) {
        if (r.getMemory() > 88 && random.nextDouble() < 0.4) return new Line("WARN", "trainer", String.format("GPU memory high: %.1f GB / 16 GB", 14 + random.nextDouble() * 1.9));
        if (random.nextDouble() < 0.02) return new Line("ERROR", "trainer", "CUDA out of memory. Retrying batch with size 32");
        epoch = epoch >= 50 ? 1 : epoch + 1;
        double loss = Math.max(0.05, 1.6 * Math.exp(-epoch / 14.0) + random.nextDouble() * 0.05);
        return new Line("INFO", "trainer", String.format(Locale.ENGLISH, "epoch %d/50 loss=%.4f acc=%.3f lr=0.0003", epoch, loss, Math.min(0.99, 1 - loss / 2)));
    }

    private Line analytics(boolean hot) {
        String[] jobs = {"daily_sales_report", "user_retention", "funnel_conversion", "inventory_forecast"};
        String job = pick(jobs);
        if (hot && random.nextDouble() < 0.4) return new Line("WARN", "spark", "Stage 3 of " + job + " is slow: " + (40 + random.nextInt(200)) + " tasks pending");
        if (random.nextDouble() < 0.5) return new Line("INFO", "spark", "Started job " + job);
        return new Line("INFO", "spark", String.format("Finished job %s in %d s (%d rows)", job, 8 + random.nextInt(120), 1000 + random.nextInt(900000)));
    }

    private Line backup() {
        return new Line("INFO", "rsync", "sent " + (1 + random.nextInt(900)) + " MB, speedup is " + (1 + random.nextInt(40)));
    }

    private String pick(String[] options) {
        return options[random.nextInt(options.length)];
    }
}
