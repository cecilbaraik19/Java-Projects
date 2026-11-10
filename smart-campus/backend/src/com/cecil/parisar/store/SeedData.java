package com.cecil.parisar.store;

import com.cecil.parisar.iot.IotService;
import com.cecil.parisar.iot.Simulator;
import com.cecil.parisar.model.Alert;
import com.cecil.parisar.model.Building;
import com.cecil.parisar.model.ClassSession;
import com.cecil.parisar.model.Course;
import com.cecil.parisar.model.Device;
import com.cecil.parisar.model.Notice;
import com.cecil.parisar.model.Role;
import com.cecil.parisar.model.Room;
import com.cecil.parisar.model.Student;
import com.cecil.parisar.model.User;
import com.cecil.parisar.service.AcademicService;
import com.cecil.parisar.util.Clock;
import com.cecil.parisar.util.Passwords;
import com.cecil.parisar.util.Stats;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The demo campus: "Kanchan Institute of Technology, Ranchi" - completely made up.
 * 6 buildings, 16 rooms, 32 IoT devices, 5 staff, 120 students, 8 courses with a weekly timetable,
 * 6 weeks of class attendance and 14 days of sensor history - all dated relative to today, so the demo is always fresh.
 */
public final class SeedData {
    private SeedData() { }

    /** The secret key of the IoT-lab room sensor, so the README can show a real device message with curl. */
    public static final String DEMO_DEVICE = "D-ENV-I02";
    public static final String DEMO_KEY = "demo-iot-lab-key";

    public static void load(DataStore store, IotService iot, AcademicService academic, Simulator sim, boolean withHistory) {
        Random r = new Random(2026);
        long now = Clock.millis();
        store.batch = true;
        users(store);
        campus(store);
        devices(store, r);
        students(store, r);
        courses(store);
        sessions(store, r, now);
        notices(store, now);
        pastAlerts(store, r, now);
        if (withHistory) {
            long start = now - 14L * 24 * DataStore.HOUR_MS;
            start -= Math.floorMod(start, DataStore.FINE_MS);
            sim.history(start, now - 40 * 60_000L);
            // demo problems, so the alert board has something to show on the first start
            sim.seedFault("R-I03", "AC_FAIL", now + 3 * DataStore.HOUR_MS);
            sim.seedFault("D-WT-H", "LEAK", now + 90 * 60_000L);
            sim.seedFault("D-ENV-H01", "SILENT", now + 6 * DataStore.HOUR_MS);
            sim.seedFault("R-A301", "LEFT_ON", now + 2 * DataStore.HOUR_MS);
            sim.seedFault("D-EM-I", "SPIKE", now + 15 * 60_000L);
            store.devices.get("D-WT-H").latest.put("level", 21.0);
            for (ClassSession s : store.sessions.values()) if (s.isOpen()) sim.scheduleTaps(s);
            for (long t = now - 35 * 60_000L; t <= now; t += 5 * 60_000L) sim.tick(t);
            iot.checkOffline();
        }
        store.saveAll();
    }

    // ------------------------------------------------------------------ people

    static void users(DataStore store) {
        user(store, "U-1", "admin", "Neha Tirkey", Role.ADMIN, "admin123", "Campus Operations", null);
        user(store, "U-2", "rkumar", "Dr. Ranjan Kumar", Role.FACULTY, "faculty123", "Information Technology", null);
        user(store, "U-3", "pmunda", "Prof. Priya Munda", Role.FACULTY, "faculty123", "Information Technology", null);
        user(store, "U-4", "soraon", "Dr. Sameer Oraon", Role.FACULTY, "faculty123", "Computer Science & Engg.", null);
        user(store, "U-5", "aekka", "Ms. Anjali Ekka", Role.FACULTY, "faculty123", "Computer Applications", null);
        store.meta.put("next.U", 8L);
    }

    static User user(DataStore store, String id, String username, String name, Role role, String pw, String dept, String studentId) {
        User u = new User();
        u.id = id;
        u.username = username;
        u.name = name;
        u.role = role;
        u.passwordHash = Passwords.hash(pw);
        u.department = dept;
        u.studentId = studentId;
        u.createdAt = Clock.millis() - 60L * 24 * DataStore.HOUR_MS;
        store.users.put(id, u);
        return u;
    }

    // ------------------------------------------------------------------ buildings and rooms

