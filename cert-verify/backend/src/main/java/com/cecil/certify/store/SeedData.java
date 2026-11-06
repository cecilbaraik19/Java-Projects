package com.cecil.certify.store;

import com.cecil.certify.cert.CertificateService;
import com.cecil.certify.cert.Verifier;
import com.cecil.certify.model.CertificateRecord;
import com.cecil.certify.model.Role;
import com.cecil.certify.model.User;
import com.cecil.certify.service.AuthService;
import com.cecil.certify.util.Clock;
import com.cecil.certify.util.Json;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Demo data for the first run. Every institution, person and number here is made up.
 * The history is spread over the last ~4 months so the ledger and the activity numbers look real.
 * All of it goes through the normal code (real keys, real signatures, real proof of work).
 */
public final class SeedData {
    private SeedData() {}

    public static final String NETWORK_NAME = "Pramaan Certificate Network (Jharkhand demo)";
    public static final String[] KEEPERS = {
            "Koel Valley Institute of Technology, Ranchi",
            "Subarnarekha University, Jamshedpur",
            "Office of the Registrar (network host)"};

    private static final long DAY = 86_400_000L;

    public static void seed(CertificateService certs, AuthService auth, Verifier verifier) {
        long realNow = System.currentTimeMillis();
        long t = realNow - 118 * DAY;
        Random rnd = new Random(2026);
        try {
            Clock.fix(t);
            // ---- accounts
            auth.createUser("admin", "Registrar's Office", Role.REGISTRAR, null, "admin123");

            // ---- institutions
            step(DAY);
            certs.registerIssuer("KVIT", "Koel Valley Institute of Technology", "Ranchi");
            step(3 * 3_600_000L);
            certs.registerIssuer("SUBU", "Subarnarekha University", "Jamshedpur");
            step(5 * 3_600_000L);
            certs.registerIssuer("DSB", "Damodar Skill Development Board", "Dhanbad");
            step(2 * DAY);
            certs.registerIssuer("RPA", "Ranchi Paramedical Academy", "Ranchi");

            auth.createUser("meera", "Meera Tirkey (Exam Cell, KVIT)", Role.ISSUER, "KVIT", "issuer123");
            auth.createUser("farhan", "Farhan Ali (Registrar's Desk, SUBU)", Role.ISSUER, "SUBU", "issuer123");
            auth.createUser("joseph", "Joseph Hembrom (Certification, DSB)", Role.ISSUER, "DSB", "issuer123");
            auth.createUser("rpa.office", "RPA Office", Role.ISSUER, "RPA", "issuer123");
            auth.createUser("priya", "Priya Oraon", Role.STUDENT, null, "student123");
            auth.createUser("aman", "Aman Mahato", Role.STUDENT, null, "student123");
            auth.createUser("sneha", "Sneha Kujur", Role.STUDENT, null, "student123");

            User meera = certs.store().users.get("meera");
            User farhan = certs.store().users.get("farhan");
            User joseph = certs.store().users.get("joseph");
            User rpa = certs.store().users.get("rpa.office");

            // ---- RPA issues a few, then is suspended (shows the "approved then, suspended later" warning)
            step(9 * DAY);
            batch(certs, rpa, csv(List.of(
                    row("Rekha Soren", "RPA/DMLT/21/014", "Diploma in Medical Laboratory Technology", "First Division (71%)", "2021-2023", "2023-08-10", ""),
                    row("Bishal Toppo", "RPA/DMLT/21/022", "Diploma in Medical Laboratory Technology", "Second Division (58%)", "2021-2023", "2023-08-10", ""),
                    row("Kavita Lakra", "RPA/DOTT/21/007", "Diploma in Operation Theatre Technology", "First Division (66%)", "2021-2023", "2023-08-10", ""))));

            // ---- KVIT: B.Tech results, one block for the whole batch
            step(21 * DAY);
            List<String[]> kvit = new ArrayList<>();
            kvit.add(row("Priya Oraon", "KVIT/CSE/22/031", "Bachelor of Technology - Computer Science and Engineering", "CGPA 8.46 / 10 - First Division with Distinction", "2022-2026", "2026-07-18", "priya"));
            String[] first = {"Ankit", "Ritika", "Saurav", "Pooja", "Rahul", "Nisha", "Deepak", "Anjali", "Vikash", "Sonam",
                    "Abhishek", "Kriti", "Manish", "Shreya", "Rohit", "Puja", "Aditya", "Tanya", "Sumit", "Neha", "Amit"};
            String[] last = {"Kumar", "Munda", "Singh", "Ekka", "Gupta", "Bara", "Mahto", "Minz", "Sharma", "Lakra",
                    "Oraon", "Prasad", "Tirkey", "Verma", "Sahu", "Kerketta", "Pandey", "Hansda", "Jha", "Xalxo", "Tudu"};
            String[][] progs = {{"CSE", "Computer Science and Engineering"}, {"ECE", "Electronics and Communication Engineering"},
                    {"ME", "Mechanical Engineering"}, {"CE", "Civil Engineering"}, {"EE", "Electrical Engineering"}};
            for (int i = 0; i < first.length; i++) {
                String[] p = progs[i % progs.length];
                double cgpa = 6.2 + rnd.nextInt(330) / 100.0;
                String div = cgpa >= 7.5 ? "First Division with Distinction" : cgpa >= 6.5 ? "First Division" : "Second Division";
                kvit.add(row(first[i] + " " + last[(i * 7) % last.length], "KVIT/" + p[0] + "/22/" + String.format("%03d", 40 + i * 3),
                        "Bachelor of Technology - " + p[1], String.format("CGPA %.2f / 10 - %s", cgpa, div), "2022-2026", "2026-07-18", ""));
            }
            kvit.add(row("Sneha Kujur", "KVIT/DIP/23/112", "Diploma in Computer Engineering", "First Division (74.2%)", "2023-2026", "2026-07-18", "sneha"));
            batch(certs, meera, csv(kvit));

            // ---- SUBU: postgraduate results
            step(4 * DAY);
            List<String[]> subu = new ArrayList<>();
            subu.add(row("Aman Mahato", "SU/MCA/24/0087", "Master of Computer Applications", "SGPA 8.10 / 10 - First Class", "2024-2026", "2026-07-25", "aman"));
            String[][] pg = {{"Farha Naaz", "M.Sc. Physics"}, {"Rajesh Murmu", "M.Sc. Chemistry"}, {"Ipsita Das", "Master of Business Administration"},
                    {"Karan Bhagat", "Master of Computer Applications"}, {"Lily Kachhap", "M.A. Economics"}, {"Omkar Nath", "M.Sc. Mathematics"},
                    {"Salomi Barla", "M.A. English"}, {"Tarun Ghosh", "Master of Business Administration"}, {"Usha Mardi", "M.Sc. Physics"},
                    {"Wasim Akhtar", "Master of Computer Applications"}, {"Yamini Rao", "M.Sc. Mathematics"}};
            for (int i = 0; i < pg.length; i++) {
                double g = 6.4 + rnd.nextInt(300) / 100.0;
                subu.add(row(pg[i][0], "SU/PG/24/" + String.format("%04d", 120 + i * 11), pg[i][1],
                        String.format("SGPA %.2f / 10 - %s", g, g >= 7.0 ? "First Class" : "Second Class"), "2024-2026", "2026-07-25", ""));
            }
            batch(certs, farhan, csv(subu));

            // ---- DSB: short skill courses
            step(6 * DAY);
            List<String[]> dsb = new ArrayList<>();
            dsb.add(row("Priya Oraon", "DSB/PY/26/0419", "Certificate Course in Python Programming (120 hours)", "Grade A", "2026", "2026-08-20", "priya"));
            String[][] skill = {{"Mohan Gope", "Certificate Course in Electrician (Domestic)"}, {"Seema Kumari", "Certificate Course in Data Entry Operator"},
                    {"Dilip Hansda", "Certificate Course in Welding Technician"}, {"Alka Tigga", "Certificate Course in Tally with GST"},
                    {"Rakesh Paswan", "Certificate Course in Solar PV Installer"}, {"Jyoti Mahli", "Certificate Course in Beauty and Wellness"},
                    {"Suresh Bediya", "Certificate Course in CNC Operator"}, {"Mamta Devi", "Certificate Course in Tailoring"}};
            String[] grades = {"Grade A", "Grade B", "Grade A+", "Grade B+"};
            for (int i = 0; i < skill.length; i++) {
                dsb.add(row(skill[i][0], "DSB/SK/26/" + String.format("%04d", 300 + i * 17), skill[i][1] + " (" + (90 + i * 10) + " hours)",
                        grades[rnd.nextInt(grades.length)], "2026", "2026-08-20", ""));
            }
            batch(certs, joseph, csv(dsb));

            // ---- RPA is suspended
            step(2 * DAY);
            certs.suspendIssuer("RPA", "Recognition withdrawn by the State Paramedical Council (inspection, Sept 2026)");

            // ---- A wrong certificate is revoked and re-issued with the right marks
            step(5 * DAY);
            CertificateRecord wrong = find(certs, "KVIT/ECE/22/043");
            if (wrong != null) {
                Map<String, String> fixed = new LinkedHashMap<>(wrong.fields);
                certs.revoke(meera, wrong.certId, "Printed with wrong CGPA - replaced by a corrected certificate");
                fixed.put("result", "CGPA 7.12 / 10 - First Division");
                fixed.put("issueDate", Clock.today().minusDays(0).toString());
                step(3_600_000L);
                certs.issue(meera, fixed, "");
            }
            step(3 * DAY);
            CertificateRecord withdrawn = find(certs, "SU/PG/24/0230");
            if (withdrawn != null) certs.revoke(farhan, withdrawn.certId, "Awarded in error - final semester result was later withheld");

            // ---- one single certificate issued by hand later
            step(4 * DAY);
            Map<String, String> one = new LinkedHashMap<>();
            one.put("holder", "Aman Mahato");
            one.put("rollNo", "DSB/WD/26/0511");
            one.put("programme", "Certificate Course in Web Development (160 hours)");
            one.put("result", "Grade A+");
            one.put("session", "2026");
            one.put("issueDate", Clock.today().toString());
            certs.issue(joseph, one, "aman");

            // ---- Priya shares her degree with an employer
            step(2 * DAY);
            CertificateRecord priyaDeg = find(certs, "KVIT/CSE/22/031");
            if (priyaDeg != null) certs.createShareLink(certs.store().users.get("priya"), priyaDeg.certId, "Job application - Tata Steel trainee", 60);

            // ---- some verification history over the last month (real checks, real results)
            List<CertificateRecord> all = new ArrayList<>(certs.store().certificates.values());
            for (int d = 30; d >= 1; d--) {
                Clock.fix(realNow - d * DAY + rnd.nextInt(8 * 3_600_000));
                int n = 1 + rnd.nextInt(4);
                for (int k = 0; k < n; k++) {
                    CertificateRecord r = all.get(rnd.nextInt(all.size()));
                    int kind = rnd.nextInt(10);
                    if (kind < 6) {
                        verifier.verifyLink(r.certId, certs.qrToken(r.certId), true);
                    } else if (kind < 8) {
                        Map<String, Object> f = certs.certificateFile(r);
                        @SuppressWarnings("unchecked") Map<String, Object> fields = (Map<String, Object>) f.get("fields");
                        fields.put("result", String.valueOf(fields.get("result")).replaceFirst("\\d", "9"));
                        verifier.verifyFile(f, true);
                    } else {
                        verifier.verifyTyped("PRM-" + (rnd.nextBoolean() ? "8KQ2-ZZ71-4" : "4N3B-7XQE-P"), Map.of("holder", "Rahul Kumar", "result", "CGPA 9.1"), true);
                    }
                    Clock.advance(rnd.nextInt(3_600_000));
                }
            }
        } finally {
            Clock.fix(null);
        }
    }

