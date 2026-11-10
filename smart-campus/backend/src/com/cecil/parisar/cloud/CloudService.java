package com.cecil.parisar.cloud;

import com.cecil.parisar.iot.IotService;
import com.cecil.parisar.model.Device;
import com.cecil.parisar.model.Point;
import com.cecil.parisar.model.Role;
import com.cecil.parisar.model.User;
import com.cecil.parisar.service.Problem;
import com.cecil.parisar.store.DataStore;
import com.cecil.parisar.util.Clock;
import com.cecil.parisar.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The cloud part, simulated on this computer so it works without an account or internet.
 * The folder data/cloud behaves like a cloud "object storage bucket" (the same idea as Amazon S3):
 *
 *   telemetry/2026-11-10.ndjson   every finished 10-minute sensor point, one JSON per line (NDJSON),
 *                                 appended by the sync job every 5 minutes - the full long-term history
 *   backups/backup-....json       a full snapshot of the database, made every day or on demand
 *   manifest.json                 the list of objects with their size, record count and SHA-256 checksum
 *
 * verify() recomputes every checksum and reports any object that was changed or damaged.
 * Moving to a real cloud would only replace writeObject()/readObject() with calls to the cloud provider.
 */
public class CloudService {
    public static final String BUCKET = "parisar-campus-data";
    public static final String REGION = "ap-south-1 (Mumbai) - simulated";
    public static final long SYNC_EVERY_MS = 5 * 60_000L;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DataStore store;
    private final IotService iot;
    private final Path root;
    private final Map<String, Map<String, Object>> manifest = new TreeMap<>();
    private final List<Object> jobs = new ArrayList<>();      // recent sync / backup runs (newest first)

    public CloudService(DataStore store, IotService iot, Path root) {
        this.store = store;
        this.iot = iot;
        this.root = root;
        try {
            Files.createDirectories(root.resolve("telemetry"));
            Files.createDirectories(root.resolve("backups"));
            Path m = root.resolve("manifest.json");
            if (Files.exists(m)) {
                Map<String, Object> all = Json.parseObject(Files.readString(m, StandardCharsets.UTF_8));
                for (Map.Entry<String, Object> e : all.entrySet())
                    if (e.getValue() instanceof Map<?, ?> v) manifest.put(e.getKey(), cast(v));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open the cloud folder " + root + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }

    // ------------------------------------------------------------------ sync: telemetry -> bucket

    /** Uploads every finished 10-minute point that was not uploaded yet. Safe to run twice (nothing is sent twice). */
    public synchronized Map<String, Object> sync(String by) {
        long now = Clock.millis();
        long last;
        Map<String, StringBuilder> byDay = new TreeMap<>();
        Map<String, Integer> counts = new TreeMap<>();
        long newest;
        synchronized (store) {
            last = Json.num(store.meta, "cloud.lastPoint", 0);
            newest = last;
            for (Device d : store.devices.values()) {
                for (Point p : store.fineOf(d.id)) {
                    if (p.t <= last || p.t + DataStore.FINE_MS > now) continue;     // already sent, or still filling
                    String day = ZonedDateTime.ofInstant(Instant.ofEpochMilli(p.t), Clock.ZONE).toLocalDate().toString();
                    Map<String, Object> line = Json.obj("device", d.id, "type", d.type, "room", d.roomId, "building", d.buildingId);
                    line.putAll(p.toJson());
                    byDay.computeIfAbsent(day, k -> new StringBuilder()).append(Json.write(line)).append('\n');
                    counts.merge(day, 1, Integer::sum);
                    newest = Math.max(newest, p.t);
                }
            }
        }
        int records = 0;
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> e : byDay.entrySet()) {
            String key = "telemetry/" + e.getKey() + ".ndjson";
            appendObject(key, e.getValue().toString(), counts.get(e.getKey()));
            records += counts.get(e.getKey());
            keys.add(key);
        }
        synchronized (store) {
            store.meta.put("cloud.lastPoint", newest);
            store.meta.put("cloud.lastSync", now);
            store.meta.put("cloud.synced", Json.num(store.meta, "cloud.synced", 0) + records);
            if (by != null) store.log(by, "cloud.sync", BUCKET, records + " records");
            store.save("telemetry", "audit");
        }
        saveManifest();
        Map<String, Object> job = Json.obj("kind", "sync", "at", now, "records", records, "objects", keys, "by", by == null ? "schedule" : store.userName(by));
        addJob(job);
        return job;
    }

    // ------------------------------------------------------------------ backups

    public synchronized Map<String, Object> backup(String by) {
        long now = Clock.millis();
        String json;
        synchronized (store) { json = Json.pretty(store.snapshot()); }
        String key = "backups/backup-" + ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), Clock.ZONE).format(STAMP) + ".json";
        writeObject(key, json, 1);
        // keep the newest 10 backups only (like a "lifecycle rule" on a real bucket)
        List<String> backups = new ArrayList<>();
        for (String k : manifest.keySet()) if (k.startsWith("backups/")) backups.add(k);
        while (backups.size() > 10) {
            String old = backups.remove(0);
            try { Files.deleteIfExists(root.resolve(old)); } catch (IOException ignored) { }
            manifest.remove(old);
        }
        saveManifest();
        synchronized (store) {
            store.meta.put("cloud.lastBackup", now);
            if (by != null) store.log(by, "cloud.backup", key, (json.length() / 1024) + " KB");
            store.save("audit");
        }
        Map<String, Object> job = Json.obj("kind", "backup", "at", now, "key", key, "bytes", json.getBytes(StandardCharsets.UTF_8).length,
                "by", by == null ? "schedule" : store.userName(by));
        addJob(job);
        return job;
    }