    static void campus(DataStore store) {
        building(store, "B-C", "C", "Admin Block & Cafeteria", "ADMIN", 6, 6, 22, 14);
        building(store, "B-A", "A", "Academic Block A", "ACADEMIC", 36, 6, 26, 14);
        building(store, "B-B", "B", "Academic Block B", "ACADEMIC", 68, 6, 24, 14);
        building(store, "B-L", "L", "Central Library", "LIBRARY", 36, 30, 20, 14);
        building(store, "B-I", "I", "Innovation Lab Complex", "LAB", 62, 30, 28, 14);
        building(store, "B-H", "H", "Kaveri Hostel", "HOSTEL", 6, 34, 22, 18);

        room(store, "R-A101", "B-A", "Classroom A-101", "CLASSROOM", 60, true);
        room(store, "R-A102", "B-A", "Classroom A-102", "CLASSROOM", 60, false);
        room(store, "R-A201", "B-A", "Classroom A-201", "CLASSROOM", 50, true);
        room(store, "R-A301", "B-A", "Seminar Hall A-301", "SEMINAR", 160, true);
        room(store, "R-B101", "B-B", "Classroom B-101", "CLASSROOM", 60, false);
        room(store, "R-B102", "B-B", "Classroom B-102", "CLASSROOM", 60, true);
        room(store, "R-B201", "B-B", "Faculty Room B-201", "OFFICE", 20, true);
        room(store, "R-L01", "B-L", "Reading Hall", "LIBRARY", 180, true);
        room(store, "R-L02", "B-L", "Digital Library", "LIBRARY", 40, true);
        room(store, "R-I01", "B-I", "Computer Lab 1", "LAB", 45, true);
        room(store, "R-I02", "B-I", "IoT & Robotics Lab", "LAB", 30, true);
        room(store, "R-I03", "B-I", "Server Room", "SERVER", 4, true);
        room(store, "R-H01", "B-H", "Hostel Common Room", "HOSTEL", 80, false);
        room(store, "R-H02", "B-H", "Hostel Mess", "CAFE", 150, false);
        room(store, "R-C01", "B-C", "Admin Office", "OFFICE", 25, true);
        room(store, "R-C02", "B-C", "Cafeteria", "CAFE", 120, false);
    }

    static void building(DataStore store, String id, String code, String name, String kind, int x, int y, int w, int h) {
        Building b = new Building();
        b.id = id; b.code = code; b.name = name; b.kind = kind; b.x = x; b.y = y; b.w = w; b.h = h;
        store.buildings.put(id, b);
    }

    static void room(DataStore store, String id, String building, String name, String kind, int cap, boolean ac) {
        Room r = new Room();
        r.id = id; r.buildingId = building; r.name = name; r.kind = kind; r.capacity = cap; r.hasAC = ac;
        r.acOn = "SERVER".equals(kind);
        store.rooms.put(id, r);
    }

    // ------------------------------------------------------------------ IoT devices

    static void devices(DataStore store, Random r) {
        for (Room room : store.rooms.values())
            device(store, r, "D-ENV-" + room.id.substring(2), "Room sensor · " + room.name, "ENV", room.id, room.buildingId);
        for (Building b : store.buildings.values())
            device(store, r, "D-EM-" + b.code, "Smart meter · " + b.name, "ENERGY", null, b.id);
        device(store, r, "D-WT-H", "Water tank · Kaveri Hostel", "WATER", null, "B-H");
        device(store, r, "D-WT-A", "Water tank · Academic Block A", "WATER", null, "B-A");
        store.devices.get("D-WT-H").latest.put("level", 70.0);
        store.devices.get("D-WT-A").latest.put("level", 85.0);
        for (String room : List.of("R-A101", "R-A102", "R-A201", "R-A301", "R-B101", "R-B102", "R-I01", "R-L01"))
            device(store, r, "D-GT-" + room.substring(2), "RFID gate · " + store.rooms.get(room).name, "GATE", room, store.rooms.get(room).buildingId);
        store.devices.get(DEMO_DEVICE).keyHash = Passwords.sha256(DEMO_KEY);
    }

    static void device(DataStore store, Random r, String id, String name, String type, String room, String building) {
        Device d = new Device();
        d.id = id;
        d.name = name;
        d.type = type;
        d.roomId = room;
        d.buildingId = building;
        d.keyHash = Passwords.sha256("pk_" + Passwords.token());      // nobody knows it; the admin can make a new one
        d.firmware = List.of("1.4.2", "1.4.2", "1.4.2", "1.3.9", "2.0.1").get(r.nextInt(5));
        d.battery = "ENV".equals(type) ? 55 + r.nextInt(46) : 100;
        store.devices.put(id, d);
    }

