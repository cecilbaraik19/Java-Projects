package com.cecil.energy.store;

import com.cecil.energy.insights.EnergyMath;
import com.cecil.energy.model.Appliance;
import com.cecil.energy.model.Enums.ApplianceType;
import com.cecil.energy.model.Enums.Role;
import com.cecil.energy.model.Enums.TipStatus;
import com.cecil.energy.model.Enums.UsageSource;
import com.cecil.energy.model.MeterReading;
import com.cecil.energy.model.Room;
import com.cecil.energy.model.Settings;
import com.cecil.energy.model.TipRecord;
import com.cecil.energy.model.UsageLog;
import com.cecil.energy.model.User;
import com.cecil.energy.util.Num;
import com.cecil.energy.util.Passwords;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Demo data for the first run: a family home with 7 rooms, 28 appliances and 6 months of daily usage.
 * All names are made up. The numbers follow the seasons (AC in summer, geyser in winter),
 * weekends, and a fridge that started using more power last week (so the app has something to warn about).
 */
public final class SeedData {

    static final int HISTORY_DAYS = 182;

    private SeedData() { }

    public static void fill(DataStore s) {
        synchronized (s.lock) {
            LocalDate today = s.today();
            LocalDateTime now = s.now();
            String created = today.minusDays(HISTORY_DAYS + 5) + "T09:00:00";

            user(s, "admin", "Site Admin", Role.ADMIN, "admin123", created);
            user(s, "meera", "Meera Sharma", Role.MANAGER, "manager123", created);
            user(s, "rohan", "Rohan Verma", Role.MEMBER, "member123", created);
            user(s, "ananya", "Ananya Verma", Role.MEMBER, "member123", created);

            Settings st = new Settings();
            st.homeName = "Verma Family Home";
            st.monthlyTargetKwh = 500;
            st.monthlyBudget = 4500;
            s.settings = st;

            room(s, "rm1", "Living room", 1);
            room(s, "rm2", "Kitchen", 2);
            room(s, "rm3", "Master bedroom", 3);
            room(s, "rm4", "Kids' room", 4);
            room(s, "rm5", "Study", 5);
            room(s, "rm6", "Bathroom", 6);
            room(s, "rm7", "Utility & outdoor", 7);

            int m = today.getMonthValue();
            //        id     name                          type                          room   watts  stby qty star hours start temp auto
            app(s, "ap01", "Split AC (1.5 ton)", ApplianceType.AIR_CONDITIONER, "rm1", 1450, 3, 1, 3, 1.5 * ac(m), 21, 22, 0, created);
            app(s, "ap02", "LED TV 43\"", ApplianceType.TELEVISION, "rm1", 85, 1, 1, 4, 4, 19, 0, 0, created);
            app(s, "ap03", "Set-top box", ApplianceType.SET_TOP_BOX, "rm1", 15, 12, 1, 0, 4, 19, 0, 0, created);
            app(s, "ap04", "Ceiling fans", ApplianceType.FAN, "rm1", 150, 0, 2, 0, 8 * fan(m), 13, 0, 0, created);
            app(s, "ap05", "LED lights", ApplianceType.LIGHT, "rm1", 36, 0, 4, 0, 5, 18, 0, 0, created);
            app(s, "ap06", "Wi-Fi router", ApplianceType.ROUTER, "rm1", 10, 0, 1, 0, 24, 0, 0, 0, created);
            app(s, "ap07", "Refrigerator (250 L)", ApplianceType.REFRIGERATOR, "rm2", 45, 0, 1, 2, 24, 0, 0, 0, created);
            app(s, "ap08", "Microwave oven", ApplianceType.MICROWAVE, "rm2", 1200, 3, 1, 0, 0.25, 20, 0, 0, created);
            app(s, "ap09", "Induction cooktop", ApplianceType.COOKTOP, "rm2", 1800, 0, 1, 0, 0.8, 7, 0, 0, created);
            app(s, "ap10", "Mixer grinder", ApplianceType.MIXER, "rm2", 750, 0, 1, 0, 0.15, 8, 0, 0, created);
            app(s, "ap11", "RO water purifier", ApplianceType.WATER_PURIFIER, "rm2", 36, 4, 1, 0, 2, 6, 0, 0, created);
            app(s, "ap12", "Tube lights", ApplianceType.LIGHT, "rm2", 80, 0, 2, 0, 5, 18, 0, 0, created);
            app(s, "ap13", "Window AC (1.5 ton)", ApplianceType.AIR_CONDITIONER, "rm3", 1650, 2, 1, 2, 2 * ac(m), 22, 20, 0, created);
            app(s, "ap14", "BLDC ceiling fan", ApplianceType.FAN, "rm3", 28, 0, 1, 5, 9 * fan(m), 22, 0, 0, created);
            app(s, "ap15", "LED lights", ApplianceType.LIGHT, "rm3", 27, 0, 3, 0, 4, 19, 0, 0, created);
            app(s, "ap16", "Ceiling fan", ApplianceType.FAN, "rm4", 75, 0, 1, 0, 8 * fan(m), 21, 0, 0, created);
            app(s, "ap17", "CFL study lamps", ApplianceType.LIGHT, "rm4", 46, 0, 2, 0, 3, 18, 0, 0, created);
            app(s, "ap18", "Gaming console", ApplianceType.GAMING, "rm4", 140, 10, 1, 0, 1.5, 18, 0, 0, created);
            app(s, "ap19", "Desktop computer", ApplianceType.COMPUTER, "rm5", 180, 4, 1, 0, 6, 10, 0, 0, created);
            app(s, "ap20", "Laptop charger", ApplianceType.LAPTOP, "rm5", 65, 0.5, 1, 0, 4, 14, 0, 0, created);
            app(s, "ap21", "Laser printer", ApplianceType.PRINTER, "rm5", 400, 6, 1, 0, 0.1, 11, 0, 0, created);
            app(s, "ap22", "LED lights", ApplianceType.LIGHT, "rm5", 18, 0, 2, 0, 5, 18, 0, 0, created);
            app(s, "ap23", "Geyser (25 L)", ApplianceType.WATER_HEATER, "rm6", 2000, 0, 1, 4, 1.0 * geyser(m), 6, 0, 0, created);
            app(s, "ap24", "Exhaust fan", ApplianceType.FAN, "rm6", 40, 0, 1, 0, 1, 7, 0, 0, created);
            app(s, "ap25", "Washing machine", ApplianceType.WASHING_MACHINE, "rm7", 500, 2, 1, 5, 1, 19, 0, 0, created);
            app(s, "ap26", "Water pump (1 HP)", ApplianceType.WATER_PUMP, "rm7", 750, 0, 1, 0, 0.75, 18, 0, 0, created);
            app(s, "ap27", "Electric iron", ApplianceType.IRON, "rm7", 1000, 0, 1, 0, 0.3, 8, 0, 0, created);
            app(s, "ap28", "EV scooter charger", ApplianceType.EV_CHARGER, "rm7", 600, 0, 1, 0, 2.5, 19, 0, 0, created);
            s.appliance("ap01").notes = "Living room AC. Usually on after dinner.";
            s.appliance("ap07").notes = "Bought in 2014.";

            // ---- 6 months of daily usage
            Random rnd = new Random(42);
            Map<String, Double> meterDaily = new HashMap<>();
            for (int i = HISTORY_DAYS; i >= 1; i--) {
                LocalDate d = today.minusDays(i);
                double dayTotal = 0;
                for (Appliance a : s.appliances) {
                    double h = hoursOn(a, d, today, rnd);
                    EnergyMath.Day day = EnergyMath.day(a, st, h, a.usualStartHour);
                    if (a.id.equals("ap07") && i <= 7) day.kwh *= 1.45; // fridge door seal problem last week
                    UsageLog u = EnergyMath.toLog("us" + Passwords.randomId(), a, d, day, UsageSource.DEMO, "system");
                    s.usage.add(u);
                    dayTotal += u.totalKwh();
                }
                meterDaily.put(d.toString(), dayTotal + 0.5 + rnd.nextDouble() * 0.3); // a little untracked use (chargers etc.)
            }

            // ---- today so far, and appliances that are on right now
            double nowHour = now.getHour() + now.getMinute() / 60.0;
            for (Appliance a : s.appliances) {
                if (a.hoursPerDay >= 23.9) {
                    a.on = true;
                    a.onSince = today.atStartOfDay().format(DataStore.TIME);
                    a.onBy = "system";
                    continue;
                }
                double start = a.usualStartHour, end = start + a.hoursPerDay;
                if (a.hoursPerDay > 0 && nowHour >= start && nowHour < end && a.type != ApplianceType.WASHING_MACHINE) {
                    a.on = true;
                    a.onSince = today.atTime(a.usualStartHour, 0).format(DataStore.TIME);
                    a.onBy = "ananya";
                    continue;
                }
                double ran = Math.max(0, Math.min(end, nowHour) - start);
                if (ran > 0.01) {
                    EnergyMath.Day day = EnergyMath.day(a, st, ran, a.usualStartHour);
                    day.standbyKwh = EnergyMath.standbyKwh(a.standbyWatts, ran) * nowHour / 24.0;
                    s.usage.add(EnergyMath.toLog("us" + Passwords.randomId(), a, today, day, UsageSource.DEMO, "system"));
                }
            }

            // ---- weekly meter readings (taken in the morning)
            double reading = 18450.0;
            for (int i = HISTORY_DAYS; i >= 0; i--) {
                LocalDate d = today.minusDays(i);
                if (i % 7 == 2 || i == HISTORY_DAYS) {
                    MeterReading r = new MeterReading();
                    r.id = "mr" + Passwords.randomId();
                    r.date = d.toString();
                    r.reading = Num.round(reading, 1);
                    r.by = i % 14 == 2 ? "rohan" : "meera";
                    s.meter.add(r);
                }
                reading += meterDaily.getOrDefault(d.toString(), 0.0);
            }

            // ---- one tip that was applied earlier: study lights changed to LED
            TipRecord tr = new TipRecord();
            tr.key = "LED:ap22";
            tr.title = "Change Study lights to LED";
            tr.status = TipStatus.APPLIED;
            tr.at = today.minusDays(45) + "T18:20:00";
            tr.by = "meera";
            tr.kwhPerMonth = 4.2;
            tr.rupeesPerMonth = 34;
            s.tips.add(tr);

            s.addEvent("TIP", "ap22", "Meera Sharma applied tip \"Change Study lights to LED\" (changed to 9 W LED)", "meera");
            s.events.get(0).at = tr.at;
            s.addEvent("SETTINGS", null, "Meera Sharma set the monthly target to 500 units and budget to ₹4,500", "meera");
            s.events.get(0).at = today.minusDays(20) + "T10:05:00";
            s.addEvent("OFF", "ap13", "Rohan Verma switched off Window AC (1.5 ton) after 2 h 40 min", "rohan");
            s.events.get(0).at = today.minusDays(1) + "T00:40:00";
            s.addEvent("AUTO_OFF", "ap23", "Auto-off: Geyser (25 L) switched off after 45 minutes", "system");
            s.events.get(0).at = today.minusDays(1) + "T06:45:00";
            s.events.sort((x, y) -> y.at.compareTo(x.at));

            s.saveAll();
        }
    }

