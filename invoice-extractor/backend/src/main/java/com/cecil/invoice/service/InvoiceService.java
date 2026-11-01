package com.cecil.invoice.service;

import com.cecil.invoice.extract.*;
import com.cecil.invoice.model.*;
import com.cecil.invoice.store.DataStore;
import com.cecil.invoice.util.Fuzzy;
import com.cecil.invoice.util.Gstin;
import com.cecil.invoice.util.Money;
import com.cecil.invoice.util.Text;

import java.time.LocalDate;
import java.util.*;

/**
 * The life of an invoice:
 *   upload -> read (extract) -> check (validate) -> review and correct -> approve / reject -> export.
 * Clean invoices from known vendors can be approved automatically ("auto-check").
 */
public class InvoiceService {
    public static final int MAX_TEXT = 200_000;

    private final DataStore db;
    private final LearningService learning;
    private ClaudeExtractor.Transport claudeTransport;   // null = use the real API (set by tests)

    public InvoiceService(DataStore db, LearningService learning) {
        this.db = db;
        this.learning = learning;
    }

    public void setClaudeTransport(ClaudeExtractor.Transport t) { this.claudeTransport = t; }

    /** Claude can be used when a key is set (or a test transport) and the admin turned it on. */
    public boolean claudeReady() {
        return db.settings.useClaude && (claudeTransport != null || ClaudeExtractor.apiKey() != null);
    }

    public boolean claudeKeyPresent() { return claudeTransport != null || ClaudeExtractor.apiKey() != null; }

    // ================================================================ upload

    /**
     * New invoice from the browser.
     * body: { kind: "text" | "pdf" | "image", fileName, text, data (Base64), mediaType, engine: "local" | "claude" }
     */
    public Invoice upload(User by, Map<String, Object> body) {
        String kind = str(body.get("kind"));
        String fileName = Text.cut(str(body.get("fileName")), 120);
        boolean wantClaude = "claude".equals(str(body.get("engine")));
        if (wantClaude && !claudeReady()) throw ApiError.bad("Claude is not available. The admin must set ANTHROPIC_API_KEY and turn Claude on in Settings.");

        Invoice inv = newInvoice(by);
        inv.fileName = fileName.isEmpty() ? "pasted-text.txt" : fileName;
        String note = "";
        long t0 = System.nanoTime();
        switch (kind) {
            case "text" -> {
                String text = str(body.get("text"));
                if (text.isBlank()) throw ApiError.bad("The invoice text is empty.");
                if (text.length() > MAX_TEXT) throw ApiError.bad("The text is too long (more than 200,000 characters).");
                inv.source = fileName.isEmpty() ? "PASTE" : "TXT";
                inv.rawText = text.replace("\r\n", "\n");
                if (wantClaude) readWithClaude(inv, "text/plain", inv.rawText);
                else readLocally(inv);
            }
            case "pdf" -> {
                byte[] pdf = base64(body.get("data"));
                inv.source = "PDF";
                String text;
                try { text = PdfText.extract(pdf); } catch (IllegalArgumentException e) { throw ApiError.bad(e.getMessage()); }
                inv.rawText = text;
                if (wantClaude) readWithClaude(inv, "application/pdf", Base64.getEncoder().encodeToString(pdf));
                else if (text.isBlank() || text.replaceAll("\\s", "").length() < 30) {
                    if (!claudeReady()) throw ApiError.bad("No text was found in this PDF - it is probably a scan (a photo). "
                            + "Turn on Claude in Settings to read scans, or paste the invoice text instead.");
                    readWithClaude(inv, "application/pdf", Base64.getEncoder().encodeToString(pdf));
                    note = "The PDF had no text layer, so Claude read it.";
                } else readLocally(inv);
            }
            case "image" -> {
                String media = str(body.get("mediaType"));
                if (!Set.of("image/png", "image/jpeg", "image/webp", "image/gif").contains(media)) throw ApiError.bad("Use a PNG, JPG or WebP image.");
                byte[] img = base64(body.get("data"));
                if (!claudeReady()) throw ApiError.bad("Photos of invoices can only be read by Claude. Ask the admin to set ANTHROPIC_API_KEY and turn Claude on in Settings, "
                        + "or paste the invoice text / upload a PDF made by software.");
                inv.source = "IMAGE";
                readWithClaude(inv, media, Base64.getEncoder().encodeToString(img));
            }
            default -> throw ApiError.bad("Unknown upload type.");
        }
        inv.extractMillis = (int) ((System.nanoTime() - t0) / 1_000_000);
        inv.note = note;
        synchronized (db) {
            finish(inv, by);
            db.invoices.add(inv);
            db.log(by.name, "Uploaded invoice", inv.id + " " + inv.fileName + " - " + inv.engine + ", " + Math.round(inv.confidence * 100) + "% sure"
                    + (inv.autoApproved ? " - auto-approved" : ""));
            db.save("invoices", "events", "vendors", "model");
        }
        return inv;
    }

