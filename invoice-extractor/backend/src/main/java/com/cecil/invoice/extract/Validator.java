package com.cecil.invoice.extract;

import com.cecil.invoice.model.*;
import com.cecil.invoice.util.Fuzzy;
import com.cecil.invoice.util.Gstin;
import com.cecil.invoice.util.Money;
import com.cecil.invoice.util.Words;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Checks that what was read makes sense. An extractor can misread a number, but
 * invoices have built-in "cross checks", so many mistakes can be caught:
 *   - the items must add up to the subtotal, and subtotal + tax to the total
 *   - qty x rate must equal each line's amount
 *   - a GSTIN has a check character
 *   - inside one state GST is split into CGST + SGST; between states it is IGST
 *   - GST rates are 5, 12, 18 or 28%
 *   - the amount in words must say the same as the total
 *   - the same bill must not be entered twice
 * Each rule gives PASS, WARN or FAIL. The overall confidence is the average field
 * confidence, lowered for every WARN and FAIL.
 */
public final class Validator {
    private Validator() {}

    public static final String PASS = "PASS", WARN = "WARN", FAIL = "FAIL";

    /** Key fields and how much each one counts in the overall confidence. */
    private static final Map<String, Double> WEIGHTS = new LinkedHashMap<>();
    static {
        WEIGHTS.put("invoiceNo", 2.0);
        WEIGHTS.put("total", 2.0);
        WEIGHTS.put("invoiceDate", 1.5);
        WEIGHTS.put("vendorName", 1.5);
        WEIGHTS.put("subtotal", 1.0);
        WEIGHTS.put("taxTotal", 1.0);
        WEIGHTS.put("vendorGstin", 1.0);
    }