    /** Hours an appliance ran on a given day: its normal hours, changed by season, weekends and a little randomness. */
    static double hoursOn(Appliance a, LocalDate d, LocalDate today, Random rnd) {
        int m = d.getMonthValue(), cm = today.getMonthValue();
        boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
        double base = a.hoursPerDay;
        switch (a.type) {
            case AIR_CONDITIONER -> base = baseHours(a) * ac(m);
            case FAN -> base = a.id.equals("ap24") ? base : baseHours(a) * fan(m);
            case WATER_HEATER -> base = 1.0 * geyser(m);
            case REFRIGERATOR, ROUTER -> { return 24; }
            default -> { }
        }
        if (a.id.equals("ap25")) { // washing machine: about 4 days a week, more on weekends
            return (weekend || rnd.nextDouble() < 0.45) ? Num.round(0.8 + rnd.nextDouble() * 0.5, 2) : 0;
        }
        if (a.type == ApplianceType.PRINTER || a.type == ApplianceType.MICROWAVE || a.type == ApplianceType.IRON) {
            if (rnd.nextDouble() < 0.25) return 0;
        }
        if (weekend) {
            if (a.type == ApplianceType.TELEVISION || a.type == ApplianceType.SET_TOP_BOX || a.type == ApplianceType.GAMING) base *= 1.5;
            if (a.type == ApplianceType.COMPUTER || a.type == ApplianceType.PRINTER) base *= 0.4;
        }
        // AC and fans: hotter days at random
        double noise = 0.8 + rnd.nextDouble() * 0.4;
        if (cm == m && a.type == ApplianceType.AIR_CONDITIONER) noise = 0.7 + rnd.nextDouble() * 0.6;
        return Num.round(Math.min(24, Math.max(0, base * noise)), 2);
    }

