package com.cecil.sanket.broker;

import com.cecil.sanket.util.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A consumer group: one service reading some topics. It remembers, for every partition, the next offset to read
 * (the "committed offset"). Lag = events written but not read yet = end offset - committed offset.
 *
 * It also remembers the ids of the last events it handled, so the same event delivered twice is skipped
 * (an "idempotent consumer"). The broker gives "at-least-once" delivery: an event is never lost, but after a
 * crash it may come again. Skipping repeats turns that into "effectively once".
 */
public class ConsumerGroup {
    public static final int SEEN_KEEP = 20_000;

    public final String name;
    public final String service;
    public final String job;
    public final List<String> topics;
    public final Set<String> handles;
    final Handler handler;

    // saved state
    final Map<String, Long> offsets = new LinkedHashMap<>();    // "topic:partition" -> next offset
    final LinkedHashSet<String> seen = new LinkedHashSet<>();
    public long processed, skipped, duplicates, retries, deadLettered, lostCommits;
    public boolean paused;
    public String pausedReason;

    // live state (not saved)
    final Map<String, Integer> attempts = new HashMap<>();
    final Map<String, Long> nextTryAt = new HashMap<>();
    public volatile String lastError;
    public volatile long lastErrorAt, lastHandledAt;
    /** Fault: handle at most one event every slowGapMs until slowUntil. */
    public volatile long slowUntil, slowGapMs;
    /** Fault: the next N successful events are handled but their offset is not saved (so they come again). */
    public volatile int loseCommits;

    public ConsumerGroup(String name, String service, String job, List<String> topics, Set<String> handles, Handler handler) {
        this.name = name; this.service = service; this.job = job; this.topics = topics; this.handles = handles; this.handler = handler;
    }

    public long offset(String topic, int partition) { return offsets.getOrDefault(topic + ":" + partition, 0L); }

    void commit(String topic, int partition, long next) { offsets.put(topic + ":" + partition, next); }

    void markSeen(String eventId) {
        seen.add(eventId);
        if (seen.size() > SEEN_KEEP) {
            var it = seen.iterator();
            it.next();
            it.remove();
        }
    }

    public boolean hasSeen(String eventId) { return seen.contains(eventId); }

    public boolean slow(long now) { return slowUntil > now; }

    public boolean retrying() { return !nextTryAt.isEmpty(); }

    /** Saved in data/groups.json. */
    public Map<String, Object> state() {
        return Json.obj("offsets", new LinkedHashMap<>(offsets), "seen", new ArrayList<>(seen), "processed", processed,
                "skipped", skipped, "duplicates", duplicates, "retries", retries, "deadLettered", deadLettered,
                "lostCommits", lostCommits, "paused", paused, "pausedReason", pausedReason);
    }

    void restore(Map<String, Object> m) {
        if (m == null) return;
        offsets.clear();
        if (m.get("offsets") instanceof Map<?, ?> o)
            for (Map.Entry<?, ?> e : o.entrySet()) if (e.getValue() instanceof Number n) offsets.put(String.valueOf(e.getKey()), n.longValue());
        seen.clear();
        for (String s : Json.strings(m, "seen")) seen.add(s);
        processed = Json.num(m, "processed", 0);
        skipped = Json.num(m, "skipped", 0);
        duplicates = Json.num(m, "duplicates", 0);
        retries = Json.num(m, "retries", 0);
        deadLettered = Json.num(m, "deadLettered", 0);
        lostCommits = Json.num(m, "lostCommits", 0);
        paused = Json.bool(m, "paused", false);
        pausedReason = Json.str(m, "pausedReason");
    }

    /** Forget everything: offsets to 0, no seen ids, counters to 0 (used before a full replay). */
    void clear() {
        offsets.clear();
        seen.clear();
        attempts.clear();
        nextTryAt.clear();
        processed = skipped = duplicates = retries = 0;
    }
}
