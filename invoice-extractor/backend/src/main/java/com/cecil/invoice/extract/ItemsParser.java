package com.cecil.invoice.extract;

import com.cecil.invoice.model.LineItem;
import com.cecil.invoice.util.Money;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads the table of items (description, HSN, qty, rate, amount).
 *
 * 1. Find the header row: a line with at least two of the column words
 *    (description / qty / rate / amount / HSN), one of which must describe the item.
 * 2. Read every row below it until a "Sub Total", "Total", "CGST" ... line.
 * 3. In each row the numbers are matched to columns. The parser tries every
 *    choice of three numbers and keeps the one where qty x rate = amount, so a
 *    "GST %" or "unit" column in between does not confuse it.
 * 4. A row with words but no numbers is the second line of the previous description.
 */
public final class ItemsParser {
    private ItemsParser() {}

    public static final class Result {
        public final List<LineItem> items = new ArrayList<>();
        public int headerLine = -1;
        public int lastLine = -1;
    }

    private static final String[][] GROUPS = {
            {"description", "particulars", "item", "items", "product", "service", "services", "details", "goods"},
            {"qty", "quantity", "nos", "units", "hrs", "hours", "pcs"},
            {"rate", "price", "unit price", "unit cost", "cost"},
            {"amount", "total", "value", "line total", "amt"},
            {"hsn", "sac", "hsn/sac", "hsn sac"},
    };

    private static final Pattern STOP = Pattern.compile(
            "(?i)^\\s*(sub\\s*-?\\s*total|total|grand total|taxable|cgst|sgst|igst|utgst|(total\\s+)?tax\\s*(amount|total|@|\\d|:)|gst\\s*(@|\\d|total|amount|:)|amount in words|in words|round|less|discount|net amount|balance|rupees)");
    private static final Pattern RULE = Pattern.compile("^[\\s\\-=_*.|+]{3,}$");
    private static final Pattern WORDY = Pattern.compile("[A-Za-z]{3,}");
    private static final Pattern HSN = Pattern.compile("^\\d{4}(\\d{2}){0,2}$");

    public static Result parse(List<String> lines) {
        Result r = new Result();
        for (int i = 0; i < lines.size(); i++) {
            if (isHeader(lines.get(i))) { r.headerLine = i; break; }
        }
        if (r.headerLine < 0) return r;

        int blanks = 0;
        for (int i = r.headerLine + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) { if (++blanks >= 2 && !r.items.isEmpty()) break; continue; }
            blanks = 0;
            if (RULE.matcher(line).matches()) { if (!r.items.isEmpty()) break; continue; }   // a ruled line closes the table
            if (STOP.matcher(line).find() || STOP.matcher(line.replace('1', 'l').replaceAll("(?i)totai", "total")).find()) break;
            LineItem item = row(line, i);
            if (item != null) { r.items.add(item); r.lastLine = i; }
            else if (!r.items.isEmpty() && WORDY.matcher(line).find() && Money.findAll(line).isEmpty()) {
                LineItem prev = r.items.get(r.items.size() - 1);    // wrapped description
                prev.description = (prev.description + " " + line.strip()).trim();
            }
        }
        return r;
    }

    static boolean isHeader(String line) {
        String l = " " + line.toLowerCase(Locale.ROOT).replaceAll("[^a-z/ ]", " ").replaceAll("\\s+", " ") + " ";
        int groups = 0;
        boolean describes = false;
        for (int g = 0; g < GROUPS.length; g++) {
            for (String w : GROUPS[g]) {
                if (l.contains(" " + w + " ")) { groups++; if (g == 0) describes = true; break; }
            }
        }
        if (Money.findAll(line).size() > 0) return false;     // header rows have no amounts
        return describes && groups >= 2 || groups >= 3;
    }

    private static final Pattern UNIT = Pattern.compile("(?i)(nos?|pcs|hrs?|units?|kg|ltr|x|@|=|-|\\d{1,2}(\\.\\d+)?%)\\.?");

    /** One table row, or null if the line has no description or no amount. */
    public static LineItem row(String line, int index) {
        String s = line.replace('|', ' ');
        // a serial number at the start ("1.", "2)", "3 ") is not part of the description
        s = s.replaceFirst("^\\s*\\d{1,3}(?:[.)]\\s*|\\s{2,}|\\s(?=[A-Za-z(]))", "");
        // the numbers are the block of number-like words at the END of the row;
        // numbers inside the description ("A4", "75 GSM", "Box of 50") stay in the description
        String[] toks = s.strip().split("\\s+");
        int cut = toks.length;
        while (cut > 0) {
            String t = toks[cut - 1];
            if (Money.parse(t) != null || UNIT.matcher(t).matches() || t.matches("(?i)(rs\\.?|inr|\u20B9)")) cut--;
            else break;
        }
        if (cut == toks.length || cut == 0) return null;
        String desc = String.join(" ", Arrays.copyOfRange(toks, 0, cut)).replaceAll("[\\s:\\-]+$", "").strip();
        if (!WORDY.matcher(desc).find()) return null;

        LineItem it = new LineItem();
        it.line = index;
        it.description = desc;
        List<Double> values = new ArrayList<>();
        for (int k = cut; k < toks.length; k++) {
            String t = toks[k];
            Double v = Money.parse(t);
            if (v == null) continue;                                    // a unit word or a % column
            String raw = t.replaceAll("[^0-9.]", "");
            if (it.hsn.isEmpty() && values.isEmpty() && !t.contains(",") && !raw.contains(".") && HSN.matcher(raw).matches()) {
                it.hsn = raw;          // the first plain 4/6/8-digit number right after the description
                continue;
            }
            values.add(v);
        }
        if (values.isEmpty()) return null;
        int n = values.size();
        it.amount = values.get(n - 1);
        if (n == 1) { it.qty = 1; it.rate = it.amount; it.mathOk = true; return it; }
        if (n == 2) {
            double a = values.get(0), b = values.get(1);
            if (Money.near(a * b, b, 0.01) && a == 1) { it.qty = 1; it.rate = b; }
            else if (Money.near(a, b, 0.01)) { it.qty = 1; it.rate = a; }
            else { it.qty = isCount(a) && a < b ? a : 1; it.rate = it.qty == 1 ? b : Money.r2(b / a); }
            it.mathOk = Money.near(it.qty * it.rate, it.amount, 1.0);
            return it;
        }
        // three or more numbers: find qty x rate = amount, preferring the last number as the amount
        for (int k = n - 1; k >= 2; k--) {
            for (int i = 0; i < k; i++) {
                for (int j = i + 1; j < k; j++) {
                    double q = values.get(i), r = values.get(j), a = values.get(k);
                    if (Money.near(q * r, a, Math.max(1.0, a * 0.001))) {
                        it.qty = q; it.rate = r; it.amount = a; it.mathOk = true;
                        return it;
                    }
                    if (Money.near(r * q, a, 1.0) && isCount(r) && !isCount(q)) {
                        it.qty = r; it.rate = q; it.amount = a; it.mathOk = true;
                        return it;
                    }
                }
            }
        }
        it.qty = values.get(n - 3);
        it.rate = values.get(n - 2);
        it.mathOk = false;
        return it;
    }

    private static boolean isCount(double v) { return v > 0 && v < 10000 && v == Math.rint(v * 100) / 100; }
}
