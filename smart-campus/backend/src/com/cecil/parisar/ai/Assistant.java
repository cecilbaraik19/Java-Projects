package com.cecil.parisar.ai;

import com.cecil.parisar.model.Alert;
import com.cecil.parisar.model.Course;
import com.cecil.parisar.model.Device;
import com.cecil.parisar.model.Notice;
import com.cecil.parisar.model.Role;
import com.cecil.parisar.model.Room;
import com.cecil.parisar.model.Student;
import com.cecil.parisar.model.User;
import com.cecil.parisar.service.AcademicService;
import com.cecil.parisar.service.AnalyticsService;
import com.cecil.parisar.service.Problem;
import com.cecil.parisar.store.DataStore;
import com.cecil.parisar.util.Clock;
import com.cecil.parisar.util.Json;
import com.cecil.parisar.util.Text;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The campus assistant ("Ask Parisar"). It works fully offline, in two steps:
 *
 *  1. Intent: is this a question about LIVE campus data?  (free rooms, my attendance, temperature in a room,
 *     energy, water, alerts, my risk, my classes today).  Found by matching keywords. Then the answer is
 *     built from the live data - IoT sensors, attendance, the AI risk model, the energy forecast.
 *  2. Otherwise: search the knowledge base (FAQ + notice board) with TF-IDF and cosine similarity
 *     (the same idea search engines started with) and answer with the best matching entry and its source.
 *
 * No question text leaves the computer, and the assistant only shows data the person is allowed to see.
 */
public class Assistant {
    private final DataStore store;
    private final AcademicService academic;
    private final AnalyticsService analytics;
    private final EnergyForecaster energy;

    public static final List<String[]> FAQ = List.of(
            new String[]{"Library timings", "The Central Library is open 8:00 am to 10:00 pm on weekdays and 9:00 am to 5:00 pm on Saturday. The Digital Library (e-books, IEEE and Springer journals) works on campus Wi-Fi 24x7. Tap your ID card at the library gate; the visit is counted automatically."},
            new String[]{"Attendance rule (75%)", "You need at least 75% attendance in every course to sit the end-semester exam. Attendance is marked automatically when you tap your ID card on the reader at the classroom door while the class is running. If the reader missed you, ask the teacher to mark you by hand on the same day (corrections are allowed for 7 days)."},
            new String[]{"Lost ID card", "Report a lost ID card at the Admin Office (Admin Block, room C-01). The old RFID card is blocked at once and a new card is printed in 2 working days for a fee of Rs 200. Until then the teacher can mark your attendance by hand."},
            new String[]{"Campus Wi-Fi", "Connect to the network 'Parisar-Campus' and sign in with your roll number and portal password. Wi-Fi problems: write to the IT help desk in the Innovation Lab Complex (room I-03) or call extension 2244."},
            new String[]{"Exam rules", "Bring your ID card and admit card. Phones, smart watches and bags stay outside the hall. Reach the hall 20 minutes early. Using unfair means cancels the paper. Re-evaluation forms must be filled within 7 days of the result."},
            new String[]{"Hostel timings", "Kaveri Hostel gates close at 9:30 pm. Mess timings: breakfast 7:30-9:00, lunch 12:30-2:00, snacks 5:00-6:00, dinner 8:00-9:30. Night attendance is taken with the RFID reader at the hostel gate."},
            new String[]{"Fee payment", "Semester fees are paid on the student portal (UPI, net banking or card). The last date is printed on the notice board; a late fine of Rs 50 per day applies after it. Receipts can be downloaded from the portal."},
            new String[]{"Scholarships", "Merit and need-based scholarships: apply on the National Scholarship Portal and submit a copy to the Accounts section with your income certificate. The scholarship cell is in the Admin Block."},
            new String[]{"Labs and lab booking", "Computer Lab 1 and the IoT & Robotics Lab are in the Innovation Lab Complex. Students can use Computer Lab 1 when no class is running there (check the live campus map for a free lab). The IoT lab needs a faculty member present."},
            new String[]{"Cafeteria", "The cafeteria in the Admin Block is open 8:00 am to 7:00 pm. It accepts UPI and the campus wallet. The busiest time is 1:00-2:00 pm - the live map shows how crowded it is."},
            new String[]{"Saving energy on campus", "The campus has smart meters in every building and sensors in every room. If lights or AC stay on in an empty room for 30 minutes, an alert goes to the campus admin, who can switch them off from the website (or let the auto energy saver do it)."},
            new String[]{"Medical help", "The campus first-aid room is in the Admin Block (ground floor). For an emergency call the campus ambulance at extension 2100. A doctor visits on Monday, Wednesday and Friday, 11 am to 1 pm."},
            new String[]{"Sports and clubs", "Sports ground and gym are open 6-8 am and 4:30-7 pm. Clubs: coding club, robotics club, cyber security club, music and drama. Join through the student portal at the start of each semester."},
            new String[]{"Mentor", "Every student has a faculty mentor. Your mentor sees your attendance and the AI risk report and may call you for a talk if something looks wrong. You can meet your mentor in the Faculty Room (B-201)."}
    );

