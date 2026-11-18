package com.cecil.swikriti.store;

import com.cecil.swikriti.core.BusinessCalendar;
import com.cecil.swikriti.core.Ledger;
import com.cecil.swikriti.model.AuditEntry;
import com.cecil.swikriti.model.Comment;
import com.cecil.swikriti.model.Delegation;
import com.cecil.swikriti.model.Document;
import com.cecil.swikriti.model.LedgerEntry;
import com.cecil.swikriti.model.Note;
import com.cecil.swikriti.model.Task;
import com.cecil.swikriti.model.Template;
import com.cecil.swikriti.model.User;
import com.cecil.swikriti.util.Clock;
import com.cecil.swikriti.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Keeps all records in memory and saves each collection as a JSON file in the data folder.
 *
 * Saving writes a temporary file first and then renames it, so a crash in the middle of a save never leaves a
 * half-written file. Every change is made while holding the lock on this object (synchronized): ONE change at a
 * time, so two directors approving at the same second can never both "complete" the same stage.
 *
 * Attachments are kept as files named after their SHA-256 (data/files/ab12....bin): the same file uploaded twice is
 * stored once, and the name itself proves the content was not changed.
 */
public class DataStore {
    public static final int MAX_AUDIT = 5000;
    public static final int MAX_NOTES_PER_USER = 80;

    private final Path dir;   // null = memory only (used by the tests)

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Template> templates = new LinkedHashMap<>();   // key = code
    public final Map<String, Document> docs = new LinkedHashMap<>();
    public final Map<String, Task> tasks = new LinkedHashMap<>();
    public final Map<String, Delegation> delegations = new LinkedHashMap<>();
    public final Map<String, Comment> comments = new LinkedHashMap<>();
    public final Map<String, Note> notes = new LinkedHashMap<>();
    public final List<LedgerEntry> ledger = new ArrayList<>();
    public final List<AuditEntry> audit = new ArrayList<>();
    public final Map<String, Object> meta = new LinkedHashMap<>();
    private final Map<String, byte[]> memoryFiles = new ConcurrentHashMap<>();
    private byte[] secret;

    /** True while the demo data is being created: nothing is written until saveAll() at the end (much faster). */
    public volatile boolean batch;

    public DataStore(Path dir) { this.dir = dir; }

    public Path dir() { return dir; }

    /** Returns true when there was nothing saved yet (first start). */
    public synchronized boolean load() throws IOException {
        if (dir == null) return true;
        Files.createDirectories(dir.resolve("files"));
        loadSecret();
        if (!Files.exists(dir.resolve("users.json"))) return true;
        readInto("users.json", users, User::fromJson, u -> u.id);
        readInto("templates.json", templates, Template::fromJson, t -> t.code);
        readInto("documents.json", docs, Document::fromJson, d -> d.id);
        readInto("tasks.json", tasks, Task::fromJson, t -> t.id);
        readInto("delegations.json", delegations, Delegation::fromJson, d -> d.id);
        readInto("comments.json", comments, Comment::fromJson, c -> c.id);
        readInto("notes.json", notes, Note::fromJson, n -> n.id);
        for (Object o : readList("ledger.json")) ledger.add(LedgerEntry.fromJson(asMap(o)));
        for (Object o : readList("audit.json")) audit.add(AuditEntry.fromJson(asMap(o)));
        Path m = dir.resolve("meta.json");
        if (Files.exists(m)) meta.putAll(Json.parseObject(Files.readString(m, StandardCharsets.UTF_8)));
        return users.isEmpty();
    }

    /** The secret key for the ledger's HMAC. Made once (32 random bytes) and kept in data/ledger.key. */
    private void loadSecret() throws IOException {
        Path k = dir.resolve("ledger.key");
        if (Files.exists(k)) {
            secret = HexFormat.of().parseHex(Files.readString(k, StandardCharsets.US_ASCII).trim());
        } else {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            Files.writeString(k, HexFormat.of().formatHex(secret), StandardCharsets.US_ASCII);
        }
    }

    public synchronized byte[] secret() {
        if (secret == null) {   // memory-only store (tests): a fresh random key
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
        }
        return secret;
    }

    // ------------------------------------------------------------------ look-ups

