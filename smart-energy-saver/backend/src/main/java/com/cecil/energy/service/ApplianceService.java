package com.cecil.energy.service;

import com.cecil.energy.insights.EnergyMath;
import com.cecil.energy.model.Appliance;
import com.cecil.energy.model.Enums;
import com.cecil.energy.model.Enums.ApplianceType;
import com.cecil.energy.model.Enums.UsageSource;
import com.cecil.energy.model.Event;
import com.cecil.energy.model.Room;
import com.cecil.energy.model.Settings;
import com.cecil.energy.model.UsageLog;
import com.cecil.energy.model.User;
import com.cecil.energy.store.DataStore;
import com.cecil.energy.util.Json;
import com.cecil.energy.util.Num;
import com.cecil.energy.util.Passwords;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rooms, appliances and the live on/off switch.
 *
 * Switching an appliance on stores the time. Switching it off works out how long it ran
 * (split by day and by peak / off-peak hours) and adds that to the day's usage record.
 * This is how a real smart plug measures energy.
 */
public class ApplianceService {

    private final DataStore store;

    public ApplianceService(DataStore store) {
        this.store = store;
    }

    // ================================================================== rooms

    public List<Map<String, Object>> rooms() {
        synchronized (store.lock) {
            List<Room> list = new ArrayList<>(store.rooms);
            list.sort(Comparator.comparingInt((Room r) -> r.sortOrder).thenComparing(r -> r.name));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Room r : list) {
                Map<String, Object> m = r.toMap();
                int n = 0;
                for (Appliance a : store.appliances) if (r.id.equals(a.roomId)) n++;
                m.put("applianceCount", n);
                out.add(m);
            }
            return out;
        }
    }

    public Map<String, Object> saveRoom(User me, String id, Map<String, Object> body) {
        AuthService.requireManager(me);
        String name = AuthService.clean(Json.str(body, "name", ""), 40);
        if (name.isEmpty()) throw ServiceException.bad("Enter a room name.");
        synchronized (store.lock) {
            for (Room r : store.rooms) {
                if (r.name.equalsIgnoreCase(name) && !r.id.equals(id)) throw ServiceException.conflict("A room with that name already exists.");
            }
            Room r;
            if (id == null) {
                r = new Room();
                r.id = "rm" + Passwords.randomId();
                r.sortOrder = store.rooms.size() + 1;
                store.rooms.add(r);
            } else {
                r = store.room(id);
                if (r == null) throw ServiceException.notFound("No such room.");
            }
            r.name = name;
            if (body.containsKey("sortOrder")) r.sortOrder = Json.integer(body, "sortOrder", r.sortOrder);
            store.saveRooms();
            return r.toMap();
        }
    }

    public void deleteRoom(User me, String id) {
        AuthService.requireManager(me);
        synchronized (store.lock) {
            Room r = store.room(id);
            if (r == null) throw ServiceException.notFound("No such room.");
            for (Appliance a : store.appliances) {
                if (id.equals(a.roomId)) throw ServiceException.conflict("Move or delete the appliances in this room first.");
            }
            store.rooms.remove(r);
            store.saveRooms();
        }
    }

    // ================================================================== appliances

    /** Basic appliance info for lists and the live page. Caller holds the lock. */
    public Map<String, Object> view(Appliance a) {
        Map<String, Object> m = a.toMap();
        m.put("typeLabel", a.type.label);
        m.put("roomName", store.roomName(a.roomId));
        m.put("shiftable", a.type.shiftable);
        Settings s = store.settings;
        EnergyMath.Day d = EnergyMath.profileDay(a, s);
        m.put("profileKwhPerDay", Num.round(d.total(), 3));
        if (a.on && a.onSince != null) {
            LocalDateTime since = LocalDateTime.parse(a.onSince, DataStore.TIME);
            long mins = Math.max(0, Duration.between(since, store.now()).toMinutes());
            m.put("onMinutes", mins);
            m.put("onByName", store.displayName(a.onBy));
            if (a.autoOffMinutes > 0) m.put("autoOffIn", Math.max(0, a.autoOffMinutes - mins));
        }
        return m;
    }

    public List<Map<String, Object>> list(String roomId, String type, String q) {
        synchronized (store.lock) {
            List<Map<String, Object>> out = new ArrayList<>();
            String needle = q == null ? "" : q.trim().toLowerCase();
            for (Appliance a : store.appliances) {
                if (roomId != null && !roomId.isEmpty() && !roomId.equals(a.roomId)) continue;
                if (type != null && !type.isEmpty() && !type.equals(a.type.name())) continue;
                if (!needle.isEmpty() && !(a.name.toLowerCase().contains(needle) || a.type.label.toLowerCase().contains(needle)
                        || store.roomName(a.roomId).toLowerCase().contains(needle))) continue;
                out.add(view(a));
            }
            return out;
        }
    }

    public Map<String, Object> create(User me, Map<String, Object> body) {
        AuthService.requireManager(me);
        synchronized (store.lock) {
            Appliance a = new Appliance();
            a.id = "ap" + Passwords.randomId();
            a.createdAt = store.nowText();
            fill(a, body, true);
            store.appliances.add(a);
            store.addEvent("EDIT", a.id, "Added appliance " + a.name, me.username);
            store.saveAppliances();
            store.saveEvents();
            return view(a);
        }
    }

    public Map<String, Object> update(User me, String id, Map<String, Object> body) {
        AuthService.requireManager(me);
        synchronized (store.lock) {
            Appliance a = store.appliance(id);
            if (a == null) throw ServiceException.notFound("No such appliance.");
            fill(a, body, false);
            store.addEvent("EDIT", a.id, "Changed settings of " + a.name, me.username);
            store.saveAppliances();
            store.saveEvents();
            return view(a);
        }
    }

    public void delete(User me, String id) {
        AuthService.requireManager(me);
        synchronized (store.lock) {
            Appliance a = store.appliance(id);
            if (a == null) throw ServiceException.notFound("No such appliance.");
            store.appliances.remove(a);
            store.usage.removeIf(u -> u.applianceId.equals(id));
            store.tips.removeIf(t -> t.key.endsWith(":" + id));
            store.addEvent("EDIT", null, "Deleted appliance " + a.name + " and its usage history", me.username);
            store.saveAll();
        }
    }

    /** Reads and checks the form fields. */
    private void fill(Appliance a, Map<String, Object> b, boolean isNew) {
        if (isNew || b.containsKey("name")) {
            String n = AuthService.clean(Json.str(b, "name", ""), 60);
            if (n.isEmpty()) throw ServiceException.bad("Enter the appliance name.");
            a.name = n;
        }
        if (isNew || b.containsKey("type")) a.type = Enums.parse(ApplianceType.class, Json.str(b, "type"), ApplianceType.OTHER);
        if (isNew || b.containsKey("roomId")) {
            String r = Json.str(b, "roomId");
            if (r == null || store.room(r) == null) throw ServiceException.bad("Choose a room.");
            a.roomId = r;
        }
        if (isNew || b.containsKey("watts")) {
            double w = Json.num(b, "watts", -1);
            if (w <= 0 || w > 20000) throw ServiceException.bad("Power must be between 1 and 20,000 watts.");
            a.watts = w;
        }
        if (isNew || b.containsKey("standbyWatts")) {
            double w = Json.num(b, "standbyWatts", 0);
            if (w < 0 || w > 200) throw ServiceException.bad("Standby power must be between 0 and 200 watts.");
            a.standbyWatts = w;
        }
        if (isNew || b.containsKey("quantity")) {
            int q = Json.integer(b, "quantity", 1);
            if (q < 1 || q > 100) throw ServiceException.bad("Quantity must be between 1 and 100.");
            a.quantity = q;
        }
        if (isNew || b.containsKey("starRating")) {
            int st = Json.integer(b, "starRating", 0);
            if (st < 0 || st > 5) throw ServiceException.bad("Star rating must be 0 (none) to 5.");
            a.starRating = st;
        }
        if (isNew || b.containsKey("hoursPerDay")) {
            double h = Json.num(b, "hoursPerDay", -1);
            if (h < 0 || h > 24) throw ServiceException.bad("Hours per day must be between 0 and 24.");
            a.hoursPerDay = h;
        }
        if (isNew || b.containsKey("usualStartHour")) {
            int h = Json.integer(b, "usualStartHour", 19);
            if (h < 0 || h > 23) throw ServiceException.bad("Start hour must be 0 to 23.");
            a.usualStartHour = h;
        }
        if (isNew || b.containsKey("setTemp")) {
            int t = Json.integer(b, "setTemp", 0);
            if (a.type != ApplianceType.AIR_CONDITIONER) t = 0;
            else if (t < 16 || t > 30) throw ServiceException.bad("AC temperature must be between 16 and 30 °C.");
            a.setTemp = t;
        }
        if (isNew || b.containsKey("autoOffMinutes")) {
            int m = Json.integer(b, "autoOffMinutes", 0);
            if (m < 0 || m > 1440) throw ServiceException.bad("Auto-off must be 0 (never) to 1440 minutes.");
            a.autoOffMinutes = m;
        }
        if (isNew || b.containsKey("notes")) a.notes = AuthService.clean(Json.str(b, "notes", ""), 300);
    }

    // ================================================================== live on / off

    /** Switches an appliance on or off. Anyone signed in can do this. */
    public Map<String, Object> setPower(User me, String id, boolean on) {
        synchronized (store.lock) {
            Appliance a = store.appliance(id);
            if (a == null) throw ServiceException.notFound("No such appliance.");
            if (a.on == on) return view(a);
            if (on) {
                a.on = true;
                a.onSince = store.nowText();
                a.onBy = me.username;
                store.addEvent("ON", a.id, me.displayName + " switched on " + a.name, me.username);
            } else {
                double hours = switchOff(a, store.now(), me.username);
                store.addEvent("OFF", a.id, me.displayName + " switched off " + a.name + " after " + durationText(hours), me.username);
            }
            store.saveAppliances();
            store.saveUsage();
            store.saveEvents();
            return view(a);
        }
    }

    /** Turns every running appliance off (for "Leaving home" button). Returns how many were switched off. */
    public int allOff(User me, boolean keepAlwaysOn) {
        synchronized (store.lock) {
            int n = 0;
            for (Appliance a : store.appliances) {
                if (!a.on) continue;
                if (keepAlwaysOn && a.hoursPerDay >= 20) continue; // fridge, router
                switchOff(a, store.now(), me.username);
                n++;
            }
            if (n > 0) {
                store.addEvent("OFF", null, me.displayName + " used \"Leaving home\": " + n + " appliance(s) switched off", me.username);
                store.saveAppliances();
                store.saveUsage();
                store.saveEvents();
            }
            return n;
        }
    }

    /** Records the running time into usage and marks it off. Returns hours run. Caller holds the lock. */
    double switchOff(Appliance a, LocalDateTime at, String by) {
        LocalDateTime since = a.onSince == null ? at : LocalDateTime.parse(a.onSince, DataStore.TIME);
        Map<LocalDate, double[]> parts = EnergyMath.splitByDay(since, at, store.settings);
        double total = 0;
        for (Map.Entry<LocalDate, double[]> e : parts.entrySet()) {
            addRunTime(a, e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], by);
            total += e.getValue()[0];
        }
        a.on = false;
        a.onSince = null;
        a.onBy = null;
        return total;
    }

    /** Adds running hours to the day's usage record (creates it if missing). */
    void addRunTime(Appliance a, LocalDate date, double hours, double peak, double offPeak, String by) {
        UsageLog log = null;
        String d = date.toString();
        for (UsageLog u : store.usage) {
            if (u.applianceId.equals(a.id) && u.date.equals(d)) { log = u; break; }
        }
        if (log == null) {
            log = new UsageLog();
            log.id = "us" + Passwords.randomId();
            log.applianceId = a.id;
            log.date = d;
            log.by = by;
            store.usage.add(log);
        }
        if (log.source == UsageSource.ESTIMATE) {
            // a real measurement replaces a guess
            log.hours = 0; log.peakHours = 0; log.offPeakHours = 0;
        }
        log.source = UsageSource.LIVE;
        log.hours = Num.round(Math.min(24, log.hours + hours), 3);
        log.peakHours = Num.round(Math.min(log.hours, log.peakHours + peak), 3);
        log.offPeakHours = Num.round(Math.min(log.hours, log.offPeakHours + offPeak), 3);
        log.kwh = Num.round(EnergyMath.runningKwh(a.watts, log.hours), 4);
        log.standbyKwh = Num.round(EnergyMath.standbyKwh(a.standbyWatts, log.hours), 4);
    }

    /**
     * Runs every minute: switches off appliances whose auto-off time has passed (like a smart plug timer).
     * Returns the names of the appliances switched off.
     */
    public List<String> autoOffSweep() {
        List<String> done = new ArrayList<>();
        synchronized (store.lock) {
            LocalDateTime now = store.now();
            for (Appliance a : store.appliances) {
                if (!a.on || a.autoOffMinutes <= 0 || a.onSince == null) continue;
                LocalDateTime since = LocalDateTime.parse(a.onSince, DataStore.TIME);
                LocalDateTime offAt = since.plusMinutes(a.autoOffMinutes);
                if (!offAt.isAfter(now)) {
                    switchOff(a, offAt, "system");
                    store.addEvent("AUTO_OFF", a.id, "Auto-off: " + a.name + " switched off after " + a.autoOffMinutes + " minutes", "system");
                    done.add(a.name);
                }
            }
            if (!done.isEmpty()) {
                store.saveAppliances();
                store.saveUsage();
                store.saveEvents();
            }
        }
        return done;
    }

    public List<Map<String, Object>> events(String applianceId, int limit) {
        synchronized (store.lock) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Event e : store.events) {
                if (applianceId != null && !applianceId.equals(e.applianceId)) continue;
                Map<String, Object> m = e.toMap();
                m.put("byName", store.displayName(e.by));
                out.add(m);
                if (out.size() >= limit) break;
            }
            return out;
        }
    }

    static String durationText(double hours) {
        long mins = Math.round(hours * 60);
        if (mins < 60) return mins + " min";
        return (mins / 60) + " h " + (mins % 60) + " min";
    }

    public static Map<String, Object> idName(String id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }
}
