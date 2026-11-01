package com.cecil.invoice.store;

import com.cecil.invoice.model.*;
import com.cecil.invoice.service.InvoiceService;
import com.cecil.invoice.service.LearningService;
import com.cecil.invoice.store.SampleInvoices.Flaw;
import com.cecil.invoice.store.SampleInvoices.Sample;
import com.cecil.invoice.store.SampleInvoices.Supplier;
import com.cecil.invoice.store.SampleInvoices.Truth;
import com.cecil.invoice.util.Money;
import com.cecil.invoice.util.Passwords;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Demo data for the first run: 4 logins and about four months of invoices from
 * 10 made-up suppliers. The invoices are really read by the extractor, and then
 * "reviewed" the way an accountant would: wrong fields are corrected, bad invoices
 * rejected, good ones approved and exported. So the vendor learning, the category
 * model and the accuracy numbers on the dashboard are all real. Dates are counted
 * back from today, so the demo always looks current.
 */
public final class SeedData {
    private SeedData() {}

    /** When each supplier sends a bill (days before today) and anything special about it. */
    private record Plan(String supplier, int daysAgo, Flaw flaw) {}

    public static void fill(DataStore db) {
        db.setNow(db.today().minusDays(130).atTime(9, 0));
        User admin = user(db, "admin", "Anil Prasad", Role.ADMIN, "admin123");
        User meera = user(db, "meera", "Meera Iyer", Role.ACCOUNTANT, "accounts123");
        User ravi = user(db, "ravi", "Ravi Kumar", Role.CLERK, "clerk123");
        User sunil = user(db, "sunil", "Sunil Oraon", Role.CLERK, "clerk123");
        db.settings.companyName = SampleInvoices.BUYER;
        db.settings.companyGstin = SampleInvoices.BUYER_GSTIN;
        db.settings.companyCity = "Ranchi";

        LearningService learning = new LearningService(db);
        InvoiceService service = new InvoiceService(db, learning);

        List<Plan> plans = new ArrayList<>();
        for (int d : new int[] {118, 98, 77, 56, 34, 13, 2}) plans.add(new Plan("kanchan", d, Flaw.NONE));
        for (int d : new int[] {112, 82, 45, 4}) plans.add(new Plan("byteline", d, Flaw.NONE));
        for (int d : new int[] {120, 90, 60, 30, 1}) plans.add(new Plan("cloudnest", d, Flaw.NONE));
        for (int d : new int[] {116, 86, 55, 25}) plans.add(new Plan("netlink", d, Flaw.NONE));
        for (int d : new int[] {100, 64, 22, 3}) plans.add(new Plan("mehta", d, Flaw.NONE));
        plans.add(new Plan("pixelprint", 1, Flaw.NONE));          // a vendor seen for the first time
        for (int d : new int[] {108, 71, 40, 9}) plans.add(new Plan("skyroute", d, Flaw.NONE));
        for (int d : new int[] {95, 50, 17}) plans.add(new Plan("fixright", d, Flaw.NONE));
        for (int d : new int[] {114, 88, 66, 37, 15, 3}) plans.add(new Plan("spiceroute", d, Flaw.NONE));
        for (int d : new int[] {110, 80, 52, 24, 2}) plans.add(new Plan("aquapure", d, Flaw.NONE));
        for (int d : new int[] {119, 89, 59, 29}) plans.add(new Plan("greenleaf", d, Flaw.NONE));
        // invoices with problems, so the checks have something to catch
        plans.add(new Plan("kanchan", 68, Flaw.OCR_NOISE));
        plans.add(new Plan("kanchan", 5, Flaw.OCR_NOISE));
        plans.add(new Plan("byteline", 61, Flaw.WRONG_TOTAL));
        plans.add(new Plan("fixright", 33, Flaw.BAD_GSTIN));
        plans.add(new Plan("skyroute", 27, Flaw.WRONG_GST_TYPE));
        plans.add(new Plan("kanchan", 46, Flaw.WORDS_MISMATCH));
        plans.add(new Plan("byteline", 1, Flaw.WRONG_TOTAL));
        plans.sort(Comparator.comparingInt((Plan p) -> -p.daysAgo).thenComparing(Plan::supplier));

        // auto-check starts switched off; the admin turns it on two months ago, once the app has learned the vendors
        db.settings.autoApprove = false;
        boolean autoOn = false;
        Random rnd = new Random(2026);
        Map<String, Integer> numbers = new HashMap<>();
        LocalDate today = db.today();
        List<Invoice> toExport = new ArrayList<>();
        Map<String, String> sampleText = new HashMap<>();
        int uploads = 0;
        for (Plan p : plans) {
            Supplier s = SampleInvoices.supplier(p.supplier);
            int no = numbers.merge(p.supplier, 30 + rnd.nextInt(20), (a, b) -> a + 1 + rnd.nextInt(6));
            LocalDate invDate = today.minusDays(p.daysAgo);
            Sample sm = SampleInvoices.make(s, no, invDate, rnd, p.flaw);
            // the bill reaches the office one or two days after its date
            LocalDateTime up = invDate.plusDays(p.daysAgo > 1 ? 1 + rnd.nextInt(2) : 0).atTime(LocalTime.of(10 + rnd.nextInt(6), rnd.nextInt(60)));
            LocalDateTime realNow = LocalDateTime.now(db.clock()).withSecond(0).withNano(0);
            if (up.isAfter(realNow)) up = realNow.minusMinutes(20 + rnd.nextInt(120));
            if (!autoOn && p.daysAgo <= 60) {
                autoOn = true;
                db.settings.autoApprove = true;
                db.setNow(up.minusHours(1));
                db.log(admin.name, "Changed settings", "Turned on auto-check: approve clean invoices from known vendors (95%+ sure, up to Rs 25,000)");
            }
            db.setNow(up);
            User clerk = uploads++ % 3 == 2 ? sunil : ravi;
            Invoice inv = service.addText(clerk, sm.text(), "SAMPLE", fileName(s, sm));
            db.log(clerk.name, "Uploaded invoice", inv.id + " " + inv.fileName + " - Local AI, " + Math.round(inv.confidence * 100) + "% sure"
                    + (inv.autoApproved ? " - auto-approved" : ""));
            sampleText.put(inv.id, sm.text());
            boolean recent = p.daysAgo <= 6;

            // a clerk scans the same Netlink bill twice by mistake
            if (p.supplier.equals("netlink") && p.daysAgo == 55) {
                db.setNow(up.plusHours(3));
                Invoice again = service.addText(sunil, sm.text(), "SAMPLE", fileName(s, sm).replace(".txt", " (1).txt"));
                db.log(sunil.name, "Uploaded invoice", again.id + " " + again.fileName + " - Local AI");
                db.setNow(up.plusDays(1).withHour(11));
                service.reject(meera, again.id, "Duplicate - same bill as " + inv.id + " was uploaded twice.");
            }
            if (inv.status != Status.TO_REVIEW || recent) {
                if (inv.status == Status.APPROVED && p.daysAgo > 35) toExport.add(inv);
                continue;
            }
            // ---- the accountant reviews it a little later
            db.setNow(officeHours(up.plusHours(1 + rnd.nextInt(5)), rnd));
            if (p.flaw == Flaw.WRONG_TOTAL || p.flaw == Flaw.BAD_GSTIN || p.flaw == Flaw.WRONG_GST_TYPE) {
                String why = switch (p.flaw) {
                    case WRONG_TOTAL -> "Total does not match the items - asked the vendor for a corrected invoice.";
                    case BAD_GSTIN -> "Vendor GSTIN fails the check - asked the vendor to re-issue with the right GSTIN.";
                    default -> "Charged CGST/SGST on an inter-state supply - IGST is due. Sent back to the vendor.";
                };
                service.reject(meera, inv.id, why);
                continue;
            }
            Map<String, Object> fix = corrections(inv, sm.truth());
            if (!fix.isEmpty()) service.correct(meera, inv.id, fix);
            Map<String, Object> body = new HashMap<>();
            if (p.flaw == Flaw.WORDS_MISMATCH) {
                body.put("override", true);
                body.put("note", "Amount in words is a typing mistake by the vendor; figures are correct. Confirmed on phone.");
            }
            service.approve(meera, inv.id, body);
            if (p.daysAgo > 35) toExport.add(inv);
        }
        // exports to accounts, at the end of each month
        Map<String, List<Invoice>> byMonth = new TreeMap<>();
        for (Invoice inv : toExport) byMonth.computeIfAbsent(inv.v("invoiceDate").substring(0, 7), k -> new ArrayList<>()).add(inv);
        for (Map.Entry<String, List<Invoice>> e : byMonth.entrySet()) {
            LocalDate end = LocalDate.parse(e.getKey() + "-01").plusMonths(1).plusDays(2);
            if (end.isAfter(today.minusDays(30))) end = today.minusDays(30);
            db.setNow(end.atTime(17, 30));
            service.markExported(meera, e.getValue());
        }
        db.setNow(today.minusDays(130).atTime(9, 0));
        db.log(admin.name, "Set up the app", "Company " + db.settings.companyName + ", GSTIN " + db.settings.companyGstin);
        db.events.sort(Comparator.comparing((Event ev) -> ev.at));
        db.setNow(null);
    }

