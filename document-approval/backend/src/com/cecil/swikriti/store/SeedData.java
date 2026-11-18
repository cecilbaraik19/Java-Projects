package com.cecil.swikriti.store;

import com.cecil.swikriti.model.DocStatus;
import com.cecil.swikriti.model.Document;
import com.cecil.swikriti.model.Note;
import com.cecil.swikriti.model.Role;
import com.cecil.swikriti.model.Task;
import com.cecil.swikriti.model.Template;
import com.cecil.swikriti.model.User;
import com.cecil.swikriti.service.Problem;
import com.cecil.swikriti.util.Clock;
import com.cecil.swikriti.util.Json;
import com.cecil.swikriti.util.Passwords;
import com.cecil.swikriti.web.ApiServer;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * Builds the demo company on the first start: Damodar Consulting Engineers (made up), an engineering consultancy in
 * Ranchi with 20 people, 6 document types, about 5 months of history and a few things waiting for the demo users.
 *
 * Nothing is typed into the data files directly. The history is PLAYED THROUGH THE REAL SERVICES with the clock moved
 * back: people write documents, submit them, approvers decide hours later (some late, so escalation happens), some
 * documents are sent back and revised, some are rejected. So every number in the reports got there by the normal rules,
 * and the ledger is a valid chain.
 */
public final class SeedData {
    private SeedData() { }

    private static ApiServer.Parts p;
    private static DataStore s;
    private static Random rnd;
    private static Instant realNow;
    private static final Map<String, User> U = new HashMap<>();

    public static synchronized void load(ApiServer.Parts parts) {
        System.setProperty("java.awt.headless", "true");
        p = parts;
        s = parts.store();
        rnd = new Random(2026);
        U.clear();
        Instant before = Clock.fixed();
        realNow = Clock.now();
        s.batch = true;
        try {
            s.meta.put("holidays", new ArrayList<>(List.of("2026-01-26", "2026-03-04", "2026-04-03", "2026-08-15",
                    "2026-10-02", "2026-10-20", "2026-11-09", "2026-11-25", "2026-12-25", "2027-01-26")));
            Clock.fix(workMoment(110, 9, 0));
            people();
            templates();
            history();
            script();
        } finally {
            Clock.fix(before);
        }
        p.flow().tick();      // escalate what became overdue "while the app was off"
        long old = Clock.millis() - 2 * 86_400_000L;
        for (Note n : s.notes.values()) if (n.at < old) n.read = true;
        s.saveAll();
    }

    // ================================================================== people

    private static void people() {
        add("U-1001", "kavita", "Kavita Ekka", "Managing Director", "Management", Role.MANAGER, null, "manager123", "Directors");
        add("U-1002", "arvind", "Arvind Mahto", "Technical Director", "Management", Role.MANAGER, "U-1001", "manager123", "Directors");
        add("U-1003", "farah", "Farah Siddiqui", "Finance Director", "Management", Role.MANAGER, "U-1001", "manager123", "Directors");
        add("U-1004", "sunita", "Sunita Lakra", "Projects Manager", "Projects", Role.MANAGER, "U-1002", "manager123");
        add("U-1005", "deepak", "Deepak Singh", "Design Manager", "Design", Role.MANAGER, "U-1002", "manager123");
        add("U-1006", "rahul", "Rahul Bhagat", "Finance Manager", "Finance", Role.MANAGER, "U-1003", "manager123", "Finance");
        add("U-1007", "neha", "Neha Kujur", "Document Controller", "Administration", Role.ADMIN, "U-1001", "admin123");
        add("U-1008", "imran", "Imran Ansari", "HR Manager", "HR", Role.MANAGER, "U-1001", "manager123", "HR");
        add("U-1009", "ankit", "Ankit Tirkey", "Design Engineer", "Design", Role.EMPLOYEE, "U-1005", "member123");
        add("U-1010", "priyanka", "Priyanka Munda", "Senior Design Engineer", "Design", Role.EMPLOYEE, "U-1005", "member123", "Design QA");
        add("U-1011", "sanjay", "Sanjay Oraon", "Draughtsman", "Design", Role.EMPLOYEE, "U-1005", "member123");
        add("U-1012", "meghna", "Meghna Roy", "Site Engineer", "Projects", Role.EMPLOYEE, "U-1004", "member123");
        add("U-1013", "vivek", "Vivek Kumar", "Project Engineer", "Projects", Role.EMPLOYEE, "U-1004", "member123");
        add("U-1014", "pooja", "Pooja Sharma", "Accountant", "Finance", Role.EMPLOYEE, "U-1006", "member123", "Finance");
        add("U-1015", "amit", "Amit Toppo", "Procurement Officer", "Projects", Role.EMPLOYEE, "U-1004", "member123", "Procurement");
        add("U-1016", "ritu", "Ritu Hembrom", "Procurement Assistant", "Projects", Role.EMPLOYEE, "U-1015", "member123", "Procurement");
        add("U-1017", "sneha", "Sneha Pandey", "Legal Counsel", "Legal", Role.EMPLOYEE, "U-1001", "member123", "Legal");
        add("U-1018", "manish", "Manish Gupta", "Senior Design Engineer", "Design", Role.EMPLOYEE, "U-1005", "member123", "Design QA");
        add("U-1019", "anjali", "Anjali Xaxa", "HR Executive", "HR", Role.EMPLOYEE, "U-1008", "member123", "HR");
        add("U-1020", "rohit", "Rohit Verma", "Site Engineer", "Projects", Role.EMPLOYEE, "U-1004", "member123");
        s.meta.put("next.U", 1021L);
    }