    /** Makes a backup if the last one is older than 24 hours (run by the scheduler). */
    public void dailyBackupIfDue() {
        long last;
        synchronized (store) { last = Json.num(store.meta, "cloud.lastBackup", 0); }
        if (Clock.millis() - last >= 24 * DataStore.HOUR_MS) backup(null);
    }

    // ------------------------------------------------------------------ integrity

    /** Recomputes the SHA-256 of every object and compares it with the manifest. */
    public synchronized Map<String, Object> verify(String by) {
        List<Object> bad = new ArrayList<>();
        int ok = 0;
        for (Map.Entry<String, Map<String, Object>> e : manifest.entrySet()) {
            Path p = root.resolve(e.getKey());
            String expected = Json.str(e.getValue(), "sha256");
            String actual;
            try { actual = Files.exists(p) ? sha256(Files.readAllBytes(p)) : "missing"; } catch (IOException ex) { actual = "unreadable"; }
            if (actual.equals(expected)) ok++;
            else bad.add(Json.obj("key", e.getKey(), "problem", actual.equals("missing") ? "missing" : "checksum does not match (changed or damaged)"));
        }
        if (by != null) synchronized (store) { store.log(by, "cloud.verify", BUCKET, ok + " ok, " + bad.size() + " bad"); store.save("audit"); }
        Map<String, Object> job = Json.obj("kind", "verify", "at", Clock.millis(), "ok", ok, "bad", bad, "by", by == null ? "schedule" : store.userName(by));
        addJob(job);
        return job;
    }

    // ------------------------------------------------------------------ status for the Cloud page

