package com.cecil.invoice.service;

import com.cecil.invoice.extract.Classifier;
import com.cecil.invoice.extract.LocalExtractor;
import com.cecil.invoice.model.*;
import com.cecil.invoice.store.DataStore;
import com.cecil.invoice.util.Dates;
import com.cecil.invoice.util.Money;

import java.time.LocalDate;
import java.util.*;

/**
 * How the app learns from people. Two kinds of learning happen when an invoice is approved:
 *
 * 1. Expense category - the approved invoice's words are added to the Naive Bayes
 *    classifier, so the next invoice with similar words gets the right category.
 * 2. Vendor labels - if a person had to correct a field, the app looks at the invoice text,
 *    finds where the right value is printed, and remembers the words in front of it.
 *    Example: Mehta &amp; Associates prints "Bill Ref : MA/26-27/041". The first time, the
 *    reader does not know that "Bill Ref" means the invoice number, so the accountant types it.
 *    The app remembers  invoiceNo -> "bill ref"  for this vendor, and reads it by itself next time.
 */
public class LearningService {
    private final DataStore db;
    private final Classifier classifier;

    /** Starter knowledge, so the classifier can guess before any invoice is approved. */
    static final String[][] STARTER = {
            {"Office Supplies", "copier paper ream ball pen gel pen stapler staples file folder register notebook marker whiteboard duster envelope glue tape"},
            {"IT Hardware", "laptop desktop monitor keyboard mouse printer toner cartridge ssd hard disk ram router switch ups battery cable webcam"},
            {"Software & Cloud", "software licence license subscription cloud hosting server domain renewal antivirus backup storage saas annual plan"},
            {"Telecom & Internet", "broadband internet leased line bandwidth mbps static mobile postpaid sim telephone data plan fibre"},
            {"Travel", "air ticket flight train hotel stay room night cab taxi hire airport travel booking visa fare"},
            {"Food & Catering", "lunch dinner breakfast tea coffee snacks meal buffet catering canteen sweets water bottle refreshment"},
            {"Repairs & Maintenance", "repair service servicing maintenance amc plumbing electrical wiring carpentry painting air conditioner spare labour"},
            {"Professional Services", "audit fees consultancy legal advice gst return filing tax professional retainer accounting advisory"},
            {"Utilities", "electricity water supply cans purifier diesel generator fuel gas cylinder sewage waste collection"},
            {"Rent & Facilities", "office rent lease premises maintenance society parking security housekeeping cleaning facility"},
    };

    public LearningService(DataStore db) {
        this.db = db;
        this.classifier = new Classifier(db.model);
        if (db.model.docs.isEmpty()) starter();
    }

    public Classifier classifier() { return classifier; }

    private void starter() {
        for (String[] s : STARTER) classifier.train(s[1], s[0]);
        db.model.updatedAt = db.nowText();
    }

    /** The words used for the category: vendor name + item descriptions (or the first lines when there is no table). */
    public static String textFor(Invoice inv) {
        StringBuilder sb = new StringBuilder(inv.v("vendorName"));
        for (LineItem it : inv.items) sb.append(' ').append(it.description);
        if (inv.items.isEmpty() && inv.rawText != null) {
            String[] lines = inv.rawText.split("\n");
            for (int i = 0; i < Math.min(lines.length, 40); i++) sb.append(' ').append(lines[i]);
        }
        return sb.toString();
    }