    private static void add(String id, String username, String name, String title, String dept, Role role, String manager,
                            String password, String... groups) {
        User u = new User();
        u.id = id; u.username = username; u.name = name; u.title = title; u.dept = dept; u.role = role;
        u.managerId = manager; u.location = id.equals("U-1012") || id.equals("U-1020") ? "Site office, Namkum" : "Ranchi";
        u.groups = new ArrayList<>(List.of(groups));
        u.passwordHash = Passwords.hash(password);
        s.users.put(id, u);
        U.put(username, u);
    }

    // ================================================================== templates (through the admin rules)

    private static void templates() {
        User neha = U.get("neha");
        String directors = "[\"USER:U-1001\",\"USER:U-1002\",\"USER:U-1003\"]";
        tpl(neha, "PR", "Purchase Requisition", "Buying goods or services. Finance checks above Rs. 50,000; two of the three directors above Rs. 5 lakh.",
                true, false, true, "["
                        + st("Line manager", "[\"MANAGER\"]", "ANY", 1, "", 8) + ","
                        + st("Procurement check", "[\"GROUP:Procurement\"]", "ANY", 1, "", 8) + ","
                        + st("Finance", "[\"GROUP:Finance\"]", "ANY", 1, "amount > 50k", 16) + ","
                        + st("Directors (2 of 3)", directors, "QUORUM", 2, "amount > 5L", 24) + "]");
        tpl(neha, "EXP", "Expense Claim", "Getting money back for work expenses. The department head also signs from Rs. 25,000.",
                true, false, false, "["
                        + st("Line manager", "[\"MANAGER\"]", "ANY", 1, "", 8) + ","
                        + st("Finance", "[\"GROUP:Finance\"]", "ANY", 1, "", 8) + ","
                        + st("Department head", "[\"MANAGER2\"]", "ANY", 1, "amount >= 25k", 16) + "]");
        tpl(neha, "TRV", "Travel Request", "Official travel. A director signs trips above Rs. 1 lakh.",
                true, false, false, "["
                        + st("Line manager", "[\"MANAGER\"]", "ANY", 1, "", 4) + ","
                        + st("HR travel desk", "[\"GROUP:HR\"]", "ANY", 1, "", 8) + ","
                        + st("Director", "[\"GROUP:Directors\"]", "ANY", 1, "amount > 1L", 16) + "]");
        tpl(neha, "CON", "Contract / Work Order", "Contracts and work orders. Legal and Finance review in parallel; two directors above Rs. 10 lakh.",
                true, false, true, "["
                        + st("Line manager", "[\"MANAGER\"]", "ANY", 1, "", 8) + ","
                        + st("Legal + Finance review", "[\"GROUP:Legal\",\"GROUP:Finance\"]", "ALL", 1, "", 24) + ","
                        + st("Directors (2 of 3)", directors, "QUORUM", 2, "amount > 10L", 24) + "]");
        tpl(neha, "POL", "Policy / SOP", "Company policies and standard operating procedures. Approved ones go to the library for everybody.",
                false, true, false, "["
                        + st("Department head", "[\"MANAGER\"]", "ANY", 1, "", 16) + ","
                        + st("HR review", "[\"GROUP:HR\"]", "ANY", 1, "", 16) + ","
                        + st("Managing Director", "[\"USER:U-1001\"]", "ANY", 1, "", 24) + "]");
        tpl(neha, "DRG", "Design Drawing", "Engineering drawings: Drawn (author) - Checked (Design QA) - Approved (design manager).",
                false, false, true, "["
                        + st("Checked", "[\"GROUP:Design QA\"]", "ANY", 1, "", 16) + ","
                        + st("Approved", "[\"MANAGER\"]", "ANY", 1, "", 16) + "]");
    }

    private static String st(String name, String seats, String mode, int quorum, String cond, int sla) {
        return "{\"name\":\"" + name + "\",\"seats\":" + seats + ",\"mode\":\"" + mode + "\",\"quorum\":" + quorum
                + ",\"condition\":\"" + cond + "\",\"slaHours\":" + sla + "}";
    }

    private static void tpl(User admin, String code, String name, String desc, boolean amount, boolean publish, boolean distinct, String stages) {
        Map<String, Object> b = Json.obj("code", code, "name", name, "description", desc, "hasAmount", amount,
                "publishOnApproval", publish, "distinct", distinct, "stages", com.cecil.swikriti.util.Json.parse(stages));
        p.admin().saveTemplate(admin, null, b);
    }

    // ================================================================== content

    private record Idea(String type, String title, long min, long max, String what) { }