    public synchronized Map<String, Object> status(User u) {
        if (u.role != Role.ADMIN) throw Problem.forbidden("Only the campus admin can see the cloud console.");
        long now = Clock.millis();
        long bytes = 0, records = 0;
        List<Object> objects = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : manifest.entrySet()) {
            bytes += Json.num(e.getValue(), "bytes", 0);
            records += Json.num(e.getValue(), "records", 0);
            Map<String, Object> o = new LinkedHashMap<>(e.getValue());
            o.put("key", e.getKey());
            objects.add(o);
        }
        java.util.Collections.reverse(objects);
        long lastSync, lastBackup, pending = 0, lastPoint;
        synchronized (store) {
            lastSync = Json.num(store.meta, "cloud.lastSync", 0);
            lastBackup = Json.num(store.meta, "cloud.lastBackup", 0);
            lastPoint = Json.num(store.meta, "cloud.lastPoint", 0);
            for (Device d : store.devices.values()) for (Point p : store.fineOf(d.id)) if (p.t > lastPoint && p.t + DataStore.FINE_MS <= now) pending++;
        }
        long lagMin = lastSync == 0 ? -1 : (now - lastSync) / 60_000;
        int msgs = iot.messagesLast5Min();
        List<Object> services = List.of(
                Json.obj("name", "IoT hub (HTTP ingest)", "state", msgs > 0 ? "OK" : "IDLE", "detail", msgs + " messages in the last 5 min"),
                Json.obj("name", "Object storage", "state", "OK", "detail", manifest.size() + " objects, " + (bytes / 1024) + " KB"),
                Json.obj("name", "Telemetry sync job", "state", lagMin >= 0 && lagMin <= 15 ? "OK" : "LATE", "detail", lagMin < 0 ? "never ran" : "last run " + lagMin + " min ago, " + pending + " points waiting"),
                Json.obj("name", "Daily backup", "state", lastBackup > 0 && now - lastBackup < 26 * DataStore.HOUR_MS ? "OK" : "LATE", "detail", lastBackup == 0 ? "no backup yet" : "last backup " + ((now - lastBackup) / 3_600_000) + " h ago"),
                Json.obj("name", "Analytics + AI engine", "state", "OK", "detail", "risk model, anomaly check, energy forecast"));
        return Json.obj("bucket", BUCKET, "region", REGION, "folder", root.toString(), "objects", objects, "bytes", bytes, "records", records,
                "lastSync", lastSync, "lastBackup", lastBackup, "pending", pending, "services", services, "jobs", jobs);
    }

    /** One object's content (for the "view" button). Only keys from the manifest are allowed - never a free path. */
    public synchronized String read(User u, String key) {
        if (u.role != Role.ADMIN) throw Problem.forbidden("Only the campus admin can open cloud objects.");
        if (key == null || !manifest.containsKey(key) || !key.matches("(telemetry|backups)/[A-Za-z0-9._-]+")) throw Problem.notFound("Object");
        try {
            String all = Files.readString(root.resolve(key), StandardCharsets.UTF_8);
            return all.length() > 200_000 ? all.substring(0, 200_000) + "\n... (cut - the object is " + all.length() / 1024 + " KB)" : all;
        } catch (IOException e) {
            throw Problem.notFound("Object");
        }
    }

    // ------------------------------------------------------------------ the "cloud API" (file system here)

    private void appendObject(String key, String text, int records) {
        try {
            Path p = root.resolve(key);
            Files.writeString(p, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Map<String, Object> meta = manifest.computeIfAbsent(key, k -> new LinkedHashMap<>());
            meta.put("bytes", Files.size(p));
            meta.put("records", Json.num(meta, "records", 0) + records);
            meta.put("sha256", sha256(Files.readAllBytes(p)));
            meta.put("contentType", "application/x-ndjson");
            meta.put("updatedAt", Clock.millis());
        } catch (IOException e) {
            throw new IllegalStateException("Cloud upload failed: " + e.getMessage(), e);
        }
    }

    private void writeObject(String key, String text, int records) {
        try {
            byte[] b = text.getBytes(StandardCharsets.UTF_8);
            Files.write(root.resolve(key), b);
            manifest.put(key, Json.obj("bytes", (long) b.length, "records", (long) records, "sha256", sha256(b),
                    "contentType", "application/json", "updatedAt", Clock.millis()));
        } catch (IOException e) {
            throw new IllegalStateException("Cloud upload failed: " + e.getMessage(), e);
        }
    }

    private void saveManifest() {
        try {
            Files.writeString(root.resolve("manifest.json"), Json.pretty(manifest), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save the manifest: " + e.getMessage(), e);
        }
    }

    private void addJob(Map<String, Object> job) {
        jobs.add(0, job);
        while (jobs.size() > 15) jobs.remove(jobs.size() - 1);
    }

    public static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Path root() { return root; }
}