    private static void batch(CertificateService certs, User by, String csv) {
        CertificateService.BatchResult r = certs.issueBatch(by, csv);
        if (!r.errors.isEmpty()) throw new IllegalStateException("Demo data problem: " + r.errors);
    }

    private static void step(long ms) {
        Clock.advance(ms);
    }

    private static String[] row(String holder, String roll, String prog, String result, String session, String date, String user) {
        return new String[]{holder, roll, prog, result, session, date, user};
    }

    private static String csv(List<String[]> rows) {
        StringBuilder sb = new StringBuilder("holder,rollNo,programme,result,session,issueDate,username\n");
        for (String[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(r[i].replace("\"", "\"\"")).append('"');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static CertificateRecord find(CertificateService certs, String rollNo) {
        for (CertificateRecord r : certs.store().certificates.values())
            if (rollNo.equals(r.fields.get("rollNo"))) return r;
        return null;
    }

    /** Ready-made examples for the Verify page ("Try an example"). Built fresh from the demo data each time. */
    public static List<Object> samples(CertificateService certs) {
        List<Object> out = new ArrayList<>();
        CertificateRecord genuine = find(certs, "KVIT/CSE/22/031");
        CertificateRecord revoked = null;
        for (CertificateRecord r : certs.store().certificates.values()) if (r.revoked) { revoked = r; break; }
        CertificateRecord suspended = null;
        for (CertificateRecord r : certs.store().certificates.values()) if ("RPA".equals(r.issuerId)) { suspended = r; break; }
        if (genuine != null) {
            out.add(Json.map("key", "genuine", "label", "Genuine degree", "hint", "Priya's B.Tech, untouched",
                    "file", certs.certificateFile(genuine)));
            Map<String, Object> f = certs.certificateFile(genuine);
            @SuppressWarnings("unchecked") Map<String, Object> fields = (Map<String, Object>) f.get("fields");
            fields.put("result", "CGPA 9.46 / 10 - First Division with Distinction");
            out.add(Json.map("key", "tampered", "label", "Marks edited", "hint", "Same file, CGPA changed 8.46 → 9.46", "file", f));
            Map<String, Object> g = certs.certificateFile(genuine);
            g.put("certId", "PRM-9QX4-K7MZ-" + com.cecil.certify.cert.CertFields.checkChar("9QX4K7MZ"));
            out.add(Json.map("key", "fake", "label", "Made-up ID", "hint", "Looks right, never issued", "file", g));
        }
        if (revoked != null) out.add(Json.map("key", "revoked", "label", "Revoked", "hint", "Cancelled by the institution", "file", certs.certificateFile(revoked)));
        if (suspended != null) out.add(Json.map("key", "suspended", "label", "Issuer later suspended", "hint", "Valid, with a warning", "file", certs.certificateFile(suspended)));
        return out;
    }

    /** Today in yyyy-mm-dd (used in the batch template). */
    public static String today() {
        return LocalDate.now(Clock.ZONE).toString();
    }
}