    private static final List<Idea> IDEAS = List.of(
            new Idea("PR", "Laptops for the design team (4 nos.)", 260_000, 340_000, "4 laptops (i7, 32 GB RAM) for STAAD and Revit work"),
            new Idea("PR", "AutoCAD and STAAD.Pro licence renewal", 180_000, 420_000, "yearly renewal of 6 design software licences"),
            new Idea("PR", "Site safety helmets and harnesses", 18_000, 46_000, "60 helmets, 20 full-body harnesses"),
            new Idea("PR", "Concrete cube testing machine", 520_000, 780_000, "2000 kN compression testing machine for the site lab"),
            new Idea("PR", "Drone survey of the Hazaribagh site", 65_000, 140_000, "aerial survey with contour map, 40 hectares"),
            new Idea("PR", "UPS batteries for the server room", 22_000, 58_000, "replacement batteries for the 10 kVA UPS"),
            new Idea("PR", "Printer paper and toner (quarterly)", 9_000, 24_000, "A4/A3 paper and toner for all printers"),
            new Idea("PR", "Soil testing kit", 35_000, 88_000, "field kit for SPT and moisture tests"),
            new Idea("PR", "Site office container (rental, 6 months)", 90_000, 180_000, "20 ft office container with AC"),
            new Idea("PR", "Office chairs for the Ranchi office", 40_000, 95_000, "15 ergonomic chairs"),
            new Idea("EXP", "Site visit expenses – Dhanbad", 3_000, 9_500, "taxi, lunch and site entry passes"),
            new Idea("EXP", "Client meeting – Patna", 7_000, 16_000, "train tickets, hotel for one night, local taxi"),
            new Idea("EXP", "Local travel – monthly", 1_500, 6_000, "auto and cab fares to sites in Ranchi"),
            new Idea("EXP", "Training course fee – IS 456 refresher", 18_000, 32_000, "two-day course by a professional body"),
            new Idea("EXP", "Hotel stay – Bokaro site", 9_000, 28_000, "4 nights during the slab casting"),
            new Idea("TRV", "Travel to Kolkata for client review", 8_000, 22_000, "2 people, train and hotel for 2 nights"),
            new Idea("TRV", "Site inspection – Jamshedpur", 4_000, 12_000, "car with driver for 2 days"),
            new Idea("TRV", "Conference – concrete technology, Delhi", 60_000, 160_000, "3 people, flights, hotel and fees"),
            new Idea("TRV", "Bid meeting – Bhubaneswar", 14_000, 38_000, "flight and one night hotel"),
            new Idea("CON", "Work order – boundary wall, Namkum site", 450_000, 900_000, "620 m brick boundary wall with gate"),
            new Idea("CON", "Annual maintenance contract – lifts", 160_000, 280_000, "AMC for 2 lifts, 12 months"),
            new Idea("CON", "Consultancy agreement – geotechnical survey", 300_000, 1_400_000, "boreholes and report for 3 sites"),
            new Idea("CON", "Rate contract – printing services", 120_000, 240_000, "plotting and blueprint copies, 1 year"),
            new Idea("CON", "Sub-contract – structural steel fabrication", 1_200_000, 3_800_000, "fabrication and erection of 140 t steel"),
            new Idea("POL", "Work-from-site safety SOP", 0, 0, ""),
            new Idea("POL", "Document retention policy", 0, 0, ""),
            new Idea("POL", "Travel and expense policy 2026", 0, 0, ""),
            new Idea("POL", "Visitor and contractor entry SOP", 0, 0, ""),
            new Idea("DRG", "Foundation layout – Block A", 0, 0, ""),
            new Idea("DRG", "Column schedule – Admin building", 0, 0, ""),
            new Idea("DRG", "Staircase details – Tower 2", 0, 0, ""),
            new Idea("DRG", "Drainage plan – Namkum site", 0, 0, ""),
            new Idea("DRG", "Roof truss elevation – Warehouse", 0, 0, ""),
            new Idea("DRG", "Retaining wall section – Ch. 1+200", 0, 0, ""));

    private static final String[] VENDORS = { "Jharkhand Office Mart", "Ranchi Instruments & Co.", "Kanke Safety Supplies",
            "Bharat Survey Tools", "Steel City Computers", "Damodar Traders", "Eastern Lab Equipment", "SkyEye Drones" };
    private static final String[] SITES = { "Namkum site", "Ratu Road site", "Hazaribagh site", "Ranchi office" };

    private static String body(Idea i, String vendor) {
        String site = SITES[rnd.nextInt(SITES.length)];
        return switch (i.type) {
            case "PR" -> "Purpose: " + i.what + ".\nNeeded for: " + site + ".\nQuotations: three quotations taken; " + vendor
                    + " is the lowest.\nBudget head: " + (rnd.nextBoolean() ? "Project expenses" : "Office equipment")
                    + ".\nDelivery: within 15 days of the order.\nPayment: 30 days after delivery and inspection.";
            case "EXP" -> "Details: " + i.what + ".\nDates: " + (3 + rnd.nextInt(20)) + "–" + (5 + rnd.nextInt(20)) + " of last month.\nBills: all bills are attached in the expense folder.\nProject code: DCE-" + (200 + rnd.nextInt(60)) + ".";
            case "TRV" -> "Trip: " + i.what + ".\nReason: " + (rnd.nextBoolean() ? "client review of the design" : "site inspection and meeting") + ".\nAdvance needed: " + (rnd.nextBoolean() ? "yes" : "no") + ".\nProject code: DCE-" + (200 + rnd.nextInt(60)) + ".";
            case "CON" -> "Scope: " + i.what + ".\nContractor: " + vendor + ".\nPeriod: " + (3 + rnd.nextInt(10)) + " months from the work order date.\nRetention: 5% until the defect liability period ends.\nPenalty: 0.5% per week of delay, at most 5%.\nPayment: monthly, on measured work.";
            case "POL" -> policyText(i.title);
            default -> drawingNotes(i.title);
        };
    }