    public Assistant(DataStore store, AcademicService academic, AnalyticsService analytics, EnergyForecaster energy) {
        this.store = store;
        this.academic = academic;
        this.analytics = analytics;
        this.energy = energy;
    }

    public Map<String, Object> ask(User u, String question) {
        String q = Text.clean(question, 300);
        if (q == null) throw Problem.bad("Type a question.");
        List<String> words = Text.words(q);
        Set<String> ws = new HashSet<>(words);
        String intent = intent(ws, q.toLowerCase());
        Map<String, Object> answer = switch (intent) {
            case "FREE_ROOM" -> freeRooms();
            case "ROOM_CLIMATE" -> roomClimate(ws);
            case "ATTENDANCE" -> attendance(u, q);
            case "RISK" -> risk(u);
            case "CLASSES" -> classesToday(u);
            case "ENERGY" -> energy();
            case "WATER" -> water();
            case "ALERTS" -> alerts(u);
            case "HELLO" -> reply("Hello! I can tell you about the campus right now. Try one of the questions below.", "help", List.of());
            default -> search(words);
        };
        answer.put("intent", intent);
        answer.put("suggestions", suggestions(u, intent));
        return answer;
    }

    // ------------------------------------------------------------------ 1. intent by keywords

    static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();
    static {
        KEYWORDS.put("FREE_ROOM", List.of("free", "empty", "vacant", "available", "study", "quiet", "sit"));
        KEYWORDS.put("ROOM_CLIMATE", List.of("temperature", "hot", "cold", "warm", "co2", "air", "humid", "humidity", "stuffy", "crowd", "crowded", "busy", "people"));
        KEYWORDS.put("ATTENDANCE", List.of("attendance", "present", "absent", "bunk", "short", "75"));
        KEYWORDS.put("RISK", List.of("risk", "fail", "failing", "danger", "doing", "performance", "predict", "prediction"));
        KEYWORDS.put("CLASSES", List.of("class", "classes", "lecture", "timetable", "schedule", "next", "today"));
        KEYWORDS.put("ENERGY", List.of("energy", "electricity", "power", "kw", "kwh", "bill", "consumption", "forecast"));
        KEYWORDS.put("WATER", List.of("water", "tank", "pump", "leak"));
        KEYWORDS.put("ALERTS", List.of("alert", "alerts", "problem", "problems", "issue", "issues", "fault", "warning"));
        KEYWORDS.put("HELLO", List.of("hi", "hello", "hey", "namaste", "help"));
    }

    static String intent(Set<String> words, String raw) {
        String best = "SEARCH";
        int bestScore = 0;
        for (Map.Entry<String, List<String>> e : KEYWORDS.entrySet()) {
            int score = 0;
            for (String k : e.getValue()) if (words.contains(Text.words(k).isEmpty() ? k : Text.words(k).get(0)) || words.contains(k)) score++;
            if (score > bestScore) { bestScore = score; best = e.getKey(); }
        }
        if ("HELLO".equals(best) && words.size() > 3) return "SEARCH";
        // "library timings" or "hostel" questions are FAQ questions even if a keyword like "today" appears
        if (("CLASSES".equals(best) || "FREE_ROOM".equals(best)) && (raw.contains("timing") || raw.contains("open"))) return "SEARCH";
        return best;
    }

