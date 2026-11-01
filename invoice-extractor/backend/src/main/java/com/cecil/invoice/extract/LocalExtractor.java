package com.cecil.invoice.extract;

import com.cecil.invoice.model.FieldValue;
import com.cecil.invoice.model.LineItem;
import com.cecil.invoice.model.Settings;
import com.cecil.invoice.model.Vendor;
import com.cecil.invoice.util.Dates;
import com.cecil.invoice.util.Fuzzy;
import com.cecil.invoice.util.Gstin;
import com.cecil.invoice.util.Money;
import com.cecil.invoice.util.Words;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The built-in "AI" that reads an invoice written as plain text. It works with no
 * internet and no outside library, in four steps:
 *
 * 1. CLEAN   - split into lines and repair common scanning mistakes inside numbers (O->0, l->1).
 * 2. FIND    - look for label words ("Invoice No", "Bill Date", "Grand Total" ...). Labels are
 *              matched with fuzzy matching, so "lnvoice N0" still counts. For every label it
 *              takes the value to the right, or in the line below (same column for table headers).
 * 3. SCORE   - every possible value (a "candidate") gets a score from: how strong the label is,
 *              where the value sits, whether it has the right shape (a real date, a real amount),
 *              and whether this vendor printed the same label before (learned from corrections).
 *              The best candidate wins and its score becomes the confidence.
 * 4. AGREE   - the amounts must agree: the subtotal candidate that equals the sum of the items,
 *              and the total that equals subtotal + tax, get a bonus. Missing values are worked
 *              out from the others (e.g. the due date from "Net 30").
 */
public final class LocalExtractor {

    /** What the extractor needs to know about the outside world. */
    public record Context(List<Vendor> vendors, Settings settings, LocalDate today) {}

    /** The extractor's answer. */
    public static final class Result {
        public final Map<String, FieldValue> fields = new LinkedHashMap<>();
        public List<LineItem> items = new ArrayList<>();
        public Vendor vendor;                 // a known vendor, or null for a new one
        public String vendorMatch = "";       // how the vendor was recognised
        public int itemsHeaderLine = -1;
    }

    // ================================================================ labels

    enum Kind { ID, DATE, AMOUNT, TEXT, WORDS }

    record Label(String field, String phrase, double weight, boolean learned) {}

    private static final Map<String, Kind> KIND = new LinkedHashMap<>();
    private static final List<Label> LABELS = new ArrayList<>();

    private static void label(String field, Kind kind, double weight, String... phrases) {
        KIND.put(field, kind);
        for (String p : phrases) LABELS.add(new Label(field, p, weight, false));
    }

    static {
        label("invoiceNo", Kind.ID, 1.0, "invoice no", "invoice number", "invoice #", "invoice num", "inv no", "inv #",
                "tax invoice no", "bill no", "bill number", "invoice id", "invoice ref");
        label("invoiceNo", Kind.ID, 0.7, "document no", "receipt no", "voucher no", "serial no", "inv", "ref no");
        label("invoiceDate", Kind.DATE, 1.0, "invoice date", "inv date", "date of invoice", "bill date", "date of issue", "issue date", "invoice dt");
        label("invoiceDate", Kind.DATE, 0.65, "dated", "date", "dt");
        label("dueDate", Kind.DATE, 1.0, "due date", "payment due", "due on", "pay by", "payable by", "payment due date", "due by");
        label("poNumber", Kind.ID, 1.0, "po no", "p o no", "po number", "purchase order", "purchase order no", "order no", "your order no", "po ref");
        label("subtotal", Kind.AMOUNT, 1.0, "sub total", "subtotal", "taxable value", "taxable amount", "total taxable value",
                "amount before tax", "total before tax", "net value");
        label("cgst", Kind.AMOUNT, 1.0, "cgst", "central gst", "central tax");
        label("sgst", Kind.AMOUNT, 1.0, "sgst", "utgst", "state gst", "state tax");
        label("igst", Kind.AMOUNT, 1.0, "igst", "integrated gst", "integrated tax");
        label("taxTotal", Kind.AMOUNT, 1.0, "total tax", "total gst", "gst total", "tax total", "total tax amount", "gst amount", "tax amount");
        label("total", Kind.AMOUNT, 1.0, "grand total", "total amount", "invoice total", "amount payable", "total payable", "net payable",
                "total amount payable", "balance due", "amount due", "total due", "total invoice value", "invoice value", "total inr");
        label("total", Kind.AMOUNT, 0.55, "total");
        label("amountInWords", Kind.WORDS, 1.0, "amount in words", "in words", "amount chargeable in words", "total in words");
        label("amountInWords", Kind.WORDS, 0.8, "rupees");
        label("paymentTerms", Kind.TEXT, 1.0, "payment terms", "terms of payment", "credit terms");
        label("paymentTerms", Kind.TEXT, 0.6, "terms");
    }