    public static List<Check> run(Invoice inv, List<Invoice> others, Settings s, LocalDate today) {
        List<Check> out = new ArrayList<>();
        double tol = s.amountTolerance;
        inv.duplicateOf = "";

        // 1. required fields
        List<String> missing = new ArrayList<>();
        for (String f : List.of("vendorName", "invoiceNo", "invoiceDate", "total")) if (inv.f(f).empty()) missing.add(label(f));
        boolean hardMissing = inv.f("invoiceNo").empty() || inv.f("total").empty();
        out.add(new Check("REQUIRED", "Key fields found", missing.isEmpty() ? PASS : hardMissing ? FAIL : WARN,
                missing.isEmpty() ? "Vendor, invoice number, date and total were all found." : "Not found: " + String.join(", ", missing) + "."));

        double sub = inv.num("subtotal"), tax = inv.num("taxTotal"), total = inv.num("total");
        double cg = inv.num("cgst"), sg = inv.num("sgst"), ig = inv.num("igst");
        if (tax == 0) tax = cg + sg + ig;

        // 2. items add up to the subtotal
        if (!inv.items.isEmpty()) {
            double sum = 0;
            for (LineItem it : inv.items) sum += it.amount;
            sum = Money.r2(sum);
            if (sub == 0) out.add(new Check("ITEMS_SUM", "Items add up to the subtotal", WARN, "No subtotal to compare with. Items add up to " + Money.format(sum) + "."));
            else out.add(Money.near(sum, sub, tol)
                    ? new Check("ITEMS_SUM", "Items add up to the subtotal", PASS, inv.items.size() + " items add up to " + Money.format(sum) + ".")
                    : new Check("ITEMS_SUM", "Items add up to the subtotal", FAIL, "Items add up to " + Money.format(sum) + " but the subtotal says " + Money.format(sub)
                    + " (difference " + Money.format(Math.abs(sum - sub)) + "). An item or the subtotal may be misread."));
            List<String> bad = new ArrayList<>();
            for (int i = 0; i < inv.items.size(); i++) {
                LineItem it = inv.items.get(i);
                if (!Money.near(it.qty * it.rate, it.amount, Math.max(1.0, it.amount * 0.001))) bad.add("row " + (i + 1));
            }
            out.add(bad.isEmpty() ? new Check("ITEM_MATH", "Qty x rate = amount on every row", PASS, "Every row's maths is right.")
                    : new Check("ITEM_MATH", "Qty x rate = amount on every row", WARN, "Does not add up on " + String.join(", ", bad) + "."));
        }

        // 3. subtotal + tax = total (a round-off of up to one rupee is normal)
        if (sub > 0 && total > 0) {
            double expect = Money.r2(sub + tax);
            double diff = Math.abs(expect - total);
            if (diff <= tol) out.add(new Check("TOTAL_MATH", "Subtotal + tax = total", PASS, Money.format(sub) + " + " + Money.format(tax) + " = " + Money.format(total) + "."));
            else if (Math.abs(Math.round(expect) - total) < 0.01) out.add(new Check("TOTAL_MATH", "Subtotal + tax = total", PASS, "Matches after rounding off to " + Money.format(total) + "."));
            else out.add(new Check("TOTAL_MATH", "Subtotal + tax = total", FAIL, Money.format(sub) + " + " + Money.format(tax) + " = " + Money.format(expect)
                    + ", but the total says " + Money.format(total) + "."));
        }

        // 4. GSTINs
        String vg = inv.v("vendorGstin"), bg = inv.v("buyerGstin");
        if (vg.isEmpty()) out.add(new Check("GSTIN_VENDOR", "Vendor GSTIN is valid", WARN, "No vendor GSTIN found. A GST invoice must show it to claim input tax credit."));
        else out.add(Gstin.valid(vg) ? new Check("GSTIN_VENDOR", "Vendor GSTIN is valid", PASS, vg + " - " + Gstin.state(vg) + ", check character correct.")
                : new Check("GSTIN_VENDOR", "Vendor GSTIN is valid", FAIL, vg + " has the wrong shape or check character. It may be misread or fake."));
        if (!bg.isEmpty()) {
            if (!Gstin.valid(bg)) out.add(new Check("GSTIN_BUYER", "Billed to our GSTIN", FAIL, bg + " has the wrong shape or check character."));
            else if (s.companyGstin != null && !s.companyGstin.isBlank() && !bg.equals(s.companyGstin))
                out.add(new Check("GSTIN_BUYER", "Billed to our GSTIN", WARN, "Billed to " + bg + ", not our GSTIN " + s.companyGstin + "."));
            else out.add(new Check("GSTIN_BUYER", "Billed to our GSTIN", PASS, bg + " is our GSTIN."));
        }

        // 5. CGST+SGST inside a state, IGST between states
        String from = vg.length() >= 2 ? vg.substring(0, 2) : "";
        String to = bg.length() >= 2 ? bg.substring(0, 2) : (s.companyGstin != null && s.companyGstin.length() >= 2 ? s.companyGstin.substring(0, 2) : "");
        if (!from.isEmpty() && !to.isEmpty() && tax > 0) {
            boolean same = from.equals(to);
            String where = same ? "Same state (" + Gstin.state(vg) + ")" : Gstin.state(vg) + " to " + Gstin.state(to + "X");
            if (same && ig > 0) out.add(new Check("GST_TYPE", "Right kind of GST", FAIL, where + ": should charge CGST + SGST, but IGST was charged."));
            else if (!same && (cg > 0 || sg > 0)) out.add(new Check("GST_TYPE", "Right kind of GST", FAIL, where + ": should charge IGST, but CGST/SGST was charged."));
            else if (same && !Money.near(cg, sg, tol)) out.add(new Check("GST_TYPE", "Right kind of GST", WARN, "CGST (" + Money.format(cg) + ") and SGST (" + Money.format(sg) + ") should be equal."));
            else out.add(new Check("GST_TYPE", "Right kind of GST", PASS, where + (same ? ": CGST + SGST charged." : ": IGST charged.")));
        }

        // 6. GST rate
        if (sub > 0 && tax > 0) {
            double rate = tax / sub * 100;
            boolean standard = false;
            for (int r : new int[] {5, 12, 18, 28, 3}) if (Math.abs(rate - r) < 0.15) standard = true;
            out.add(standard ? new Check("GST_RATE", "Standard GST rate", PASS, "Tax is " + Math.round(rate * 10) / 10.0 + "% of the subtotal.")
                    : new Check("GST_RATE", "Standard GST rate", WARN, "Tax is " + Math.round(rate * 10) / 10.0 + "% of the subtotal - not 5, 12, 18 or 28%. Mixed rates, or a misread tax amount."));
        }

        // 7. dates
        LocalDate d = date(inv.v("invoiceDate")), due = date(inv.v("dueDate"));
        if (d != null) {
            String detail = "";
            String res = PASS;
            if (d.isAfter(today)) { res = FAIL; detail = "Invoice date is in the future. "; }
            else if (ChronoUnit.DAYS.between(d, today) > 540) { res = WARN; detail = "Invoice is more than 18 months old. "; }
            if (due != null && due.isBefore(d)) { res = FAIL; detail += "Due date is before the invoice date. "; }
            out.add(new Check("DATES", "Dates make sense", res, detail.isEmpty() ? "Invoice date and due date are in order." : detail.strip()));
        }

        // 8. amount in words
        String words = inv.v("amountInWords");
        if (!words.isEmpty() && total > 0) {
            Double w = Words.parse(words);
            out.add(w != null && Money.near(w, total, 1.0)
                    ? new Check("WORDS", "Amount in words = total", PASS, "Words say " + Money.format(w) + ".")
                    : new Check("WORDS", "Amount in words = total", FAIL, "Words say " + (w == null ? "?" : Money.format(w)) + " but the total is " + Money.format(total) + "."));
        }

        // 9. duplicates
        String no = normal(inv.v("invoiceNo"));
        Invoice dup = null; boolean exact = false;
        for (Invoice o : others) {
            if (o.id.equals(inv.id) || o.status == Status.REJECTED) continue;
            boolean sameVendor = !inv.vendorId.isEmpty() && inv.vendorId.equals(o.vendorId)
                    || !vg.isEmpty() && vg.equals(o.v("vendorGstin"))
                    || Fuzzy.sameCompany(inv.v("vendorName"), o.v("vendorName")) >= 0.9;
            if (!sameVendor) continue;
            if (!no.isEmpty() && no.equals(normal(o.v("invoiceNo")))) { dup = o; exact = true; break; }
            LocalDate od = date(o.v("invoiceDate"));
            if (total > 0 && Money.near(total, o.num("total"), 0.01) && d != null && od != null
                    && Math.abs(ChronoUnit.DAYS.between(d, od)) <= s.duplicateDays && dup == null) dup = o;
        }
        if (dup != null) {
            inv.duplicateOf = dup.id;
            out.add(exact ? new Check("DUPLICATE", "Not a duplicate", FAIL, "Same vendor and invoice number as " + dup.id + " (" + dup.status.label + "). Paying it twice must be avoided.")
                    : new Check("DUPLICATE", "Not a duplicate", WARN, "Same vendor and same total (" + Money.format(total) + ") as " + dup.id + " within " + s.duplicateDays + " days. Check it is not the same bill."));
        } else out.add(new Check("DUPLICATE", "Not a duplicate", PASS, "No other invoice from this vendor with this number."));

        inv.checks = out;
        inv.confidence = overall(inv);
        return out;
    }