    // ------------------------------------------------------------------ 2. live-data answers

    private Map<String, Object> freeRooms() {
        synchronized (store) {
            List<String> lines = new ArrayList<>();
            List<Object> sources = new ArrayList<>();
            List<Room> rooms = new ArrayList<>(store.rooms.values());
            rooms.removeIf(r -> !List.of("CLASSROOM", "LAB", "LIBRARY", "SEMINAR").contains(r.kind));
            rooms.sort(Comparator.comparingDouble(r -> occupancyShare(r)));
            for (Room r : rooms) {
                if (store.openSessionIn(r.id) != null) continue;
                Device env = store.deviceOf("ENV", r.id);
                if (env == null || env.latest.isEmpty()) continue;
                double people = env.latest.getOrDefault("occupancy", 0.0);
                if (people > r.capacity * 0.5) continue;
                lines.add("• " + r.name + " - " + Math.round(people) + " of " + r.capacity + " seats used, " + Text.one(env.latest.getOrDefault("temp", 0.0)) + " °C"
                        + (env.latest.getOrDefault("co2", 0.0) > 1000 ? ", air a bit stale" : ", fresh air"));
                sources.add(Json.obj("kind", "room", "id", r.id, "title", r.name));
                if (lines.size() >= 5) break;
            }
            if (lines.isEmpty()) return reply("Every study space is busy right now. Try again after the current class ends.", "live IoT room sensors", sources);
            return reply("Rooms with no class running now (from the live room sensors):\n" + String.join("\n", lines), "live IoT room sensors", sources);
        }
    }

    private double occupancyShare(Room r) {
        Device env = store.deviceOf("ENV", r.id);
        return env == null ? 1 : env.latest.getOrDefault("occupancy", 0.0) / Math.max(1, r.capacity);
    }

    private Map<String, Object> roomClimate(Set<String> words) {
        synchronized (store) {
            Room best = null;
            int bestScore = 0;
            for (Room r : store.rooms.values()) {
                Set<String> rw = new HashSet<>(Text.words(r.name + " " + r.id.substring(2) + " " + store.buildings.get(r.buildingId).name));
                int s = 0;
                for (String w : words) if (rw.contains(w)) s += w.matches("[a-z]?\\d+") ? 3 : 1;
                if (s > bestScore) { bestScore = s; best = r; }
            }
            if (best == null) {
                Room hottest = null;
                double t = -99;
                for (Room r : store.rooms.values()) {
                    Device env = store.deviceOf("ENV", r.id);
                    if (env != null && env.latest.getOrDefault("temp", -99.0) > t) { t = env.latest.get("temp"); hottest = r; }
                }
                return reply("Which room? For example: \"temperature in the Reading Hall\" or \"is Lab 1 crowded\". Right now the warmest room is "
                        + (hottest == null ? "-" : hottest.name + " at " + Text.one(t) + " °C") + ".", "live IoT room sensors", List.of());
            }
            Device env = store.deviceOf("ENV", best.id);
            if (env == null || env.latest.isEmpty()) return reply(best.name + " has no room sensor yet.", "devices", List.of());
            Map<String, Double> v = env.latest;
            String air = v.getOrDefault("co2", 0.0) > 1200 ? "stale - open the windows" : v.getOrDefault("co2", 0.0) > 900 ? "OK" : "fresh";
            String text = best.name + " right now: " + Text.one(v.getOrDefault("temp", 0.0)) + " °C, humidity " + Math.round(v.getOrDefault("humidity", 0.0))
                    + "%, CO₂ " + Math.round(v.getOrDefault("co2", 0.0)) + " ppm (air " + air + "), " + Math.round(v.getOrDefault("occupancy", 0.0))
                    + " of " + best.capacity + " seats used. AC " + (best.hasAC ? (best.acOn ? "on" : "off") : "not fitted") + ", lights " + (best.lightsOn ? "on" : "off") + ".";
            return reply(text, "sensor " + env.id, List.of(Json.obj("kind", "room", "id", best.id, "title", best.name)));
        }
    }

