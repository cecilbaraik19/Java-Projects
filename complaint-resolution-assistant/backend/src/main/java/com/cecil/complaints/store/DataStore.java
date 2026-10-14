package com.cecil.complaints.store;

import com.cecil.complaints.model.Complaint;
import com.cecil.complaints.model.Solution;
import com.cecil.complaints.model.User;
import com.cecil.complaints.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves everything as readable JSON files in a data folder:
 *
 *   data/complaints.json
 *   data/solutions.json
 *   data/users.json
 *
 * Files are written to a temp file first and then moved, so a crash never leaves a half-written file.
 * A DataStore created with a null folder keeps data in memory only (used by tests).
 */
public class DataStore {

    private final Path dir;
    private final Map<String, Complaint> complaints = new LinkedHashMap<>();
    private final Map<String, Solution> solutions = new LinkedHashMap<>();
    private final Map<String, User> users = new LinkedHashMap<>();

    public DataStore(Path dir) {
        this.dir = dir;
    }

    public static DataStore inMemory() {
        return new DataStore(null);
    }

    public boolean isEmpty() {
        return complaints.isEmpty() && solutions.isEmpty() && users.isEmpty();
    }

    // ---------------------------------------------------------------- access

    public Map<String, Complaint> complaints() {
        return complaints;
    }

    public Map<String, Solution> solutions() {
        return solutions;
    }

    public Map<String, User> users() {
        return users;
    }

    public synchronized String nextComplaintId() {
        int max = 1000;
        for (String id : complaints.keySet()) {
            max = Math.max(max, numberPart(id));
        }
        return "CMP-" + (max + 1);
    }

    public synchronized String nextSolutionId() {
        int max = 0;
        for (String id : solutions.keySet()) {
            max = Math.max(max, numberPart(id));
        }
        return String.format("SOL-%03d", max + 1);
    }

    private static int numberPart(String id) {
        try {
            return Integer.parseInt(id.substring(id.indexOf('-') + 1));
        } catch (Exception ex) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- load / save

    public synchronized void load() throws IOException {
        if (dir == null) {
            return;
        }
        Files.createDirectories(dir);
        for (Map<String, Object> m : readList("complaints.json")) {
            Complaint c = Complaint.fromMap(m);
            complaints.put(c.id, c);
        }
        for (Map<String, Object> m : readList("solutions.json")) {
            Solution s = Solution.fromMap(m);
            solutions.put(s.id, s);
        }
        for (Map<String, Object> m : readList("users.json")) {
            User u = User.fromMap(m);
            users.put(u.username, u);
        }
    }

    public synchronized void save() {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            List<Object> c = new ArrayList<>();
            complaints.values().forEach(x -> c.add(x.toMap()));
            write("complaints.json", c);
            List<Object> s = new ArrayList<>();
            solutions.values().forEach(x -> s.add(x.toMap()));
            write("solutions.json", s);
            List<Object> u = new ArrayList<>();
            users.values().forEach(x -> u.add(x.toMap()));
            write("users.json", u);
        } catch (IOException ex) {
            System.err.println("[Store] Could not save data: " + ex.getMessage());
        }
    }

    private List<Map<String, Object>> readList(String file) throws IOException {
        Path p = dir.resolve(file);
        if (!Files.exists(p)) {
            return List.of();
        }
        String text = Files.readString(p, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return List.of();
        }
        Map<String, Object> wrapper = Map.of("items", Json.parse(text));
        return Json.mapList(new LinkedHashMap<>(wrapper), "items");
    }

    private void write(String file, Object data) throws IOException {
        Path target = dir.resolve(file);
        Path tmp = dir.resolve(file + ".tmp");
        Files.writeString(tmp, Json.pretty(data), StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
