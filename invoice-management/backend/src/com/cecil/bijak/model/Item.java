package com.cecil.bijak.model;

import com.cecil.bijak.util.Json;

import java.util.Map;

/** Something the company sells: a product (HSN code) or a service (SAC code), with its usual rate and GST rate. */
public class Item implements Json.Writable {
    public String id, name, hsn, unit;
    /** Rate per unit in paise, before GST. */
    public long rate;
    /** GST rate in basis points: 1800 = 18 %. One of 0, 500, 1200, 1800, 2800. */
    public int gstBp;
    public boolean service, active = true;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "name", name, "hsn", hsn, "unit", unit, "rate", rate, "gstBp", gstBp,
                "service", service, "active", active);
    }

    public static Item fromJson(Map<String, Object> m) {
        Item i = new Item();
        i.id = Json.str(m, "id");
        i.name = Json.str(m, "name");
        i.hsn = Json.str(m, "hsn");
        i.unit = Json.str(m, "unit");
        i.rate = Json.num(m, "rate", 0);
        i.gstBp = (int) Json.num(m, "gstBp", 1800);
        i.service = Json.bool(m, "service", false);
        i.active = Json.bool(m, "active", true);
        return i;
    }
}