    private Map<String, Object> attendance(User u, String q) {
        synchronized (store) {
            Student s = null;
            if (u.role == Role.STUDENT) s = store.students.get(u.studentId);
            else {
                String lower = q.toLowerCase();
                for (Student x : store.students.values()) {
                    if (!academic.canSee(u, x)) continue;
                    String first = x.name.toLowerCase().split(" ")[0];
                    if (lower.contains(x.name.toLowerCase()) || lower.contains(x.roll.toLowerCase()) || (first.length() > 3 && lower.matches(".*\\b" + first + "\\b.*"))) { s = x; break; }
                }
                if (s == null) {
                    Map<String, Map<String, int[]>> t = academic.attendanceTable();
                    int below = 0, seen = 0;
                    for (Student x : store.students.values()) {
                        if (!academic.canSee(u, x)) continue;
                        seen++;
                        if (AcademicService.pct(t.get(x.id)) < AcademicService.MIN_ATTENDANCE) below++;
                    }
                    return reply(below + " of your " + seen + " students are below 75% attendance. Ask about one student by name or roll number, "
                            + "or open Students and sort by attendance.", "attendance (RFID)", List.of(Json.obj("kind", "page", "id", "students", "title", "Students")));
                }
            }
            List<Map<String, Object>> rows = academic.attendanceOf(s, academic.attendanceTable());
            List<String> lines = new ArrayList<>();
            for (Map<String, Object> r : rows)
                lines.add("• " + r.get("courseId") + " " + r.get("courseName") + ": " + r.get("attended") + "/" + r.get("held") + " = " + r.get("pct") + "%"
                        + (Boolean.TRUE.equals(r.get("ok")) ? "" : "  ⚠ below 75% - attend the next " + r.get("classesNeeded") + " classes in a row to get back"));
            String who = u.role == Role.STUDENT ? "Your" : s.name + "'s";
            return reply(who + " attendance (overall " + Math.round(academic.attendancePct(s.id)) + "%):\n" + String.join("\n", lines),
                    "attendance (RFID)", List.of(Json.obj("kind", "student", "id", s.id, "title", s.name)));
        }
    }

    private Map<String, Object> risk(User u) {
        if (u.role != Role.STUDENT) {
            Map<String, Object> list = analytics.students(u, Map.of("risk", "HIGH"));
            List<?> rows = (List<?>) list.get("students");
            List<String> names = new ArrayList<>();
            for (Object o : rows) { if (names.size() >= 6) break; Map<?, ?> m = (Map<?, ?>) o; names.add("• " + m.get("name") + " (" + m.get("roll") + ") - " + m.get("riskPct") + "%"); }
            return reply(rows.size() + " of your students are at HIGH risk of failing (AI model)." + (names.isEmpty() ? "" : "\n" + String.join("\n", names)),
                    "AI risk model", List.of(Json.obj("kind", "page", "id", "students", "title", "Students")));
        }
        Map<String, Object> d = analytics.studentDetail(u, u.studentId);
        Map<?, ?> r = (Map<?, ?>) d.get("risk");
        String band = (String) r.get("band");
        String head = switch (band) {
            case "HIGH" -> "The AI model says you are at HIGH risk (" + r.get("riskPct") + "%) of failing this semester - but it is not too late.";
            case "MEDIUM" -> "The AI model puts you at MEDIUM risk (" + r.get("riskPct") + "%). A few changes will make you safe.";
            default -> "Good news: the AI model puts you at LOW risk (" + r.get("riskPct") + "%). Keep going!";
        };
        List<String> lines = new ArrayList<>();
        for (Object o : (List<?>) r.get("reasons")) lines.add("• " + o);
        return reply(head + "\n" + String.join("\n", lines), "AI risk model", List.of(Json.obj("kind", "page", "id", "me", "title", "My report")));
    }