    /** Used by the demo data and the tests: read a text invoice at a given time. */
    public Invoice addText(User by, String text, String source, String fileName) {
        Invoice inv = newInvoice(by);
        inv.source = source;
        inv.fileName = fileName;
        inv.rawText = text;
        long t0 = System.nanoTime();
        readLocally(inv);
        inv.extractMillis = Math.max(1, (int) ((System.nanoTime() - t0) / 1_000_000));
        synchronized (db) {
            finish(inv, by);
            db.invoices.add(inv);
        }
        return inv;
    }

    private Invoice newInvoice(User by) {
        Invoice inv = new Invoice();
        inv.id = db.nextId("IX");
        inv.uploadedBy = by.id;
        inv.uploadedAt = db.nowText();
        return inv;
    }

    private void readLocally(Invoice inv) {
        LocalExtractor ex = new LocalExtractor(new LocalExtractor.Context(db.vendors, db.settings, db.today()));
        LocalExtractor.Result r = ex.extract(inv.rawText);
        inv.engine = "Local AI";
        inv.fields = r.fields;
        inv.items = r.items;
        inv.vendorId = r.vendor == null ? "" : r.vendor.id;
    }

    private void readWithClaude(Invoice inv, String mediaType, String data) {
        ClaudeExtractor.Transport t = claudeTransport != null ? claudeTransport : ClaudeExtractor.http(ClaudeExtractor.apiKey());
        ClaudeExtractor c = new ClaudeExtractor(db.settings.claudeModel, t);
        try {
            ClaudeExtractor.Reading r = c.read(mediaType, data);
            inv.engine = "Claude (" + db.settings.claudeModel + ")";
            inv.fields = r.fields;
            inv.items = r.items;
            if (inv.rawText == null || inv.rawText.isBlank()) inv.rawText = r.text;
            inv.vendorId = matchVendor(inv.v("vendorGstin"), inv.v("vendorName"));
        } catch (Exception e) {
            if (inv.rawText != null && !inv.rawText.isBlank()) {
                readLocally(inv);
                inv.note = "Claude could not be reached (" + Text.cut(e.getMessage(), 160) + "), so the built-in reader was used.";
            } else {
                throw ApiError.bad("Claude could not read the invoice: " + Text.cut(String.valueOf(e.getMessage()), 200));
            }
        }
    }

    /** Category, checks, confidence, and maybe automatic approval. Caller holds the db lock. */
    private void finish(Invoice inv, User by) {
        Vendor v = db.vendor(inv.vendorId);
        learning.classify(inv, v);
        Validator.run(inv, db.invoices, db.settings, db.today());
        maybeAutoApprove(inv, v);
    }

    private void maybeAutoApprove(Invoice inv, Vendor v) {
        Settings s = db.settings;
        if (!s.autoApprove || v == null || inv.status != Status.TO_REVIEW) return;
        if (inv.confidence < s.autoApproveConfidence) return;
        if (s.autoApproveMaxTotal > 0 && inv.num("total") > s.autoApproveMaxTotal) return;
        if (Validator.count(inv.checks, Validator.FAIL) > 0) return;
        for (Check c : inv.checks) if (c.code.equals("DUPLICATE") && !c.result.equals(Validator.PASS)) return;
        if (inv.categoryConfidence < 0.6) return;
        inv.status = Status.APPROVED;
        inv.autoApproved = true;
        inv.reviewedBy = "auto";
        inv.reviewedAt = db.nowText();
        learning.learnFromApproval(inv, v);
    }

