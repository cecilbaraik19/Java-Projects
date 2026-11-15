package com.cecil.sangam.store;

import com.cecil.sangam.core.WorkHours;
import com.cecil.sangam.model.AuditEntry;
import com.cecil.sangam.model.Channel;
import com.cecil.sangam.model.Doc;
import com.cecil.sangam.model.Handoff;
import com.cecil.sangam.model.Meeting;
import com.cecil.sangam.model.Message;
import com.cecil.sangam.model.Note;
import com.cecil.sangam.model.Project;
import com.cecil.sangam.model.Standup;
import com.cecil.sangam.model.Task;
import com.cecil.sangam.model.User;
import com.cecil.sangam.util.Clock;
import com.cecil.sangam.util.Json;

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
 * half-written file. Every change is made while holding the lock on this object: ONE change at a time, so two
 * people pressing a button at the same moment can never see a half-done change.
 */
public class DataStore {
    public static final int MAX_AUDIT = 5000;
    public static final int MAX_NOTES_PER_USER = 80;

    private final Path dir;   // null = memory only (used by the tests)

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Project> projects = new LinkedHashMap<>();
    public final Map<String, Task> tasks = new LinkedHashMap<>();
    public final Map<String, Channel> channels = new LinkedHashMap<>();
    public final Map<String, Message> messages = new LinkedHashMap<>();
    public final Map<String, Doc> docs = new LinkedHashMap<>();
    public final Map<String, Standup> standups = new LinkedHashMap<>();
    public final Map<String, Handoff> handoffs = new LinkedHashMap<>();
    public final Map<String, Meeting> meetings = new LinkedHashMap<>();
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
        readInto("projects.json", projects, Project::fromJson, p -> p.id);
        readInto("tasks.json", tasks, Task::fromJson, t -> t.id);
        readInto("channels.json", channels, Channel::fromJson, c -> c.id);
        readInto("messages.json", messages, Message::fromJson, m -> m.id);
        readInto("docs.json", docs, Doc::fromJson, d -> d.id);
        readInto("standups.json", standups, Standup::fromJson, s -> s.id);
        readInto("handoffs.json", handoffs, Handoff::fromJson, h -> h.id);
        readInto("meetings.json", meetings, Meeting::fromJson, m -> m.id);
        readInto("notes.json", notes, Note::fromJson, n -> n.id);
        for (Object o : readList("audit.json")) audit.add(AuditEntry.fromJson(asMap(o)));
        Path m = dir.resolve("meta.json");
        if (Files.exists(m)) meta.putAll(Json.parseObject(Files.readString(m, StandardCharsets.UTF_8)));
        return users.isEmpty();
    }

    // ------------------------------------------------------------------ ids, log, notifications

    /** Next id for a kind of record, e.g. nextId("T", 1001) gives "T-1001", then "T-1002" ... */
    public synchronized String nextId(String prefix, long start) {
        long n = Json.num(meta, "next." + prefix, start);
        meta.put("next." + prefix, n + 1);
        return prefix + "-" + n;
    }

    /** The next message number (one counter for all channels). */
    public synchronized long nextSeq() {
        long n = Json.num(meta, "seq", 0) + 1;
        meta.put("seq", n);
        return n;
    }

    public synchronized void log(String userId, String action, String target, String detail) {
        audit.add(new AuditEntry(Clock.millis(), userId, action, target, detail));
        while (audit.size() > MAX_AUDIT) audit.remove(0);
    }

    /**
     * Sends a notification, respecting QUIET HOURS: when the person is outside their working hours or in focus
     * time, it waits until they are back (unless urgent). Returns the note (its deliverAt says when it shows).
     */
    public synchronized Note notify(String userId, String text, String link, boolean urgent) {
        User u = userId == null ? null : users.get(userId);
        if (u == null || !u.active) return null;
        long now = Clock.millis();
        Note n = new Note();
        n.id = nextId("N", 1);
        n.userId = userId;
        n.text = text;
        n.link = link;
        n.at = now;
        n.urgent = urgent;
        n.deliverAt = urgent ? now : deliverAt(u, now);
        notes.put(n.id, n);
        List<Note> mine = new ArrayList<>();
        for (Note x : notes.values()) if (userId.equals(x.userId)) mine.add(x);
        for (int i = 0; i < mine.size() - MAX_NOTES_PER_USER; i++) notes.remove(mine.get(i).id);
        return n;
    }

    /** When a normal (not urgent) notification sent now would reach this person. */
    public static long deliverAt(User u, long now) {
        long t = now;
        if (u.focusUntil > t) t = u.focusUntil;
        return WorkHours.nextStart(u, t);
    }

    // ------------------------------------------------------------------ saving

    public static final String[] ALL = { "users", "projects", "tasks", "channels", "messages", "docs", "standups",
            "handoffs", "meetings", "notes", "audit" };

    public synchronized void saveAll() {
        batch = false;
        save(ALL);
    }

    /** Saves just the named collections (faster than saving everything after each small change). */
    public synchronized void save(String... names) {
        for (String n : names) {
            switch (n) {
                case "users" -> write("users.json", users.values(), true);
                case "projects" -> write("projects.json", projects.values(), true);
                case "tasks" -> write("tasks.json", tasks.values(), true);
                case "channels" -> write("channels.json", channels.values(), true);
                case "messages" -> write("messages.json", messages.values(), false);
                case "docs" -> write("docs.json", docs.values(), true);
                case "standups" -> write("standups.json", standups.values(), true);
                case "handoffs" -> write("handoffs.json", handoffs.values(), true);
                case "meetings" -> write("meetings.json", meetings.values(), true);
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

    public synchronized Task taskByKey(String key) {
        for (Task t : tasks.values()) if (t.key.equalsIgnoreCase(key)) return t;
        return null;
    }
}
