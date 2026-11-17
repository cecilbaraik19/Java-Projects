package com.cecil.kaushal.store;

import com.cecil.kaushal.core.Levels;
import com.cecil.kaushal.model.AuditEntry;
import com.cecil.kaushal.model.Category;
import com.cecil.kaushal.model.Cert;
import com.cecil.kaushal.model.Claim;
import com.cecil.kaushal.model.Endorsement;
import com.cecil.kaushal.model.Goal;
import com.cecil.kaushal.model.JobRole;
import com.cecil.kaushal.model.Note;
import com.cecil.kaushal.model.Project;
import com.cecil.kaushal.model.Skill;
import com.cecil.kaushal.model.Snapshot;
import com.cecil.kaushal.model.User;
import com.cecil.kaushal.util.Clock;
import com.cecil.kaushal.util.Json;
import com.cecil.kaushal.util.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps all records in memory and saves each collection as a JSON file in the data folder.
 *
 * Saving writes a temporary file first and then renames it, so a crash in the middle of a save never leaves a
 * half-written file. Every change is made while holding the lock on this object (synchronized): ONE change at a
 * time, so two managers verifying at the same moment can never see a half-done change.
 */
public class DataStore {
    public static final int MAX_AUDIT = 5000;
    public static final int MAX_NOTES_PER_USER = 60;

    private final Path dir;   // null = memory only (used by the tests)

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Category> categories = new LinkedHashMap<>();
    public final Map<String, Skill> skills = new LinkedHashMap<>();
    public final Map<String, Claim> claims = new LinkedHashMap<>();
    public final Map<String, Endorsement> endorsements = new LinkedHashMap<>();
    public final Map<String, Cert> certs = new LinkedHashMap<>();
    public final Map<String, JobRole> roles = new LinkedHashMap<>();
    public final Map<String, Goal> goals = new LinkedHashMap<>();
    public final Map<String, Project> projects = new LinkedHashMap<>();
    public final Map<String, Note> notes = new LinkedHashMap<>();
    public final Map<String, Snapshot> snapshots = new LinkedHashMap<>();   // key = yyyy-mm
    public final List<AuditEntry> audit = new ArrayList<>();
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
        readInto("categories.json", categories, Category::fromJson, c -> c.id);
        readInto("skills.json", skills, Skill::fromJson, s -> s.id);
        readInto("claims.json", claims, Claim::fromJson, c -> c.id);
        readInto("endorsements.json", endorsements, Endorsement::fromJson, e -> e.id);
        readInto("certs.json", certs, Cert::fromJson, c -> c.id);
        readInto("roles.json", roles, JobRole::fromJson, r -> r.id);
        readInto("goals.json", goals, Goal::fromJson, g -> g.id);
        readInto("projects.json", projects, Project::fromJson, p -> p.id);
        readInto("notes.json", notes, Note::fromJson, n -> n.id);
        readInto("snapshots.json", snapshots, Snapshot::fromJson, s -> s.month);
        for (Object o : readList("audit.json")) audit.add(AuditEntry.fromJson(asMap(o)));
        Path m = dir.resolve("meta.json");
        if (Files.exists(m)) meta.putAll(Json.parseObject(Files.readString(m, StandardCharsets.UTF_8)));
        return users.isEmpty();
    }

    // ------------------------------------------------------------------ look-ups

    public synchronized User userByName(String username) {
        if (username == null) return null;
        String k = username.trim().toLowerCase();
        for (User u : users.values()) if (u.username.equals(k)) return u;
        return null;
    }

    /** A person's claim for one skill, or null. */
    public synchronized Claim claim(String userId, String skillId) {
        for (Claim c : claims.values()) if (c.userId.equals(userId) && c.skillId.equals(skillId)) return c;
        return null;
    }

    public synchronized List<Claim> claimsOf(String userId) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : claims.values()) if (c.userId.equals(userId)) out.add(c);
        return out;
    }

    /** Months without use after which this skill counts one level lower. */
    public synchronized int halfLife(Skill s) {
        if (s.halfLife > 0) return s.halfLife;
        Category c = categories.get(s.categoryId);
        return c == null ? 18 : c.halfLife;
    }

    /** The level that counts today: verified level after decay (0 = not verified). */
    public synchronized int effective(Claim c, LocalDate today) {
        Skill s = skills.get(c.skillId);
        if (s == null) return 0;
        return Levels.effective(c.verified, c.lastUsed, halfLife(s), today);
    }

    /**
     * skillId -> the level that counts today for one person. Only verified levels count, unless includeClaimed
     * (then a not-yet-verified claim counts at its claimed level - used to show "maybe" matches in search).
     */
    public synchronized Map<String, Integer> levels(String userId, boolean includeClaimed, LocalDate today) {
        Map<String, Integer> out = new HashMap<>();
        for (Claim c : claims.values()) {
            if (!c.userId.equals(userId)) continue;
            Skill s = skills.get(c.skillId);
            if (s == null || !s.active) continue;
            int base = c.verified;
            if (includeClaimed && c.status != com.cecil.kaushal.model.ClaimStatus.VERIFIED) base = Math.max(base, c.claimed);
            int eff = Levels.effective(base, c.lastUsed, halfLife(s), today);
            if (eff > 0) out.put(c.skillId, eff);
        }
        return out;
    }

    /** Finds a skill by name, symbol or synonym (not case sensitive). */
    public synchronized Skill skillByName(String name) {
        String k = Text.key(name);
        if (k.isEmpty()) return null;
        for (Skill s : skills.values()) if (s.active && (Text.key(s.name).equals(k) || Text.key(s.symbol).equals(k))) return s;
        for (Skill s : skills.values()) if (s.active) for (String syn : s.synonyms) if (Text.key(syn).equals(k)) return s;
        return null;
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

    // ------------------------------------------------------------------ ids, log, notifications

    /** Next id for a kind of record, e.g. nextId("C", 1001) gives "C-1001", then "C-1002" ... */
    public synchronized String nextId(String prefix, long start) {
        long n = Json.num(meta, "next." + prefix, start);
        meta.put("next." + prefix, n + 1);
        return prefix + "-" + n;
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

    // ------------------------------------------------------------------ saving

    public static final String[] ALL = { "users", "categories", "skills", "claims", "endorsements", "certs", "roles",
            "goals", "projects", "notes", "snapshots", "audit" };

    public synchronized void saveAll() {
        batch = false;
        save(ALL);
    }

    /** Saves just the named collections (faster than saving everything after each small change). */
    public synchronized void save(String... names) {
        for (String n : names) {
            switch (n) {
                case "users" -> write("users.json", users.values(), true);
                case "categories" -> write("categories.json", categories.values(), true);
                case "skills" -> write("skills.json", skills.values(), true);
                case "claims" -> write("claims.json", claims.values(), true);
                case "endorsements" -> write("endorsements.json", endorsements.values(), true);
                case "certs" -> write("certs.json", certs.values(), true);
                case "roles" -> write("roles.json", roles.values(), true);
                case "goals" -> write("goals.json", goals.values(), true);
                case "projects" -> write("projects.json", projects.values(), true);
                case "notes" -> write("notes.json", notes.values(), false);
                case "snapshots" -> write("snapshots.json", snapshots.values(), false);
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
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) o;
    }
}
