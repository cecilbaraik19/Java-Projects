package com.cecil.bijak.model;

import com.cecil.bijak.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One line of an invoice. Quantities are kept in thousandths (2.5 kg = 2500) so they stay exact.
 * The tax fields are filled by core.Tax - nobody types them.
 */
public class Line implements Json.Writable {
    public String itemId, description, hsn, unit;
    public long qtyMilli;      // 1 piece = 1000
    public long rate;          // paise per unit
    public int discountBp;     // 500 = 5 % off
    public int gstBp;          // 1800 = 18 %
    /** Credit note only: which line of the original invoice this line corrects (0, 1, 2 ...), else -1. */
    public int src = -1;
    // worked out by Tax.compute
    public long gross, discount, taxable, cgst, sgst, igst;

    public Line copy() {
        Line l = new Line();
        l.itemId = itemId; l.description = description; l.hsn = hsn; l.unit = unit;
        l.qtyMilli = qtyMilli; l.rate = rate; l.discountBp = discountBp; l.gstBp = gstBp; l.src = src;
        l.gross = gross; l.discount = discount; l.taxable = taxable; l.cgst = cgst; l.sgst = sgst; l.igst = igst;
        return l;
    }

    public long tax() { return cgst + sgst + igst; }

    public long amount() { return taxable + tax(); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("itemId", itemId, "description", description, "hsn", hsn, "unit", unit, "qtyMilli", qtyMilli,
                "rate", rate, "discountBp", discountBp, "gstBp", gstBp, "src", src, "gross", gross, "discount", discount,
                "taxable", taxable, "cgst", cgst, "sgst", sgst, "igst", igst);
    }

    public static Line fromJson(Map<String, Object> m) {
        Line l = new Line();
        l.itemId = Json.str(m, "itemId");
        l.description = Json.str(m, "description");
        l.hsn = Json.str(m, "hsn");
        l.unit = Json.str(m, "unit");
        l.qtyMilli = Json.num(m, "qtyMilli", 0);
        l.rate = Json.num(m, "rate", 0);
        l.discountBp = (int) Json.num(m, "discountBp", 0);
        l.gstBp = (int) Json.num(m, "gstBp", 0);
        l.src = (int) Json.num(m, "src", -1);
        l.gross = Json.num(m, "gross", 0);
        l.discount = Json.num(m, "discount", 0);
        l.taxable = Json.num(m, "taxable", 0);
        l.cgst = Json.num(m, "cgst", 0);
        l.sgst = Json.num(m, "sgst", 0);
        l.igst = Json.num(m, "igst", 0);
        return l;
    }

    public static List<Line> list(List<Object> raw) {
        List<Line> out = new ArrayList<>();
        for (Object o : raw) if (o instanceof Map<?, ?> m) out.add(fromJson(cast(m)));
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }
}
