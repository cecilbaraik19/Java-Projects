package com.cecil.qpg.store;

import com.cecil.qpg.generator.PaperGenerator;
import com.cecil.qpg.model.Blueprint;
import com.cecil.qpg.model.Enums.Bloom;
import com.cecil.qpg.model.Enums.Difficulty;
import com.cecil.qpg.model.Enums.PaperStatus;
import com.cecil.qpg.model.Enums.QType;
import com.cecil.qpg.model.Enums.Role;
import com.cecil.qpg.model.Paper;
import com.cecil.qpg.model.Question;
import com.cecil.qpg.model.Subject;
import com.cecil.qpg.model.User;
import com.cecil.qpg.util.Passwords;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Demo data made on the first run: 4 people, 4 subjects with units, about 190 questions,
 * 4 blueprints and a few papers in different stages. All names are made up.
 */
public final class SeedData {
    private SeedData() {}

    public static void fill(DataStore s) {
        String now = s.now();
        user(s, "admin", "Admin", "Examination Cell", Role.ADMIN, "admin123", now);
        user(s, "meera", "Dr. Meera Sinha", "Information Technology", Role.HOD, "hod12345", now);
        user(s, "rohan", "Rohan Verma", "Information Technology", Role.FACULTY, "faculty123", now);
        user(s, "ananya", "Ananya Kujur", "Information Technology", Role.FACULTY, "faculty123", now);

        subject(s, "CSIT135", "Operating Systems", "B.Sc. IT", 2,
                "Introduction to Operating Systems", "Process Management and CPU Scheduling",
                "Process Synchronization and Deadlocks", "Memory Management", "File Systems and Disk Scheduling");
        subject(s, "CSIT124", "Object Oriented Programming using Java", "B.Sc. IT", 2,
                "Java Basics", "Classes, Objects and Inheritance", "Exceptions and Packages",
                "Multithreading and Collections", "GUI Programming with Swing");
        subject(s, "CSIT213", "Database Management Systems", "B.Sc. IT", 3,
                "Introduction and ER Model", "Relational Model and SQL", "Normalization", "Transactions and Concurrency");
        subject(s, "CSIT225", "Computer Networks", "B.Sc. IT", 3,
                "Network Models and Devices", "Physical and Data Link Layer", "Network Layer and IP Addressing",
                "Transport and Application Layer");

        load(s, "CSIT135", SeedQuestions.OS, new String[]{"rohan", "meera"});
        load(s, "CSIT124", SeedQuestions.JAVA, new String[]{"ananya", "rohan"});
        load(s, "CSIT213", SeedQuestions.DBMS, new String[]{"ananya"});
        load(s, "CSIT225", SeedQuestions.CN, new String[]{"rohan"});

        List<String> std = List.of(
                "Attempt all sections. Read the instructions of each section carefully.",
                "Figures on the right show full marks.",
                "Draw neat diagrams wherever needed.",
                "Mobile phones and smart watches are not allowed in the exam hall.");

        Blueprint endTerm = blueprint(s, "OS End-Term (70 marks)", "CSIT135", "End-Term Examination", 180, std,
                new int[]{30, 50, 20}, List.of(), 90, "meera",
                sec("Section A", "Choose the correct option.", QType.MCQ, 10, 10, 1),
                sec("Section B", "State whether True or False.", QType.TRUE_FALSE, 5, 5, 1),
                sec("Section C", "Answer any five questions.", QType.SHORT, 7, 5, 5),
                sec("Section D", "Answer any three questions.", QType.LONG, 4, 3, 10));
        Blueprint midTerm = blueprint(s, "OS Mid-Term (30 marks)", "CSIT135", "Mid-Term Examination", 90,
                List.of("Answer all questions in Section A.", "Figures on the right show full marks."),
                new int[]{40, 40, 20}, List.of(1, 2, 3), 0, "rohan",
                sec("Section A", "Choose the correct option.", QType.MCQ, 10, 10, 1),
                sec("Section B", "Answer any four questions.", QType.SHORT, 5, 4, 5));
        blueprint(s, "Java Class Test (20 marks)", "CSIT124", "Class Test 1", 45,
                List.of("Answer all questions in Section A.", "Write programs with proper indentation."),
                new int[]{40, 40, 20}, List.of(), 0, "ananya",
                sec("Section A", "Choose the correct option.", QType.MCQ, 10, 10, 1),
                sec("Section B", "Answer any two questions.", QType.SHORT, 3, 2, 5));
        blueprint(s, "OS Numerical Quiz (20 marks)", "CSIT135", "Problem Solving Quiz", 60,
                List.of("Show every step of your working.", "Calculators are allowed."),
                new int[]{0, 50, 50}, List.of(2, 4, 5), 0, "rohan",
                sec("Problems", "Answer any two problems.", QType.NUMERICAL, 3, 2, 10));

        LocalDate today = s.today();
        // A mid-term released about 6 weeks ago, so the bank has some "recently used" history
        List<Paper> old = make(s, midTerm, 1, 20260815L, today.minusDays(44), "rohan");
        Paper released = old.get(0);
        released.status = PaperStatus.APPROVED;
        released.approvedBy = "meera";
        released.approvedAt = stamp(today.minusDays(46), "15:20:00");
        released.status = PaperStatus.RELEASED;
        released.releasedAt = stamp(today.minusDays(44), "09:00:00");
        for (String id : released.questionIds()) {
            Question q = s.question(id);
            q.timesUsed++;
            q.lastUsedOn = today.minusDays(44).toString();
        }
        s.log("rohan", "generated", "Generated " + released.title, "paper:" + released.id);
        s.log("meera", "approved", "Approved " + released.title, "paper:" + released.id);
        s.log("meera", "released", "Released " + released.title + " for the exam", "paper:" + released.id);

        // End-term in two sets: Set A approved, Set B waiting for review
        List<Paper> sets = make(s, endTerm, 2, 20261101L, today.plusDays(24), "meera");
        sets.get(0).status = PaperStatus.APPROVED;
        sets.get(0).approvedBy = "meera";
        sets.get(0).approvedAt = s.now();
        sets.get(1).status = PaperStatus.SUBMITTED;
        s.log("meera", "generated", "Generated 2 sets of " + endTerm.name, "paper:" + sets.get(0).id);
        s.log("meera", "approved", "Approved " + sets.get(0).title, "paper:" + sets.get(0).id);
        s.log("meera", "submitted", "Sent " + sets.get(1).title + " for review", "paper:" + sets.get(1).id);

        Blueprint javaBp = s.blueprints.get(2);
        List<Paper> java = make(s, javaBp, 1, 20261029L, today.plusDays(6), "ananya");
        s.log("ananya", "generated", "Generated " + java.get(0).title, "paper:" + java.get(0).id);
    }