    // ------------------------------------------------------------------ students

    static final String[] FIRST = {"Aarav", "Ananya", "Abhishek", "Priyanka", "Rahul", "Sneha", "Vikash", "Pooja", "Amit", "Kriti",
            "Sunil", "Nisha", "Deepak", "Anjali", "Rakesh", "Shreya", "Manish", "Riya", "Sanjay", "Komal", "Nitin", "Payal",
            "Arjun", "Sakshi", "Rohan", "Kavya", "Aman", "Tanya", "Kunal", "Divya", "Saurabh", "Megha", "Ravi", "Swati",
            "Ankit", "Neha", "Prakash", "Jyoti", "Vivek", "Simran", "Akash", "Muskan", "Gaurav", "Puja", "Harsh", "Sweta",
            "Ajay", "Rupa", "Sumit", "Alka", "Imran", "Sana", "Bijay", "Sushma", "Ashish", "Mamta", "Tarun", "Rekha", "Anil", "Poonam"};
    static final String[] LAST = {"Tirkey", "Minz", "Lakra", "Kujur", "Toppo", "Hembrom", "Soren", "Murmu", "Mahato", "Singh",
            "Kumari", "Sharma", "Verma", "Gupta", "Ansari", "Oraon", "Munda", "Ekka", "Xalxo", "Barla", "Bhagat", "Sahu",
            "Prasad", "Mishra", "Pandey", "Das", "Roy", "Dutta", "Kerketta", "Bara", "Khalkho", "Lohra", "Kachhap", "Tigga"};

    static void students(DataStore store, Random r) {
        Set<String> used = new HashSet<>();
        int n = 1001;
        String[][] programs = {{"B.Sc. IT", "2025-BSCIT/", "2"}, {"BCA", "2025-BCA/", "2"}, {"B.Tech CSE", "2024-BTCSE/", "3"}};
        for (String[] p : programs) {
            for (int i = 1; i <= 40; i++) {
                Student s = new Student();
                s.id = "S-" + n++;
                s.program = p[0];
                s.year = Integer.parseInt(p[2]);
                s.roll = p[1] + String.format("%03d", i + 10);
                String name;
                do { name = FIRST[r.nextInt(FIRST.length)] + " " + LAST[r.nextInt(LAST.length)]; } while (!used.add(name));
                s.name = name;
                s.cardId = String.format("RF-%06X", r.nextInt(0xFFFFFF));
                double habit = Stats.clamp(0.8 + r.nextGaussian() * 0.13, 0.42, 0.99);
                s.attendHabit = Stats.round2(habit);
                s.internalPct = Stats.round1(Stats.clamp(30 + habit * 52 + r.nextGaussian() * 9, 8, 98));
                s.assignmentPct = Stats.round1(Stats.clamp(25 + habit * 70 + r.nextGaussian() * 11, 0, 100));
                s.lmsHours = Stats.round1(Stats.clamp(habit * 6 + r.nextGaussian() * 1.3, 0.2, 12));
                s.libraryHabit = Stats.round2(Stats.clamp(habit * 0.45 + r.nextGaussian() * 0.15, 0.02, 1));
                s.mentorId = switch (p[0]) { case "B.Sc. IT" -> i % 2 == 0 ? "U-2" : "U-3"; case "BCA" -> "U-5"; default -> "U-4"; };
                store.students.put(s.id, s);
            }
        }
        // two students with logins: one doing well, one at risk
        Student aditi = store.students.get("S-1004");
        aditi.name = "Aditi Lakra"; aditi.attendHabit = 0.95; aditi.internalPct = 86; aditi.assignmentPct = 96; aditi.lmsHours = 7.5; aditi.libraryHabit = 0.9;
        Student rohit = store.students.get("S-1009");
        rohit.name = "Rohit Mahato"; rohit.attendHabit = 0.58; rohit.internalPct = 37; rohit.assignmentPct = 44; rohit.lmsHours = 1.1; rohit.libraryHabit = 0.04;
        user(store, "U-6", "aditi", aditi.name, Role.STUDENT, "student123", null, aditi.id);
        user(store, "U-7", "rohit", rohit.name, Role.STUDENT, "student123", null, rohit.id);
    }

