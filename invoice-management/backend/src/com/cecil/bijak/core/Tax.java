package com.cecil.bijak.core;

import com.cecil.bijak.model.Invoice;
import com.cecil.bijak.model.Line;
import com.cecil.bijak.util.Money;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out the GST on an invoice.
 *
 * For every line:
 *     gross    = quantity x rate                         (rounded to the paisa)
 *     discount = gross x discount %
 *     taxable  = gross - discount
 *     same state as the seller  ->  CGST = SGST = taxable x (GST rate / 2)    ("intra-state")
 *     another state             ->  IGST = taxable x GST rate                   ("inter-state")
 *
 * Tax is worked out per line and rounded per line, then added up. The grand total is rounded to the nearest rupee
 * and the difference is shown as "Round off" (at most 50 paise either way), as most Indian invoices do.
 */
public final class Tax {
    public static final List<Integer> RATES = List.of(0, 500, 1200, 1800, 2800);

    private Tax() { }

    /** Is the place of supply in the seller's own state? */
    public static boolean intraState(String sellerState, String placeOfSupply) {
        return sellerState != null && sellerState.equals(placeOfSupply);
    }

    public static void line(Line l, boolean intra) {
        l.gross = Money.div(l.qtyMilli * l.rate, 1000);
        l.discount = Money.pct(l.gross, l.discountBp / 100.0);
        l.taxable = l.gross - l.discount;
        if (intra) {
            l.cgst = Money.div(l.taxable * l.gstBp, 20_000);   // half the rate: bp/2/10000
            l.sgst = l.cgst;
            l.igst = 0;
        } else {
            l.igst = Money.div(l.taxable * l.gstBp, 10_000);
            l.cgst = 0;
            l.sgst = 0;
        }
    }

    /** Fills every line and the totals of the invoice. */
    public static void compute(Invoice inv, String sellerState) {
        boolean intra = intraState(sellerState, inv.placeOfSupply);
        long taxable = 0, cgst = 0, sgst = 0, igst = 0;
        for (Line l : inv.lines) {
            line(l, intra);
            taxable += l.taxable;
            cgst += l.cgst;
            sgst += l.sgst;
            igst += l.igst;
        }
        long exact = taxable + cgst + sgst + igst;
        long rounded = Money.div(exact, 100) * 100;
        inv.taxable = taxable;
        inv.cgst = cgst;
        inv.sgst = sgst;
        inv.igst = igst;
        inv.roundOff = rounded - exact;
        inv.total = rounded;
    }

    /** Tax summary by rate (printed under the lines, and used for the GST report): rate -> {taxable, cgst, sgst, igst}. */
    public static Map<Integer, long[]> byRate(List<Line> lines) {
        Map<Integer, long[]> m = new LinkedHashMap<>();
        for (int r : RATES) m.put(r, new long[4]);
        for (Line l : lines) {
            long[] a = m.computeIfAbsent(l.gstBp, k -> new long[4]);
            a[0] += l.taxable; a[1] += l.cgst; a[2] += l.sgst; a[3] += l.igst;
        }
        m.values().removeIf(a -> a[0] == 0 && a[1] == 0 && a[3] == 0);
        return m;
    }
}