    /** Guess the category. A known vendor's usual category counts for 40%. */
    public void classify(Invoice inv, Vendor v) {
        if (inv.categoryCorrected) return;
        LinkedHashMap<String, Double> p = classifier.predict(textFor(inv));
        if (v != null && !v.defaultCategory.isBlank()) {
            Map<String, Double> blended = new HashMap<>();
            for (Map.Entry<String, Double> e : p.entrySet()) blended.put(e.getKey(), e.getValue() * 0.6);
            blended.merge(v.defaultCategory, 0.4, Double::sum);
            List<Map.Entry<String, Double>> list = new ArrayList<>(blended.entrySet());
            list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            p = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : list) p.put(e.getKey(), Math.round(e.getValue() * 1000) / 1000.0);
        }
        inv.categoryScores = new LinkedHashMap<>();
        int k = 0;
        for (Map.Entry<String, Double> e : p.entrySet()) { if (k++ >= 4) break; inv.categoryScores.put(e.getKey(), e.getValue()); }
        if (p.isEmpty()) { inv.category = ""; inv.categoryConfidence = 0; return; }
        Map.Entry<String, Double> top = p.entrySet().iterator().next();
        inv.category = top.getKey();
        inv.categoryConfidence = top.getValue();
    }

    /** Learn from an approved invoice. Returns what was learned, in words (for the history log). */
    public List<String> learnFromApproval(Invoice inv, Vendor v) {
        List<String> learned = new ArrayList<>();
        classifier.train(textFor(inv), inv.category);
        db.model.updatedAt = db.nowText();
        if (v == null) return learned;
        if (!inv.category.isBlank()) v.defaultCategory = inv.category;
        int fixed = 0;
        for (Map.Entry<String, FieldValue> e : inv.fields.entrySet()) {
            FieldValue f = e.getValue();
            if (!f.corrected) continue;
            fixed++;
            if (e.getKey().equals("vendorName") && !f.original.isBlank() && !f.original.equalsIgnoreCase(v.name)
                    && !v.aliases.contains(f.original) && v.aliases.size() < 10) {
                v.aliases.add(f.original);
                learned.add("another spelling of the vendor name: \"" + f.original + "\"");
                continue;
            }
            String label = labelFor(inv.rawText, e.getKey(), f.value);
            if (label != null && !label.equals(v.labelHints.get(e.getKey()))) {
                v.labelHints.put(e.getKey(), label);
                learned.add("\"" + label + "\" means " + com.cecil.invoice.extract.Validator.label(e.getKey()).toLowerCase(Locale.ROOT));
            }
        }
        v.corrections += fixed;
        return learned;
    }

    private static final Set<String> LABEL_FIELDS = Set.of("invoiceNo", "invoiceDate", "dueDate", "poNumber", "subtotal", "cgst",
            "sgst", "igst", "taxTotal", "total", "paymentTerms");
    private static final Set<String> AMOUNTS = Set.of("subtotal", "cgst", "sgst", "igst", "taxTotal", "total");

    /**
     * Find where a value is printed on the invoice and return the label words in front of it
     * (or above it, for table-style headings). null if it cannot be found.
     */
    public static String labelFor(String rawText, String field, String value) {
        if (!LABEL_FIELDS.contains(field) || value == null || value.isBlank() || rawText == null) return null;
        List<String> lines = LocalExtractor.lines(rawText);
        boolean amount = AMOUNTS.contains(field);
        boolean date = field.equals("invoiceDate") || field.equals("dueDate");
        LocalDate wantDate = date ? safeDate(value) : null;
        double wantAmount = amount ? safeNum(value) : 0;
        // totals are usually near the bottom, so look from the bottom for them
        for (int step = 0; step < lines.size(); step++) {
            int i = amount ? lines.size() - 1 - step : step;
            String line = lines.get(i);
            int col = -1;
            if (amount) {
                for (Money.Found m : Money.findAll(line)) if (Math.abs(m.value() - wantAmount) < 0.005) col = m.start();
            } else if (date) {
                for (Dates.Found d : Dates.findAll(line)) if (d.date().equals(wantDate)) { col = d.start(); break; }
            } else {
                int at = line.toUpperCase(Locale.ROOT).indexOf(value.toUpperCase(Locale.ROOT));
                if (at >= 0) col = at;
            }
            if (col < 0) continue;
            // the words just before the value, in the same column (columns are split by 3+ spaces)
            String before = line.substring(0, col).stripTrailing();
            String[] chunks = before.split("\\s{3,}");
            String seg = chunks.length == 0 ? "" : chunks[chunks.length - 1];
            String label = wordy(seg) ? clean(seg) : null;
            if (label != null) return label;
            // nothing in front: the label may be in the line above, in the same column
            for (int j = i - 1; j >= 0 && j >= i - 2; j--) {
                String up = lines.get(j);
                if (up.isBlank()) continue;
                if (col >= up.length()) break;
                int end = up.indexOf("   ", col);
                String piece = up.substring(Math.max(0, col - 2), end < 0 ? up.length() : end);
                label = clean(piece);
                if (label != null) return label;
                break;
            }
            return null;
        }
        return null;
    }

    /** Mostly letters (a label), not a value from another column. */
    private static boolean wordy(String s) {
        int letters = 0, other = 0;
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c)) letters++;
            else if (Character.isDigit(c)) other++;
        }
        return letters >= 2 && other <= 1;
    }

    /** "  Bill Ref :" -> "bill ref". Only letters, numbers, # and &amp;; at most 4 words. */
    static String clean(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9#& ]", " ").replaceAll("\\s+", " ").strip();
        if (t.isEmpty() || !t.matches(".*[a-z]{2,}.*")) return null;
        String[] w = t.split(" ");
        if (w.length > 4) t = String.join(" ", Arrays.copyOfRange(w, w.length - 4, w.length));
        return t.length() > 30 ? null : t;
    }

    private static LocalDate safeDate(String s) { try { return LocalDate.parse(s); } catch (Exception e) { return null; } }
    private static double safeNum(String s) { try { return Double.parseDouble(s); } catch (Exception e) { return -1; } }

    /** Forget and learn again from the starter words and every approved invoice. */
    public Map<String, Object> retrain() {
        classifier.reset();
        starter();
        int n = 0;
        for (Invoice inv : db.invoices) {
            if ((inv.status == Status.APPROVED || inv.status == Status.EXPORTED) && !inv.category.isBlank()) {
                classifier.train(textFor(inv), inv.category);
                n++;
            }
        }
        db.model.updatedAt = db.nowText();
        return Map.of("invoices", n, "examples", db.model.trainedOn);
    }
}