    private static List<Paper> make(DataStore s, Blueprint bp, int sets, long seed, LocalDate examDate, String by) {
        PaperGenerator.Options o = new PaperGenerator.Options();
        o.sets = sets;
        o.seed = seed;
        o.examDate = examDate.toString();
        o.institute = s.settings.institute;
        o.today = s.today();
        o.duplicateThreshold = s.settings.duplicateThreshold;
        List<Paper> out = PaperGenerator.generate(bp, s.subject(bp.subjectCode), s.questions, o);
        String group = s.newGroupId();
        for (Paper p : out) {
            p.id = s.newPaperId();
            p.groupId = group;
            p.createdBy = by;
            p.createdAt = s.now();
            p.updatedAt = p.createdAt;
            s.papers.add(p);
        }
        return out;
    }

    private static String stamp(LocalDate d, String time) { return d + "T" + time; }

    private static void user(DataStore s, String un, String name, String dept, Role role, String pw, String now) {
        User u = new User();
        u.username = un;
        u.displayName = name;
        u.department = dept;
        u.role = role;
        u.passwordHash = Passwords.hash(pw);
        u.createdAt = now;
        s.users.add(u);
    }

    private static void subject(DataStore s, String code, String name, String course, int sem, String... units) {
        Subject sub = new Subject();
        sub.code = code;
        sub.name = name;
        sub.course = course;
        sub.semester = sem;
        for (int i = 0; i < units.length; i++) sub.units.add(new Subject.Unit(i + 1, units[i]));
        s.subjects.add(sub);
    }

    /** Reads the question lines from SeedQuestions. */
    static void load(DataStore s, String subject, String block, String[] authors) {
        int n = 0;
        for (String line : block.split("\n")) {
            if (line.isBlank()) continue;
            String[] f = line.split("\\|", -1);
            if (f.length != 8) throw new IllegalStateException("Bad seed line: " + line);
            Question q = new Question();
            q.id = s.newQuestionId();
            q.subjectCode = subject;
            q.unit = Integer.parseInt(f[0].trim());
            q.type = switch (f[1].trim()) {
                case "TF" -> QType.TRUE_FALSE;
                case "FILL" -> QType.FILL_BLANK;
                case "NUM" -> QType.NUMERICAL;
                default -> QType.valueOf(f[1].trim());
            };
            q.difficulty = switch (f[2].trim()) { case "E" -> Difficulty.EASY; case "H" -> Difficulty.HARD; default -> Difficulty.MEDIUM; };
            q.bloom = switch (f[3].trim()) {
                case "R" -> Bloom.REMEMBER; case "AP" -> Bloom.APPLY; case "AN" -> Bloom.ANALYZE;
                case "EV" -> Bloom.EVALUATE; case "C" -> Bloom.CREATE; default -> Bloom.UNDERSTAND;
            };
            q.marks = Integer.parseInt(f[4].trim());
            q.text = f[5].trim();
            q.options = f[6].isBlank() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(f[6].split("~")));
            q.answer = f[7].trim();
            if (q.type == QType.TRUE_FALSE) q.options = new ArrayList<>(List.of("True", "False"));
            q.createdBy = authors[n % authors.length];
            q.createdAt = s.today().minusDays(120 - (n % 90)) + "T10:00:00";
            q.updatedAt = q.createdAt;
            s.questions.add(q);
            n++;
        }
    }

    private static Blueprint.Section sec(String title, String instr, QType t, int count, int attempt, int marks) {
        Blueprint.Section x = new Blueprint.Section();
        x.title = title;
        x.instructions = instr;
        x.type = t;
        x.count = count;
        x.attempt = attempt;
        x.marksEach = marks;
        return x;
    }

    private static Blueprint blueprint(DataStore s, String name, String subject, String exam, int minutes, List<String> instr,
                                       int[] mix, List<Integer> units, int avoidDays, String by, Blueprint.Section... secs) {
        Blueprint b = new Blueprint();
        b.id = s.newBlueprintId();
        b.name = name;
        b.subjectCode = subject;
        b.examType = exam;
        b.durationMin = minutes;
        b.instructions = new ArrayList<>(instr);
        b.mix.put(Difficulty.EASY, mix[0]);
        b.mix.put(Difficulty.MEDIUM, mix[1]);
        b.mix.put(Difficulty.HARD, mix[2]);
        b.units = new ArrayList<>(units);
        b.avoidRecentDays = avoidDays;
        b.sections.addAll(Arrays.asList(secs));
        b.createdBy = by;
        b.createdAt = s.now();
        b.updatedAt = b.createdAt;
        s.blueprints.add(b);
        return b;
    }
}