    private Map<String, Object> classesToday(User u) {
        Map<String, Object> v = academic.classesView(u);
        List<?> today = (List<?>) v.get("today");
        if (today.isEmpty()) return reply("No classes today" + (u.role == Role.ADMIN ? " on the timetable." : " for you.") + " Enjoy the day!", "timetable", List.of());
        List<String> lines = new ArrayList<>();
        for (Object o : today) {
            Map<?, ?> m = (Map<?, ?>) o;
            int h = ((Number) m.get("hour")).intValue();
            String state = switch ((String) m.get("state")) { case "LIVE" -> "  ← running now"; case "DONE" -> "  (over)"; case "MISSED" -> "  (not held)"; default -> ""; };
            lines.add("• " + h + ":00 " + m.get("courseId") + " " + m.get("courseName") + " - " + m.get("roomName") + state);
            if (lines.size() >= 8) break;
        }
        return reply("Today's classes:\n" + String.join("\n", lines), "timetable", List.of(Json.obj("kind", "page", "id", "classes", "title", "Classes")));
    }

    private Map<String, Object> energy() {
        Map<String, Object> f = energy.forecast(null, 12);
        double nowKw = 0;
        synchronized (store) { for (Device d : store.devices.values()) if ("ENERGY".equals(d.type)) nowKw += d.latest.getOrDefault("power", 0.0); }
        String text = "The campus is using " + Math.round(nowKw) + " kW right now. Today so far: " + f.get("kwhToday") + " kWh (about Rs " + f.get("costToday")
                + ", " + f.get("co2Today") + " kg CO₂). Yesterday: " + f.get("kwhYesterday") + " kWh.\nAI forecast: the next peak is about " + f.get("peakKw")
                + " kW at " + hourText(((Number) f.get("peakAt")).longValue()) + (f.get("mape") == null ? "." : " (yesterday's forecast was off by " + f.get("mape") + "% on average).");
        return reply(text, "smart meters + AI forecast", List.of(Json.obj("kind", "page", "id", "energy", "title", "Energy")));
    }

    private Map<String, Object> water() {
        synchronized (store) {
            List<String> lines = new ArrayList<>();
            for (Device d : store.devices.values()) {
                if (!"WATER".equals(d.type)) continue;
                double l = d.latest.getOrDefault("level", 0.0);
                lines.add("• " + d.name + ": " + Math.round(l) + "%" + (l < 20 ? "  ⚠ low" : ""));
            }
            return reply("Water tanks now:\n" + String.join("\n", lines), "water level sensors", List.of());
        }
    }

    private Map<String, Object> alerts(User u) {
        if (u.role == Role.STUDENT) return reply("Alerts are for staff. If you see a problem on campus (too hot, water leak, a broken light), tell the Admin Office.", "help", List.of());
        synchronized (store) {
            List<Alert> open = new ArrayList<>();
            for (Alert a : store.alerts.values()) if (a.isOpen()) open.add(a);
            if (open.isEmpty()) return reply("No open alerts. All sensors look normal.", "IoT alerts", List.of());
            open.sort(Comparator.comparing((Alert a) -> "CRITICAL".equals(a.severity) ? 0 : "WARNING".equals(a.severity) ? 1 : 2));
            List<String> lines = new ArrayList<>();
            for (Alert a : open) { if (lines.size() >= 6) break; lines.add("• [" + a.severity + "] " + a.message); }
            return reply(open.size() + " open alert" + (open.size() == 1 ? "" : "s") + ":\n" + String.join("\n", lines), "IoT alerts",
                    List.of(Json.obj("kind", "page", "id", "alerts", "title", "Alerts")));
        }
    }

    // ------------------------------------------------------------------ 3. TF-IDF search over FAQ + notices

    record Doc(String id, String title, String text, String kind) { }

    List<Doc> docs() {
        List<Doc> d = new ArrayList<>();
        for (int i = 0; i < FAQ.size(); i++) d.add(new Doc("FAQ-" + (i + 1), FAQ.get(i)[0], FAQ.get(i)[1], "faq"));
        synchronized (store) { for (Notice n : store.notices.values()) d.add(new Doc(n.id, n.title, n.body, "notice")); }
        return d;
    }

