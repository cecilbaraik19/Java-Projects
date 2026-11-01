package com.cecil.invoice.service;

import com.cecil.invoice.extract.Classifier;
import com.cecil.invoice.extract.Validator;
import com.cecil.invoice.model.*;
import com.cecil.invoice.store.DataStore;
import com.cecil.invoice.util.Csv;
import com.cecil.invoice.util.Gstin;
import com.cecil.invoice.util.Json;
import com.cecil.invoice.util.Money;
import com.cecil.invoice.util.Text;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;

/** The numbers for the Day Book (home page), the ledger, vendors, the AI model page, settings and exports. */
public class DashboardService {
    private final DataStore db;
    private final LearningService learning;
    private final InvoiceService invoices;

    public DashboardService(DataStore db, LearningService learning, InvoiceService invoices) {
        this.db = db;
        this.learning = learning;
        this.invoices = invoices;
    }

    private static boolean booked(Invoice i) { return i.status == Status.APPROVED || i.status == Status.EXPORTED; }

    // ================================================================ home

    public Map<String, Object> home() {
        synchronized (db) {
            LocalDate today = db.today();
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Integer> byStatus = new LinkedHashMap<>();
            for (Status s : Status.values()) byStatus.put(s.name(), 0);
            double pendingAmount = 0, monthSpend = 0, monthTax = 0;
            int monthCount = 0, last30 = 0, auto30 = 0, people30 = 0;
            double conf30 = 0;
            long millis = 0; int timed = 0;
            String thisMonth = YearMonth.from(today).toString();
            for (Invoice i : db.invoices) {
                byStatus.merge(i.status.name(), 1, Integer::sum);
                if (i.status == Status.TO_REVIEW) pendingAmount += i.num("total");
                if (booked(i) && i.v("invoiceDate").compareTo(today.minusDays(30).toString()) >= 0) { monthSpend += i.num("total"); monthTax += tax(i); monthCount++; }
                if (i.uploadedAt.compareTo(today.minusDays(30).toString()) >= 0) {
                    last30++;
                    conf30 += i.confidence;
                    if (i.autoApproved) auto30++;
                    else if (booked(i) || i.status == Status.REJECTED) people30++;
                }
                if (i.extractMillis > 0) { millis += i.extractMillis; timed++; }
            }
            out.put("byStatus", byStatus);
            out.put("pendingAmount", Money.r2(pendingAmount));
            out.put("monthLabel", today.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + today.getYear());
            out.put("monthSpend", Money.r2(monthSpend));
            out.put("monthTax", Money.r2(monthTax));
            out.put("monthCount", monthCount);
            out.put("uploaded30", last30);
            out.put("avgConfidence30", last30 == 0 ? 0 : Math.round(conf30 / last30 * 1000) / 1000.0);
            out.put("autoRate30", auto30 + people30 == 0 ? 0 : Math.round(100.0 * auto30 / (auto30 + people30)) / 100.0);
            out.put("avgMillis", timed == 0 ? 0 : millis / timed);
            out.put("accuracy", fieldAccuracy());

            // spend per month (last 6 months), by invoice date
            List<Map<String, Object>> months = new ArrayList<>();
            for (int k = 5; k >= 0; k--) {
                YearMonth ym = YearMonth.from(today).minusMonths(k);
                double sum = 0, t = 0; int n = 0;
                for (Invoice i : db.invoices) if (booked(i) && i.v("invoiceDate").startsWith(ym.toString())) { sum += i.num("total"); t += tax(i); n++; }
                months.add(Map.of("month", ym.toString(), "label", ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                        "total", Money.r2(sum), "tax", Money.r2(t), "count", n));
            }
            out.put("months", months);

            Map<String, Double> cats = new LinkedHashMap<>();
            Map<String, double[]> vend = new LinkedHashMap<>();
            for (Invoice i : db.invoices) {
                if (!booked(i) || i.v("invoiceDate").compareTo(today.minusDays(90).toString()) < 0) continue;
                cats.merge(i.category.isEmpty() ? "Uncategorised" : i.category, i.num("total"), Double::sum);
                double[] v = vend.computeIfAbsent(i.v("vendorName"), x -> new double[2]);
                v[0] += i.num("total"); v[1]++;
            }
            out.put("categories", sortedList(cats, "category", 10));
            List<Map<String, Object>> tv = new ArrayList<>();
            vend.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0])).limit(6)
                    .forEach(e -> tv.add(Map.of("vendor", e.getKey(), "total", Money.r2(e.getValue()[0]), "count", (int) e.getValue()[1])));
            out.put("topVendors", tv);

            // waiting for review, oldest first
            List<Map<String, Object>> queue = new ArrayList<>();
            List<Invoice> waiting = new ArrayList<>();
            for (Invoice i : db.invoices) if (i.status == Status.TO_REVIEW) waiting.add(i);
            waiting.sort(Comparator.comparing(i -> i.uploadedAt));
            for (Invoice i : waiting) queue.add(invoices.summary(i));
            out.put("queue", queue);

            // bills due in the next 14 days (approved, by due date)
            List<Map<String, Object>> due = new ArrayList<>();
            for (Invoice i : db.invoices) {
                if (!booked(i) || i.v("dueDate").isEmpty()) continue;
                String d = i.v("dueDate");
                if (d.compareTo(today.minusDays(3).toString()) >= 0 && d.compareTo(today.plusDays(14).toString()) <= 0) due.add(invoices.summary(i));
            }
            due.sort(Comparator.comparing(m -> String.valueOf(m.get("dueDate"))));
            out.put("dueSoon", due);

            // which checks fail most often
            Map<String, Integer> failing = new LinkedHashMap<>();
            for (Invoice i : db.invoices)
                for (Check c : i.checks) if (!c.result.equals(Validator.PASS)) failing.merge(c.label, 1, Integer::sum);
            List<Map<String, Object>> fl = new ArrayList<>();
            failing.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(5)
                    .forEach(e -> fl.add(Map.of("label", e.getKey(), "count", e.getValue())));
            out.put("failingChecks", fl);
            out.put("recent", recentEvents(8));
            return out;
        }
    }

    private static double tax(Invoice i) {
        double t = i.num("taxTotal");
        return t > 0 ? t : i.num("cgst") + i.num("sgst") + i.num("igst");
    }

    /** For each field: how often the reader got it right without a person's correction (reviewed invoices only). */
    public List<Map<String, Object>> fieldAccuracy() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String f : List.of("vendorName", "invoiceNo", "invoiceDate", "dueDate", "vendorGstin", "subtotal", "taxTotal", "total")) {
            int seen = 0, fixed = 0;
            for (Invoice i : db.invoices) {
                if (!booked(i)) continue;
                FieldValue v = i.f(f);
                if (v.empty() && !v.corrected) continue;
                seen++;
                if (v.corrected) fixed++;
            }
            out.add(Map.of("field", f, "label", Validator.label(f), "seen", seen, "corrected", fixed,
                    "accuracy", seen == 0 ? 0 : Math.round(1000.0 * (seen - fixed) / seen) / 1000.0));
        }
        return out;
    }

    private static List<Map<String, Object>> sortedList(Map<String, Double> m, String key, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        m.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(limit)
                .forEach(e -> out.add(Map.of(key, e.getKey(), "total", Money.r2(e.getValue()))));
        return out;
    }

    public List<Event> recentEvents(int n) {
        List<Event> list = new ArrayList<>(db.events);
        Collections.reverse(list);
        return new ArrayList<>(list.subList(0, Math.min(n, list.size())));
    }

    // ================================================================ ledger (approved + exported)

    public Map<String, Object> ledger(Map<String, String> q) {
        synchronized (db) {
            String from = q.getOrDefault("from", ""), to = q.getOrDefault("to", "");
            String status = q.getOrDefault("status", "");
            List<Map<String, Object>> rows = new ArrayList<>();
            double sub = 0, tax = 0, total = 0;
            for (Invoice i : ledgerInvoices(from, to, status, q.get("category"), q.get("vendor"))) {
                rows.add(invoices.summary(i));
                sub += i.num("subtotal"); tax += tax(i); total += i.num("total");
            }
            return Map.of("rows", rows, "subtotal", Money.r2(sub), "tax", Money.r2(tax), "total", Money.r2(total));
        }
    }

    List<Invoice> ledgerInvoices(String from, String to, String status, String category, String vendor) {
        List<Invoice> out = new ArrayList<>();
        for (Invoice i : db.invoices) {
            if (!booked(i)) continue;
            if (!status.isEmpty() && !status.equals(i.status.name())) continue;
            String d = i.v("invoiceDate");
            if (!from.isEmpty() && d.compareTo(from) < 0) continue;
            if (!to.isEmpty() && d.compareTo(to) > 0) continue;
            if (category != null && !category.isEmpty() && !category.equals(i.category)) continue;
            if (vendor != null && !vendor.isEmpty() && !vendor.equals(i.vendorId)) continue;
            out.add(i);
        }
        out.sort(Comparator.comparing((Invoice i) -> i.v("invoiceDate")).thenComparing(i -> i.id));
        return out;
    }

    // ================================================================ vendors

    public List<Map<String, Object>> vendors() {
        synchronized (db) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Vendor v : db.vendors) out.add(vendorView(v));
            out.sort((a, b) -> Double.compare((Double) b.get("spend"), (Double) a.get("spend")));
            return out;
        }
    }

    private Map<String, Object> vendorView(Vendor v) {
        int count = 0, auto = 0, waiting = 0;
        double spend = 0, conf = 0;
        String last = "";
        for (Invoice i : db.invoices) {
            if (!v.id.equals(i.vendorId)) continue;
            count++;
            conf += i.confidence;
            if (i.status == Status.TO_REVIEW) waiting++;
            if (booked(i)) spend += i.num("total");
            if (i.autoApproved) auto++;
            if (i.v("invoiceDate").compareTo(last) > 0) last = i.v("invoiceDate");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id);
        m.put("name", v.name);
        m.put("gstin", v.gstin);
        m.put("gstinValid", Gstin.valid(v.gstin));
        m.put("state", v.gstin.isEmpty() ? "" : Gstin.state(v.gstin));
        m.put("defaultCategory", v.defaultCategory);
        m.put("aliases", v.aliases);
        List<Map<String, Object>> hints = new ArrayList<>();
        for (Map.Entry<String, String> e : v.labelHints.entrySet())
            hints.add(Map.of("field", e.getKey(), "label", Validator.label(e.getKey()), "words", e.getValue()));
        m.put("labelHints", hints);
        m.put("corrections", v.corrections);
        m.put("invoices", count);
        m.put("waiting", waiting);
        m.put("autoApproved", auto);
        m.put("spend", Money.r2(spend));
        m.put("avgConfidence", count == 0 ? 0 : Math.round(conf / count * 1000) / 1000.0);
        m.put("lastInvoice", last);
        m.put("createdAt", v.createdAt);
        return m;
    }

    /** Edit a vendor: name, GSTIN, usual category; remove a learned label. */
    public Map<String, Object> saveVendor(User by, String id, Map<String, Object> in) {
        synchronized (db) {
            Vendor v = db.vendor(id);
            if (v == null) throw ApiError.notFound("Vendor");
            String name = Text.cut(InvoiceService.str(in.get("name")), 80);
            if (name.length() < 2) throw ApiError.bad("Please enter the vendor's name.");
            String gstin = InvoiceService.str(in.get("gstin")).toUpperCase(Locale.ROOT).replaceAll("\\s", "");
            if (!gstin.isEmpty() && !Gstin.valid(gstin)) throw ApiError.bad("This GSTIN is not valid (wrong shape or check character).");
            for (Vendor o : db.vendors) if (!o.id.equals(v.id) && !gstin.isEmpty() && gstin.equals(o.gstin)) throw ApiError.conflict(o.name + " already has this GSTIN.");
            String cat = InvoiceService.str(in.get("defaultCategory"));
            if (!cat.isEmpty() && !Classifier.CATEGORIES.contains(cat)) throw ApiError.bad("Unknown category.");
            if (!name.equals(v.name) && !v.aliases.contains(v.name)) v.aliases.add(v.name);
            v.name = name;
            v.gstin = gstin;
            v.defaultCategory = cat;
            if (in.get("forget") instanceof String f && v.labelHints.remove(f) != null)
                db.log(by.name, "Removed learned label", v.name + ": " + Validator.label(f));
            db.log(by.name, "Edited vendor", v.name);
            db.save("vendors", "events");
            return vendorView(v);
        }
    }

    // ================================================================ the AI model page

    public Map<String, Object> model() {
        synchronized (db) {
            CategoryModel m = db.model;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("trainedOn", m.trainedOn);
            out.put("starterExamples", LearningService.STARTER.length);
            out.put("updatedAt", m.updatedAt);
            Set<String> vocab = new HashSet<>();
            for (Map<String, Double> c : m.words.values()) vocab.addAll(c.keySet());
            out.put("vocabulary", vocab.size());
            Map<String, List<String>> top = learning.classifier().topWords(8);
            List<Map<String, Object>> cats = new ArrayList<>();
            for (String c : Classifier.CATEGORIES) {
                int approved = 0, rightFirst = 0;
                for (Invoice i : db.invoices) if (booked(i) && c.equals(i.category)) { approved++; if (!i.categoryCorrected) rightFirst++; }
                cats.add(Map.of("category", c, "examples", m.docs.getOrDefault(c, 0.0).intValue(), "invoices", approved,
                        "rightFirstTime", rightFirst, "topWords", top.getOrDefault(c, List.of())));
            }
            out.put("categories", cats);
            int booked = 0, catRight = 0;
            for (Invoice i : db.invoices) if (booked(i)) { booked++; if (!i.categoryCorrected) catRight++; }
            out.put("categoryAccuracy", booked == 0 ? 0 : Math.round(1000.0 * catRight / booked) / 1000.0);
            out.put("fieldAccuracy", fieldAccuracy());
            List<Map<String, Object>> learned = new ArrayList<>();
            for (Vendor v : db.vendors)
                for (Map.Entry<String, String> e : v.labelHints.entrySet())
                    learned.add(Map.of("vendor", v.name, "vendorId", v.id, "field", Validator.label(e.getKey()), "words", e.getValue()));
            out.put("learnedLabels", learned);
            // how the share of invoices that needed no correction changed, month by month
            List<Map<String, Object>> trend = new ArrayList<>();
            LocalDate today = db.today();
            for (int k = 3; k >= 0; k--) {
                YearMonth ym = YearMonth.from(today).minusMonths(k);
                int n = 0, clean = 0, auto = 0;
                for (Invoice i : db.invoices) {
                    if (!booked(i) || !i.uploadedAt.startsWith(ym.toString())) continue;
                    n++;
                    boolean fixed = i.categoryCorrected;
                    for (FieldValue f : i.fields.values()) if (f.corrected) fixed = true;
                    if (!fixed) clean++;
                    if (i.autoApproved) auto++;
                }
                trend.add(Map.of("label", ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), "invoices", n,
                        "clean", n == 0 ? 0 : Math.round(100.0 * clean / n) / 100.0, "auto", n == 0 ? 0 : Math.round(100.0 * auto / n) / 100.0));
            }
            out.put("trend", trend);
            out.put("claudeKey", invoices.claudeKeyPresent());
            out.put("claudeOn", invoices.claudeReady());
            return out;
        }
    }

    public Map<String, Object> testCategory(String text) {
        if (text == null || text.isBlank()) throw ApiError.bad("Type some words to test.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("words", Classifier.words(text));
        out.put("scores", learning.classifier().predict(Text.cut(text, 2000)));
        return out;
    }

    public Map<String, Object> retrain(User by) {
        synchronized (db) {
            Map<String, Object> r = learning.retrain();
            db.log(by.name, "Retrained category model", r.get("invoices") + " approved invoices + starter words");
            db.save("model", "events");
            return r;
        }
    }

    // ================================================================ settings

    public Settings saveSettings(User by, Map<String, Object> in) {
        synchronized (db) {
            Settings s = db.settings;
            String name = Text.cut(InvoiceService.str(in.get("companyName")), 80);
            if (name.length() < 2) throw ApiError.bad("Enter the company name.");
            String gstin = InvoiceService.str(in.get("companyGstin")).toUpperCase(Locale.ROOT).replaceAll("\\s", "");
            if (!gstin.isEmpty() && !Gstin.valid(gstin)) throw ApiError.bad("Our GSTIN is not valid (wrong shape or check character).");
            double conf = InvoiceService.num(in.get("autoApproveConfidence"));
            if (conf > 1) conf = conf / 100;
            if (conf < 0.5 || conf > 0.99) throw ApiError.bad("Auto-approve confidence must be between 50% and 99%.");
            double max = InvoiceService.num(in.get("autoApproveMaxTotal"));
            if (max < 0 || max > 10_000_000) throw ApiError.bad("The auto-approve limit must be between 0 and 1,00,00,000.");
            double tol = InvoiceService.num(in.get("amountTolerance"));
            if (tol < 0 || tol > 100) throw ApiError.bad("Rounding allowance must be between 0 and 100 rupees.");
            int days = (int) InvoiceService.num(in.get("duplicateDays"));
            if (days < 1 || days > 365) throw ApiError.bad("Duplicate window must be 1 to 365 days.");
            String model = Text.cut(InvoiceService.str(in.get("claudeModel")), 60);
            if (!model.matches("[a-z0-9.-]{3,60}")) throw ApiError.bad("Claude model name looks wrong.");
            s.companyName = name;
            s.companyGstin = gstin;
            s.companyCity = Text.cut(InvoiceService.str(in.get("companyCity")), 40);
            s.autoApprove = Boolean.TRUE.equals(in.get("autoApprove"));
            s.autoApproveConfidence = Math.round(conf * 100) / 100.0;
            s.autoApproveMaxTotal = Money.r2(max);
            s.amountTolerance = Money.r2(tol);
            s.duplicateDays = days;
            s.useClaude = Boolean.TRUE.equals(in.get("useClaude"));
            s.claudeModel = model;
            db.log(by.name, "Changed settings", "Auto-check " + (s.autoApprove ? "on (" + Math.round(s.autoApproveConfidence * 100) + "%+, up to Rs "
                    + Money.format(s.autoApproveMaxTotal) + ")" : "off") + ", Claude " + (s.useClaude ? "on" : "off"));
            db.save("settings", "events");
            return s;
        }
    }

    // ================================================================ exports

    /** The invoice register as CSV (one row per invoice). */
    public String registerCsv(List<Invoice> list) {
        List<List<Object>> rows = new ArrayList<>();
        for (Invoice i : list) {
            rows.add(List.of(i.id, i.v("invoiceDate"), i.v("vendorName"), i.v("vendorGstin"), i.v("invoiceNo"), i.v("poNumber"), i.v("dueDate"),
                    i.category, i.num("subtotal"), i.num("cgst"), i.num("sgst"), i.num("igst"), Money.r2(tax(i)), i.num("total"), i.v("currency"),
                    i.status.label, Math.round(i.confidence * 100), i.autoApproved ? "Auto-check" : db.userName(i.reviewedBy), i.fileName));
        }
        return Csv.build(List.of("Ref", "Invoice date", "Vendor", "Vendor GSTIN", "Invoice no", "PO no", "Due date", "Category",
                "Taxable value", "CGST", "SGST", "IGST", "Total tax", "Total", "Currency", "Status", "Confidence %", "Approved by", "File"), rows);
    }

    /** Every line item as CSV (one row per item). */
    public String itemsCsv(List<Invoice> list) {
        List<List<Object>> rows = new ArrayList<>();
        for (Invoice i : list)
            for (LineItem it : i.items)
                rows.add(List.of(i.id, i.v("invoiceDate"), i.v("vendorName"), i.v("invoiceNo"), it.description, it.hsn, it.qty, it.rate, it.amount, i.category));
        return Csv.build(List.of("Ref", "Invoice date", "Vendor", "Invoice no", "Description", "HSN/SAC", "Qty", "Rate", "Amount", "Category"), rows);
    }

    /** Clean JSON for another system (for example an accounting app). */
    public String json(List<Invoice> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Invoice i : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", i.id);
            for (String f : Invoice.FIELDS) {
                FieldValue v = i.f(f);
                if (v.empty()) continue;
                m.put(f, Set.of("subtotal", "cgst", "sgst", "igst", "taxTotal", "total").contains(f) ? (Object) i.num(f) : v.value);
            }
            m.put("category", i.category);
            List<Map<String, Object>> items = new ArrayList<>();
            for (LineItem it : i.items)
                items.add(Map.of("description", it.description, "hsn", it.hsn, "qty", it.qty, "rate", it.rate, "amount", it.amount));
            m.put("items", items);
            m.put("status", i.status.name());
            out.add(m);
        }
        return Json.write(out).replace("\\u003c", "<").replace("\\u003e", ">").replace("\\u0026", "&");
    }
}