    private static String policyText(String title) {
        return "1. Purpose: this " + (title.contains("SOP") ? "SOP" : "policy") + " explains " + title.toLowerCase().replace(" sop", "") + " at Damodar Consulting Engineers.\n"
                + "2. Who: it applies to all staff, trainees and contractors.\n"
                + "3. Rules: follow the steps in the annexure; ask your manager when in doubt.\n"
                + "4. Records: keep the records for at least 3 years.\n"
                + "5. Review: HR reviews this document every year.";
    }

    private static String drawingNotes(String title) {
        return "Drawing: " + title + ".\n"
                + "1. All dimensions are in mm unless noted.\n"
                + "2. Concrete grade M25, steel Fe500D.\n"
                + "3. Clear cover 50 mm for footings, 40 mm for columns.\n"
                + "4. Safe bearing capacity taken as 150 kN/m² (soil report SR-07).\n"
                + "5. Read with the architectural drawings and the general notes sheet.";
    }

    private static final String[] OK_NOTES = { "Fine.", "Approved.", "Checked, in order.", "OK as per budget.", "Approved - please keep the bills.",
            "Looks good.", "Within the project budget.", "", "", "" };
    private static final String[] RETURN_NOTES = { "Please attach the third quotation.", "Please split this by project code.",
            "The amount does not match the quotation - please check.", "Add the delivery site and contact person.",
            "Please reduce the quantity; we have stock for two months." };
    private static final String[] REJECT_NOTES = { "Not in this year's budget.", "We already have a rate contract for this.",
            "Postpone to next quarter, please.", "Not needed - the site work is on hold." };

    // ================================================================== 5 months of history (an event simulation)

    private record Ev(long at, int seq, String kind, String ref) { }

