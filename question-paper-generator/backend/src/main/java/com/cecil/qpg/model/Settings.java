package com.cecil.qpg.model;

import com.cecil.qpg.util.Json;

import java.util.Map;

/** Things printed on every paper's header, and generator defaults. */
public class Settings implements Json.JsonAble {
    public String institute = "Amity University Jharkhand";
    public String department = "Amity Institute of Information Technology";
    /** Two questions this alike (0..1) or more are treated as near-duplicates. */
    public double duplicateThreshold = 0.6;

    @Override
    public Object toJson() {
        return Json.map("institute", institute, "department", department, "duplicateThreshold", duplicateThreshold);
    }

    public static Settings fromJson(Map<String, Object> m) {
        Settings s = new Settings();
        if (m == null) return s;
        s.institute = Json.str(m, "institute", s.institute);
        s.department = Json.str(m, "department", s.department);
        Object t = m.get("duplicateThreshold");
        if (t instanceof Number n) s.duplicateThreshold = n.doubleValue();
        return s;
    }
}
