package com.cecil.sld.store;

import com.cecil.sld.model.*;
import com.cecil.sld.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Keeps all records in memory and saves them as JSON files in the data folder
 * (one file per kind of record). No database is needed.
 *
 * Every change goes through a service, which calls {@link #save(String...)}.
 * Files are written to a temp file first and then moved, so a crash in the
 * middle of a save never leaves a half-written file.
 */
public class DataStore {
    public final List<User> users = new ArrayList<>();
    public final List<Subject> subjects = new ArrayList<>();
    public final List<Topic> topics = new ArrayList<>();
    public final List<Question> questions = new ArrayList<>();
    public final List<Attempt> attempts = new ArrayList<>();
    public final List<SelfReport> reports = new ArrayList<>();
    public final List<SupportPlan> plans = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();
    public Settings settings = new Settings();

    private final Path dir;          // null = keep in memory only (used by tests)
    private final Clock clock;
    private final Map<String, Integer> counters = new HashMap<>();

    public DataStore(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    public Clock clock() { return clock; }
    public LocalDate today() { return LocalDate.now(clock); }
    public LocalDateTime now() { return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS); }
    public String nowText() { return now().toString(); }

    // ------------------------------------------------------------ load / save

    /** Read the data folder. If it is empty, fill it with demo data. */
    public synchronized void load() {
        if (dir != null && Files.exists(dir.resolve("users.json"))) {
            readList("users", User.class, users);
            readList("subjects", Subject.class, subjects);
            readList("topics", Topic.class, topics);
            readList("questions", Question.class, questions);
            readList("attempts", Attempt.class, attempts);
            readList("reports", SelfReport.class, reports);
            readList("plans", SupportPlan.class, plans);
            readList("events", Event.class, events);
            Path s = dir.resolve("settings.json");
            if (Files.exists(s)) settings = Json.bind(Json.parse(readFile(s)), Settings.class);
            rebuildCounters();
        } else {
            SeedData.fill(this);
            rebuildCounters();
            saveAll();
        }
    }

    public synchronized void saveAll() {
        save("users", "subjects", "topics", "questions", "attempts", "reports", "plans", "events", "settings");
    }

    /** Save the named files, e.g. save("attempts", "events"). */
    public synchronized void save(String... names) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            for (String n : names) {
                Object data = switch (n) {
                    case "users" -> users;
                    case "subjects" -> subjects;
                    case "topics" -> topics;
                    case "questions" -> questions;
                    case "attempts" -> attempts;
                    case "reports" -> reports;
                    case "plans" -> plans;
                    case "events" -> events;
                    case "settings" -> settings;
                    default -> throw new IllegalArgumentException("Unknown file " + n);
                };
                Path target = dir.resolve(n + ".json");
                Path tmp = dir.resolve(n + ".json.tmp");
                Files.writeString(tmp, Json.write(data), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not save data: " + e.getMessage(), e);
        }
    }

    private <T> void readList(String name, Class<T> type, List<T> into) {
        Path p = dir.resolve(name + ".json");
        if (!Files.exists(p)) return;
        Object parsed = Json.parse(readFile(p));
        if (parsed instanceof List<?> list) for (Object o : list) into.add(Json.bind(o, type));
    }

    private static String readFile(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + p + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ ids

    /** Next id with a prefix: "Q" -> Q0001, Q0002 ... (never reused). */
    public synchronized String nextId(String prefix) {
        int n = counters.getOrDefault(prefix, 0) + 1;
        counters.put(prefix, n);
        return prefix + String.format("%04d", n);
    }

    private void rebuildCounters() {
        counters.clear();
        List<String> ids = new ArrayList<>();
        users.forEach(x -> ids.add(x.id));
        subjects.forEach(x -> ids.add(x.id));
        topics.forEach(x -> ids.add(x.id));
        questions.forEach(x -> ids.add(x.id));
        attempts.forEach(x -> ids.add(x.id));
        reports.forEach(x -> ids.add(x.id));
        plans.forEach(x -> ids.add(x.id));
        events.forEach(x -> ids.add(x.id));
        for (String id : ids) {
            if (id == null) continue;
            int i = 0;
            while (i < id.length() && !Character.isDigit(id.charAt(i))) i++;
            if (i == 0 || i == id.length()) continue;
            try {
                int n = Integer.parseInt(id.substring(i));
                counters.merge(id.substring(0, i), n, Math::max);
            } catch (NumberFormatException ignored) { }
        }
    }

    // ------------------------------------------------------------ lookups

    public User user(String id) { return find(users, u -> u.id.equals(id)); }
    public User userByName(String username) { return find(users, u -> u.username.equalsIgnoreCase(username)); }
    public Subject subject(String id) { return find(subjects, s -> s.id.equals(id)); }
    public Topic topic(String id) { return find(topics, t -> t.id.equals(id)); }
    public Question question(String id) { return find(questions, q -> q.id.equals(id)); }
    public Attempt attempt(String id) { return find(attempts, a -> a.id.equals(id)); }
    public SupportPlan plan(String id) { return find(plans, p -> p.id.equals(id)); }

    public List<Topic> topicsOf(String subjectId) {
        List<Topic> out = new ArrayList<>();
        for (Topic t : topics) if (t.subjectId.equals(subjectId)) out.add(t);
        out.sort(Comparator.comparingInt((Topic t) -> t.unit).thenComparingInt(t -> t.order).thenComparing(t -> t.name));
        return out;
    }

    public List<User> students() {
        List<User> out = new ArrayList<>();
        for (User u : users) if (u.role == Role.STUDENT) out.add(u);
        out.sort(Comparator.comparing((User u) -> u.rollNo).thenComparing(u -> u.name));
        return out;
    }

    private static <T> T find(List<T> list, java.util.function.Predicate<T> p) {
        for (T t : list) if (p.test(t)) return t;
        return null;
    }

    /** Add a history line (kept to the newest 2000). Caller saves "events". */
    public synchronized void log(String user, String action, String detail) {
        Event e = new Event();
        e.id = nextId("E");
        e.at = nowText();
        e.user = user;
        e.action = action;
        e.detail = detail;
        events.add(e);
        while (events.size() > 2000) events.remove(0);
    }
}