    /** Find a known vendor by GSTIN, then by name. "" when it is new. */
    String matchVendor(String gstin, String name) {
        if (gstin != null && !gstin.isBlank()) for (Vendor v : db.vendors) if (gstin.equals(v.gstin)) return v.id;
        Vendor best = null; double bestSim = 0;
        for (Vendor v : db.vendors) {
            double s = Fuzzy.sameCompany(name, v.name);
            for (String a : v.aliases) s = Math.max(s, Fuzzy.sameCompany(name, a));
            if (s > bestSim) { bestSim = s; best = v; }
        }
        return best != null && bestSim >= 0.85 ? best.id : "";
    }

    // ================================================================ review

    /** Read the invoice again (e.g. after the app learned this vendor's labels). People's corrections are kept. */
    public Invoice reread(User by, String id) {
        synchronized (db) {
            Invoice inv = need(id);
            editable(inv);
            if (inv.rawText == null || inv.rawText.isBlank()) throw ApiError.bad("There is no text to read again.");
            Map<String, FieldValue> kept = new LinkedHashMap<>();
            for (Map.Entry<String, FieldValue> e : inv.fields.entrySet()) if (e.getValue().corrected) kept.put(e.getKey(), e.getValue());
            readLocally(inv);
            inv.fields.putAll(kept);
            if (!kept.containsKey("vendorName") && inv.vendorId.isEmpty()) inv.vendorId = matchVendor(inv.v("vendorGstin"), inv.v("vendorName"));
            learning.classify(inv, db.vendor(inv.vendorId));
            Validator.run(inv, db.invoices, db.settings, db.today());
            db.log(by.name, "Read invoice again", inv.id);
            db.save("invoices", "events");
            return inv;
        }
    }