    /** The October-level hours for appliances whose seasonal hours are stored in the profile. */
    private static double baseHours(Appliance a) {
        return switch (a.id) {
            case "ap01" -> 1.5;
            case "ap13" -> 2;
            case "ap04" -> 8;
            case "ap14" -> 9;
            case "ap16" -> 8;
            default -> a.hoursPerDay;
        };
    }

    /** AC use by month, October = 1. */
    static double ac(int month) {
        double[] f = {0, 0, 0.6, 2.0, 2.8, 3.0, 2.2, 2.0, 1.8, 1.0, 0.2, 0};
        return f[month - 1];
    }

    /** Fan use by month, October = 1. */
    static double fan(int month) {
        double[] f = {0.15, 0.3, 0.85, 1.25, 1.4, 1.4, 1.25, 1.25, 1.2, 1.0, 0.45, 0.15};
        return f[month - 1];
    }

    /** Geyser use by month, October = 1. */
    static double geyser(int month) {
        double[] f = {1.8, 1.6, 1.1, 0.6, 0.35, 0.3, 0.4, 0.4, 0.55, 1.0, 1.4, 1.8};
        return f[month - 1];
    }

    private static void user(DataStore s, String username, String name, Role role, String password, String created) {
        User u = new User();
        u.username = username;
        u.displayName = name;
        u.role = role;
        u.passwordHash = Passwords.hash(password);
        u.active = true;
        u.createdAt = created;
        s.users.add(u);
    }

    private static void room(DataStore s, String id, String name, int order) {
        Room r = new Room();
        r.id = id;
        r.name = name;
        r.sortOrder = order;
        s.rooms.add(r);
    }

    private static void app(DataStore s, String id, String name, ApplianceType type, String room, double watts, double standby,
                            int qty, int star, double hours, int start, int temp, int autoOff, String created) {
        Appliance a = new Appliance();
        a.id = id;
        a.name = name;
        a.type = type;
        a.roomId = room;
        a.watts = watts;
        a.standbyWatts = standby;
        a.quantity = qty;
        a.starRating = star;
        a.hoursPerDay = Num.round(hours, 2);
        a.usualStartHour = start;
        a.setTemp = temp;
        a.autoOffMinutes = autoOff;
        a.createdAt = created;
        s.appliances.add(a);
    }
}
