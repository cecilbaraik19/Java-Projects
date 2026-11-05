package com.cecil.sql.store;

import com.cecil.sql.ai.ExampleMemory;
import com.cecil.sql.db.*;
import com.cecil.sql.model.*;
import com.cecil.sql.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Saves everything as plain files in the data folder, so you can open them and look:
 *   users.json, history.json, saved.json, learned.json, settings.json
 *   databases/<id>/database.json  (title, owner, table schemas)  +  databases/<id>/<table>.csv  (rows)
 * Writes go to a temporary file first and are then moved into place, so a crash cannot leave half a file.
 */
public final class DataStore {
    public final Path dir;

    public DataStore(Path dir) {
        this.dir = dir;
        try { Files.createDirectories(dir.resolve("databases")); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    // ------------------------------------------------------------------ JSON files
    public synchronized void writeJson(String file, Object value) {
        write(dir.resolve(file), Json.write(value));
    }

    public synchronized Object readJson(String file) {
        Path p = dir.resolve(file);
        if (!Files.exists(p)) return null;
        try { return Json.parse(Files.readString(p, StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        catch (IllegalArgumentException e) {
            // a broken file is kept aside rather than lost
            try { Files.move(p, p.resolveSibling(file + ".broken-" + System.currentTimeMillis())); } catch (IOException ignored) { }
            return null;
        }
    }

    private static void write(Path p, String text) {
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private List<Map<String, Object>> list(String file) {
        Object v = readJson(file);
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) out.add(Json.obj(o));
        return out;
    }

    public List<User> loadUsers() { List<User> l = new ArrayList<>(); for (var m : list("users.json")) l.add(User.fromJson(m)); return l; }
    public void saveUsers(Collection<User> users) { writeJson("users.json", new ArrayList<>(users)); }

    public List<HistoryItem> loadHistory() { List<HistoryItem> l = new ArrayList<>(); for (var m : list("history.json")) l.add(HistoryItem.fromJson(m)); return l; }
    public void saveHistory(List<HistoryItem> h) { writeJson("history.json", h); }

    public List<SavedQuery> loadSaved() { List<SavedQuery> l = new ArrayList<>(); for (var m : list("saved.json")) l.add(SavedQuery.fromJson(m)); return l; }
    public void saveSaved(List<SavedQuery> s) { writeJson("saved.json", s); }

    public List<ExampleMemory.Example> loadExamples() {
        List<ExampleMemory.Example> l = new ArrayList<>();
        for (var m : list("learned.json")) l.add(ExampleMemory.Example.fromJson(m));
        return l;
    }
    public void saveExamples(List<ExampleMemory.Example> e) { writeJson("learned.json", e); }

    public Settings loadSettings() {
        Object v = readJson("settings.json");
        return v instanceof Map ? Settings.fromJson(Json.obj(v)) : new Settings();
    }
    public void saveSettings(Settings s) { writeJson("settings.json", s); }

    // ------------------------------------------------------------------ databases
    public synchronized void saveDatabase(Database db) {
        Path d = dir.resolve("databases").resolve(safe(db.id));
        List<Object> tables = new ArrayList<>();
        for (Table t : db.tables()) {
            tables.add(t.schema);
            write(d.resolve(safe(t.schema.name) + ".csv"), t.toCsv());
        }
        write(d.resolve("database.json"), Json.write(Json.map("id", db.id, "title", db.title, "description", db.description,
                "owner", db.owner, "tables", tables)));
    }

    public synchronized void deleteTableFile(Database db, String table) {
        try { Files.deleteIfExists(dir.resolve("databases").resolve(safe(db.id)).resolve(safe(table) + ".csv")); }
        catch (IOException ignored) { }
    }

    public synchronized List<Database> loadDatabases() {
        List<Database> out = new ArrayList<>();
        Path root = dir.resolve("databases");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            List<Path> dirs = new ArrayList<>();
            for (Path p : ds) if (Files.isDirectory(p)) dirs.add(p);
            // the college demo first, then the shop, then uploads
            dirs.sort(Comparator.comparing((Path x) -> x.getFileName().toString().equals("koel_college") ? 0
                    : x.getFileName().toString().startsWith("my_") ? 2 : 1).thenComparing(Path::toString));
            for (Path d : dirs) {
                Path meta = d.resolve("database.json");
                if (!Files.exists(meta)) continue;
                Map<String, Object> m = Json.parseObject(Files.readString(meta, StandardCharsets.UTF_8));
                Database db = new Database(Json.str(m, "id"), Json.str(m, "title"), Json.str(m, "description"), Json.str(m, "owner"));
                for (Object o : Json.list(m, "tables")) {
                    TableSchema s = TableSchema.fromJson(Json.obj(o));
                    Path csv = d.resolve(safe(s.name) + ".csv");
                    String text = Files.exists(csv) ? Files.readString(csv, StandardCharsets.UTF_8) : "";
                    db.add(Table.fromCsv(s, text));
                }
                out.add(db);
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return out;
    }

    /** Only letters, digits and _ in file names, so names cannot point outside the data folder. */
    public static String safe(String name) {
        String s = name.replaceAll("[^A-Za-z0-9_]", "_");
        return s.isEmpty() ? "_" : s;
    }
}
