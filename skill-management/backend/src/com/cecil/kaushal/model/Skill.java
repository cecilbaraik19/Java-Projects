package com.cecil.kaushal.model;

import com.cecil.kaushal.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One skill in the catalogue, drawn as an "element" tile: number, symbol (e.g. "Jv"), name.
 * prereqs: skills you should have first, e.g. Kubernetes needs Docker at level 2. These form a graph that must have
 * no loops (checked by SkillGraph).
 */
public class Skill implements Json.Writable {
    public String id;
    public int number;               // the "atomic number" shown on the tile
    public String symbol;            // 1-3 characters, unique
    public String name;              // unique
    public String categoryId;
    public String description;
    public int halfLife;             // months; 0 = use the category's value
    public List<String> synonyms = new ArrayList<>();
    /** skillId -> level needed first. */
    public Map<String, Integer> prereqs = new LinkedHashMap<>();
    public boolean active = true;

    @Override public Map<String, Object> toJson() {
        return Json.obj("id", id, "number", number, "symbol", symbol, "name", name, "categoryId", categoryId,
                "description", description, "halfLife", halfLife, "synonyms", synonyms, "prereqs", prereqs, "active", active);
    }

    @SuppressWarnings("unchecked")
    public static Skill fromJson(Map<String, Object> m) {
        Skill s = new Skill();
        s.id = Json.str(m, "id");
        s.number = (int) Json.num(m, "number", 0);
        s.symbol = Json.str(m, "symbol");
        s.name = Json.str(m, "name");
        s.categoryId = Json.str(m, "categoryId");
        s.description = Json.str(m, "description", "");
        s.halfLife = (int) Json.num(m, "halfLife", 0);
        s.synonyms = Json.strings(m, "synonyms");
        Object p = m.get("prereqs");
        if (p instanceof Map<?, ?> pm) for (Map.Entry<?, ?> e : pm.entrySet())
            s.prereqs.put(String.valueOf(e.getKey()), ((Number) e.getValue()).intValue());
        s.active = Json.bool(m, "active", true);
        return s;
    }
}
