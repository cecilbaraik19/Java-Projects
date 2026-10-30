package com.cecil.ngo.store;

import com.cecil.ngo.model.Activity;
import com.cecil.ngo.model.Beneficiary;
import com.cecil.ngo.model.Donation;
import com.cecil.ngo.model.Donor;
import com.cecil.ngo.model.Event;
import com.cecil.ngo.model.Expense;
import com.cecil.ngo.model.HoursLog;
import com.cecil.ngo.model.Program;
import com.cecil.ngo.model.Settings;
import com.cecil.ngo.model.User;
import com.cecil.ngo.model.Volunteer;
import com.cecil.ngo.util.Json;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps all data in memory and saves it as JSON files in the data folder (one file per kind of record).
 * Every change is written to a temporary file first and then moved over the old one,
 * so a crash never leaves a half-written file.
 * All services lock on this object (synchronized (store)) so two requests never change data at once.
 */
public class DataStore {
    public final Path dir;
    public final Clock clock;

    public final List<User> users = new ArrayList<>();
    public final List<Program> programs = new ArrayList<>();
    public final List<Activity> activities = new ArrayList<>();
    public final List<Volunteer> volunteers = new ArrayList<>();
    public final List<HoursLog> hours = new ArrayList<>();
    public final List<Donor> donors = new ArrayList<>();
    public final List<Donation> donations = new ArrayList<>();
    public final List<Expense> expenses = new ArrayList<>();
    public final List<Beneficiary> beneficiaries = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();
    public Settings settings = new Settings();

    /** Next number for each kind of id, e.g. "A" -> 12 means the next activity is A-012. */
    private final Map<String, Integer> counters = new HashMap<>();
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
        readList("programs.json", programs, Program::fromJson);
        readList("activities.json", activities, Activity::fromJson);
        readList("volunteers.json", volunteers, Volunteer::fromJson);
        readList("hours.json", hours, HoursLog::fromJson);
        readList("donors.json", donors, Donor::fromJson);
        readList("donations.json", donations, Donation::fromJson);
        readList("expenses.json", expenses, Expense::fromJson);
        readList("beneficiaries.json", beneficiaries, Beneficiary::fromJson);
        readList("events.json", events, Event::fromJson);
        Path sp = dir.resolve("settings.json");
        if (Files.exists(sp)) settings = Settings.fromJson(Json.parseObject(Files.readString(sp, StandardCharsets.UTF_8)));
        for (Program x : programs) bump("P", x.id);
        for (Activity x : activities) bump("A", x.id);
        for (Volunteer x : volunteers) bump("V", x.id);
        for (HoursLog x : hours) bump("H", x.id);
        for (Donor x : donors) bump("D", x.id);
        for (Donation x : donations) bump("G", x.id);
        for (Expense x : expenses) bump("E", x.id);
        for (Beneficiary x : beneficiaries) bump("B", x.id);
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

    private void bump(String prefix, String id) {
        int n = num(id);
        counters.merge(prefix, n + 1, Math::max);
    }

    private static int num(String id) {
        if (id == null) return 0;
        String d = id.replaceAll("[^0-9]", "");
        try { return d.isEmpty() ? 0 : Integer.parseInt(d); } catch (NumberFormatException e) { return 0; }
    }

    // ------------------------------------------------------------------ ids and time

    /** A new id like "A-007". digits = how many digits to show. */
    public synchronized String newId(String prefix, int digits) {
        int n = counters.getOrDefault(prefix, 1);
        counters.put(prefix, n + 1);
        return prefix + "-" + String.format("%0" + digits + "d", n);
    }

    public String now() { return LocalDateTime.now(clock).format(TS); }
    public LocalDate today() { return LocalDate.now(clock); }

    // ------------------------------------------------------------------ finding

    public User user(String username) {
        if (username == null) return null;
        for (User u : users) if (u.username.equalsIgnoreCase(username)) return u;
        return null;
    }

    private static <T> T find(List<T> list, Function<T, String> id, String want) {
        if (want == null) return null;
        for (T t : list) if (want.equals(id.apply(t))) return t;
        return null;
    }

    public Program program(String id) { return find(programs, p -> p.id, id); }
    public Activity activity(String id) { return find(activities, a -> a.id, id); }
    public Volunteer volunteer(String id) { return find(volunteers, v -> v.id, id); }
    public HoursLog hoursLog(String id) { return find(hours, h -> h.id, id); }
    public Donor donor(String id) { return find(donors, d -> d.id, id); }
    public Donation donation(String id) { return find(donations, d -> d.id, id); }
    public Expense expense(String id) { return find(expenses, e -> e.id, id); }
    public Beneficiary beneficiary(String id) { return find(beneficiaries, b -> b.id, id); }

    public synchronized void log(String user, String action, String detail, String ref) {
        events.add(new Event(now(), user, action, detail, ref));
        if (events.size() > MAX_EVENTS) events.subList(0, events.size() - MAX_EVENTS).clear();
        save("events.json", events);
    }

    // ------------------------------------------------------------------ saving

    public synchronized void saveAll() {
        saveUsers(); savePrograms(); saveActivities(); saveVolunteers(); saveHours();
        saveDonors(); saveDonations(); saveExpenses(); saveBeneficiaries(); saveSettings();
        save("events.json", events);
    }

    public synchronized void saveUsers() { save("users.json", users); }
    public synchronized void savePrograms() { save("programs.json", programs); }
    public synchronized void saveActivities() { save("activities.json", activities); }
    public synchronized void saveVolunteers() { save("volunteers.json", volunteers); }
    public synchronized void saveHours() { save("hours.json", hours); }
    public synchronized void saveDonors() { save("donors.json", donors); }
    public synchronized void saveDonations() { save("donations.json", donations); }
    public synchronized void saveExpenses() { save("expenses.json", expenses); }
    public synchronized void saveBeneficiaries() { save("beneficiaries.json", beneficiaries); }
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
