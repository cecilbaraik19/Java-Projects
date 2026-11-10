package com.cecil.parisar.service;

import com.cecil.parisar.model.ClassSession;
import com.cecil.parisar.model.Course;
import com.cecil.parisar.model.Device;
import com.cecil.parisar.model.Role;
import com.cecil.parisar.model.Room;
import com.cecil.parisar.model.Student;
import com.cecil.parisar.model.User;
import com.cecil.parisar.store.DataStore;
import com.cecil.parisar.util.Clock;
import com.cecil.parisar.util.Json;
import com.cecil.parisar.util.Stats;
import com.cecil.parisar.util.Text;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Classes, attendance and students.
 *
 * Smart attendance: when a timetable slot starts, the class "opens" by itself. Every student who taps their RFID
 * card on the reader at the classroom door while the class is open is marked present. The teacher can still
 * correct the list by hand. The class closes by itself after one hour.
 */
public class AcademicService {
    public static final double MIN_ATTENDANCE = 75;   // university rule: 75% attendance needed to sit the exam
    public static final long LIBRARY_DEDUPE_MS = 30 * 60_000L;
    public static final long SIXTY_DAYS = 60L * 24 * 3_600_000L;

    private final DataStore store;

    public AcademicService(DataStore store) { this.store = store; }

    // ------------------------------------------------------------------ automatic classes from the timetable