    /**
     * Save a person's corrections.
     * body: { fields: { name: value }, items: [ {description, hsn, qty, rate, amount} ], category, note }
     */
    @SuppressWarnings("unchecked")
    public Invoice correct(User by, String id, Map<String, Object> body) {
        synchronized (db) {
            Invoice inv = need(id);
            editable(inv);
            List<String> changed = new ArrayList<>();
            if (body.get("fields") instanceof Map<?, ?> m) {
                for (String f : Invoice.FIELDS) {
                    if (!m.containsKey(f)) continue;
                    String value = normalise(f, str(m.get(f)));
                    FieldValue old = inv.f(f);
                    if (Objects.equals(old.value, value)) continue;
                    FieldValue nf = new FieldValue(value, 1.0, old.line, old.label, "corrected by " + by.name);
                    nf.corrected = true;
                    nf.original = old.corrected ? old.original : old.value;
                    if (value.equals(nf.original)) { nf.corrected = false; nf.method = "put back by " + by.name; nf.confidence = Math.max(old.confidence, 0.5); }
                    inv.fields.put(f, nf);
                    changed.add(Validator.label(f));
                }
            }
            if (body.get("items") instanceof List<?> list) {
                List<LineItem> items = new ArrayList<>();
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> r)) continue;
                    LineItem it = new LineItem();
                    it.description = Text.cut(str(r.get("description")), 200);
                    it.hsn = Text.cut(str(r.get("hsn")), 10).replaceAll("[^0-9]", "");
                    it.qty = num(r.get("qty"));
                    it.rate = num(r.get("rate"));
                    it.amount = num(r.get("amount"));
                    it.mathOk = Money.near(it.qty * it.rate, it.amount, Math.max(1.0, it.amount * 0.001));
                    if (it.description.isEmpty() && it.amount == 0) continue;
                    items.add(it);
                }
                if (items.size() > 200) throw ApiError.bad("Too many item rows.");
                if (!sameItems(items, inv.items)) { inv.items = items; changed.add("line items"); }
            }
            String cat = str(body.get("category"));
            if (!cat.isEmpty() && !cat.equals(inv.category)) {
                if (!Classifier.CATEGORIES.contains(cat)) throw ApiError.bad("Unknown category.");
                inv.category = cat;
                inv.categoryCorrected = true;
                inv.categoryConfidence = 1.0;
                changed.add("category");
            }
            if (body.containsKey("note")) inv.note = Text.cut(str(body.get("note")), 500);
            if (changed.contains(Validator.label("vendorName")) || changed.contains(Validator.label("vendorGstin"))) {
                inv.vendorId = matchVendor(inv.v("vendorGstin"), inv.v("vendorName"));
            }
            if (!changed.isEmpty()) learning.classify(inv, db.vendor(inv.vendorId));
            Validator.run(inv, db.invoices, db.settings, db.today());
            if (!changed.isEmpty()) db.log(by.name, "Corrected invoice", inv.id + ": " + String.join(", ", changed));
            db.save("invoices", "events");
            return inv;
        }
    }

    public Invoice approve(User by, String id, Map<String, Object> body) {
        synchronized (db) {
            Invoice inv = need(id);
            if (inv.status != Status.TO_REVIEW) throw ApiError.conflict("Only invoices waiting for review can be approved.");
            Validator.run(inv, db.invoices, db.settings, db.today());
            int fails = Validator.count(inv.checks, Validator.FAIL);
            String note = Text.cut(str(body.get("note")), 500);
            if (inv.f("invoiceNo").empty() || inv.f("total").empty() || inv.f("vendorName").empty())
                throw ApiError.bad("Fill in the vendor, invoice number and total before approving.");
            if (fails > 0 && !Boolean.TRUE.equals(body.get("override")))
                throw ApiError.conflict(fails + " check" + (fails == 1 ? " has" : "s have") + " failed. Fix the fields, or approve anyway with a note.");
            if (fails > 0 && note.length() < 5) throw ApiError.bad("Write a short note saying why you approve it although a check failed.");
            Vendor v = vendorFor(inv);
            inv.status = Status.APPROVED;
            inv.reviewedBy = by.id;
            inv.reviewedAt = db.nowText();
            if (!note.isEmpty()) inv.note = note;
            List<String> learned = learning.learnFromApproval(inv, v);
            db.log(by.name, "Approved invoice", inv.id + " " + inv.v("vendorName") + " " + inv.v("invoiceNo") + " - Rs " + Money.format(inv.num("total"))
                    + (fails > 0 ? " (approved although " + fails + " check(s) failed: " + note + ")" : ""));
            if (!learned.isEmpty()) db.log("Learning", "Learned from " + inv.id, v.name + ": " + String.join("; ", learned));
            db.save("invoices", "vendors", "events", "model");
            return inv;
        }
    }

    /** The vendor record for an approved invoice - created the first time this vendor is approved. */
    private Vendor vendorFor(Invoice inv) {
        String id = inv.vendorId.isEmpty() ? matchVendor(inv.v("vendorGstin"), inv.v("vendorName")) : inv.vendorId;
        Vendor v = db.vendor(id);
        if (v == null) {
            v = new Vendor();
            v.id = db.nextId("V");
            v.name = Text.titleIfShouting(inv.v("vendorName"));
            v.gstin = Gstin.valid(inv.v("vendorGstin")) ? inv.v("vendorGstin") : "";
            v.createdAt = db.nowText();
            db.vendors.add(v);
            db.log("Learning", "New vendor", v.name + (v.gstin.isEmpty() ? "" : " (" + v.gstin + ")"));
        } else if (v.gstin.isEmpty() && Gstin.valid(inv.v("vendorGstin"))) {
            v.gstin = inv.v("vendorGstin");
        }
        inv.vendorId = v.id;
        return v;
    }

    public Invoice reject(User by, String id, String reason) {
        synchronized (db) {
            Invoice inv = need(id);
            if (inv.status != Status.TO_REVIEW) throw ApiError.conflict("Only invoices waiting for review can be rejected.");
            reason = Text.cut(reason, 300);
            if (reason.length() < 3) throw ApiError.bad("Please give a reason.");
            inv.status = Status.REJECTED;
            inv.reviewedBy = by.id;
            inv.reviewedAt = db.nowText();
            inv.note = reason;
            db.log(by.name, "Rejected invoice", inv.id + ": " + reason);
            db.save("invoices", "events");
            return inv;
        }
    }

    /** Send an approved (not yet exported) or rejected invoice back for review. */
    public Invoice reopen(User by, String id) {
        synchronized (db) {
            Invoice inv = need(id);
            if (inv.status == Status.EXPORTED) throw ApiError.conflict("This invoice was already exported to accounts and cannot be reopened.");
            if (inv.status == Status.TO_REVIEW) return inv;
            inv.status = Status.TO_REVIEW;
            inv.autoApproved = false;
            inv.reviewedBy = "";
            inv.reviewedAt = "";
            Validator.run(inv, db.invoices, db.settings, db.today());
            db.log(by.name, "Reopened invoice", inv.id);
            db.save("invoices", "events");
            return inv;
        }
    }

    /** Mark approved invoices as sent to accounts. Returns how many changed. */
    public int markExported(User by, List<Invoice> list) {
        synchronized (db) {
            int n = 0;
            for (Invoice inv : list) if (inv.status == Status.APPROVED) { inv.status = Status.EXPORTED; n++; }
            if (n > 0) {
                db.log(by.name, "Exported to accounts", n + " invoice" + (n == 1 ? "" : "s"));
                db.save("invoices", "events");
            }
            return n;
        }
    }

    // ================================================================ reading lists

    public List<Map<String, Object>> list(Map<String, String> q) {
        String status = q.get("status"), text = q.getOrDefault("q", "").toLowerCase(Locale.ROOT).strip(), vendor = q.get("vendor");
        String category = q.get("category");
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (db) {
            List<Invoice> sorted = new ArrayList<>(db.invoices);
            sorted.sort((a, b) -> b.uploadedAt.compareTo(a.uploadedAt));
            for (Invoice inv : sorted) {
                if (status != null && !status.isEmpty() && !status.equals(inv.status.name())) continue;
                if (vendor != null && !vendor.isEmpty() && !vendor.equals(inv.vendorId)) continue;
                if (category != null && !category.isEmpty() && !category.equals(inv.category)) continue;
                if (!text.isEmpty()) {
                    String hay = (inv.id + " " + inv.v("vendorName") + " " + inv.v("invoiceNo") + " " + inv.fileName + " " + inv.v("total") + " " + inv.category).toLowerCase(Locale.ROOT);
                    if (!hay.contains(text)) continue;
                }
                out.add(summary(inv));
            }
        }
        return out;
    }

    public Map<String, Object> summary(Invoice inv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", inv.id);
        m.put("fileName", inv.fileName);
        m.put("source", inv.source);
        m.put("engine", inv.engine);
        m.put("status", inv.status);
        m.put("statusLabel", inv.status.label);
        m.put("autoApproved", inv.autoApproved);
        m.put("vendorName", inv.v("vendorName"));
        m.put("vendorId", inv.vendorId);
        m.put("newVendor", inv.vendorId.isEmpty());
        m.put("invoiceNo", inv.v("invoiceNo"));
        m.put("invoiceDate", inv.v("invoiceDate"));
        m.put("dueDate", inv.v("dueDate"));
        m.put("subtotal", inv.num("subtotal"));
        m.put("taxTotal", inv.num("taxTotal") > 0 ? inv.num("taxTotal") : inv.num("cgst") + inv.num("sgst") + inv.num("igst"));
        m.put("total", inv.num("total"));
        m.put("currency", inv.v("currency"));
        m.put("category", inv.category);
        m.put("confidence", inv.confidence);
        m.put("fails", Validator.count(inv.checks, Validator.FAIL));
        m.put("warns", Validator.count(inv.checks, Validator.WARN));
        m.put("duplicateOf", inv.duplicateOf);
        m.put("uploadedAt", inv.uploadedAt);
        m.put("uploadedBy", db.userName(inv.uploadedBy));
        m.put("reviewedBy", "auto".equals(inv.reviewedBy) ? "Auto-check" : db.userName(inv.reviewedBy));
        m.put("reviewedAt", inv.reviewedAt);
        int corrected = 0;
        for (FieldValue f : inv.fields.values()) if (f.corrected) corrected++;
        m.put("corrected", corrected);
        return m;
    }

    /** Everything about one invoice, for the review screen. */
    public Map<String, Object> detail(String id) {
        synchronized (db) {
            Invoice inv = need(id);
            Map<String, Object> m = summary(inv);
            m.put("rawLines", LocalExtractor.lines(inv.rawText));
            List<Map<String, Object>> fields = new ArrayList<>();
            for (String f : Invoice.FIELDS) {
                FieldValue v = inv.f(f);
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("name", f);
                fm.put("label", Validator.label(f));
                fm.put("value", v.value);
                fm.put("confidence", v.empty() ? 0 : v.confidence);
                fm.put("line", v.line);
                fm.put("foundLabel", v.label);
                fm.put("method", v.method);
                fm.put("corrected", v.corrected);
                fm.put("original", v.original);
                fields.add(fm);
            }
            m.put("fields", fields);
            m.put("items", inv.items);
            m.put("checks", inv.checks);
            m.put("categoryScores", inv.categoryScores);
            m.put("categoryConfidence", inv.categoryConfidence);
            m.put("categoryCorrected", inv.categoryCorrected);
            m.put("note", inv.note);
            m.put("extractMillis", inv.extractMillis);
            Vendor v = db.vendor(inv.vendorId);
            if (v != null) {
                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("id", v.id);
                vm.put("name", v.name);
                vm.put("gstin", v.gstin);
                vm.put("defaultCategory", v.defaultCategory);
                vm.put("labelHints", v.labelHints);
                m.put("vendor", vm);
            }
            Invoice dup = db.invoice(inv.duplicateOf);
            if (dup != null) m.put("duplicate", summary(dup));
            return m;
        }
    }

    // ================================================================ helpers

    private Invoice need(String id) {
        Invoice inv = db.invoice(id);
        if (inv == null) throw ApiError.notFound("Invoice");
        return inv;
    }

    private static void editable(Invoice inv) {
        if (inv.status != Status.TO_REVIEW) throw ApiError.conflict("This invoice is " + inv.status.label.toLowerCase(Locale.ROOT) + ". Reopen it first to make changes.");
    }

    /** Clean a value typed by a person into the stored form. */
    static String normalise(String field, String value) {
        value = Text.cut(value, 300);
        if (value.isEmpty()) return "";
        switch (field) {
            case "subtotal", "cgst", "sgst", "igst", "taxTotal", "total" -> {
                Double d = Money.parse(value);
                if (d == null) throw ApiError.bad(Validator.label(field) + ": \"" + value + "\" is not an amount.");
                return Money.plain(d);
            }
            case "invoiceDate", "dueDate" -> {
                LocalDate d = com.cecil.invoice.util.Dates.parse(value);
                if (d == null) throw ApiError.bad(Validator.label(field) + ": \"" + value + "\" is not a date.");
                return d.toString();
            }
            case "vendorGstin", "buyerGstin" -> { return value.toUpperCase(Locale.ROOT).replaceAll("\\s", ""); }
            case "currency" -> { return value.toUpperCase(Locale.ROOT); }
            default -> { return value; }
        }
    }

    private static boolean sameItems(List<LineItem> a, List<LineItem> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            LineItem x = a.get(i), y = b.get(i);
            if (!x.description.equals(y.description) || !x.hsn.equals(y.hsn) || x.qty != y.qty || x.rate != y.rate || x.amount != y.amount) return false;
        }
        return true;
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o).strip(); }

    static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        Double d = Money.parse(str(o));
        return d == null ? 0 : d;
    }

    private static byte[] base64(Object o) {
        String s = str(o);
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) s = s.substring(comma + 1);
        try {
            byte[] b = Base64.getDecoder().decode(s.replaceAll("\\s", ""));
            if (b.length == 0) throw ApiError.bad("The file is empty.");
            if (b.length > 8 * 1024 * 1024) throw ApiError.bad("The file is too big (more than 8 MB).");
            return b;
        } catch (IllegalArgumentException e) {
            throw ApiError.bad("The file could not be read.");
        }
    }
}
