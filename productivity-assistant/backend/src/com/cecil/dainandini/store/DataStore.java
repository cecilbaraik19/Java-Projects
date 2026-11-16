package com.cecil.dainandini.store;

import com.cecil.dainandini.model.AuditEntry;
import com.cecil.dainandini.model.CalEvent;
import com.cecil.dainandini.model.ChatMessage;
import com.cecil.dainandini.model.FocusSession;
import com.cecil.dainandini.model.MeetingNote;
import com.cecil.dainandini.model.Task;
import com.cecil.dainandini.model.User;
import com.cecil.dainandini.util.Clock;
import com.cecil.dainandini.util.Json;

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
 * half-written file. Every change is made while holding the lock on this object: ONE change at a time.
 */
public class DataStore {
    public static final int MAX_AUDIT = 4000;
    public static final int MAX_CHAT_PER_USER = 120;

    private final Path dir;   // null = memory only (used by the tests)

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Task> tasks = new LinkedHashMap<>();
    public final Map<String, CalEvent> events = new LinkedHashMap<>();
    public final Map<String, FocusSession> focus = new LinkedHashMap<>();
    public final Map<String, MeetingNote> notes = new LinkedHashMap<>();
    public final Map<String, ChatMessage> chat = new LinkedHashMap<>();
    public final List<AuditEntry> audit = new ArrayList<>();
    /** Id counters and settings (for example "claude" = OFF / WHEN_UNSURE). */
    public final Map<String, Object> meta = new LinkedHashMap<>();

    /** True while the demo data is being made: nothing is written until saveAll() at the end (much faster). */
    public volatile boolean batch;

    public DataStore(Path dir) { this.dir = dir; }

    /** Returns true when there was nothing saved yet (first start). */
    public synchronized boolean load() throws IOException {
        if (dir == null) return true;
        Files.createDirectories(dir);
        if (!Files.exists(dir.resolve("users.json"))) return true;
        readInto("users.json", users, User::fromJson, u -> u.id);
        readInto("tasks.json", tasks, Task::fromJson, t -> t.id);
        readInto("events.json", events, CalEvent::fromJson, e -> e.id);
        readInto("focus.json", focus, FocusSession::fromJson, f -> f.id);
        readInto("notes.json", notes, MeetingNote::fromJson, n -> n.id);
        readInto("chat.json", chat, ChatMessage::fromJson, c -> c.id);
        for (Object o : readList("audit.json")) audit.add(AuditEntry.fromJson(asMap(o)));
        Path m = dir.resolve("meta.json");
        if (Files.exists(m)) meta.putAll(Json.parseObject(Files.readString(m, StandardCharsets.UTF_8)));
        return users.isEmpty();
    }

    /** Next id for a kind of record, e.g. nextId("T", 1001) gives "T-1001", then "T-1002" ... */
    public synchronized String nextId(String prefix, long start) {
        long n = Json.num(meta, "next." + prefix, start);
        meta.put("next." + prefix, n + 1);
        return prefix + "-" + n;
    }

    public synchronized void log(String userId, String action, String target, String detail) {
        audit.add(new AuditEntry(Clock.millis(), userId, action, target, detail));
        while (audit.size() > MAX_AUDIT) audit.remove(0);
    }

    public synchronized String setting(String key, String fallback) { return Json.str(meta, "setting." + key, fallback); }

    public synchronized void setSetting(String key, String value) { meta.put("setting." + key, value); }

    // ------------------------------------------------------------------ saving

    public static final String[] ALL = { "users", "tasks", "events", "focus", "notes", "chat", "audit" };

    public synchronized void saveAll() {
        batch = false;
        save(ALL);
    }

    /** Saves just the named collections (faster than saving everything after each small change). */
    public synchronized void save(String... names) {
        for (String n : names) {
            switch (n) {
                case "users" -> write("users.json", users.values(), true);
                case "tasks" -> write("tasks.json", tasks.values(), true);
                case "events" -> write("events.json", events.values(), true);
                case "focus" -> write("focus.json", focus.values(), false);
                case "notes" -> write("notes.json", notes.values(), true);
                case "chat" -> write("chat.json", chat.values(), false);
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

    /** Finds a person from "@meera", "Meera" or "meera.k" (username or first name). */
    public synchronized User userByMention(String word) {
        if (word == null) return null;
        String w = word.replaceAll("^@", "").replaceAll("[.,:;!?]+$", "").toLowerCase();
        if (w.isEmpty()) return null;
        for (User u : users.values()) if (u.active && u.username.equalsIgnoreCase(w)) return u;
        for (User u : users.values()) if (u.active && u.firstName().equalsIgnoreCase(w)) return u;
        return null;
    }

    public synchronized String userName(String id) {
        User u = id == null ? null : users.get(id);
        return u == null ? "—" : u.name;
    }

    public synchronized List<Task> tasksOf(String userId) {
        List<Task> out = new ArrayList<>();
        for (Task t : tasks.values()) if (userId.equals(t.ownerId)) out.add(t);
        return out;
    }

    public synchronized List<Task> openTasksOf(String userId) {
        List<Task> out = new ArrayList<>();
        for (Task t : tasks.values()) if (userId.equals(t.ownerId) && t.open()) out.add(t);
        return out;
    }

    /** Calendar events of a person that overlap [from, to). */
    public synchronized List<CalEvent> eventsOf(String userId, long from, long to) {
        List<CalEvent> out = new ArrayList<>();
        for (CalEvent e : events.values()) if (e.involves(userId) && e.start < to && e.end > from) out.add(e);
        out.sort((a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    public synchronized List<FocusSession> focusOf(String userId, long from) {
        List<FocusSession> out = new ArrayList<>();
        for (FocusSession f : focus.values()) if (userId.equals(f.userId) && f.start >= from) out.add(f);
        return out;
    }

    public synchronized FocusSession runningFocus(String userId) {
        for (FocusSession f : focus.values()) if (userId.equals(f.userId) && f.running()) return f;
        return null;
    }
}
