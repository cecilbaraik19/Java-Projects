package com.cecil.energy.store;

import com.cecil.energy.model.Appliance;
import com.cecil.energy.model.Event;
import com.cecil.energy.model.MeterReading;
import com.cecil.energy.model.Room;
import com.cecil.energy.model.Settings;
import com.cecil.energy.model.TipRecord;
import com.cecil.energy.model.UsageLog;
import com.cecil.energy.model.User;
import com.cecil.energy.util.Json;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps all data in memory and saves it to JSON files in the data folder.
 *
 * Why JSON files and not a database? The project should run with only Java installed.
 * The files are small and easy to open and read.
 *
 * Thread safety: the web server handles requests on several threads, so every service
 * does its reading and changing inside synchronized (store.lock).
 *
 * Safe saving: each file is first written to "name.json.tmp" and then moved over the real file,
 * so a crash in the middle never leaves a half-written file.
 */
public class DataStore {

    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public final Object lock = new Object();
    private final Path dir;
    private final Clock clock;

    public final List<User> users = new ArrayList<>();
    public final List<Room> rooms = new ArrayList<>();
    public final List<Appliance> appliances = new ArrayList<>();
    public final List<UsageLog> usage = new ArrayList<>();
    public final List<MeterReading> meter = new ArrayList<>();
    public final List<TipRecord> tips = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();
    public Settings settings = new Settings();

    public DataStore(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    public Clock clock() { return clock; }
    public LocalDate today() { return LocalDate.now(clock); }
    public LocalDateTime now() { return LocalDateTime.now(clock).withNano(0); }
    public String nowText() { return now().format(TIME); }
    public Path dir() { return dir; }

    /** Loads every file. Returns false when there is no data yet (first run). */
    public boolean load() throws IOException {
        Files.createDirectories(dir);
        if (!Files.exists(dir.resolve("users.json"))) return false;
        synchronized (lock) {
            readList("users.json", User::fromMap, users);
            readList("rooms.json", Room::fromMap, rooms);
            readList("appliances.json", Appliance::fromMap, appliances);
            readList("usage.json", UsageLog::fromMap, usage);
            readList("meter.json", MeterReading::fromMap, meter);
            readList("tips.json", TipRecord::fromMap, tips);
            readList("events.json", Event::fromMap, events);
            Path s = dir.resolve("settings.json");
            if (Files.exists(s)) settings = Settings.fromMap(Json.parseObject(Files.readString(s, StandardCharsets.UTF_8)));
        }
        return !users.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private <T> void readList(String file, Function<Map<String, Object>, T> maker, List<T> into) throws IOException {
        into.clear();
        Path p = dir.resolve(file);
        if (!Files.exists(p)) return;
        Object data = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
        if (!(data instanceof List)) throw new IOException(file + " is not a JSON list");
        for (Object o : (List<Object>) data) {
            if (o instanceof Map) into.add(maker.apply((Map<String, Object>) o));
        }
    }

    // ------------------------------------------------------------------ saving

    public void saveAll() {
        saveUsers(); saveRooms(); saveAppliances(); saveUsage(); saveMeter(); saveTips(); saveEvents(); saveSettings();
    }

    public void saveUsers() { writeList("users.json", users, User::toMap); }
    public void saveRooms() { writeList("rooms.json", rooms, Room::toMap); }
    public void saveAppliances() { writeList("appliances.json", appliances, Appliance::toMap); }
    public void saveUsage() { writeList("usage.json", usage, UsageLog::toMap); }
    public void saveMeter() { writeList("meter.json", meter, MeterReading::toMap); }
    public void saveTips() { writeList("tips.json", tips, TipRecord::toMap); }
    public void saveEvents() { writeList("events.json", events, Event::toMap); }
    public void saveSettings() { writeText("settings.json", Json.pretty(settings.toMap())); }

    private <T> void writeList(String file, List<T> list, Function<T, Map<String, Object>> conv) {
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        synchronized (lock) {
            for (T t : list) out.add(conv.apply(t));
        }
        // usage.json can have thousands of rows, so it is written compact; the rest is easy to read
        writeText(file, file.equals("usage.json") ? Json.write(out) : Json.pretty(out));
    }

    private void writeText(String file, String text) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(file);
            Path tmp = dir.resolve(file + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + file + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ lookups

    public User user(String username) {
        if (username == null) return null;
        for (User u : users) if (u.username.equalsIgnoreCase(username)) return u;
        return null;
    }

    public Room room(String id) {
        for (Room r : rooms) if (r.id.equals(id)) return r;
        return null;
    }

    public Appliance appliance(String id) {
        for (Appliance a : appliances) if (a.id.equals(id)) return a;
        return null;
    }

    public String roomName(String id) {
        Room r = room(id);
        return r == null ? "No room" : r.name;
    }

    public String displayName(String username) {
        User u = user(username);
        return u == null ? (username == null ? "System" : username) : u.displayName;
    }

    /** Adds a history line (keeps the newest 600). Caller holds the lock and saves. */
    public void addEvent(String kind, String applianceId, String text, String by) {
        Event e = new Event();
        e.id = "ev" + com.cecil.energy.util.Passwords.randomId();
        e.at = nowText();
        e.kind = kind;
        e.applianceId = applianceId;
        e.text = text;
        e.by = by;
        events.add(0, e);
        while (events.size() > 600) events.remove(events.size() - 1);
    }

    /** Usage logs grouped by date (only dates in [from, to]). */
    public Map<String, List<UsageLog>> usageByDate(LocalDate from, LocalDate to) {
        Map<String, List<UsageLog>> m = new LinkedHashMap<>();
        String f = from.toString(), t = to.toString();
        for (UsageLog u : usage) {
            if (u.date.compareTo(f) >= 0 && u.date.compareTo(t) <= 0) m.computeIfAbsent(u.date, k -> new ArrayList<>()).add(u);
        }
        return m;
    }
}