    // ------------------------------------------------------------------ courses and timetable

    static void courses(DataStore store) {
        course(store, "CSIT131", "Computer Architecture & Assembly Language", "B.Sc. IT", "U-2", "R-A101", new int[][]{{1, 9}, {3, 9}, {5, 11}});
        course(store, "CSIT135", "Operating Systems", "B.Sc. IT", "U-3", "R-A101", new int[][]{{2, 10}, {4, 10}, {5, 9}});
        course(store, "CSIT141", "Java Programming Lab", "B.Sc. IT", "U-3", "R-I01", new int[][]{{1, 14}, {3, 14}, {4, 14}});
        course(store, "BCA201", "Data Structures", "BCA", "U-5", "R-A102", new int[][]{{1, 10}, {2, 11}, {4, 9}});
        course(store, "BCA205", "Database Management Systems", "BCA", "U-5", "R-B101", new int[][]{{2, 9}, {3, 11}, {5, 10}});
        course(store, "CSE301", "Computer Networks", "B.Tech CSE", "U-4", "R-B102", new int[][]{{1, 11}, {3, 10}, {5, 14}});
        course(store, "CSE305", "Machine Learning", "B.Tech CSE", "U-4", "R-A201", new int[][]{{2, 14}, {4, 11}, {5, 15}});
        course(store, "EVS101", "Environmental Studies", "All", "U-2", "R-A301", new int[][]{{2, 15}, {4, 15}});
    }

    static void course(DataStore store, String id, String name, String program, String faculty, String room, int[][] slots) {
        Course c = new Course();
        c.id = id; c.name = name; c.program = program; c.facultyId = faculty; c.roomId = room;
        for (int[] s : slots) c.slots.add(s);
        for (Student s : store.students.values()) if ("All".equals(program) || s.program.equals(program)) c.studentIds.add(s.id);
        store.courses.put(id, c);
    }

    // ------------------------------------------------------------------ 6 weeks of classes

    static void sessions(DataStore store, Random r, long now) {
        LocalDate today = Clock.today();
        for (LocalDate d = today.minusDays(42); !d.isAfter(today); d = d.plusDays(1)) {
            int day = d.getDayOfWeek().getValue();
            for (Course c : store.courses.values()) {
                for (int[] slot : c.slots) {
                    if (slot[0] != day) continue;
                    long start = d.atTime(slot[1], 0).atZone(Clock.ZONE).toInstant().toEpochMilli();
                    if (start > now) continue;
                    ClassSession s = new ClassSession();
                    s.id = AcademicService.sessionId(c.id, d.toString(), slot[1]);
                    s.courseId = c.id;
                    s.roomId = c.roomId;
                    s.date = d.toString();
                    s.hour = slot[1];
                    s.startedAt = start;
                    s.source = "TIMETABLE";
                    boolean open = now < start + DataStore.HOUR_MS;
                    s.endedAt = open ? 0 : start + DataStore.HOUR_MS;
                    if (!open) {
                        double dip = (slot[1] == 9 ? 0.05 : 0) + (day == 5 && slot[1] >= 14 ? 0.08 : 0);
                        for (String sid : c.studentIds) {
                            Student st = store.students.get(sid);
                            if (r.nextDouble() > st.attendHabit - dip) continue;
                            long at = start + (long) (Math.abs(r.nextGaussian()) * 4 * 60_000) + (r.nextDouble() < 0.12 ? 8 * 60_000 : 0);
                            s.present.put(sid, (r.nextDouble() < 0.04 ? "MANUAL@" : "RFID@") + at);
                        }
                    }
                    store.sessions.put(s.id, s);
                }
            }
        }
    }

    // ------------------------------------------------------------------ notice board

