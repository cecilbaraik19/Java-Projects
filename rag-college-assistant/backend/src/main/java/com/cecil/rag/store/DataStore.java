package com.cecil.rag.store;

import com.cecil.rag.model.Doc;
import com.cecil.rag.model.QaRecord;
import com.cecil.rag.model.Settings;
import com.cecil.rag.model.User;
import com.cecil.rag.model.Verified;
import com.cecil.rag.util.Json;
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
 * Keeps everything in memory and saves it as JSON files in the data folder:
 * users.json, documents.json, questions.json, verified.json, settings.json, evaluation.json.
 * Files are written to a temp file first and then moved, so a crash never leaves half a file.
 * (No database is needed for a project this size; the files are easy to open and read.)
 */
public final class DataStore {
    private final Path dir;
    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Doc> docs = new LinkedHashMap<>();
    public final List<QaRecord> records = new ArrayList<>();
    public final Map<String, Verified> verified = new LinkedHashMap<>();
    public Settings settings = new Settings();
    public Map<String, Object> evaluation = new LinkedHashMap<>();   // eval questions + last results
    private long counter;

    public DataStore(Path dir) { this.dir = dir; }

    public Path dir() { return dir; }

    public boolean exists() { return Files.exists(dir.resolve("documents.json")); }

    public synchronized void load() throws IOException {
        Files.createDirectories(dir);
        users.clear(); docs.clear(); records.clear(); verified.clear();
        for (Object o : readList("users.json")) users.put(((User) o).id, (User) o);
        for (Object o : readList("documents.json")) docs.put(((Doc) o).id, (Doc) o);
        for (Object o : readList("questions.json")) records.add((QaRecord) o);
        for (Object o : readList("verified.json")) verified.put(((Verified) o).id, (Verified) o);
        Path s = dir.resolve("settings.json");
        if (Files.exists(s)) {
            Map<String, Object> m = Json.parseObject(Files.readString(s, StandardCharsets.UTF_8));
            settings = Settings.from(Json.obj(m, "settings"));
            counter = Json.num(m, "counter", 1000);
        }
        Path e = dir.resolve("evaluation.json");
        if (Files.exists(e)) evaluation = Json.parseObject(Files.readString(e, StandardCharsets.UTF_8));
    }

    private List<Object> readList(String name) throws IOException {
        Path p = dir.resolve(name);
        List<Object> out = new ArrayList<>();
        if (!Files.exists(p)) return out;
        Function<Map<String, Object>, Object> maker = switch (name) {
            case "users.json" -> User::from;
            case "documents.json" -> Doc::from;
            case "questions.json" -> QaRecord::from;
            default -> Verified::from;
        };
        for (Object o : (List<?>) Json.parse(Files.readString(p, StandardCharsets.UTF_8))) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            out.add(maker.apply(m));
        }
        return out;
    }

    public synchronized String nextId(String prefix) {
        counter++;
        return prefix + counter;
    }

    // --------------------------------------------------------------- saving (each part separately)

    public synchronized void saveUsers() { write("users.json", new ArrayList<>(users.values())); }
    public synchronized void saveDocs() { write("documents.json", new ArrayList<>(docs.values())); }
    public synchronized void saveRecords() { write("questions.json", records); }
    public synchronized void saveVerified() { write("verified.json", new ArrayList<>(verified.values())); }
    public synchronized void saveSettings() { write("settings.json", Json.map("settings", settings, "counter", counter)); }
    public synchronized void saveEvaluation() { write("evaluation.json", evaluation); }

    public synchronized void saveAll() {
        saveUsers(); saveDocs(); saveRecords(); saveVerified(); saveSettings(); saveEvaluation();
    }

    /** While true nothing is written (used when the demo data is being made; saved once at the end). */
    public volatile boolean pauseSaving;

    private void write(String name, Object value) {
        if (pauseSaving) return;
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(name + ".tmp");
            Files.writeString(tmp, Json.pretty(value), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.writeString(dir.resolve(name), Json.pretty(value), StandardCharsets.UTF_8);
            } catch (IOException e2) {
                System.err.println("Could not save " + name + ": " + e2.getMessage());
            }
        }
    }

    public User userByName(String username) {
        for (User u : users.values()) if (u.username.equalsIgnoreCase(username)) return u;
        return null;
    }
}
