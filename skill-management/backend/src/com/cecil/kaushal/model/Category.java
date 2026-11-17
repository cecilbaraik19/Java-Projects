package com.cecil.kaushal.model;

import com.cecil.kaushal.util.Json;

import java.util.Map;

/**
 * A group of skills - like a group (column colour) in the periodic table.
 * group = the colour key the website uses (lang, cloud, data, sec, design, soft, method).
 */
public class Category implements Json.Writable {
    public String id;
    public String name;
    public String group;
    public int order;
    /** Default "half-life": after this many months without use, a skill counts one level lower. */
    public int halfLife = 18;

    @Override public Map<String, Object> toJson() {
        return Json.obj("id", id, "name", name, "group", group, "order", order, "halfLife", halfLife);
    }

    public static Category fromJson(Map<String, Object> m) {
        Category c = new Category();
        c.id = Json.str(m, "id");
        c.name = Json.str(m, "name");
        c.group = Json.str(m, "group", "method");
        c.order = (int) Json.num(m, "order", 0);
        c.halfLife = (int) Json.num(m, "halfLife", 18);
        return c;
    }
}
