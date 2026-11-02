package com.cecil.rag.store;

import com.cecil.rag.model.Doc;
import com.cecil.rag.model.QaRecord;
import com.cecil.rag.model.Role;
import com.cecil.rag.model.User;
import com.cecil.rag.model.Verified;
import com.cecil.rag.service.AskService;
import com.cecil.rag.service.KnowledgeService;
import com.cecil.rag.util.Clock;
import com.cecil.rag.util.Json;
import com.cecil.rag.util.Passwords;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Demo data for the first run: users, the college documents from the "knowledge" folder,
 * verified answers, test questions, and about one month of real question history.
 * The history is made by really asking the assistant, so every number on the Insights page is real.
 * All people and the college are made up.
 */
public final class SeedData {
    private SeedData() {}

    // ------------------------------------------------------------------ users

    public static void users(DataStore store) {
        add(store, "admin", "Anita Xalxo", Role.ADMIN, "Knowledge Cell", "", "admin123");
        add(store, "meena", "Dr. Meena Tirkey", Role.FACULTY, "Information Technology", "", "faculty123");
        add(store, "rohit", "Mr. Rohit Ekka", Role.FACULTY, "Training and Placement", "", "faculty123");
        add(store, "aarav", "Aarav Sharma", Role.STUDENT, "Information Technology", "B.Sc. IT - Semester 3", "student123");
        add(store, "priya", "Priya Kumari", Role.STUDENT, "Computer Applications", "BCA - Semester 5", "student123");
        String[][] more = {
                {"rahul", "Rahul Munda", "B.Sc. IT - Semester 3"}, {"sneha", "Sneha Lakra", "B.Sc. IT - Semester 5"},
                {"amit", "Amit Mahto", "B.Tech CSE - Semester 3"}, {"pooja", "Pooja Singh", "BCA - Semester 1"},
                {"karan", "Karan Gupta", "MCA - Semester 1"}, {"riya", "Riya Tirkey", "B.Com - Semester 3"},
                {"vikas", "Vikas Oraon", "B.Sc. IT - Semester 1"}, {"neha", "Neha Kujur", "B.Tech CSE - Semester 5"},
                {"arjun", "Arjun Prasad", "BCA - Semester 3"}, {"anjali", "Anjali Minz", "B.Sc. IT - Semester 5"},
                {"sumit", "Sumit Kachhap", "MCA - Semester 3"}, {"divya", "Divya Hembrom", "B.Sc. IT - Semester 3"},
                {"rohan", "Rohan Ekka", "B.Tech CSE - Semester 7"}, {"kajal", "Kajal Toppo", "B.Com - Semester 5"},
                {"manish", "Manish Bara", "BCA - Semester 5"}, {"shreya", "Shreya Sinha", "B.Sc. IT - Semester 1"}};
        for (String[] m : more) add(store, m[0], m[1], Role.STUDENT, m[2].startsWith("B.Com") ? "Commerce" : "Computing", m[2], "student123");
    }

    private static void add(DataStore store, String username, String name, Role role, String dept, String programme, String pw) {
        User u = new User();
        u.id = store.nextId("u");
        u.username = username;
        u.name = name;
        u.role = role;
        u.dept = dept;
        u.programme = programme;
        u.passwordHash = Passwords.hash(pw);
        u.createdAt = Clock.now() - 120L * 86_400_000;
        store.users.put(u.id, u);
    }

    // ------------------------------------------------------------------ documents