    private static final PriorityQueue<Ev> Q = new PriorityQueue<>((a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Integer.compare(a.seq, b.seq));
    private static int seq;
    private static final Set<String> scheduled = new HashSet<>();
    private static long cutoff;

    private static void history() {
        Q.clear();
        scheduled.clear();
        cutoff = officeAgo(40).toEpochMilli();
        long first = workMoment(105, 10, 0).toEpochMilli(), last = workMoment(16, 17, 0).toEpochMilli();
        List<User> authors = new ArrayList<>(s.users.values());
        authors.removeIf(u -> u.id.equals("U-1001"));
        int n = 120;
        for (int i = 0; i < n; i++) {
            long t = first + (long) ((last - first) * (i + rnd.nextDouble()) / n);
            Q.add(new Ev(office(t), seq++, "NEW", null));
        }
        while (!Q.isEmpty()) {
            Ev e = Q.poll();
            if (e.at >= cutoff) continue;
            Clock.fix(Instant.ofEpochMilli(e.at));
            p.flow().tick();
            try {
                switch (e.kind) {
                    case "NEW" -> newDoc(authors);
                    case "DECIDE" -> decide(e.ref);
                    case "REVISE" -> revise(e.ref);
                    case "WITHDRAW" -> {
                        Document d = s.docs.get(e.ref);
                        if (d.status == DocStatus.IN_REVIEW) p.docs().withdraw(s.users.get(d.authorId), d.id, "No longer needed - the plan changed.");
                    }
                    default -> { }
                }
            } catch (Problem ignored) {
                // a rule said no (for example the task was closed meanwhile) - that is fine, the history goes on
            }
        }
        finishLeftovers();
    }

    /** Old documents still open when the history ends are decided (or withdrawn) one minute after another. */
    private static void finishLeftovers() {
        long t = cutoff;
        for (Document d : new ArrayList<>(s.docs.values())) {
            for (int guard = 0; guard < 12 && (d.status == DocStatus.IN_REVIEW || d.status == DocStatus.RETURNED); guard++) {
                Clock.fix(Instant.ofEpochMilli(t += 60_000));
                if (d.status == DocStatus.RETURNED) {
                    p.docs().withdraw(s.users.get(d.authorId), d.id, "Replaced by a new request.");
                    break;
                }
                boolean moved = false;
                for (Task k : s.tasksOf(d.id)) {
                    if (!k.state.open()) continue;
                    for (String uid : k.assignees) {
                        try { p.flow().decide(s.users.get(uid), k.id, "APPROVE", "OK."); moved = true; break; } catch (Problem tryNext) { /* next */ }
                    }
                    if (moved) break;
                }
                if (!moved) break;
            }
        }
    }

    private static void newDoc(List<User> authors) {
        Idea i = IDEAS.get(rnd.nextInt(IDEAS.size()));
        User a = null;
        for (int tries = 0; tries < 40 && a == null; tries++) {
            User c = authors.get(rnd.nextInt(authors.size()));
            if (i.type.equals("DRG") && !c.dept.equals("Design")) continue;
            if (i.type.equals("POL") && !c.manager()) continue;
            if (i.type.equals("CON") && !(c.manager() || c.inGroup("Procurement"))) continue;
            a = c;
        }
        if (a == null) return;
        String vendor = VENDORS[rnd.nextInt(VENDORS.length)];
        long amount = i.max == 0 ? 0 : round(i.min + (long) (rnd.nextDouble() * (i.max - i.min)));
        Document d = p.docs().create(a, Json.obj("type", i.type, "title", i.title, "amount", amount,
                "vendor", i.type.equals("PR") || i.type.equals("CON") ? vendor : null, "urgent", rnd.nextInt(12) == 0, "body", body(i, vendor)));
        if (i.type.equals("DRG")) attachDrawing(a, d, "A");
        p.docs().submit(a, d.id, null);
        if (rnd.nextInt(30) == 0) after(d.id, "WITHDRAW", 2 + rnd.nextInt(4));
        else plan(d);
    }

    /** Schedules a decision for every newly opened task of the document. */
    private static void plan(Document d) {
        for (Task k : s.tasksOf(d.id)) {
            if (!k.state.open() || !scheduled.add(k.id)) continue;
            Template t = s.templates.get(d.type);
            double sla = t.stages.get(Math.min(k.stage, t.stages.size() - 1)).slaHours;
            double h = rnd.nextInt(7) == 0 ? sla * (1.15 + rnd.nextDouble()) : sla * (0.08 + rnd.nextDouble() * 0.9);
            Q.add(new Ev(s.calendar().addHours(Clock.now(), h).toEpochMilli(), seq++, "DECIDE", k.id));
        }
    }

    private static void after(String docId, String kind, double officeHours) {
        Q.add(new Ev(s.calendar().addHours(Clock.now(), officeHours).toEpochMilli(), seq++, kind, docId));
    }

    private static void decide(String taskId) {
        Task k = s.tasks.get(taskId);
        if (k == null || !k.state.open()) return;
        Document d = s.docs.get(k.docId);
        if (d.status != DocStatus.IN_REVIEW) return;
        double r = rnd.nextDouble();
        boolean drawing = d.type.equals("DRG");
        String action = r < 0.05 && !drawing ? "REJECT" : r < (drawing ? 0.30 : 0.14) && d.rev() < 3 ? "RETURN" : "APPROVE";
        List<String> who = new ArrayList<>(k.candidates);
        Collections.shuffle(who, rnd);
        for (String extra : k.assignees) if (!who.contains(extra)) who.add(rnd.nextInt(3) == 0 ? 0 : who.size(), extra);
        for (String uid : who) {
            User u = s.users.get(uid);
            try {
                if (action.equals("RETURN") && drawing) {
                    int lines = d.latest().body.split("\n").length;
                    p.docs().comment(u, d.id, 2 + rnd.nextInt(Math.max(1, lines - 2)), rnd.nextBoolean() ? "Check this value against the soil report."
                            : "Give the grid reference here.");
                }
                String note = switch (action) {
                    case "REJECT" -> REJECT_NOTES[rnd.nextInt(REJECT_NOTES.length)];
                    case "RETURN" -> drawing ? "Please correct the marked lines and re-issue." : RETURN_NOTES[rnd.nextInt(RETURN_NOTES.length)];
                    default -> OK_NOTES[rnd.nextInt(OK_NOTES.length)];
                };
                p.flow().decide(u, k.id, action, note);
                if (action.equals("RETURN")) after(d.id, "REVISE", 3 + rnd.nextInt(14));
                else plan(d);
                return;
            } catch (Problem tryNext) {
                // e.g. this person already signed an earlier stage - let someone else on the seat decide
            }
        }
    }

    private static void revise(String docId) {
        Document d = s.docs.get(docId);
        if (d.status != DocStatus.RETURNED) return;
        User a = s.users.get(d.authorId);
        Map<String, Object> b = Json.obj("body", d.body + "\nRevised: " + (d.type.equals("DRG") ? "marked lines corrected." : "changed as asked by the reviewer."));
        if (d.amount > 0) b.put("amount", round((long) (d.amount * (0.85 + rnd.nextDouble() * 0.1))));
        p.docs().update(a, d.id, b);
        if (d.type.equals("DRG")) attachDrawing(a, d, d.latest() == null ? "A" : com.cecil.swikriti.model.Revision.letter(d.rev() + 1));
        p.docs().submit(a, d.id, d.type.equals("DRG") ? "Corrected the redlined items." : "Changed as asked.");
        plan(d);
    }

    // ================================================================== the last few days, written by hand

    private record Step(long at, Runnable run) { }

    private static final Map<String, String> K = new HashMap<>();

    private static void script() {
        K.clear();
        List<Step> steps = new ArrayList<>();
        // 1. Ankit's drawing: redlined and sent back, revised (rev B), checked and approved -> certificate, diff A -> B
        steps.add(ago(30, () -> {
            Document d = doc("drg1", "ankit", "DRG", "Foundation layout – Block B", 0, null, false, String.join("\n",
                    "Drawing: Foundation layout – Block B (G+4 hostel).",
                    "1. All dimensions are in mm unless noted.",
                    "2. Isolated footings F1 1800 x 1800 x 450, F2 2100 x 2100 x 500.",
                    "3. Concrete grade M25, steel Fe500D.",
                    "4. Clear cover 50 mm for footings.",
                    "5. Safe bearing capacity 150 kN/m² (soil report SR-07).",
                    "6. Founding level 1.8 m below natural ground level."));
            attachDrawing(U.get("ankit"), d, "A");
            p.docs().submit(U.get("ankit"), d.id, null);
        }));
        steps.add(ago(26, () -> {
            Document d = s.docs.get(K.get("drg1"));
            p.docs().comment(U.get("priyanka"), d.id, 3, "F2 is too small for column C7 (load 1450 kN). Use 2400 x 2400.");
            p.docs().comment(U.get("priyanka"), d.id, 7, "Founding level must follow SR-07: 2.1 m, not 1.8 m.");
            p.docs().comment(U.get("priyanka"), d.id, 0, "Add grid line references (A–F / 1–7) to the footing schedule.");
            decide("priyanka", "drg1", "RETURN", "Please correct F2 and the founding level, and add grid references.");
        }));
        steps.add(ago(20, () -> {
            Document d = s.docs.get(K.get("drg1"));
            p.docs().update(U.get("ankit"), d.id, Json.obj("body", String.join("\n",
                    "Drawing: Foundation layout – Block B (G+4 hostel).",
                    "1. All dimensions are in mm unless noted.",
                    "2. Isolated footings F1 1800 x 1800 x 450, F2 2400 x 2400 x 550.",
                    "3. Concrete grade M25, steel Fe500D.",
                    "4. Clear cover 50 mm for footings.",
                    "5. Safe bearing capacity 150 kN/m² (soil report SR-07).",
                    "6. Founding level 2.1 m below natural ground level (as per SR-07).",
                    "7. Footing schedule with grid references A–F / 1–7 on sheet S-102.")));
            attachDrawing(U.get("ankit"), d, "B");
            p.docs().submit(U.get("ankit"), d.id, "F2 enlarged to 2400 x 2400, founding level 2.1 m, grid references added.");
        }));
        steps.add(ago(15, () -> decide("priyanka", "drg1", "APPROVE", "All redlines closed. Checked.")));
        steps.add(ago(10, () -> decide("deepak", "drg1", "APPROVE", "Approved for construction.")));

        // 2. a second drawing of Ankit's, sent back to him - his to-do
        steps.add(ago(21, () -> {
            Document d = doc("drg2", "ankit", "DRG", "Pile cap details – Block B", 0, null, false, String.join("\n",
                    "Drawing: Pile cap details – Block B.",
                    "1. Piles 450 mm dia, bored cast-in-situ, safe load 600 kN.",
                    "2. Pile cap PC2 1600 x 1600 x 900.",
                    "3. Concrete grade M30 for pile caps.",
                    "4. Bottom reinforcement 16 mm at 150 c/c both ways.",
                    "5. Cut-off level 300 mm above the pile top."));
            attachDrawing(U.get("ankit"), d, "A");
            p.docs().submit(U.get("ankit"), d.id, null);
        }));
        steps.add(ago(11, () -> {
            Document d = s.docs.get(K.get("drg2"));
            p.docs().comment(U.get("manish"), d.id, 3, "PC2 for 2 piles needs at least 2 x 3 x 450 = 2700 mm length.");
            p.docs().comment(U.get("manish"), d.id, 5, "16 @ 150 is not enough for PC2 - please recheck the design.");
            decide("manish", "drg2", "RETURN", "Pile cap PC2 size and steel need to be rechecked - see the redlines.");
        }));

        // 3. Ankit's purchase: waits for his manager Deepak (Finance is skipped: Rs. 38,500 is not above 50k)
        steps.add(ago(3, () -> submit(doc("pr1", "ankit", "PR", "Plotter ink cartridges and A0 rolls", 38_500, "Steel City Computers", false,
                "Purpose: ink for the HP T830 plotter (6 cartridges) and 10 rolls of A0 paper.\nNeeded for: Ranchi office.\nQuotations: three taken; Steel City Computers is the lowest.\nBudget head: Office equipment."))));

        // 4. Meghna's purchase waits for Sunita and is OVERDUE -> escalated to Arvind by the background job
        steps.add(ago(12, () -> submit(doc("pr2", "meghna", "PR", "Safety nets and barricades – Ratu Road site", 46_000, "Kanke Safety Supplies", true,
                "Purpose: safety nets for the 3rd floor slab edge and 40 road barricades.\nNeeded for: Ratu Road site.\nQuotations: two taken (urgent - the labour inspector visits next week).\nBudget head: Project expenses."))));

        // 5. Vivek's purchase: manager and procurement done, now at Finance (Rahul / Pooja)
        steps.add(ago(14, () -> submit(doc("pr3", "vivek", "PR", "Total station rental (3 months)", 185_000, "Bharat Survey Tools", false,
                "Purpose: a total station with operator for the Hazaribagh road alignment survey.\nPeriod: 3 months.\nQuotations: three taken; Bharat Survey Tools is the lowest.\nBudget head: Project expenses."))));
        steps.add(ago(10.5, () -> decide("sunita", "pr3", "APPROVE", "Needed for the alignment survey.")));
        steps.add(ago(6, () -> decide("amit", "pr3", "APPROVE", "Rates checked against last year's order.")));

        // 6. Rohit's small expense claim waits for Sunita
        steps.add(ago(2, () -> submit(doc("exp1", "rohit", "EXP", "Site visit expenses – Dhanbad", 6_850, null, false,
                "Details: taxi to Dhanbad and back, lunch for 2, site entry passes.\nDates: 10–11 of this month.\nProject code: DCE-231."))));

        // 7. Sunita's work order: Legal has signed, Finance (parallel seat) still to sign
        steps.add(ago(24, () -> submit(doc("con1", "sunita", "CON", "Work order – boundary wall, Namkum site", 860_000, "Damodar Traders", false,
                "Scope: 620 m brick boundary wall, 2.4 m high, with one main gate.\nContractor: Damodar Traders.\nPeriod: 4 months from the work order date.\nRetention: 5% until the defect liability period ends.\nPenalty: 0.5% per week of delay, at most 5%.\nPayment: monthly, on measured work."))));
        steps.add(ago(18, () -> decide("arvind", "con1", "APPROVE", "OK - within the project budget.")));
        steps.add(ago(4, () -> decide("sneha", "con1", "APPROVE", "Clauses on penalty and retention are fine.")));

        // 8. Farah is on leave and delegates to Rahul; a big purchase waits for the 2nd director (Kavita, or Rahul for Farah)
        steps.add(ago(16, () -> p.flow().delegate(U.get("farah"), Json.obj("toId", "U-1006",
                "start", Clock.today().toString(), "end", Clock.today().plusDays(8).toString(), "note", "On leave - Rahul signs for me."))));
        steps.add(ago(32, () -> submit(doc("pr4", "amit", "PR", "Steel scaffolding (purchase)", 1_280_000, "Damodar Traders", false,
                "Purpose: buy 1,800 m² of cup-lock scaffolding instead of renting it (rent paid so far: Rs. 9.6 lakh a year).\nNeeded for: Namkum and Ratu Road sites.\nQuotations: three taken; Damodar Traders is the lowest.\nBudget head: Capital equipment."))));
        steps.add(ago(28, () -> decide("sunita", "pr4", "APPROVE", "Pays back in about 16 months.")));
        steps.add(ago(22, () -> decide("ritu", "pr4", "APPROVE", "Quotations compared.")));
        steps.add(ago(15.5, () -> decide("pooja", "pr4", "APPROVE", "Budget available under capital equipment.")));
        steps.add(ago(5, () -> decide("arvind", "pr4", "APPROVE", "Agreed.")));

        // 9. Deepak's SOP: department head and HR done, the Managing Director still to sign
        steps.add(ago(34, () -> submit(doc("pol1", "deepak", "POL", "Drawing numbering and revision SOP", 0, null, false, String.join("\n",
                "1. Purpose: one way of numbering drawings and revisions in all projects.",
                "2. Number: project code / discipline / sheet, e.g. DCE-231/S/102.",
                "3. Revisions: A, B, C ... while in review; 0, 1, 2 ... after approval for construction.",
                "4. Every revision gets a line in the revision table: letter, date, what changed, who.",
                "5. Changed areas are marked with a revision cloud and the revision triangle.",
                "6. Only drawings approved in Swikriti may be sent to site.")))));
        steps.add(ago(27, () -> decide("arvind", "pol1", "APPROVE", "Good - please train the draughtsmen.")));
        steps.add(ago(9, () -> decide("imran", "pol1", "APPROVE", "No HR issues.")));

        // 10. Priyanka's expense claim, approved end to end
        steps.add(ago(19, () -> submit(doc("exp2", "priyanka", "EXP", "Training course fee – IS 456 refresher", 18_000, null, false,
                "Details: two-day refresher course on IS 456 by a professional body.\nDates: 3–4 of this month.\nCertificate: attached in the HR folder."))));
        steps.add(ago(13, () -> decide("deepak", "exp2", "APPROVE", "Useful course.")));
        steps.add(ago(8, () -> decide("pooja", "exp2", "APPROVE", "")));

        // 11. Ankit's travel request is still a draft
        steps.add(ago(1, () -> doc("trv1", "ankit", "TRV", "Travel to Kolkata for client review", 14_500, null, false,
                "Trip: 2 people (Ankit, Sanjay), train and hotel for 2 nights.\nReason: client review of the Block B drawings.")));

        steps.sort((a, b) -> Long.compare(a.at, b.at));
        for (Step st : steps) {
            Clock.fix(Instant.ofEpochMilli(st.at));
            st.run.run();
        }
    }

    private static Step ago(double officeHours, Runnable r) { return new Step(officeAgo(officeHours).toEpochMilli(), r); }

    /** The moment that lies `hours` OFFICE hours before now (walking back in 5-minute steps). */
    static Instant officeAgo(double hours) {
        com.cecil.swikriti.core.BusinessCalendar cal = s.calendar();
        Instant t = realNow;
        long left = Math.round(hours * 60);
        int guard = 0;
        while (left > 0 && guard++ < 200_000) {
            t = t.minusSeconds(300);
            LocalTime lt = LocalTime.ofInstant(t, Clock.ZONE);
            if (cal.workingDay(LocalDate.ofInstant(t, Clock.ZONE)) && !lt.isBefore(com.cecil.swikriti.core.BusinessCalendar.OPEN)
                    && lt.isBefore(com.cecil.swikriti.core.BusinessCalendar.CLOSE)) left -= 5;
        }
        return t;
    }

    private static Document doc(String key, String author, String type, String title, long amount, String vendor, boolean urgent, String body) {
        Document d = p.docs().create(U.get(author), Json.obj("type", type, "title", title, "amount", amount, "vendor", vendor, "urgent", urgent, "body", body));
        K.put(key, d.id);
        return d;
    }

    private static void submit(Document d) { p.docs().submit(s.users.get(d.authorId), d.id, null); }

    private static void decide(String username, String key, String action, String comment) {
        User u = U.get(username);
        for (Task k : s.tasksOf(K.get(key))) if (k.state.open() && k.assignees.contains(u.id)) {
            p.flow().decide(u, k.id, action, comment);
            return;
        }
        throw new IllegalStateException("Demo script: no open task for " + username + " on " + key);
    }

    // ================================================================== time helpers

    /** A moment `workDaysAgo` working days before today, at h:m India time (never in the future). */
    static Instant workMoment(int workDaysAgo, int h, int m) {
        com.cecil.swikriti.core.BusinessCalendar cal = s.calendar();
        LocalDate d = LocalDate.ofInstant(realNow, Clock.ZONE);
        int n = 0;
        while (n < workDaysAgo) {
            d = d.minusDays(1);
            if (cal.workingDay(d)) n++;
        }
        while (!cal.workingDay(d)) d = d.minusDays(1);
        Instant t = d.atTime(LocalTime.of(h, m)).atZone(Clock.ZONE).toInstant();
        return t.isAfter(realNow) ? realNow.minusSeconds(600) : t;
    }

    /** Moves a moment into office hours (and adds a few random minutes so not everything happens on the hour). */
    private static long office(long t) {
        return s.calendar().addHours(Instant.ofEpochMilli(t), rnd.nextDouble() * 0.5).toEpochMilli();
    }

    private static long round(long v) { return v < 10_000 ? Math.round(v / 50.0) * 50 : Math.round(v / 500.0) * 500; }

    // ================================================================== a small "blueprint" picture for drawings

    private static void attachDrawing(User author, Document d, String rev) {
        p.docs().attach(author, d.id, Json.obj("name", d.title.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("-+$", "") + "-rev-" + rev + ".png",
                "data", Base64.getEncoder().encodeToString(drawing(d.title, rev))));
    }

    /** Paints a simple foundation plan in white on blueprint blue: grid, footings, dimensions and a title block. */
    static byte[] drawing(String title, String rev) {
        int w = 960, h = 620;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x16, 0x45, 0x7e));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 255, 255, 22));
        for (int x = 0; x < w; x += 20) g.drawLine(x, 0, x, h);
        for (int y = 0; y < h; y += 20) g.drawLine(0, y, w, y);
        Color ink = new Color(0xe8, 0xf1, 0xff);
        g.setColor(ink);
        g.setStroke(new BasicStroke(3));
        g.drawRect(20, 20, w - 40, h - 40);
        g.setStroke(new BasicStroke(1));
        int cols = 6, rows = 4, x0 = 90, y0 = 90, dx = 110, dy = 105;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        float[] dash = { 14, 4, 3, 4 };
        g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, dash, 0));
        for (int c = 0; c < cols; c++) {
            int x = x0 + c * dx;
            g.drawLine(x, 60, x, y0 + (rows - 1) * dy + 30);
        }
        for (int r = 0; r < rows; r++) {
            int y = y0 + r * dy;
            g.drawLine(60, y, x0 + (cols - 1) * dx + 30, y);
        }
        g.setStroke(new BasicStroke(1.4f));
        for (int c = 0; c < cols; c++) {
            int x = x0 + c * dx;
            g.drawOval(x - 13, 34, 26, 26);
            g.drawString(String.valueOf((char) ('A' + c)), x - 5, 52);
        }
        for (int r = 0; r < rows; r++) {
            int y = y0 + r * dy;
            g.drawOval(34, y - 13, 26, 26);
            g.drawString(String.valueOf(r + 1), 43, y + 5);
        }
        g.setStroke(new BasicStroke(2));
        for (int c = 0; c < cols; c++) for (int r = 0; r < rows; r++) {
            int x = x0 + c * dx, y = y0 + r * dy, s2 = (c == 2 || c == 3) && (r == 1 || r == 2) ? 26 : 20;
            g.drawRect(x - s2, y - s2, 2 * s2, 2 * s2);
            g.fillRect(x - 6, y - 6, 12, 12);
        }
        g.setStroke(new BasicStroke(1));
        int dimY = y0 + (rows - 1) * dy + 55;
        g.drawLine(x0, dimY, x0 + (cols - 1) * dx, dimY);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        for (int c = 0; c < cols; c++) {
            int x = x0 + c * dx;
            g.drawLine(x - 5, dimY + 5, x + 5, dimY - 5);
            if (c < cols - 1) g.drawString("4500", x + dx / 2 - 15, dimY - 6);
        }
        // title block
        int tx = w - 330, ty = h - 170;
        g.setStroke(new BasicStroke(2));
        g.drawRect(tx, ty, 310, 150);
        g.setStroke(new BasicStroke(1));
        g.drawLine(tx, ty + 40, tx + 310, ty + 40);
        g.drawLine(tx, ty + 80, tx + 310, ty + 80);
        g.drawLine(tx, ty + 115, tx + 310, ty + 115);
        g.drawLine(tx + 230, ty + 80, tx + 230, ty + 150);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.drawString("DAMODAR CONSULTING ENGINEERS", tx + 10, ty + 26);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        String t = title.length() > 34 ? title.substring(0, 34) : title;
        g.drawString(t.toUpperCase(), tx + 10, ty + 66);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        g.drawString("SCALE 1:100   SHEET S-101", tx + 10, ty + 102);
        g.drawString("FOR REVIEW", tx + 10, ty + 138);
        g.drawString("REV", tx + 240, ty + 100);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        g.drawString(rev, tx + 255, ty + 140);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.drawString("FOUNDATION PLAN  —  demo drawing made by Swikriti's seed data", 30, h - 32);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Used by the tests: true when every demo document number has the right shape. */
    static boolean selfCheck() {
        for (Document d : s.docs.values())
            if (d.number != null && !d.number.matches("(PR|EXP|TRV|CON|POL|DRG)/\\d\\d-\\d\\d/\\d{4}")) return false;
        return true;
    }
}