    /** Move a time into office hours (10:00 - 18:00). */
    private static LocalDateTime officeHours(LocalDateTime t, Random rnd) {
        if (t.getHour() >= 18) t = t.plusDays(1).withHour(10).withMinute(rnd.nextInt(60));
        if (t.getHour() < 10) t = t.withHour(10).withMinute(rnd.nextInt(60));
        return t;
    }

    /** What the accountant changes so that the invoice matches the truth. */
    static Map<String, Object> corrections(Invoice inv, Truth t) {
        Map<String, Object> fields = new LinkedHashMap<>();
        check(fields, inv, "vendorName", t.vendorName, true);
        check(fields, inv, "vendorGstin", t.vendorGstin, false);
        check(fields, inv, "invoiceNo", t.invoiceNo, false);
        check(fields, inv, "invoiceDate", t.invoiceDate, false);
        check(fields, inv, "dueDate", t.dueDate, false);
        check(fields, inv, "poNumber", t.poNumber, false);
        check(fields, inv, "buyerGstin", t.buyerGstin, false);
        check(fields, inv, "subtotal", Money.plain(t.subtotal), false);
        check(fields, inv, "cgst", t.cgst == 0 ? "" : Money.plain(t.cgst), false);
        check(fields, inv, "sgst", t.sgst == 0 ? "" : Money.plain(t.sgst), false);
        check(fields, inv, "igst", t.igst == 0 ? "" : Money.plain(t.igst), false);
        check(fields, inv, "total", Money.plain(t.total), false);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!fields.isEmpty()) out.put("fields", fields);
        if (!t.category.equals(inv.category)) out.put("category", t.category);
        return out;
    }

    private static void check(Map<String, Object> fields, Invoice inv, String f, String want, boolean ignoreCase) {
        String got = inv.v(f);
        if (ignoreCase ? !got.equalsIgnoreCase(want) : !got.equals(want)) fields.put(f, want);
    }

    private static String fileName(Supplier s, Sample sm) {
        return s.key() + "-" + sm.truth().invoiceNo.replaceAll("[^A-Za-z0-9-]", "-") + ".txt";
    }

    private static User user(DataStore db, String username, String name, Role role, String password) {
        User u = new User();
        u.id = db.nextId("U");
        u.username = username;
        u.name = name;
        u.role = role;
        u.passwordHash = Passwords.hash(password);
        u.createdAt = db.nowText();
        db.users.add(u);
        return u;
    }
}