    /** Weighted average of the key field confidences, lowered by each WARN (-4%) and FAIL (-15%). */
    public static double overall(Invoice inv) {
        double sum = 0, weights = 0;
        for (Map.Entry<String, Double> e : WEIGHTS.entrySet()) {
            FieldValue f = inv.f(e.getKey());
            double c = f.corrected ? 1.0 : (f.empty() ? 0 : f.confidence);
            sum += c * e.getValue();
            weights += e.getValue();
        }
        double conf = weights == 0 ? 0 : sum / weights;
        for (Check c : inv.checks) {
            if (FAIL.equals(c.result)) conf -= 0.15;
            else if (WARN.equals(c.result)) conf -= 0.04;
        }
        return Math.round(Math.max(0.05, Math.min(0.99, conf)) * 1000) / 1000.0;
    }

    public static int count(List<Check> checks, String result) {
        int n = 0;
        for (Check c : checks) if (result.equals(c.result)) n++;
        return n;
    }

    private static String normal(String s) { return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""); }

    private static LocalDate date(String s) {
        try { return s == null || s.isBlank() ? null : LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    public static String label(String field) {
        return switch (field) {
            case "vendorName" -> "Vendor name";
            case "vendorGstin" -> "Vendor GSTIN";
            case "invoiceNo" -> "Invoice number";
            case "invoiceDate" -> "Invoice date";
            case "dueDate" -> "Due date";
            case "poNumber" -> "PO number";
            case "buyerName" -> "Billed to";
            case "buyerGstin" -> "Buyer GSTIN";
            case "currency" -> "Currency";
            case "subtotal" -> "Subtotal (taxable value)";
            case "cgst" -> "CGST";
            case "sgst" -> "SGST";
            case "igst" -> "IGST";
            case "taxTotal" -> "Total tax";
            case "total" -> "Grand total";
            case "amountInWords" -> "Amount in words";
            case "paymentTerms" -> "Payment terms";
            default -> field;
        };
    }
}