    /** Opens the classes whose timetable slot is now, and closes classes whose hour is over. Returns newly opened classes. */
    public List<ClassSession> autoSessions(long now) {
        synchronized (store) {
            ZonedDateTime z = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), Clock.ZONE);
            String date = z.toLocalDate().toString();
            int day = z.getDayOfWeek().getValue(), hour = z.getHour();
            boolean changed = false;
            for (ClassSession s : store.sessions.values()) {
                if (!s.isOpen()) continue;
                long end = "EXTRA".equals(s.source) ? s.startedAt + 60 * 60_000L : endOf(s);
                if (now >= end) { s.endedAt = end; changed = true; }
            }
            List<ClassSession> opened = new ArrayList<>();
            for (Course c : store.courses.values()) {
                if (!c.hasSlot(day, hour)) continue;
                String id = sessionId(c.id, date, hour);
                if (store.sessions.containsKey(id)) continue;
                if (store.openSessionIn(c.roomId) != null) continue;          // room busy with an extra class
                ClassSession s = newSession(id, c, date, hour, z.withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli(), "TIMETABLE");
                opened.add(s);
                changed = true;
            }
            if (changed) store.save("sessions");
            return opened;
        }
    }

    private static long endOf(ClassSession s) {
        LocalDate d = LocalDate.parse(s.date);
        return d.atTime(s.hour + 1, 0).atZone(Clock.ZONE).toInstant().toEpochMilli();
    }

    public static String sessionId(String courseId, String date, int hour) {
        return "CS-" + courseId + "-" + date.replace("-", "") + "-" + hour;
    }

    private ClassSession newSession(String id, Course c, String date, int hour, long startedAt, String source) {
        ClassSession s = new ClassSession();
        s.id = id;
        s.courseId = c.id;
        s.roomId = c.roomId;
        s.date = date;
        s.hour = hour;
        s.startedAt = startedAt;
        s.source = source;
        store.sessions.put(s.id, s);
        Room r = store.rooms.get(c.roomId);
        if (r != null) r.lightsOn = true;                                  // the smart room switches the lights on
        return s;
    }

    /** A teacher starts an extra class now (for example a make-up class or the demo on a Sunday). */
    public ClassSession startExtra(User user, String courseId) {
        synchronized (store) {
            Course c = courseFor(user, courseId);
            if (store.openSessionIn(c.roomId) != null) throw Problem.conflict(store.rooms.get(c.roomId).name + " already has a class running.");
            long now = Clock.millis();
            ZonedDateTime z = ZonedDateTime.ofInstant(Clock.now(), Clock.ZONE);
            String id = "CS-" + c.id + "-X" + now;
            ClassSession s = newSession(id, c, z.toLocalDate().toString(), z.getHour(), now, "EXTRA");
            store.log(user.id, "class.start", s.id, c.id + " extra class in " + store.rooms.get(c.roomId).name);
            store.save("sessions", "rooms", "audit");
            return s;
        }
    }

    public ClassSession end(User user, String sessionId) {
        synchronized (store) {
            ClassSession s = session(user, sessionId);
            if (!s.isOpen()) throw Problem.bad("This class has already ended.");
            s.endedAt = Clock.millis();
            store.log(user.id, "class.end", s.id, s.present.size() + " present");
            store.save("sessions", "audit");
            return s;
        }
    }

    // ------------------------------------------------------------------ RFID taps

    /** A student's card was tapped at a gate reader. Returns PRESENT, ALREADY, LIBRARY, NOT_ENROLLED or NO_CLASS. */
    public String tap(Device gate, Student s, long at) {
        synchronized (store) {
            Room room = gate.roomId == null ? null : store.rooms.get(gate.roomId);
            if (room != null && "LIBRARY".equals(room.kind)) {
                if (s.libraryVisits.isEmpty() || at - s.libraryVisits.get(s.libraryVisits.size() - 1) > LIBRARY_DEDUPE_MS) {
                    s.libraryVisits.add(at);
                    s.libraryVisits.removeIf(t -> at - t > SIXTY_DAYS);
                    store.save("students");
                }
                return "LIBRARY";
            }
            ClassSession open = room == null ? null : store.openSessionIn(room.id);
            if (open == null) return "NO_CLASS";
            Course c = store.courses.get(open.courseId);
            if (c == null || !c.studentIds.contains(s.id)) return "NOT_ENROLLED";
            if (open.present.containsKey(s.id)) return "ALREADY";
            open.present.put(s.id, "RFID@" + at);
            store.save("sessions");
            return "PRESENT";
        }
    }

    /** The teacher marks a student present or absent by hand. */
    public ClassSession mark(User user, String sessionId, String studentId, boolean present) {
        synchronized (store) {
            ClassSession s = session(user, sessionId);
            Course c = store.courses.get(s.courseId);
            if (!c.studentIds.contains(studentId)) throw Problem.bad("That student is not in this course.");
            if (!s.isOpen() && Clock.millis() - s.endedAt > 7L * 24 * 3_600_000L)
                throw Problem.bad("Attendance can only be corrected within 7 days of the class.");
            if (present) s.present.putIfAbsent(studentId, "MANUAL@" + Clock.millis());
            else s.present.remove(studentId);
            store.log(user.id, "attendance." + (present ? "present" : "absent"), s.id, store.students.get(studentId).name);
            store.save("sessions", "audit");
            return s;
        }
    }

    // ------------------------------------------------------------------ attendance numbers

    /** Attendance of every student in every course: studentId -> courseId -> {attended, held}. */
    public Map<String, Map<String, int[]>> attendanceTable() {
        synchronized (store) {
            Map<String, Map<String, int[]>> t = new HashMap<>();
            for (ClassSession s : store.sessions.values()) {
                Course c = store.courses.get(s.courseId);
                if (c == null) continue;
                for (String sid : c.studentIds) {
                    int[] a = t.computeIfAbsent(sid, k -> new HashMap<>()).computeIfAbsent(c.id, k -> new int[2]);
                    a[1]++;
                    if (s.present.containsKey(sid)) a[0]++;
                }
            }
            return t;
        }
    }

    public static double pct(Map<String, int[]> perCourse) {
        int att = 0, held = 0;
        if (perCourse != null) for (int[] a : perCourse.values()) { att += a[0]; held += a[1]; }
        return held == 0 ? 100 : 100.0 * att / held;
    }

    public double attendancePct(String studentId) {
        return pct(attendanceTable().get(studentId));
    }

    public int libraryVisits30(Student s) {
        long now = Clock.millis();
        int n = 0;
        for (long t : s.libraryVisits) if (now - t <= 30L * 24 * 3_600_000L && t <= now) n++;
        return n;
    }

    // ------------------------------------------------------------------ what each person may see

    public boolean canSee(User u, Student s) {
        if (u.role == Role.ADMIN) return true;
        if (u.role == Role.STUDENT) return s.id.equals(u.studentId);
        if (u.id.equals(s.mentorId)) return true;
        for (Course c : store.courses.values()) if (u.id.equals(c.facultyId) && c.studentIds.contains(s.id)) return true;
        return false;
    }

    public Student student(User u, String id) {
        synchronized (store) {
            Student s = store.students.get(id);
            if (s == null || !canSee(u, s)) throw Problem.notFound("Student");
            return s;
        }
    }

    private Course courseFor(User user, String courseId) {
        Course c = store.courses.get(courseId);
        if (c == null) throw Problem.notFound("Course");
        if (user.role == Role.STUDENT) throw Problem.forbidden("Only teachers can run classes.");
        if (user.role == Role.FACULTY && !user.id.equals(c.facultyId)) throw Problem.forbidden("This is not your course.");
        return c;
    }

    private ClassSession session(User user, String id) {
        ClassSession s = store.sessions.get(id);
        if (s == null) throw Problem.notFound("Class");
        courseFor(user, s.courseId);
        return s;
    }

    // ------------------------------------------------------------------ views for the website

    /** Today's timetable, the classes running now and (for teachers) their courses. */
    public Map<String, Object> classesView(User u) {
        synchronized (store) {
            ZonedDateTime z = ZonedDateTime.ofInstant(Clock.now(), Clock.ZONE);
            int day = z.getDayOfWeek().getValue();
            String today = z.toLocalDate().toString();
            Map<String, Map<String, int[]>> table = attendanceTable();
            List<Map<String, Object>> todayList = new ArrayList<>();
            List<Map<String, Object>> courses = new ArrayList<>();
            for (Course c : store.courses.values()) {
                if (!involved(u, c)) continue;
                for (int[] slot : c.slots) {
                    if (slot[0] != day) continue;
                    ClassSession s = store.sessions.get(sessionId(c.id, today, slot[1]));
                    todayList.add(Json.obj("courseId", c.id, "courseName", c.name, "hour", slot[1], "roomId", c.roomId,
                            "roomName", store.rooms.get(c.roomId).name, "faculty", store.userName(c.facultyId),
                            "sessionId", s == null ? null : s.id, "open", s != null && s.isOpen(),
                            "present", s == null ? 0 : s.present.size(), "enrolled", c.studentIds.size(),
                            "state", s == null ? (slot[1] > z.getHour() ? "UPCOMING" : "MISSED") : s.isOpen() ? "LIVE" : "DONE"));
                }
                Map<String, Object> cm = courseSummary(c, table);
                if (u.role == Role.STUDENT) {
                    int[] mine = table.getOrDefault(u.studentId, Map.of()).get(c.id);
                    cm.put("myAttended", mine == null ? 0 : mine[0]);
                    cm.put("myHeld", mine == null ? 0 : mine[1]);
                }
                courses.add(cm);
            }
            for (ClassSession s : store.sessions.values()) {      // extra classes started today
                if (!"EXTRA".equals(s.source) || !today.equals(s.date)) continue;
                Course c = store.courses.get(s.courseId);
                if (c == null || !involved(u, c)) continue;
                todayList.add(Json.obj("courseId", c.id, "courseName", c.name + " (extra)", "hour", s.hour, "roomId", s.roomId,
                        "roomName", store.rooms.get(s.roomId).name, "faculty", store.userName(c.facultyId), "sessionId", s.id,
                        "open", s.isOpen(), "present", s.present.size(), "enrolled", c.studentIds.size(), "state", s.isOpen() ? "LIVE" : "DONE"));
            }
            todayList.sort(Comparator.comparingInt(m -> ((Number) m.get("hour")).intValue()));
            List<Map<String, Object>> live = new ArrayList<>();
            for (ClassSession s : store.sessions.values()) {
                if (!s.isOpen()) continue;
                Course c = store.courses.get(s.courseId);
                if (c != null && involved(u, c)) live.add(sessionView(s, u.role != Role.STUDENT));
            }
            return Json.obj("today", todayList, "live", live, "courses", courses, "weekday", day, "hourNow", z.getHour());
        }
    }

    private boolean involved(User u, Course c) {
        if (u.role == Role.ADMIN) return true;
        if (u.role == Role.FACULTY) return u.id.equals(c.facultyId);
        return u.studentId != null && c.studentIds.contains(u.studentId);
    }

    private Map<String, Object> courseSummary(Course c, Map<String, Map<String, int[]>> table) {
        int att = 0, held = 0, below = 0;
        for (String sid : c.studentIds) {
            int[] a = table.getOrDefault(sid, Map.of()).get(c.id);
            if (a == null) continue;
            att += a[0];
            held += a[1];
            if (a[1] > 0 && 100.0 * a[0] / a[1] < MIN_ATTENDANCE) below++;
        }
        Map<String, Object> m = c.toJson();
        m.remove("studentIds");
        m.put("enrolled", c.studentIds.size());
        m.put("faculty", store.userName(c.facultyId));
        m.put("roomName", store.rooms.get(c.roomId).name);
        m.put("avgAttendance", held == 0 ? null : Stats.round1(100.0 * att / held));
        m.put("below75", below);
        return m;
    }

    public Map<String, Object> sessionDetail(User u, String id) {
        synchronized (store) {
            ClassSession s = session(u, id);
            return sessionView(s, true);
        }
    }

    private Map<String, Object> sessionView(ClassSession s, boolean withList) {
        Course c = store.courses.get(s.courseId);
        Map<String, Object> m = Json.obj("id", s.id, "courseId", c.id, "courseName", c.name, "roomId", s.roomId,
                "roomName", store.rooms.get(s.roomId).name, "date", s.date, "hour", s.hour, "startedAt", s.startedAt,
                "endedAt", s.endedAt, "open", s.isOpen(), "source", s.source, "present", s.present.size(),
                "enrolled", c.studentIds.size(), "faculty", store.userName(c.facultyId));
        if (withList) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (String sid : c.studentIds) {
                Student st = store.students.get(sid);
                String how = s.present.get(sid);
                list.add(Json.obj("id", sid, "name", st.name, "roll", st.roll, "present", how != null,
                        "how", how == null ? null : how.substring(0, how.indexOf('@')),
                        "at", how == null ? null : Long.parseLong(how.substring(how.indexOf('@') + 1))));
            }
            list.sort(Comparator.comparing(x -> (String) x.get("roll")));
            m.put("students", list);
        }
        return m;
    }

    /** Course-wise attendance for one student, plus whether they meet the 75% rule. */
    public List<Map<String, Object>> attendanceOf(Student s, Map<String, Map<String, int[]>> table) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, int[]> mine = table.getOrDefault(s.id, Map.of());
        for (Course c : store.courses.values()) {
            if (!c.studentIds.contains(s.id)) continue;
            int[] a = mine.getOrDefault(c.id, new int[2]);
            double p = a[1] == 0 ? 100 : 100.0 * a[0] / a[1];
            // how many more classes in a row to reach 75%:  (att + k) / (held + k) >= 0.75
            int need = 0;
            if (p < MIN_ATTENDANCE) need = (int) Math.ceil((0.75 * a[1] - a[0]) / 0.25);
            out.add(Json.obj("courseId", c.id, "courseName", c.name, "attended", a[0], "held", a[1], "pct", Stats.round1(p),
                    "ok", p >= MIN_ATTENDANCE, "classesNeeded", need));
        }
        return out;
    }

    /** Weekly attendance % for one student or for everyone (the last 6 weeks). */
    public List<Map<String, Object>> weekly(String studentIdOrNull, String courseIdOrNull) {
        synchronized (store) {
            LocalDate today = Clock.today();
            LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
            List<Map<String, Object>> out = new ArrayList<>();
            for (int w = 5; w >= 0; w--) {
                LocalDate from = monday.minusWeeks(w), to = from.plusDays(7);
                int att = 0, held = 0;
                for (ClassSession s : store.sessions.values()) {
                    LocalDate d = LocalDate.parse(s.date);
                    if (d.isBefore(from) || !d.isBefore(to)) continue;
                    if (courseIdOrNull != null && !courseIdOrNull.equals(s.courseId)) continue;
                    Course c = store.courses.get(s.courseId);
                    if (c == null) continue;
                    if (studentIdOrNull != null) {
                        if (!c.studentIds.contains(studentIdOrNull)) continue;
                        held++;
                        if (s.present.containsKey(studentIdOrNull)) att++;
                    } else {
                        held += c.studentIds.size();
                        att += s.present.size();
                    }
                }
                out.add(Json.obj("week", from.toString(), "pct", held == 0 ? null : Stats.round1(100.0 * att / held)));
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ notices

    public List<Object> notices() {
        synchronized (store) {
            List<com.cecil.parisar.model.Notice> list = new ArrayList<>(store.notices.values());
            list.sort(Comparator.comparing((com.cecil.parisar.model.Notice n) -> n.pinned ? 0 : 1).thenComparing(n -> -n.postedAt));
            List<Object> out = new ArrayList<>();
            for (com.cecil.parisar.model.Notice n : list) {
                Map<String, Object> m = n.toJson();
                m.put("postedByName", store.userName(n.postedBy));
                out.add(m);
            }
            return out;
        }
    }

    public com.cecil.parisar.model.Notice postNotice(User u, Map<String, Object> body) {
        if (!u.role.atLeast(Role.FACULTY)) throw Problem.forbidden("Only staff can post notices.");
        String title = Text.clean(Json.str(body, "title"), 120);
        String text = Text.clean(Json.str(body, "body"), 2000);
        String cat = Text.clean(Json.str(body, "category"), 20);
        if (title == null || text == null) throw Problem.bad("A notice needs a title and some text.");
        if (cat == null || !List.of("ACADEMIC", "EXAM", "EVENT", "FACILITY", "HOSTEL").contains(cat.toUpperCase())) cat = "ACADEMIC";
        synchronized (store) {
            com.cecil.parisar.model.Notice n = new com.cecil.parisar.model.Notice();
            n.id = store.nextId("N", 101);
            n.title = title;
            n.body = text;
            n.category = cat.toUpperCase();
            n.postedBy = u.id;
            n.postedAt = Clock.millis();
            n.pinned = u.role == Role.ADMIN && Json.bool(body, "pinned", false);
            store.notices.put(n.id, n);
            store.log(u.id, "notice.post", n.id, n.title);
            store.save("notices", "audit");
            return n;
        }
    }

    public void deleteNotice(User u, String id) {
        synchronized (store) {
            com.cecil.parisar.model.Notice n = store.notices.get(id);
            if (n == null) throw Problem.notFound("Notice");
            if (u.role != Role.ADMIN && !u.id.equals(n.postedBy)) throw Problem.forbidden("You can only remove your own notices.");
            store.notices.remove(id);
            store.log(u.id, "notice.delete", id, n.title);
            store.save("notices", "audit");
        }
    }

    /** Small helper used by several views. */
    public Map<String, Object> studentRow(Student s, Map<String, Map<String, int[]>> table) {
        Map<String, Object> m = new LinkedHashMap<>(s.publicView());
        m.put("attendance", Stats.round1(pct(table.get(s.id))));
        m.put("libraryVisits30", libraryVisits30(s));
        m.put("mentor", store.userName(s.mentorId));
        return m;
    }
}