    public synchronized User userByName(String username) {
        if (username == null) return null;
        String k = username.trim().toLowerCase();
        for (User u : users.values()) if (u.username.equals(k)) return u;
        return null;
    }

    public synchronized List<Task> tasksOf(String docId) {
        List<Task> out = new ArrayList<>();
        for (Task t : tasks.values()) if (t.docId.equals(docId)) out.add(t);
        return out;
    }

    /** Is `boss` above `u` in the reporting line (manager, manager's manager ...)? */
    public synchronized boolean manages(User boss, User u) {
        int guard = 0;
        for (String m = u.managerId; m != null && guard++ < 50; ) {
            if (m.equals(boss.id)) return true;
            User mu = users.get(m);
            m = mu == null ? null : mu.managerId;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public synchronized List<String> holidays() {
        Object h = meta.get("holidays");
        if (h instanceof List<?> l) { List<String> out = new ArrayList<>(); for (Object o : l) out.add(String.valueOf(o)); return out; }
        return new ArrayList<>();
    }

    public synchronized BusinessCalendar calendar() { return new BusinessCalendar(holidays()); }

    // ------------------------------------------------------------------ ids, log, notifications, ledger

    /** Next id for a kind of record, e.g. nextId("D", 1001) gives "D-1001", then "D-1002" ... */
    public synchronized String nextId(String prefix, long start) {
        long n = Json.num(meta, "next." + prefix, start);
        meta.put("next." + prefix, n + 1);
        return prefix + "-" + n;
    }

    /** Next running number for a counter name (used for document numbers per type and financial year). */
    public synchronized long nextNumber(String counter) {
        long n = Json.num(meta, "no." + counter, 1);
        meta.put("no." + counter, n + 1);
        return n;
    }

    public synchronized void log(String userId, String action, String target, String detail) {
        audit.add(new AuditEntry(Clock.millis(), userId, action, target, detail));
        while (audit.size() > MAX_AUDIT) audit.remove(0);
    }

    public synchronized Note notify(String userId, String text, String link) {
        User u = userId == null ? null : users.get(userId);
        if (u == null || !u.active) return null;
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
        return n;
    }

    /** Adds one signed link to the approval ledger. */
    public synchronized LedgerEntry chain(String docId, int rev, String action, String userId, String onBehalfOf, String docHash) {
        String prev = ledger.isEmpty() ? Ledger.GENESIS : ledger.get(ledger.size() - 1).hash();
        LedgerEntry e = Ledger.make(secret(), ledger.size() + 1, Clock.millis(), docId, rev, action, userId, onBehalfOf, docHash, prev);
        ledger.add(e);
        return e;
    }

    // ------------------------------------------------------------------ attachment files

    public void putFile(String sha, byte[] bytes) {
        if (dir == null) { memoryFiles.put(sha, bytes); return; }
        try {
            Path f = dir.resolve("files").resolve(sha + ".bin");
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Path tmp = f.resolveSibling(sha + ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not save the file: " + e.getMessage(), e);
        }
    }

    public byte[] getFile(String sha) {
        if (sha == null || !sha.matches("[0-9a-f]{64}")) return null;
        if (dir == null) return memoryFiles.get(sha);
        try {
            Path f = dir.resolve("files").resolve(sha + ".bin");
            return Files.exists(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ saving

    public static final String[] ALL = { "users", "templates", "docs", "tasks", "delegations", "comments", "notes", "ledger", "audit" };

    public synchronized void saveAll() {
        batch = false;
        save(ALL);
    }

    /** Saves just the named collections (faster than saving everything after each small change). */
    public synchronized void save(String... names) {
        for (String n : names) {
            switch (n) {
                case "users" -> write("users.json", users.values(), true);
                case "templates" -> write("templates.json", templates.values(), true);
                case "docs" -> write("documents.json", docs.values(), true);
                case "tasks" -> write("tasks.json", tasks.values(), true);
                case "delegations" -> write("delegations.json", delegations.values(), true);
                case "comments" -> write("comments.json", comments.values(), true);
                case "notes" -> write("notes.json", notes.values(), false);
                case "ledger" -> write("ledger.json", ledger, true);
                case "audit" -> write("audit.json", audit, false);
                case "meta" -> { }   // meta.json is written with every save anyway
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
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) o;
    }
}
