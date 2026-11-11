package com.cecil.sanket.broker;

import com.cecil.sanket.model.Event;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small event broker that works like Apache Kafka, written with the JDK only.
 *
 *  - A TOPIC is an append-only log of events. Each topic is split into PARTITIONS.
 *  - The partition is chosen from the event's KEY (the order id): partition = hash(key) mod partitions.
 *    So all events of one order land in the same partition, and inside one partition the order never changes.
 *  - Every event gets an OFFSET: its position in the partition (0, 1, 2 ...). Events are never changed or removed.
 *  - Consumers do not "take" events away. Each consumer group just remembers the next offset it will read,
 *    so many groups can read the same log, and a group can go back and read it again (replay).
 *
 * Every partition is saved as one NDJSON file (one JSON event per line) in data/log/, e.g. orders-0.ndjson.
 * A new event is added to the end of the file. All methods are synchronized: one writer at a time.
 */
public class EventBroker {
    /** Topic name -> number of partitions. */
    public static final Map<String, Integer> TOPICS = new LinkedHashMap<>();
    static {
        TOPICS.put("orders", 3);
        TOPICS.put("inventory", 3);
        TOPICS.put("payments", 3);
        TOPICS.put("shipments", 3);
        TOPICS.put("dead-letter", 1);
    }

    private final Path dir;                                    // null = memory only (tests)
    private final Map<String, List<List<Event>>> logs = new LinkedHashMap<>();
    private final Map<String, List<Event>> byOrder = new HashMap<>();   // correlationId -> events (for tracing)
    private final Map<String, Event> byId = new HashMap<>();
    private long sequence;                                     // for event ids
    private volatile boolean down;
    /** While true (building the demo history), nothing is written to disk until flush(). */
    public volatile boolean batch;

    public EventBroker(Path dir) {
        this.dir = dir;
        for (Map.Entry<String, Integer> t : TOPICS.entrySet()) {
            List<List<Event>> parts = new ArrayList<>();
            for (int p = 0; p < t.getValue(); p++) parts.add(new ArrayList<>());
            logs.put(t.getKey(), parts);
        }
    }

    // ------------------------------------------------------------------ writing

    /** Checks the event against its schema, picks the partition from the key and appends it. Returns the stored event. */
    public synchronized Event publish(String topic, String type, int version, String key, String correlationId,
                                      String causationId, String source, Map<String, Object> payload) {
        if (down) throw new BrokerDown();
        List<List<Event>> parts = logs.get(topic);
        if (parts == null) throw new IllegalArgumentException("No such topic " + topic);
        Schemas.validate(topic, type, version, payload);
        int partition = partitionFor(key, parts.size());
        List<Event> log = parts.get(partition);
        sequence++;
        String id = "E-" + String.format("%06d", sequence);
        Event e = new Event(id, topic, partition, log.size(), type, version, key, Clock.millis(), correlationId,
                causationId, source, new LinkedHashMap<>(payload));
        log.add(e);
        index(e);
        if (dir != null && !batch) append(e);
        return e;
    }

    /**
     * Which partition a key goes to. The same key always gives the same partition.
     * (String.hashCode is mixed a little so that keys like ORD-1001, ORD-1002 spread evenly.)
     */
    public static int partitionFor(String key, int partitions) {
        if (key == null) return 0;
        int h = key.hashCode();
        h ^= (h >>> 16);
        h *= 0x45d9f3b;
        h ^= (h >>> 16);
        return Math.floorMod(h, partitions);
    }

    private void index(Event e) {
        byId.put(e.id, e);
        if (e.correlationId != null) byOrder.computeIfAbsent(e.correlationId, k -> new ArrayList<>()).add(e);
    }

    // ------------------------------------------------------------------ reading

    /** The event at this offset, or null if the consumer has reached the end of the partition. */
    public synchronized Event read(String topic, int partition, long offset) {
        if (down) throw new BrokerDown();
        List<Event> log = logs.get(topic).get(partition);
        return offset >= 0 && offset < log.size() ? log.get((int) offset) : null;
    }

    /** The offset the next event will get (= number of events in the partition). */
    public synchronized long end(String topic, int partition) { return logs.get(topic).get(partition).size(); }