    /** Reads every .md file in the knowledge folder. The part between --- lines holds the details. */
    public static void documents(DataStore store, Path knowledgeDir) throws IOException {
        if (!Files.isDirectory(knowledgeDir)) {
            System.err.println("Knowledge folder not found: " + knowledgeDir.toAbsolutePath());
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(knowledgeDir)) {
            files = s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
        long base = Clock.now() - 100L * 86_400_000;
        int i = 0;
        for (Path p : files) {
            Doc d = parse(Files.readString(p, StandardCharsets.UTF_8), p.getFileName().toString());
            d.uploadedAt = base + (i++) * 3_600_000L * 5;
            d.updatedAt = d.uploadedAt;
            if (d.id == null) d.id = store.nextId("doc-");
            store.docs.put(d.id, d);
        }
    }

    public static Doc parse(String raw, String fileName) {
        Doc d = new Doc();
        d.fileName = fileName;
        d.fileType = "md";
        String text = raw.replace("\r\n", "\n");
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 4);
            String head = text.substring(4, end);
            text = text.substring(text.indexOf('\n', end + 1) + 1);
            for (String line : head.split("\n")) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String k = line.substring(0, c).strip(), v = line.substring(c + 1).strip();
                switch (k) {
                    case "id" -> d.id = v;
                    case "title" -> d.title = v;
                    case "category" -> d.category = v;
                    case "department" -> d.department = v;
                    case "audience" -> d.audience = v;
                    case "status" -> d.status = v;
                    case "effective" -> d.effectiveDate = v;
                    case "review" -> d.reviewDate = v;
                    case "uploadedBy" -> { d.uploadedBy = v; d.updatedBy = v; }
                    default -> {}
                }
            }
        }
        d.text = text.strip();
        if (d.title == null || d.title.isBlank()) d.title = fileName;
        return d;
    }

    // ------------------------------------------------------------------ verified answers and test questions

    public static void verified(DataStore store) {
        User meena = store.userByName("meena");
        User admin = store.userByName("admin");
        v(store, meena, "What is the minimum attendance required to sit the exam?",
                "You need at least 75% attendance in every subject; theory and practical are counted separately. "
                        + "If your attendance is between 65% and 75%, you can sit the exam only on medical grounds: give a medical "
                        + "certificate to the HoD within 7 days and pay a condonation fee of ₹500 per subject. Below 65% you are "
                        + "detained in that subject and repeat it with the next batch.",
                List.of("doc-handbook", "doc-exams"));
        v(store, meena, "Which electives can I choose in B.Sc. IT semester 5?",
                "You choose two electives from: Cyber Security and Ethical Hacking, Cloud Computing, Data Science with Python, "
                        + "Mobile App Development, and Internet of Things. An elective runs only if at least 15 students choose it. "
                        + "Choices are made on the portal in the last week of semester 4.",
                List.of("doc-bscit"));
        v(store, admin, "How do I connect my laptop to the campus Wi-Fi?",
                "Take your laptop or phone and your college ID card to the IT Help Desk (Room A-014). They register the device "
                        + "for the \"SVIT-Campus\" network. Each student can register 2 devices. In the hostel, Wi-Fi works from "
                        + "6:00 AM to midnight.",
                List.of("doc-contacts", "doc-transport", "doc-hostel"));
    }

    private static void v(DataStore store, User by, String q, String a, List<String> docs) {
        Verified v = new Verified();
        v.id = store.nextId("v");
        v.question = q;
        v.answer = a;
        v.docIds.addAll(docs);
        v.byId = by.id;
        v.byName = by.name;
        v.at = Clock.now() - 20L * 86_400_000;
        store.verified.put(v.id, v);
    }

    public static final String[][] EVAL = {
            {"How much is the tuition fee for B.Sc. IT?", "doc-fees", "38,000"},
            {"What is the late fee if I pay the semester fee after the due date?", "doc-fees", "50"},
            {"Can I get a refund if I cancel admission in the first week of classes?", "doc-fees", "80%"},
            {"What is the minimum attendance required?", "doc-handbook", "75%"},
            {"How many books can an undergraduate student borrow?", "doc-library", "4 books"},
            {"What are the library timings on Saturday?", "doc-library", "9:00 AM,5:00 PM"},
            {"What is the fine for returning a library book late?", "doc-library", "₹2"},
            {"What time is dinner served in the hostel mess?", "doc-hostel", "8:00 PM"},
            {"What is the in-time for the girls hostel?", "doc-hostel", "8:30 PM"},
            {"How much does accommodation cost for a shared room?", "doc-hostel", "48,000"},
            {"How do I apply for re-evaluation and what does it cost?", "doc-exams", "750"},
            {"What are the passing marks in a subject?", "doc-exams", "40%"},
            {"How do I convert CGPA into percentage?", "doc-exams", "9.5"},
            {"Can I get my answer sheet rechecked?", "doc-exams", "re-evaluation"},
            {"When do the end semester theory exams start?", "doc-calendar", "24 November"},
            {"When is the Durga Puja break?", "doc-calendar", "17 October"},
            {"What CGPA is needed for campus placement?", "doc-placement", "6.5"},
            {"Is the summer internship compulsory for B.Sc. IT students?", "doc-placement", "6 to 8 weeks"},
            {"How do I report ragging?", "doc-conduct", "helpline"},
            {"Which subjects are taught in B.Sc. IT semester 3?", "doc-bscit", "Operating Systems"},
            {"What is the extension number of the Registrar?", "doc-contacts", "205"},
            {"When does the college bus leave the campus in the evening?", "doc-transport", "4:45 PM"},
            {"What is the bus fee for one semester?", "doc-transport", "9,000"},
            {"How can I join the cyber security club?", "doc-clubs", "CipherSquad"},
            {"What scholarship does the first rank holder get?", "doc-scholarships", "50%"},
            {"What is the family income limit for the freeship?", "doc-scholarships", "2.5 lakh"},
            {"How long does it take to get a bonafide certificate?", "doc-handbook", "2 working days"},
            {"When is the doctor available in the medical room?", "doc-contacts", "Tuesdays"},
    };

    public static void evaluation(DataStore store) {
        List<Object> cases = new ArrayList<>();
        for (String[] e : EVAL) {
            List<Object> kw = new ArrayList<>(List.of((Object[]) e[2].split(",")));
            cases.add(Json.map("id", store.nextId("e"), "question", e[0], "expectedDoc", e[1], "keywords", kw));
        }
        store.evaluation.put("cases", cases);
    }

    // ------------------------------------------------------------------ question history

    static final String[] QUESTIONS = {
            "How much is the tuition fee for B.Sc. IT?", "What is the BCA fee per semester?",
            "What is the last date to pay the odd semester fee?", "What is the late fee for fees?",
            "Can I pay the fees in instalments?", "How much is the caution deposit?",
            "What is the minimum attendance required?", "What happens if my attendance is below 65%?",
            "How many books can I borrow from the library?", "What are the library timings?",
            "Is the library open on Sunday?", "What is the library fine for late return?",
            "What are the mess timings?", "What is the hostel fee for a single room?",
            "What is the in-time for boys hostel?", "How do I get night out permission?",
            "When can parents visit the hostel?", "How do I apply for re-evaluation?",
            "What is the passing mark?", "When will admit cards be available?",
            "What is the fee for supplementary exam?", "How is CGPA converted to percentage?",
            "When do end semester exams start?", "When is the winter break?", "When is Sanskriti 2027?",
            "When is the convocation?", "What is the eligibility for placement?", "What was the highest package last year?",
            "Is internship compulsory?", "What is the dream company rule?", "How do I report ragging?",
            "How do I file a grievance?", "Who is the HoD of IT?", "What are the semester 3 subjects of B.Sc. IT?",
            "What are the units of Operating Systems?", "Which electives are there in semester 5?",
            "How do I connect to campus Wi-Fi?", "What is the bus fee?", "What time does the first bus leave?",
            "What are the canteen timings?", "How can I join ByteBuilders?", "How do I get a bonafide certificate?",
            "What is the cost of a duplicate ID card?", "When can I apply for scholarship?",
            "What documents are needed for the scholarship?", "Who is the scholarship coordinator?",
            "When is the doctor available?", "Where is the counsellor's office?", "What is the dress code?",
            "How much is the transcript fee?", "What is the refund policy?", "How many credits is B.Sc. IT?",
    };

    static final String[][] FOLLOW_UPS = {
            {"What is the hostel fee for twin sharing?", "and the mess charges?"},
            {"What are the library timings?", "and on Saturday?"},
            {"How do I apply for re-evaluation?", "how much does it cost?"},
            {"What is the eligibility for placement?", "what about backlogs?"},
            {"When do end semester exams start?", "and when do they end?"},
    };

    static final String[] GAPS = {
            "What are the gym timings?", "Is there a gym on campus?", "Is there a swimming pool?",
            "What is the wifi password?", "What is the dress code for convocation?", "What is today's canteen menu?",
            "What is the registration fee for Sanskriti for outside students?", "Can I do a minor degree in AI?",
            "Who won the inter-college cricket match?", "What is the convocation registration fee?",
    };

    /** Asks ~350 real questions spread over the last 35 days. */
    public static void history(DataStore store, AskService ask, KnowledgeService knowledge) {
        Random rnd = new Random(42);
        List<User> students = new ArrayList<>();
        List<User> faculty = new ArrayList<>();
        for (User u : store.users.values()) (u.role == Role.STUDENT ? students : faculty).add(u);
        LocalDate today = Clock.today();
        long realNow = Clock.now();
        java.time.Instant before = Clock.fixedOrNull();
        List<QaRecord> made = new ArrayList<>();
        for (int day = 34; day >= 0; day--) {
            LocalDate d = today.minusDays(day);
            int n = 5 + rnd.nextInt(8) + (d.getDayOfWeek().getValue() <= 5 ? 3 : 0);
            if (day == 0) n = 4;
            for (int k = 0; k < n; k++) {
                User u = rnd.nextInt(10) == 0 ? faculty.get(rnd.nextInt(faculty.size())) : students.get(rnd.nextInt(students.size()));
                int hour = pickHour(rnd);
                long at = ZonedDateTime.of(d.atTime(hour, rnd.nextInt(60)), Clock.ZONE).toInstant().toEpochMilli();
                if (at > realNow) at = realNow - 60_000 - rnd.nextInt(3_600_000);
                double roll = rnd.nextDouble();
                if (roll < 0.10) {
                    String[] f = FOLLOW_UPS[rnd.nextInt(FOLLOW_UPS.length)];
                    QaRecord first = askOnce(store, ask, u, f[0], "", at, rnd);
                    made.add(first);
                    made.add(askOnce(store, ask, u, f[1], first.sessionId, at + 40_000, rnd));
                } else if (roll < 0.10 + (day > 20 ? 0.07 : 0.035)) {
                    made.add(askOnce(store, ask, u, GAPS[rnd.nextInt(GAPS.length)], "", at, rnd));
                } else {
                    made.add(askOnce(store, ask, u, QUESTIONS[rnd.nextInt(QUESTIONS.length)], "", at, rnd));
                }
            }
        }
        if (before == null) Clock.unfix(); else Clock.fix(before);
        // older gaps about Wi-Fi were already answered with a verified answer -> mark resolved
        for (QaRecord r : made) {
            if ("unanswered".equals(r.status) && r.question.toLowerCase().contains("wifi password")
                    && r.at < realNow - 20L * 86_400_000) {
                r.resolved = true;
                r.resolvedNote = "The password is personal; added a verified answer on registering devices.";
            }
        }
    }

    private static QaRecord askOnce(DataStore store, AskService ask, User u, String q, String session, long at, Random rnd) {
        Clock.fix(java.time.Instant.ofEpochMilli(at)); // the question is "asked" at that past time
        ask.answer(u, q, session, "", true);
        QaRecord r = store.records.get(store.records.size() - 1);
        r.ms = 4 + rnd.nextInt(30);
        if ("answered".equals(r.status)) {
            double p = rnd.nextDouble();
            if (r.confidence >= 0.6) r.feedback = p < 0.52 ? "up" : p < 0.54 ? "down" : "";
            else r.feedback = p < 0.25 ? "up" : p < 0.40 ? "down" : "";
            if ("down".equals(r.feedback)) r.feedbackNote = rnd.nextBoolean() ? "Answer was not specific enough" : "";
        }
        return r;
    }

    private static int pickHour(Random rnd) {
        int[] weights = {0, 0, 0, 0, 0, 0, 1, 2, 4, 6, 8, 8, 6, 7, 8, 7, 6, 5, 4, 5, 7, 8, 6, 3};
        int sum = 0;
        for (int w : weights) sum += w;
        int x = rnd.nextInt(sum);
        for (int h = 0; h < 24; h++) { x -= weights[h]; if (x < 0) return h; }
        return 12;
    }

    /** Fills a new store with everything. */
    public static void seed(DataStore store, Path knowledgeDir, java.util.function.Supplier<AskService> askMaker,
                            java.util.function.Supplier<KnowledgeService> knowledgeMaker) throws IOException {
        store.pauseSaving = true;
        try {
            users(store);
            documents(store, knowledgeDir);
            verified(store);
            evaluation(store);
            KnowledgeService ks = knowledgeMaker.get();
            AskService ask = askMaker.get();
            history(store, ask, ks);
            store.records.sort((a, b) -> Long.compare(a.at, b.at));
        } finally {
            store.pauseSaving = false;
        }
        store.saveAll();
    }
}