    static void notices(DataStore store, long now) {
        long day = 24 * DataStore.HOUR_MS;
        notice(store, "N-101", "Mid-semester exams from next Monday", "Mid-semester exams start next Monday at 10:00 am in Academic Block A. "
                + "Seating plans will be on the notice board two days before. Bring your ID card and admit card. Students below 75% attendance must meet their mentor this week.", "EXAM", "U-1", now - 2 * day, true);
        notice(store, "N-102", "Library open till 11 pm during exams", "The Reading Hall stays open until 11:00 pm from Monday to Saturday during the exam weeks. The Digital Library is open 24x7 on campus Wi-Fi.", "ACADEMIC", "U-1", now - 3 * day, false);
        notice(store, "N-103", "Water supply maintenance - Kaveri Hostel", "The hostel water pump will be serviced on Saturday from 10 am to 1 pm. Please store water in advance. The tank level can be watched live on the campus map.", "HOSTEL", "U-1", now - 5 * day, false);
        notice(store, "N-104", "Hackathon: Code for Jharkhand", "A 24-hour hackathon on smart city and smart campus ideas, in the Innovation Lab Complex. Teams of 3-4. Register on the student portal before Friday. Prizes for the top three teams.", "EVENT", "U-4", now - 6 * day, false);
        notice(store, "N-105", "Switch off - save energy drive", "Please switch off lights, fans and AC when you leave a room. Every room now has a sensor; rooms left on while empty are reported to the campus admin. Last week we saved 410 kWh.", "FACILITY", "U-1", now - 8 * day, false);
        notice(store, "N-106", "Guest lecture: Security of IoT devices", "Guest lecture on securing IoT devices (device keys, firmware updates, network segmentation) on Thursday at 3 pm in Seminar Hall A-301. All IT, BCA and CSE students are welcome.", "EVENT", "U-2", now - 9 * day, false);
        store.meta.put("next.N", 107L);
    }

    static void notice(DataStore store, String id, String title, String body, String cat, String by, long at, boolean pinned) {
        Notice n = new Notice();
        n.id = id; n.title = title; n.body = body; n.category = cat; n.postedBy = by; n.postedAt = at; n.pinned = pinned;
        store.notices.put(id, n);
    }

    // ------------------------------------------------------------------ old (resolved) alerts, for the analytics page

    static void pastAlerts(DataStore store, Random r, long now) {
        String[][] samples = {
                {"CO2", "WARNING", "D-ENV-A102", "Stale air in Classroom A-102: CO₂ 1290 ppm - open windows or switch on ventilation."},
                {"CO2", "WARNING", "D-ENV-B101", "Stale air in Classroom B-101: CO₂ 1245 ppm - open windows or switch on ventilation."},
                {"ENERGY_WASTE", "INFO", "D-ENV-A201", "AC and lights on in empty Classroom A-201 for 34 min - switch off to save energy."},
                {"ENERGY_WASTE", "INFO", "D-ENV-B102", "Lights on in empty Classroom B-102 for 41 min - switch off to save energy."},
                {"ENERGY_WASTE", "INFO", "D-ENV-L02", "AC and lights on in empty Digital Library for 52 min - switch off to save energy."},
                {"OFFLINE", "WARNING", "D-GT-B101", "RFID gate · Classroom B-101 has sent nothing for 12 minutes - check power / Wi-Fi, or send a reboot."},
                {"WATER_LOW", "WARNING", "D-WT-H", "Water tank low at Kaveri Hostel: 18% - check the pump or look for a leak."},
                {"HEAT", "WARNING", "D-ENV-C02", "Too hot in Cafeteria: 32.4 °C - open windows / switch on fans."},
                {"ANOMALY", "WARNING", "D-EM-B", "Unusual power use at Academic Block B: 61.2 kW (normal about 24.0 kW, 6.3 std. deviations away)."},
                {"CO2", "WARNING", "D-ENV-I01", "Stale air in Computer Lab 1: CO₂ 1310 ppm - open windows or switch on ventilation."}};
        long seq = 1001;
        for (int i = 0; i < 16; i++) {
            String[] s = samples[i % samples.length];
            Device d = store.devices.get(s[2]);
            Alert a = new Alert();
            a.id = "AL-" + seq++;
            a.rule = s[0];
            a.severity = s[1];
            a.deviceId = d.id;
            a.roomId = d.roomId;
            a.buildingId = d.buildingId;
            a.message = s[3];
            a.createdAt = now - (long) ((1 + r.nextDouble() * 13) * 24 * DataStore.HOUR_MS);
            a.updatedAt = a.createdAt + (20 + r.nextInt(90)) * 60_000L;
            a.status = "RESOLVED";
            boolean byHand = i % 3 == 0;
            a.resolvedBy = byHand ? "U-1" : "auto";
            a.note = byHand ? "switched off from the website" : "back to normal";
            store.alerts.put(a.id, a);
        }
        store.meta.put("next.AL", seq);
    }
}