    public synchronized long total(String topic) {
        long n = 0;
        for (List<Event> l : logs.get(topic)) n += l.size();
        return n;
    }

    public synchronized long total() {
        long n = 0;
        for (String t : logs.keySet()) n += total(t);
        return n;
    }

    public int partitions(String topic) { return TOPICS.getOrDefault(topic, 0); }

    /** Events from one partition, newest first, starting below 'before' (paging backwards). */
    public synchronized List<Event> page(String topic, int partition, long before, int limit, String type) {
        List<Event> log = logs.get(topic).get(partition);
        List<Event> out = new ArrayList<>();
        long start = before < 0 || before > log.size() ? log.size() : before;
        for (long i = start - 1; i >= 0 && out.size() < limit; i--) {
            Event e = log.get((int) i);
            if (type == null || type.isBlank() || e.type.equals(type)) out.add(e);
        }
        return out;
    }

    public synchronized Event byId(String id) { return byId.get(id); }

    /** All events of one order, in time order (they come from several topics). */
    public synchronized List<Event> trace(String correlationId) {
        List<Event> l = new ArrayList<>(byOrder.getOrDefault(correlationId, List.of()));
        l.sort((a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : a.id.compareTo(b.id));
        return l;
    }

    /** Every event written at or after 'since' (all topics), oldest first. */
    public synchronized List<Event> since(long since) {
        List<Event> out = new ArrayList<>();
        for (List<List<Event>> parts : logs.values())
            for (List<Event> log : parts)
                for (int i = log.size() - 1; i >= 0 && log.get(i).at >= since; i--) out.add(log.get(i));
        out.sort((a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : a.id.compareTo(b.id));
        return out;
    }

    /** The newest n events over all topics. */
    public synchronized List<Event> latest(int n) {
        List<Event> out = new ArrayList<>();
        for (List<List<Event>> parts : logs.values())
            for (List<Event> log : parts)
                for (int i = log.size() - 1; i >= 0 && i >= log.size() - n; i--) out.add(log.get(i));
        out.sort((a, b) -> a.at != b.at ? Long.compare(b.at, a.at) : b.id.compareTo(a.id));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    // ------------------------------------------------------------------ the "broker down" fault

    public boolean isDown() { return down; }

    public void setDown(boolean d) { down = d; }

    // ------------------------------------------------------------------ files

    /** Reads the saved partitions from data/log. */
    public synchronized void load() throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return;
        List<Event> all = new ArrayList<>();
        for (Map.Entry<String, List<List<Event>>> t : logs.entrySet()) {
            for (int p = 0; p < t.getValue().size(); p++) {
                Path f = dir.resolve(t.getKey() + "-" + p + ".ndjson");
                if (!Files.exists(f)) continue;
                List<Event> log = t.getValue().get(p);
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    Event e;
                    try { e = Event.fromJson(Json.parseObject(line)); }
                    catch (IllegalArgumentException halfWritten) { continue; }   // a crash in the middle of a write: skip it
                    if (e.offset != log.size()) continue;   // out of place: skip it
                    log.add(e);
                    all.add(e);
                }
            }
        }
        all.sort((a, b) -> Long.compare(a.at, b.at));
        for (Event e : all) {
            index(e);
            try { sequence = Math.max(sequence, Long.parseLong(e.id.substring(2))); } catch (RuntimeException ignored) { }
        }
    }

    private void append(Event e) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(e.topic + "-" + e.partition + ".ndjson"), Json.write(e) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not write the event log: " + ex.getMessage(), ex);
        }
    }

    /** Writes every partition file in full (used once after the demo history is built). */
    public synchronized void flush() {
        batch = false;
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, List<List<Event>>> t : logs.entrySet()) {
                for (int p = 0; p < t.getValue().size(); p++) {
                    StringBuilder sb = new StringBuilder();
                    for (Event e : t.getValue().get(p)) sb.append(Json.write(e)).append('\n');
                    Files.writeString(dir.resolve(t.getKey() + "-" + p + ".ndjson"), sb.toString(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not write the event log: " + ex.getMessage(), ex);
        }
    }

    public Path dir() { return dir; }
}
