package com.cecil.bijak.model;

import com.cecil.bijak.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** "This much of the payment (or credit note) settles that invoice." Amount in paise. */
public class Alloc implements Json.Writable {
    public String invoiceId;
    public long amount;
    public long at;

    public Alloc() { }

    public Alloc(String invoiceId, long amount, long at) { this.invoiceId = invoiceId; this.amount = amount; this.at = at; }

    @Override
    public Map<String, Object> toJson() { return Json.obj("invoiceId", invoiceId, "amount", amount, "at", at); }

    public static Alloc fromJson(Map<String, Object> m) {
        return new Alloc(Json.str(m, "invoiceId"), Json.num(m, "amount", 0), Json.num(m, "at", 0));
    }

    public static List<Alloc> list(List<Object> raw) {
        List<Alloc> out = new ArrayList<>();
        for (Object o : raw) if (o instanceof Map<?, ?> m) out.add(fromJson(Line.cast(m)));
        return out;
    }

    public static long sum(List<Alloc> list) {
        long s = 0;
        for (Alloc a : list) s += a.amount;
        return s;
    }
}