    /** Ranks documents by cosine similarity of TF-IDF vectors. Title words count double. */
    public List<Map<String, Object>> rank(List<String> query) {
        List<Doc> docs = docs();
        List<Map<String, Integer>> tfs = new ArrayList<>();
        Map<String, Integer> df = new HashMap<>();
        for (Doc d : docs) {
            Map<String, Integer> tf = new HashMap<>();
            for (String w : Text.words(d.text())) tf.merge(w, 1, Integer::sum);
            for (String w : Text.words(d.title())) tf.merge(w, 2, Integer::sum);
            tfs.add(tf);
            for (String w : tf.keySet()) df.merge(w, 1, Integer::sum);
        }
        int n = docs.size();
        Map<String, Double> qv = new HashMap<>();
        for (String w : query) qv.merge(w, 1.0, Double::sum);
        qv.replaceAll((w, tf) -> tf * idf(n, df.getOrDefault(w, 0)));
        double qNorm = norm(qv);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Double> dv = new HashMap<>();
            for (Map.Entry<String, Integer> e : tfs.get(i).entrySet()) dv.put(e.getKey(), (1 + Math.log(e.getValue())) * idf(n, df.get(e.getKey())));
            double dot = 0;
            for (Map.Entry<String, Double> e : qv.entrySet()) dot += e.getValue() * dv.getOrDefault(e.getKey(), 0.0);
            double sim = qNorm == 0 ? 0 : dot / (qNorm * norm(dv));
            Doc d = docs.get(i);
            out.add(Json.obj("id", d.id(), "title", d.title(), "text", d.text(), "kind", d.kind(), "score", Math.round(sim * 1000) / 1000.0));
        }
        out.sort(Comparator.comparingDouble(m -> -((Number) m.get("score")).doubleValue()));
        return out;
    }

    static double idf(int n, int df) { return Math.log((n + 1.0) / (df + 1.0)) + 1; }

    static double norm(Map<String, Double> v) {
        double s = 0;
        for (double x : v.values()) s += x * x;
        return Math.sqrt(s);
    }

    private Map<String, Object> search(List<String> words) {
        List<Map<String, Object>> ranked = rank(words);
        Map<String, Object> top = ranked.get(0);
        double score = ((Number) top.get("score")).doubleValue();
        if (score < 0.12)
            return reply("Sorry, I could not find that. I know about library and hostel timings, attendance rules, exams, fees, Wi-Fi, labs, "
                    + "the notice board - and live things like free rooms, temperature, energy and your attendance.", "knowledge base", List.of());
        List<Object> sources = new ArrayList<>();
        sources.add(Json.obj("kind", top.get("kind"), "id", top.get("id"), "title", top.get("title"), "score", score));
        for (int i = 1; i < Math.min(3, ranked.size()); i++)
            if (((Number) ranked.get(i).get("score")).doubleValue() >= 0.12) sources.add(Json.obj("kind", ranked.get(i).get("kind"),
                    "id", ranked.get(i).get("id"), "title", ranked.get(i).get("title"), "score", ranked.get(i).get("score")));
        return reply(top.get("title") + ": " + top.get("text"), "notice".equals(top.get("kind")) ? "notice board" : "campus FAQ", sources);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> reply(String text, String basedOn, List<Object> sources) {
        return Json.obj("answer", text, "basedOn", basedOn, "sources", sources);
    }

    private static List<String> suggestions(User u, String intent) {
        List<String> all = u.role == Role.STUDENT
                ? List.of("Where can I study now?", "What is my attendance?", "Am I at risk of failing?", "My classes today", "Library timings", "I lost my ID card")
                : List.of("Any open alerts?", "Which students are at risk?", "How much energy are we using?", "Temperature in the server room", "Where is a free room?", "Water tank level");
        List<String> out = new ArrayList<>();
        for (String s : all) if (out.size() < 4) out.add(s);
        return out;
    }

    private static String hourText(long t) {
        int h = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), Clock.ZONE).getHour();
        return (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? " am" : " pm");
    }

    /** Used by the course pages: courses taught by a teacher. */
    static List<Course> coursesOf(DataStore store, User u) {
        List<Course> out = new ArrayList<>();
        for (Course c : store.courses.values()) if (u.id.equals(c.facultyId)) out.add(c);
        return out;
    }
}
