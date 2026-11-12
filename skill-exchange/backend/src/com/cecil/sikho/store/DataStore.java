package com.cecil.sikho.store;

import com.cecil.sikho.model.AuditEntry;
import com.cecil.sikho.model.Circle;
import com.cecil.sikho.model.Exchange;
import com.cecil.sikho.model.LedgerEntry;
import com.cecil.sikho.model.Listing;
import com.cecil.sikho.model.Locality;
import com.cecil.sikho.model.Note;
import com.cecil.sikho.model.Report;
import com.cecil.sikho.model.Review;
import com.cecil.sikho.model.Skill;
import com.cecil.sikho.model.User;
import com.cecil.sikho.util.Clock;
import com.cecil.sikho.util.Json;

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
 * Saving writes a temporary file first and then renames it, so a crash in the middle of a save never leaves
 * a half-written file. Every change is made while holding the lock on this object: one change at a time.
 */
public class DataStore {
    public static final int MAX_AUDIT = 5000;
    public static final int MAX_NOTES_PER_USER = 60;

    private final Path dir;   // null = memory only (used by the tests)

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Locality> localities = new LinkedHashMap<>();
    public final Map<String, Skill> skills = new LinkedHashMap<>();
    public final Map<String, Listing> listings = new LinkedHashMap<>();
    public final Map<String, Exchange> exchanges = new LinkedHashMap<>();
    public final Map<String, Review> reviews = new LinkedHashMap<>();
    public final Map<String, Circle> circles = new LinkedHashMap<>();
    public final Map<String, Report> reports = new LinkedHashMap<>();
    public final Map<String, Note> notes = new LinkedHashMap<>();
    public final List<LedgerEntry> ledger = new ArrayList<>();
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
        readInto("localities.json", localities, Locality::fromJson, l -> l.id);
        readInto("skills.json", skills, Skill::fromJson, s -> s.id);
        readInto("listings.json", listings, Listing::fromJson, l -> l.id);
        readInto("exchanges.json", exchanges, Exchange::fromJson, e -> e.id);
        readInto("reviews.json", reviews, Review::fromJson, r -> r.id);
        readInto("circles.json", circles, Circle::fromJson, c -> c.id);
        readInto("reports.json", reports, Report::fromJson, r -> r.id);
        readInto("notes.json", notes, Note::fromJson, n -> n.id);
        for (Object o : readList("ledger.json")) ledger.add(LedgerEntry.fromJson(asMap(o)));
        for (Object o : readList("audit.json")) audit.add(AuditEntry.fromJson(asMap(o)));
        Path m = dir.resolve("meta.json");
        if (Files.exists(m)) meta.putAll(Json.parseObject(Files.readString(m, StandardCharsets.UTF_8)));
        return users.isEmpty();
    }

    // ------------------------------------------------------------------ ids, log, notifications

    /** Next id for a kind of record, e.g. nextId("X", 1001) gives "X-1001", then "X-1002" ... */
    public synchronized String nextId(String prefix, long start) {
        long n = Json.num(meta, "next." + prefix, start);
        meta.put("next." + prefix, n + 1);
        return prefix + "-" + n;
    }

    public synchronized void log(String userId, String action, String target, String detail) {
        audit.add(new AuditEntry(Clock.millis(), userId, action, target, detail));
        while (audit.size() > MAX_AUDIT) audit.remove(0);
    }

    /** Adds a notification for one member (keeps only their newest 60). */
    public synchronized void notify(String userId, String text, String link) {
        if (userId == null) return;
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

    public static final String[] ALL = { "users", "localities", "skills", "listings", "exchanges", "reviews",
            "circles", "reports", "notes", "ledger", "audit" };

    public synchronized void saveAll() {
        batch = false;
        save(ALL);
    }

    /** Saves just the named collections (faster than saving everything after each small change). */
    public synchronized void save(String... names) {
        for (String n : names) {
            switch (n) {
                case "users" -> write("users.json", users.values(), true);
                case "localities" -> write("localities.json", localities.values(), true);
                case "skills" -> write("skills.json", skills.values(), true);
                case "listings" -> write("listings.json", listings.values(), true);
                case "exchanges" -> write("exchanges.json", exchanges.values(), true);
                case "reviews" -> write("reviews.json", reviews.values(), true);
                case "circles" -> write("circles.json", circles.values(), true);
                case "reports" -> write("reports.json", reports.values(), true);
                case "notes" -> write("notes.json", notes.values(), false);
                case "ledger" -> write("ledger.json", ledger, true);
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
        if ("BANK".equals(id)) return "Community bank";
        if ("ESCROW".equals(id)) return "Escrow";
        User u = id == null ? null : users.get(id);
        return u == null ? "—" : u.name;
    }

    public synchronized String skillName(String id) {
        Skill s = id == null ? null : skills.get(id);
        return s == null ? "—" : s.name;
    }

    public synchronized String localityName(String id) {
        Locality l = id == null ? null : localities.get(id);
        return l == null ? "—" : l.name;
    }

    /** A member who can take part right now (exists, switched on, not suspended). */
    public synchronized boolean canAct(User u) { return u != null && u.active && !u.suspended; }
}
