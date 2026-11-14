package com.cecil.kaksh.store;

import com.cecil.kaksh.model.AuditEntry;
import com.cecil.kaksh.model.Block;
import com.cecil.kaksh.model.Booking;
import com.cecil.kaksh.model.Note;
import com.cecil.kaksh.model.Room;
import com.cecil.kaksh.model.Series;
import com.cecil.kaksh.model.User;
import com.cecil.kaksh.model.WaitEntry;
import com.cecil.kaksh.util.Clock;
import com.cecil.kaksh.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps all records in memory and saves each collection as a JSON file in the data folder.
 *
 * Saving writes a temporary file first and then renames it, so a crash in the middle of a save never leaves a
 * half-written file. Every change is made while holding the lock on this object: ONE change at a time. This is
 * what makes "two people book the same room at the same moment" safe – the second one always sees the first.
 */
public class DataStore {
    public static final int MAX_AUDIT = 5000;
    public static final int MAX_NOTES_PER_USER = 50;

    private final Path dir;   // null = memory only (used by the tests)

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Room> rooms = new LinkedHashMap<>();
    public final Map<String, Booking> bookings = new LinkedHashMap<>();
    public final Map<String, Series> series = new LinkedHashMap<>();
    public final Map<String, Block> blocks = new LinkedHashMap<>();
    public final Map<String, WaitEntry> waitlist = new LinkedHashMap<>();
    public final Map<String, Note> notes = new LinkedHashMap<>();
    public final List<AuditEntry> audit = new ArrayList<>();
    /** Id counters and small settings. */
    public final Map<String, Object> meta = new LinkedHashMap<>();

    /** True while the demo data is being created: nothing is written until saveAll() at the end (much faster). */
    public volatile boolean batch;

    public DataStore(Path dir) { this.dir = dir; }

    public Path dir() { return dir; }

    /** Returns true when there was nothing saved yet (first start). */
    public synchronized boolean load() throws IOException {
        if (dir == null) return true;
        Files.createDirectories(dir);
        if (!Files.exists(dir.resolve("users.json"))) return true;
        readInto("users.json", users, User::fromJson, u -> u.id);
        readInto("rooms.json", rooms, Room::fromJson, r -> r.id);
        readInto("bookings.json", bookings, Booking::fromJson, b -> b.id);
        readInto("series.json", series, Series::fromJson, s -> s.id);
        readInto("blocks.json", blocks, Block::fromJson, b -> b.id);
        readInto("waitlist.json", waitlist, WaitEntry::fromJson, w -> w.id);
        readInto("notes.json", notes, Note::fromJson, n -> n.id);
        for (Object o : readList("audit.json")) audit.add(AuditEntry.fromJson(asMap(o)));
        Path m = dir.resolve("meta.json");
        if (Files.exists(m)) meta.putAll(Json.parseObject(Files.readString(m, StandardCharsets.UTF_8)));
        return users.isEmpty();
    }

    // ------------------------------------------------------------------ ids, log, notifications

    /** Next id for a kind of record, e.g. nextId("B", 1001) gives "B-1001", then "B-1002" ... */
    public synchronized String nextId(String prefix, long start) {
        long n = Json.num(meta, "next." + prefix, start);
        meta.put("next." + prefix, n + 1);
        return prefix + "-" + n;
    }

    public synchronized void log(String userId, String action, String target, String detail) {
        audit.add(new AuditEntry(Clock.millis(), userId, action, target, detail));
        while (audit.size() > MAX_AUDIT) audit.remove(0);
    }

    /** Adds a notification for one person (keeps only their newest 50). */
    public synchronized void notify(String userId, String text, String link) {
        if (userId == null || !users.containsKey(userId)) return;
        Note n = new Note();
        n.id = nextId("N", 1);
        n.userId = userId;
        n.text = text;
        n.link = link;
        n.at = Clock.millis();
        notes.put(n.id, n);
        List<Note> mine = new ArrayList<>();
        for (Note x : notes.values()) if (userId.equals(x.userId)) mine.add(x);
        for (int i = 0; i < mine.size() - MAX_NOTES_PER_USER; i++) notes.remove(mine.get(i).id);
    }

    // ------------------------------------------------------------------ saving

    public static final String[] ALL = { "users", "rooms", "bookings", "series", "blocks", "waitlist", "notes", "audit" };

    public synchronized void saveAll() {
        batch = false;
        save(ALL);
    }

    /** Saves just the named collections (faster than saving everything after each small change). */
    public synchronized void save(String... names) {
        for (String n : names) {
            switch (n) {
                case "users" -> write("users.json", users.values(), true);
                case "rooms" -> write("rooms.json", rooms.values(), true);
                case "bookings" -> write("bookings.json", bookings.values(), true);
                case "series" -> write("series.json", series.values(), true);
                case "blocks" -> write("blocks.json", blocks.values(), true);
                case "waitlist" -> write("waitlist.json", waitlist.values(), true);
                case "notes" -> write("notes.json", notes.values(), false);
                case "audit" -> write("audit.json", audit, false);
                default -> throw new IllegalArgumentException("Unknown collection " + n);
            }
        }
        write("meta.json", meta, true);
    }

    private void write(String file, Object data, boolean pretty) {
        if (dir == null || batch) return;
        try {
            Path tmp = dir.resolve(file + ".tmp");
            Files.writeString(tmp, pretty ? Json.pretty(data) : Json.write(data), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicNotSupported) {
                Files.move(tmp, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + file + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ reading helpers

    private <T> void readInto(String file, Map<String, T> into, Function<Map<String, Object>, T> maker,
                              Function<T, String> idOf) throws IOException {
        for (Object o : readList(file)) {
            T t = maker.apply(asMap(o));
            into.put(idOf.apply(t), t);
        }
    }

    private List<?> readList(String file) throws IOException {
        Path p = dir.resolve(file);
        if (!Files.exists(p)) return List.of();
        Object v = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
        if (!(v instanceof List<?> l)) throw new IOException(file + " is not a JSON list");
        return l;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected a JSON object in the data file");
        return (Map<String, Object>) o;
    }

    // ------------------------------------------------------------------ small lookups

    public synchronized User userByName(String username) {
        if (username == null) return null;
        for (User u : users.values()) if (u.username.equalsIgnoreCase(username.trim())) return u;
        return null;
    }

    public synchronized String userName(String id) {
        User u = id == null ? null : users.get(id);
        return u == null ? "—" : u.name;
    }

    public synchronized String roomName(String id) {
        Room r = id == null ? null : rooms.get(id);
        return r == null ? "—" : r.name;
    }
}
