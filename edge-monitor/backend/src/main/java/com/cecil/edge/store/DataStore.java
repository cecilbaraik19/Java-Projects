package com.cecil.edge.store;

import com.cecil.edge.model.Alert;
import com.cecil.edge.model.Command;
import com.cecil.edge.model.DayStat;
import com.cecil.edge.model.EdgeNode;
import com.cecil.edge.model.Event;
import com.cecil.edge.model.HourAgg;
import com.cecil.edge.model.Rule;
import com.cecil.edge.model.Sensor;
import com.cecil.edge.model.Settings;
import com.cecil.edge.model.Site;
import com.cecil.edge.model.User;
import com.cecil.edge.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Keeps all data in memory and saves it as JSON files in the data folder.
 * No database is needed. Each collection is one file, e.g. data/nodes.json.
 * Files are written to a temp file first and then moved into place, so a crash never leaves half a file.
 *
 * Not thread-safe on its own: the Hub calls it while holding one lock.
 */
public class DataStore {
    public final Path dir;
    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Site> sites = new LinkedHashMap<>();
    public final Map<String, EdgeNode> nodes = new LinkedHashMap<>();
    public final Map<String, Sensor> sensors = new LinkedHashMap<>();
    public final Map<String, Rule> rules = new LinkedHashMap<>();
    public final Map<String, Alert> alerts = new LinkedHashMap<>();
    public final Map<String, Command> commands = new LinkedHashMap<>();
    public final List<Event> events = new ArrayList<>();
    public final Map<String, DayStat> days = new LinkedHashMap<>();
    /** key = sensorId|hourStart */
    public final Map<String, HourAgg> hours = new LinkedHashMap<>();
    public Settings settings = new Settings();

    private final Set<String> dirty = new HashSet<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    public static final int MAX_EVENTS = 3000;
    public static final int MAX_ALERTS = 4000;
    public static final int MAX_COMMANDS = 600;

    public DataStore(Path dir) {
        this.dir = dir;
    }

    /** true when there was nothing saved yet (first run). */
    public boolean isEmpty() {
        return users.isEmpty();
    }

    // ------------------------------------------------------------------ load

    public void load() throws IOException {
        Files.createDirectories(dir);
        loadMap("users", User.class, users, u -> u.username);
        loadMap("sites", Site.class, sites, s -> s.id);
        loadMap("nodes", EdgeNode.class, nodes, n -> n.id);
        loadMap("sensors", Sensor.class, sensors, s -> s.id);
        loadMap("rules", Rule.class, rules, r -> r.id);
        loadMap("alerts", Alert.class, alerts, a -> a.id);
        loadMap("commands", Command.class, commands, c -> c.id);
        loadMap("days", DayStat.class, days, DayStat::key);
        loadMap("hours", HourAgg.class, hours, h -> h.sensorId + "|" + h.hour);
        events.clear();
        for (Map<String, Object> m : readList("events")) events.add(Json.fromMap(m, Event.class));
        Path sf = dir.resolve("settings.json");
        if (Files.exists(sf)) settings = Json.fromMap(Json.parseObject(Files.readString(sf, StandardCharsets.UTF_8)), Settings.class);
        dirty.clear();
    }

    private <T> void loadMap(String name, Class<T> type, Map<String, T> into, Function<T, String> key) throws IOException {
        into.clear();
        for (Map<String, Object> m : readList(name)) {
            T obj = Json.fromMap(m, type);
            into.put(key.apply(obj), obj);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readList(String name) throws IOException {
        Path f = dir.resolve(name + ".json");
        if (!Files.exists(f)) return List.of();
        Object v = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
        if (!(v instanceof List<?> l)) throw new IOException(f + " is not a JSON list");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) if (o instanceof Map) out.add((Map<String, Object>) o);
        return out;
    }

    // ------------------------------------------------------------------ save

    public void markDirty(String... names) {
        for (String n : names) dirty.add(n);
    }

    public boolean hasChanges() {
        return !dirty.isEmpty();
    }

    /** Writes only the collections that changed since the last save. */
    public void saveDirty() throws IOException {
        if (dirty.isEmpty()) return;
        Files.createDirectories(dir);
        Set<String> todo = new HashSet<>(dirty);
        dirty.clear();
        for (String n : todo) {
            Object data = switch (n) {
                case "users" -> users.values();
                case "sites" -> sites.values();
                case "nodes" -> nodes.values();
                case "sensors" -> sensors.values();
                case "rules" -> rules.values();
                case "alerts" -> alerts.values();
                case "commands" -> commands.values();
                case "events" -> events;
                case "days" -> days.values();
                case "hours" -> hours.values();
                case "settings" -> settings;
                default -> null;
            };
            if (data != null) write(n + ".json", Json.write(data));
        }
    }

    public void saveAll() throws IOException {
        markDirty("users", "sites", "nodes", "sensors", "rules", "alerts", "commands", "events", "days", "hours", "settings");
        saveDirty();
    }

    private void write(String file, String text) throws IOException {
        Path target = dir.resolve(file);
        Path tmp = dir.resolve(file + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ ids and history

    /** Next readable id such as "N-104". Starts after the highest number already used. */
    public String nextId(String prefix, Iterable<String> existing) {
        int max = counters.getOrDefault(prefix, 100);
        for (String id : existing) {
            if (id != null && id.startsWith(prefix + "-")) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(prefix.length() + 1)));
                } catch (NumberFormatException ignored) { /* not one of ours */ }
            }
        }
        counters.put(prefix, max + 1);
        return prefix + "-" + (max + 1);
    }

    public void addEvent(long at, String user, String kind, String nodeId, String text) {
        Event e = new Event();
        e.id = "E" + at + "-" + events.size();
        e.at = at;
        e.user = user;
        e.kind = kind;
        e.nodeId = nodeId;
        e.text = text;
        events.add(e);
        if (events.size() > MAX_EVENTS) events.subList(0, events.size() - MAX_EVENTS).clear();
        markDirty("events");
    }

    /** Drops the oldest resolved alerts and finished commands when the lists get long. */
    public void trim() {
        if (alerts.size() > MAX_ALERTS) {
            List<String> drop = new ArrayList<>();
            int extra = alerts.size() - MAX_ALERTS;
            for (Alert a : alerts.values()) {
                if (extra <= 0) break;
                if (a.status == com.cecil.edge.model.Enums.AlertStatus.RESOLVED) { drop.add(a.id); extra--; }
            }
            drop.forEach(alerts::remove);
            markDirty("alerts");
        }
        if (commands.size() > MAX_COMMANDS) {
            List<String> ids = new ArrayList<>(commands.keySet());
            for (int i = 0; i < ids.size() - MAX_COMMANDS; i++) {
                if (commands.get(ids.get(i)).status != com.cecil.edge.model.Enums.CommandStatus.PENDING) commands.remove(ids.get(i));
            }
            markDirty("commands");
        }
    }
}
