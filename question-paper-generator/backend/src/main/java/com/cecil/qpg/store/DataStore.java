package com.cecil.qpg.store;

import com.cecil.qpg.model.Blueprint;
import com.cecil.qpg.model.Event;
import com.cecil.qpg.model.Paper;
import com.cecil.qpg.model.Question;
import com.cecil.qpg.model.Settings;
import com.cecil.qpg.model.Subject;
import com.cecil.qpg.model.User;
import com.cecil.qpg.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps all data in memory and saves it as JSON files in the data folder.
 * Every change is written to a temporary file first and then moved over the old one,
 * so a crash never leaves a half-written file.
 * All services lock on this object (synchronized (store)) so two requests never change data at once.
 */
public class DataStore {
    public final Path dir;
    public final Clock clock;

    public final List<User> users = new ArrayList<>();
    public final List<Subject> subjects = new ArrayList<>();
    public final List<Question> questions = new ArrayList<>();
    public final List<Blueprint> blueprints = new ArrayList<>();
    public final List<Paper> papers = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();
    public Settings settings = new Settings();

    private int nextQuestion = 1, nextBlueprint = 1, nextPaper = 1, nextGroup = 1;
    private static final int MAX_EVENTS = 2000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public DataStore(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    /** Loads the files; if there are none yet, fills in the demo data. Returns true if demo data was created. */
    public synchronized boolean load() throws IOException {
        Files.createDirectories(dir);
        boolean fresh = !Files.exists(dir.resolve("users.json"));
        if (fresh) {
            SeedData.fill(this);
            saveAll();
            return true;
        }
        readList("users.json", users, User::fromJson);
        readList("subjects.json", subjects, Subject::fromJson);
        readList("questions.json", questions, Question::fromJson);
        readList("blueprints.json", blueprints, Blueprint::fromJson);
        readList("papers.json", papers, Paper::fromJson);
        readList("events.json", events, Event::fromJson);
        Path sp = dir.resolve("settings.json");
        if (Files.exists(sp)) settings = Settings.fromJson(Json.parseObject(Files.readString(sp, StandardCharsets.UTF_8)));
        for (Question q : questions) nextQuestion = Math.max(nextQuestion, num(q.id) + 1);
        for (Blueprint b : blueprints) nextBlueprint = Math.max(nextBlueprint, num(b.id) + 1);
        for (Paper p : papers) {
            nextPaper = Math.max(nextPaper, num(p.id) + 1);
            nextGroup = Math.max(nextGroup, num(p.groupId) + 1);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private <T> void readList(String file, List<T> into, Function<Map<String, Object>, T> fn) throws IOException {
        into.clear();
        Path p = dir.resolve(file);
        if (!Files.exists(p)) return;
        Object v = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
        if (v instanceof List<?> l) for (Object o : l) into.add(fn.apply((Map<String, Object>) o));
    }

    private static int num(String id) {
        if (id == null) return 0;
        String d = id.replaceAll("[^0-9]", "");
        try { return d.isEmpty() ? 0 : Integer.parseInt(d); } catch (NumberFormatException e) { return 0; }
    }

    // ------------------------------------------------------------------ ids and time

    public synchronized String newQuestionId() { return String.format("Q-%04d", nextQuestion++); }
    public synchronized String newBlueprintId() { return String.format("B-%03d", nextBlueprint++); }
    public synchronized String newPaperId() { return String.format("P-%04d", nextPaper++); }
    public synchronized String newGroupId() { return String.format("G-%03d", nextGroup++); }

    public String now() { return LocalDateTime.now(clock).format(TS); }
    public LocalDate today() { return LocalDate.now(clock); }

    // ------------------------------------------------------------------ finding

    public User user(String username) {
        if (username == null) return null;
        for (User u : users) if (u.username.equalsIgnoreCase(username)) return u;
        return null;
    }

    public Subject subject(String code) {
        if (code == null) return null;
        for (Subject s : subjects) if (s.code.equalsIgnoreCase(code)) return s;
        return null;
    }

    public Question question(String id) {
        for (Question q : questions) if (q.id.equals(id)) return q;
        return null;
    }

    public Blueprint blueprint(String id) {
        for (Blueprint b : blueprints) if (b.id.equals(id)) return b;
        return null;
    }

    public Paper paper(String id) {
        for (Paper p : papers) if (p.id.equals(id)) return p;
        return null;
    }

    public synchronized void log(String user, String action, String detail, String ref) {
        events.add(new Event(now(), user, action, detail, ref));
        if (events.size() > MAX_EVENTS) events.subList(0, events.size() - MAX_EVENTS).clear();
        save("events.json", events);
    }

    // ------------------------------------------------------------------ saving

    public synchronized void saveAll() {
        saveUsers(); saveSubjects(); saveQuestions(); saveBlueprints(); savePapers(); saveSettings();
        save("events.json", events);
    }

    public synchronized void saveUsers() { save("users.json", users); }
    public synchronized void saveSubjects() { save("subjects.json", subjects); }
    public synchronized void saveQuestions() { save("questions.json", questions); }
    public synchronized void saveBlueprints() { save("blueprints.json", blueprints); }
    public synchronized void savePapers() { save("papers.json", papers); }
    public synchronized void saveSettings() { save("settings.json", settings); }

    private void save(String file, Object data) {
        try {
            Path target = dir.resolve(file);
            Path tmp = dir.resolve(file + ".tmp");
            Files.writeString(tmp, Json.pretty(data), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + file + ": " + e.getMessage(), e);
        }
    }
}