    /** Words that are labels themselves and must never be taken as a value. */
    private static final Set<String> NOT_A_VALUE = Set.of("no", "number", "date", "dt", "no.", "#", "ref", "id");

    private final Context ctx;

    public LocalExtractor(Context ctx) { this.ctx = ctx; }

    // ================================================================ step 1: clean

    /** Split into lines, turn tabs into spaces and fix common misreads inside numbers. */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String l = raw.replace("\t", "    ").replace(' ', ' ').replaceAll("\\s+$", "");
            out.add(fixDigits(l));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) out.remove(out.size() - 1);
        return out;
    }

    /**
     * "1,O8O.OO" -> "1,080.00", "l2,500" -> "12,500". Only whole words that are mostly digits
     * and contain no other letters are changed, so a GSTIN or an invoice number is never touched.
     */
    public static String fixDigits(String line) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\\S+").matcher(line);
        int last = 0;
        while (m.find()) {
            sb.append(line, last, m.start());
            String t = m.group();
            String core = t.replaceAll("[;:)(]+$", "");
            long digits = core.chars().filter(Character::isDigit).count();
            long letters = core.chars().filter(Character::isLetter).count();
            if (letters > 0 && digits >= 2 && digits >= letters * 2 && core.matches("[0-9OolISB,./-]+")) {
                t = core.replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1').replace('S', '5').replace('B', '8') + t.substring(core.length());
            }
            sb.append(t);
            last = m.end();
        }
        sb.append(line.substring(last));
        return sb.toString();
    }

    // ================================================================ step 2: find labels

    /** A label found on a line: which field, where, and how well it matched (0..1). */
    record Hit(Label label, int line, int start, int end, double strength) {}

    private record Tok(String text, int start, int end) {}

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9#&]+");

    private static List<Tok> tokens(String line) {
        List<Tok> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(line);
        while (m.find()) out.add(new Tok(m.group().toLowerCase(Locale.ROOT), m.start(), m.end()));
        return out;
    }

    /** Letters that a scanner often confuses, mapped back for label matching. */
    private static String unOcr(String s) {
        return s.replace('0', 'o').replace('1', 'l').replace('5', 's').replace('|', 'l');
    }

    /** Find every label on every line. Shorter labels inside longer ones are dropped ("total" inside "grand total"). */
    static List<Hit> findLabels(List<String> lines, List<Label> labels) {
        List<Hit> all = new ArrayList<>();
        for (int li = 0; li < lines.size(); li++) {
            List<Tok> toks = tokens(lines.get(li));
            List<Hit> hits = new ArrayList<>();
            for (Label lab : labels) {
                String target = lab.phrase.replace(" ", "");
                int words = lab.phrase.split(" ").length;
                for (int size = Math.max(1, words - 1); size <= words + 1; size++) {
                    for (int s = 0; s + size <= toks.size(); s++) {
                        StringBuilder sb = new StringBuilder();
                        for (int k = s; k < s + size; k++) sb.append(toks.get(k).text);
                        String got = sb.toString();
                        if (got.length() > 40) continue;
                        double sim;
                        if (got.equals(target)) sim = 1.0;
                        else if (target.length() <= 5) continue;                    // short labels must match exactly
                        else {
                            if (!looksLikeLabelWord(got)) continue;
                            sim = Math.max(Fuzzy.similarity(got, target), Fuzzy.similarity(unOcr(got), target));
                            if (sim < 0.84) continue;
                            sim *= 0.92;                                              // a fuzzy match counts a bit less
                        }
                        hits.add(new Hit(lab, li, toks.get(s).start, toks.get(s + size - 1).end, sim));
                    }
                }
            }
            // where labels overlap keep the best one: exact before fuzzy, then the longest ("grand total" beats "total")
            hits.sort((a, b) -> {
                if (a.strength != b.strength) return Double.compare(b.strength, a.strength);
                int la = a.end - a.start, lb = b.end - b.start;
                if (la != lb) return Integer.compare(lb, la);
                return Double.compare(b.label.weight, a.label.weight);
            });
            List<Hit> kept = new ArrayList<>();
            for (Hit h : hits) {
                boolean overlaps = false;
                for (Hit k : kept) if (h.start < k.end && k.start < h.end) { overlaps = true; break; }
                if (!overlaps) kept.add(h);
            }
            kept.sort(Comparator.comparingInt(Hit::start));
            all.addAll(kept);
        }
        return all;
    }

    private static boolean looksLikeLabelWord(String s) {
        int letters = 0;
        for (char c : s.toCharArray()) if (Character.isLetter(c)) letters++;
        return letters >= s.length() - 2;
    }

    // ================================================================ step 3: candidates and scores

    /** A possible value for a field, with its score and where it came from. */
    record Cand(String field, String value, double score, int line, String label, String method) {}

    /** Turn a label hit into candidate values. */
    private void candidates(Hit h, List<Hit> hitsOnLine, List<String> lines, int itemsHeader, Map<String, List<Cand>> out) {
        String field = h.label.field;
        Kind kind = KIND.getOrDefault(field, Kind.TEXT);
        String line = lines.get(h.line);
        if (h.line == itemsHeader && kind == Kind.AMOUNT) return;          // "Total" as a column heading

        // the value on the same line ends where the next label starts
        int stop = line.length();
        if (kind != Kind.WORDS) for (Hit o : hitsOnLine) if (o.start >= h.end && o.start < stop) stop = o.start;
        String rest = line.substring(Math.min(h.end, stop), stop);
        if (kind == Kind.WORDS && h.label.phrase.equals("rupees")) rest = line.substring(h.start, stop);

        double base = 0.55 * h.strength * h.label.weight + (h.label.learned ? 0.14 : 0);
        String labelText = line.substring(h.start, h.end).strip();
        String how = h.label.learned ? "learned label for this vendor" : (h.strength < 1 ? "fuzzy label match" : "label");

        String v = value(kind, rest);
        if (v != null) {
            add(out, new Cand(field, v, base + 0.30 + 0.12, h.line, labelText, how + ", same line"));
            return;
        }
        // nothing to the right: look in the next lines (table-style header row)
        boolean headerRow = hitsOnLine.size() >= 2;
        int found = 0;
        for (int j = h.line + 1; j < lines.size() && j <= h.line + 3 && found < 1; j++) {
            String next = lines.get(j);
            if (next.isBlank()) continue;
            String piece = next;
            double place = 0.18;
            if (headerRow) {
                // take the part of the line under this label's column
                int from = Math.max(0, h.start - 3);
                int to = next.length();
                for (Hit o : hitsOnLine) if (o.start > h.start) { to = Math.min(to, Math.max(from, o.start - 1)); break; }
                if (from >= next.length()) break;
                piece = next.substring(from, Math.min(next.length(), Math.max(to, from)));
                place = 0.27;
            }
            String nv = value(kind, piece);
            if (nv != null) {
                add(out, new Cand(field, nv, base + place + 0.12, j, labelText, how + (headerRow ? ", value below (same column)" : ", value on next line")));
                found++;
            }
            if (!headerRow) break;
        }
    }

    private static void add(Map<String, List<Cand>> out, Cand c) {
        out.computeIfAbsent(c.field, k -> new ArrayList<>()).add(c);
    }

    /** Read a value of the right kind from a piece of text, or null. */
    static String value(Kind kind, String text) {
        String t = text.replaceFirst("^[\\s:.#\\-–=|]+", "").strip();
        if (t.isEmpty()) return null;
        switch (kind) {
            case DATE -> {
                List<Dates.Found> d = Dates.findAll(t);
                if (d.isEmpty() || d.get(0).start() > 6) return null;
                return d.get(0).date().toString();
            }
            case AMOUNT -> {
                List<Money.Found> a = Money.findAll(t);
                if (a.isEmpty()) return null;
                return Money.plain(a.get(a.size() - 1).value());
            }
            case ID -> {
                for (String tok : t.split("\\s+")) {
                    String c = tok.replaceAll("^[:#.\\-]+|[,;:.]+$", "");
                    if (c.isEmpty() || NOT_A_VALUE.contains(c.toLowerCase(Locale.ROOT))) continue;
                    if (!c.matches("[A-Za-z0-9][A-Za-z0-9/_.#-]{0,30}") || !c.matches(".*\\d.*")) return null;
                    if (Dates.parse(c) != null && c.matches("[0-9/.-]+")) return null;
                    if (Gstin.PATTERN.matcher(c.toUpperCase()).matches()) return null;
                    return c;
                }
                return null;
            }
            case WORDS -> {
                String w = t.replaceAll("(?i)^(amount|total)?\\s*(chargeable)?\\s*(in words)?\\s*[:\\-]?\\s*", "").replaceAll("^[(\\[]+|[)\\].]+$", "").strip();
                return Words.parse(w) != null && w.matches("(?i).*[a-z]{3,}.*") ? w : null;
            }
            default -> {
                // payment terms: must say something about time or money ("Net 30", "15 days", "advance")
                t = t.split("(?<=\\w)\\.\\s")[0].replaceAll("[.;,]+$", "").strip();     // first sentence only
                if (t.length() > 80 || !t.matches("(?i).*(\\d|net|days|advance|immediate|receipt|due|credit).*")) return null;
                return t;
            }
        }
    }

    // ================================================================ main

    public Result extract(String text) {
        Result r = new Result();
        List<String> lines = lines(text);
        ItemsParser.Result table = ItemsParser.parse(lines);
        r.itemsHeaderLine = table.headerLine;
        r.items = table.items;

        // ---- GSTINs: whose is whose
        Gstins g = gstins(lines, table.headerLine);
        // ---- vendor name, then try to recognise a known vendor
        FieldValue vendorName = vendorName(lines, table.headerLine);
        r.vendor = knownVendor(g.vendor, vendorName, r);
        if (r.vendor != null) {
            double conf = r.vendorMatch.startsWith("GSTIN") ? 0.97 : 0.9;
            int line = vendorName.empty() ? g.vendorLine : vendorName.line;
            vendorName = new FieldValue(r.vendor.name, conf, line, "", "known vendor (" + r.vendorMatch + ")");
        }
        r.fields.put("vendorName", vendorName);
        r.fields.put("vendorGstin", g.vendor == null ? new FieldValue()
                : new FieldValue(g.vendor, Gstin.valid(g.vendor) ? 0.95 : 0.6, g.vendorLine, "GSTIN", g.vendorWhy));
        r.fields.put("buyerGstin", g.buyer == null ? new FieldValue()
                : new FieldValue(g.buyer, Gstin.valid(g.buyer) ? 0.95 : 0.6, g.buyerLine, "GSTIN", g.buyerWhy));
        r.fields.put("buyerName", buyerName(lines));

        // ---- labelled fields (plus labels this vendor taught us)
        List<Label> labels = new ArrayList<>(LABELS);
        if (r.vendor != null) {
            for (Map.Entry<String, String> e : r.vendor.labelHints.entrySet()) {
                if (KIND.containsKey(e.getKey()) && !e.getValue().isBlank())
                    labels.add(new Label(e.getKey(), e.getValue().toLowerCase(Locale.ROOT), 1.0, true));
            }
        }
        List<Hit> hits = findLabels(lines, labels);
        Map<String, List<Cand>> cands = new HashMap<>();
        for (Hit h : hits) {
            List<Hit> same = new ArrayList<>();
            for (Hit o : hits) if (o.line == h.line) same.add(o);
            candidates(h, same, lines, table.headerLine, cands);
        }
        // totals near the bottom of the page are more likely the real total
        int n = Math.max(1, lines.size());
        for (String f : List.of("total", "taxTotal")) {
            List<Cand> list = cands.get(f);
            if (list == null) continue;
            list.replaceAll(c -> new Cand(c.field, c.value, c.score + 0.08 * c.line / n, c.line, c.label, c.method));
        }

        // ---- step 4: amounts must agree with each other
        double itemsSum = 0;
        for (LineItem it : r.items) itemsSum += it.amount;
        itemsSum = Money.r2(itemsSum);
        pickAmounts(cands, itemsSum, r);

        for (String f : List.of("invoiceNo", "invoiceDate", "dueDate", "poNumber", "amountInWords", "paymentTerms")) {
            Cand best = best(cands.get(f), null);
            r.fields.put(f, best == null ? new FieldValue() : toField(best));
        }
        // an invoice date picked from a plain "Date" label must not be the due date
        if (!r.fields.get("invoiceDate").empty() && r.fields.get("invoiceDate").value.equals(r.fields.get("dueDate").value)) {
            Cand other = best(cands.get("invoiceDate"), c -> !c.value.equals(r.fields.get("dueDate").value));
            r.fields.put("invoiceDate", other == null ? new FieldValue() : toField(other));
        }
        fallbacks(lines, r);
        r.fields.put("currency", currency(lines));

        // keep the fields in the standard order
        Map<String, FieldValue> ordered = new LinkedHashMap<>();
        for (String f : com.cecil.invoice.model.Invoice.FIELDS) ordered.put(f, r.fields.getOrDefault(f, new FieldValue()));
        r.fields.clear();
        r.fields.putAll(ordered);
        return r;
    }

    private static FieldValue toField(Cand c) {
        return new FieldValue(c.value, Math.min(0.99, Math.max(0.05, c.score)), c.line, c.label, c.method);
    }

    private static Cand best(List<Cand> list, java.util.function.Predicate<Cand> ok) {
        if (list == null) return null;
        Cand best = null;
        for (Cand c : list) {
            if (ok != null && !ok.test(c)) continue;
            if (best == null || c.score > best.score) best = c;
        }
        return best;
    }

    /** Pick subtotal, taxes and total so that they agree with each other and with the items. */
    private void pickAmounts(Map<String, List<Cand>> cands, double itemsSum, Result r) {
        double tol = ctx.settings() == null ? 1.0 : ctx.settings().amountTolerance;
        Cand sub = best(cands.get("subtotal"), null);
        if (itemsSum > 0 && cands.get("subtotal") != null) {
            for (Cand c : cands.get("subtotal")) {
                double s = c.score + (Money.near(Double.parseDouble(c.value), itemsSum, tol) ? 0.2 : 0);
                if (sub == null || s > sub.score) sub = new Cand(c.field, c.value, Math.min(0.99, s), c.line, c.label, c.method);
            }
        }
        Cand cgst = best(cands.get("cgst"), null), sgst = best(cands.get("sgst"), null), igst = best(cands.get("igst"), null);
        Cand tax = best(cands.get("taxTotal"), null);
        double taxes = tax != null ? Double.parseDouble(tax.value)
                : (cgst == null ? 0 : Double.parseDouble(cgst.value)) + (sgst == null ? 0 : Double.parseDouble(sgst.value))
                + (igst == null ? 0 : Double.parseDouble(igst.value));
        double subV = sub != null ? Double.parseDouble(sub.value) : itemsSum;

        Cand total = null;
        if (cands.get("total") != null) {
            double expect = Money.r2(subV + taxes);
            for (Cand c : cands.get("total")) {
                double v = Double.parseDouble(c.value);
                double s = c.score;
                if (expect > 0 && Money.near(v, expect, tol)) s += 0.2;
                else if (expect > 0 && Math.abs(v - Math.round(expect)) <= 1.0) s += 0.15;   // rounded off
                else if (expect > 0 && v < subV) s -= 0.2;
                if (total == null || s > total.score) total = new Cand(c.field, c.value, Math.min(0.99, s), c.line, c.label, c.method);
            }
        }
        r.fields.put("subtotal", sub == null ? new FieldValue() : toField(sub));
        r.fields.put("cgst", cgst == null ? new FieldValue() : toField(cgst));
        r.fields.put("sgst", sgst == null ? new FieldValue() : toField(sgst));
        r.fields.put("igst", igst == null ? new FieldValue() : toField(igst));
        r.fields.put("taxTotal", tax == null ? new FieldValue() : toField(tax));
        r.fields.put("total", total == null ? new FieldValue() : toField(total));
    }

    /** Work out missing values from the ones that were found. */
    private void fallbacks(List<String> lines, Result r) {
        Map<String, FieldValue> f = r.fields;
        double itemsSum = 0;
        for (LineItem it : r.items) itemsSum += it.amount;
        itemsSum = Money.r2(itemsSum);

        if (f.get("subtotal").empty() && itemsSum > 0)
            f.put("subtotal", new FieldValue(Money.plain(itemsSum), 0.62, -1, "", "added up the line items"));
        if (f.get("taxTotal").empty()) {
            double t = num(f, "cgst") + num(f, "sgst") + num(f, "igst");
            if (t > 0) {
                double c = Math.min(conf(f, "cgst", "igst"), 0.9);
                f.put("taxTotal", new FieldValue(Money.plain(t), Math.max(0.5, c - 0.05), -1, "", "added up CGST + SGST + IGST"));
            }
        }
        if (f.get("total").empty()) {
            // the biggest amount in the lower half of the page
            double biggest = 0; int at = -1;
            for (int i = lines.size() / 2; i < lines.size(); i++)
                for (Money.Found m : Money.findAll(lines.get(i))) if (m.value() > biggest) { biggest = m.value(); at = i; }
            double expect = num(f, "subtotal") + num(f, "taxTotal");
            if (expect > 0 && (biggest == 0 || !Money.near(biggest, expect, 1))) {
                f.put("total", new FieldValue(Money.plain(expect), 0.55, -1, "", "subtotal + tax"));
            } else if (biggest > 0) {
                f.put("total", new FieldValue(Money.plain(biggest), 0.45, at, "", "largest amount near the bottom"));
            }
        }
        if (f.get("subtotal").empty() && !f.get("total").empty() && num(f, "taxTotal") > 0)
            f.put("subtotal", new FieldValue(Money.plain(num(f, "total") - num(f, "taxTotal")), 0.5, -1, "", "total - tax"));

        // payment terms written anywhere ("Net 30", "within 15 days")
        Pattern net = Pattern.compile("(?i)\\b(net\\s*(\\d{1,3})|(?:within|in)\\s+(\\d{1,3})\\s+days)");
        if (f.get("paymentTerms").empty()) {
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = net.matcher(lines.get(i));
                if (m.find()) { f.put("paymentTerms", new FieldValue(m.group(1), 0.7, i, "", "pattern 'Net N' / 'within N days'")); break; }
            }
        }
        if (f.get("dueDate").empty() && !f.get("invoiceDate").empty() && !f.get("paymentTerms").empty()) {
            Matcher m = Pattern.compile("(\\d{1,3})").matcher(f.get("paymentTerms").value);
            if (m.find()) {
                int days = Integer.parseInt(m.group(1));
                LocalDate due = LocalDate.parse(f.get("invoiceDate").value).plusDays(days);
                f.put("dueDate", new FieldValue(due.toString(), 0.6, f.get("paymentTerms").line, "", "invoice date + " + days + " days (payment terms)"));
            }
        }
        // a lone date in the top part is probably the invoice date
        if (f.get("invoiceDate").empty()) {
            for (int i = 0; i < Math.min(lines.size(), 15); i++) {
                List<Dates.Found> d = Dates.findAll(lines.get(i));
                if (!d.isEmpty()) { f.put("invoiceDate", new FieldValue(d.get(0).date().toString(), 0.4, i, "", "first date on the page")); break; }
            }
        }
    }

    private static double num(Map<String, FieldValue> f, String k) {
        FieldValue v = f.get(k);
        if (v == null || v.empty()) return 0;
        try { return Double.parseDouble(v.value); } catch (NumberFormatException e) { return 0; }
    }

    private static double conf(Map<String, FieldValue> f, String... keys) {
        double best = 0;
        for (String k : keys) if (f.get(k) != null) best = Math.max(best, f.get(k).confidence);
        return best;
    }

    // ================================================================ GSTIN, names, currency

    private static final Pattern BUYER_WORDS = Pattern.compile("(?i)\\b(bill(ed)?\\s*to|buyer|customer|client|recipient|sold\\s*to|invoice\\s*to|ship(ped)?\\s*to|consignee)\\b");
    private static final Pattern SELLER_WORDS = Pattern.compile("(?i)\\b(seller|supplier|vendor|our\\s+gstin|from)\\b");

    static final class Gstins {
        String vendor, buyer, vendorWhy = "", buyerWhy = "";
        int vendorLine = -1, buyerLine = -1;
    }

    private Gstins gstins(List<String> lines, int itemsHeader) {
        Gstins g = new Gstins();
        String ours = ctx.settings() == null ? "" : ctx.settings().companyGstin;
        int buyerFrom = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (BUYER_WORDS.matcher(line).find() && buyerFrom < 0) buyerFrom = i;
            for (Gstin.Found f : Gstin.findAll(line)) {
                String why;
                boolean buyer;
                String before = line.substring(0, f.start()).toLowerCase(Locale.ROOT);
                if (ours != null && !ours.isBlank() && f.gstin().equals(ours)) { buyer = true; why = "matches our company's GSTIN"; }
                else if (BUYER_WORDS.matcher(before).find()) { buyer = true; why = "labelled as the buyer's"; }
                else if (SELLER_WORDS.matcher(before).find()) { buyer = false; why = "labelled as the seller's"; }
                else if (buyerFrom >= 0 && i >= buyerFrom && i <= buyerFrom + 6 && (itemsHeader < 0 || i < itemsHeader)) { buyer = true; why = "in the 'Bill To' block"; }
                else { buyer = false; why = "in the seller's heading"; }
                if (buyer && g.buyer == null) { g.buyer = f.gstin(); g.buyerLine = i; g.buyerWhy = why; }
                else if (!buyer && g.vendor == null) { g.vendor = f.gstin(); g.vendorLine = i; g.vendorWhy = why; }
            }
        }
        return g;
    }

    private static final Pattern COMPANY = Pattern.compile(
            "(?i)\\b(pvt|private|ltd|limited|llp|enterprises?|traders?|solutions?|services|agency|agencies|corporation|industries|"
                    + "technologies|tech|systems|stores?|mart|travels?|associates|consultants?|caterers?|properties|computers|infotech|"
                    + "broadband|networks?|& co|and co|stationers?|stationery|suppliers?|works|hotels?)\\b");
    private static final Pattern NOT_NAME = Pattern.compile(
            "(?i)(tax\\s*invoice|^\\s*invoice\\s*$|\\bbill of supply\\b|\\b(receipt|original|duplicate|copy|gstin|uin|phone|ph|mob|mobile|tel|email|e-mail|pin|road|street|nagar|floor|sector|lane|address|date|cin|pan|state code|chowk|market)\\b|@|www\\.|https?:|invoice\\s*no)");

    /** The seller's name: a company-like line near the top, or the line after "From:" / "Seller:". */
    private FieldValue vendorName(List<String> lines, int itemsHeader) {
        Pattern from = Pattern.compile("(?i)^\\s*(from|seller|supplier|sold by|vendor)\\s*[:\\-]\\s*(.*)$");
        for (int i = 0; i < Math.min(lines.size(), 25); i++) {
            Matcher m = from.matcher(lines.get(i));
            if (m.find()) {
                String v = m.group(2).strip();
                if (v.isEmpty() && i + 1 < lines.size()) { v = lines.get(i + 1).strip(); i++; }
                v = cleanName(v);
                if (!v.isEmpty()) return new FieldValue(v, 0.85, i, m.group(1), "label");
            }
        }
        double bestScore = 0; int bestLine = -1; String bestText = "";
        int limit = Math.min(lines.size(), itemsHeader > 0 ? Math.min(itemsHeader, 12) : 12);
        for (int i = 0; i < limit; i++) {
            String raw = lines.get(i).strip();
            if (BUYER_WORDS.matcher(raw).find()) break;           // everything after "Bill To" is the buyer
            String t = cleanName(raw.split("\\s{3,}")[0]);
            if (t.length() < 3 || t.length() > 60 || NOT_NAME.matcher(t).find()) continue;
            long letters = t.chars().filter(Character::isLetter).count();
            if (letters < t.length() * 0.6) continue;
            double s = 0.3 + 0.25 * (1.0 - (double) i / limit);
            if (COMPANY.matcher(t).find()) s += 0.35;
            if (t.equals(t.toUpperCase()) && letters > 4) s += 0.05;
            if (s > bestScore) { bestScore = s; bestLine = i; bestText = t; }
        }
        if (bestLine < 0) return new FieldValue();
        return new FieldValue(bestText, Math.min(0.88, bestScore), bestLine, "", "company-like line near the top");
    }

    private static String cleanName(String s) {
        return s.replaceAll("(?i)^m/s\\.?\\s*", "").replaceAll("[,;:]+$", "").replaceAll("\\s{2,}", " ").strip();
    }

    private FieldValue buyerName(List<String> lines) {
        Pattern p = Pattern.compile("(?i)^\\s*(bill(?:ed)?\\s*to|buyer|customer|client|sold\\s*to|invoice\\s*to|ship(?:ped)?\\s*to|to)\\s*(?:\\(.*?\\))?\\s*[:\\-]?\\s*(.*)$");
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = p.matcher(lines.get(i));
            if (!m.find()) continue;
            if (m.group(1).equalsIgnoreCase("to") && !lines.get(i).contains(":")) continue;
            String rest = m.group(2).split("\\s{3,}")[0].strip();
            int at = i;
            if (rest.isEmpty() || !rest.matches(".*[A-Za-z]{3,}.*")) {
                for (int j = i + 1; j < Math.min(lines.size(), i + 3); j++) {
                    if (!lines.get(j).isBlank()) { rest = lines.get(j).strip().split("\\s{3,}")[0]; at = j; break; }
                }
            }
            rest = cleanName(rest);
            if (rest.isEmpty() || Gstin.PATTERN.matcher(rest.toUpperCase()).find()) continue;
            double conf = 0.82;
            String how = "line after '" + m.group(1).strip() + "'";
            if (ctx.settings() != null && Fuzzy.sameCompany(rest, ctx.settings().companyName) >= 0.85) { conf = 0.96; how += ", matches our company name"; }
            return new FieldValue(rest, conf, at, m.group(1).strip(), how);
        }
        return new FieldValue();
    }

    private Vendor knownVendor(String gstin, FieldValue name, Result r) {
        if (ctx.vendors() == null) return null;
        if (gstin != null) {
            for (Vendor v : ctx.vendors()) if (gstin.equals(v.gstin)) { r.vendorMatch = "GSTIN"; return v; }
        }
        if (name != null && !name.empty()) {
            Vendor best = null; double bestSim = 0;
            for (Vendor v : ctx.vendors()) {
                double s = Fuzzy.sameCompany(name.value, v.name);
                for (String a : v.aliases) s = Math.max(s, Fuzzy.sameCompany(name.value, a));
                if (s > bestSim) { bestSim = s; best = v; }
            }
            if (best != null && bestSim >= 0.85) {
                // a different GSTIN with the same name is a different branch or a mistake - still match by name, but say so
                r.vendorMatch = "name " + Math.round(bestSim * 100) + "% alike";
                return best;
            }
        }
        return null;
    }

    private static FieldValue currency(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.contains("₹") || l.matches("(?i).*\\b(rs\\.?|inr|rupees)\\b.*")) return new FieldValue("INR", 0.95, i, "", "rupee sign / Rs. / INR");
            if (l.contains("$") || l.matches("(?i).*\\busd\\b.*")) return new FieldValue("USD", 0.9, i, "", "dollar sign / USD");
            if (l.contains("€") || l.matches("(?i).*\\beur\\b.*")) return new FieldValue("EUR", 0.9, i, "", "euro sign / EUR");
        }
        return new FieldValue("INR", 0.55, -1, "", "assumed (no currency sign on the invoice)");
    }
}
