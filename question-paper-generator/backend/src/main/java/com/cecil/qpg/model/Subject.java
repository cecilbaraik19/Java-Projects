package com.cecil.qpg.model;

import com.cecil.qpg.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A course subject (for example CSIT135 Operating Systems) split into numbered units (chapters). */
public class Subject implements Json.JsonAble {
    public String code;
    public String name;
    public String course;
    public int semester;
    public List<Unit> units = new ArrayList<>();

    public static class Unit {
        public int no;
        public String title;
        public Unit(int no, String title) { this.no = no; this.title = title; }
    }

    public Unit unit(int no) {
        for (Unit u : units) if (u.no == no) return u;
        return null;
    }

    public String unitTitle(int no) {
        Unit u = unit(no);
        return u == null ? "Unit " + no : u.title;
    }

    @Override
    public Object toJson() {
        List<Object> us = new ArrayList<>();
        for (Unit u : units) us.add(Json.map("no", u.no, "title", u.title));
        return Json.map("code", code, "name", name, "course", course, "semester", semester, "units", us);
    }

    @SuppressWarnings("unchecked")
    public static Subject fromJson(Map<String, Object> m) {
        Subject s = new Subject();
        s.code = Json.str(m, "code");
        s.name = Json.str(m, "name");
        s.course = Json.str(m, "course", "");
        s.semester = Json.integer(m, "semester", 1);
        List<Object> us = Json.list(m, "units");
        if (us != null) for (Object o : us) {
            Map<String, Object> u = (Map<String, Object>) o;
            s.units.add(new Unit(Json.integer(u, "no", 1), Json.str(u, "title", "")));
        }
        return s;
    }
}
