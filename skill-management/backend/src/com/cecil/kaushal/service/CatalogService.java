package com.cecil.kaushal.service;

import com.cecil.kaushal.core.SkillGraph;
import com.cecil.kaushal.model.Category;
import com.cecil.kaushal.model.Skill;
import com.cecil.kaushal.model.User;
import com.cecil.kaushal.store.DataStore;
import com.cecil.kaushal.util.Json;
import com.cecil.kaushal.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The skill catalogue (HR only): categories, skills, synonyms and prerequisites.
 * Rules: names, symbols and synonyms are unique (so "JS" can only mean one skill); prerequisites must not loop.
 */
public class CatalogService {
    public static final String[] GROUPS = { "lang", "web", "cloud", "data", "sec", "design", "soft", "method" };
    private final DataStore store;

    public CatalogService(DataStore store) { this.store = store; }

    public Category addCategory(User by, Map<String, Object> b) {
        Access.admin(by);
        synchronized (store) {
            String name = Text.clean(Json.str(b, "name"), 40);
            if (Text.blank(name)) throw Problem.bad("Give the category a name.");
            for (Category c : store.categories.values()) if (Text.key(c.name).equals(Text.key(name))) throw Problem.conflict("There is already a category called " + c.name + ".");
            String group = Json.str(b, "group", "method");
            if (!List.of(GROUPS).contains(group)) throw Problem.bad("Unknown colour group.");
            Category c = new Category();
            c.id = store.nextId("CAT", 1);
            c.name = name;
            c.group = group;
            c.order = store.categories.size() + 1;
            c.halfLife = halfLife(Json.num(b, "halfLife", 18));
            store.categories.put(c.id, c);
            store.log(by.id, "category.add", c.id, name);
            store.save("categories", "audit");
            return c;
        }
    }

    public Skill saveSkill(User by, String id, Map<String, Object> b) {
        Access.admin(by);
        synchronized (store) {
            Skill s = id == null ? new Skill() : store.skills.get(id);
            if (s == null) throw Problem.notFound("Skill");
            String name = Text.clean(Json.str(b, "name", s.name), 40);
            String symbol = Text.clean(Json.str(b, "symbol", s.symbol), 3);
            String cat = Json.str(b, "categoryId", s.categoryId);
            if (Text.blank(name)) throw Problem.bad("Give the skill a name.");
            if (symbol == null || !symbol.matches("[A-Z][A-Za-z0-9#+]{0,2}"))
                throw Problem.bad("The symbol is 1-3 characters and starts with a capital letter, like Jv or K8s.");
            if (cat == null || !store.categories.containsKey(cat)) throw Problem.bad("Pick a category.");
            List<String> syns = new ArrayList<>();
            List<Object> rawSyn = b.containsKey("synonyms") ? Json.list(b, "synonyms") : new ArrayList<>(s.synonyms);
            for (Object o : rawSyn) {
                String t = Text.clean(String.valueOf(o), 40);
                if (!Text.blank(t) && !Text.key(t).equals(Text.key(name)) && syns.stream().noneMatch(x -> Text.key(x).equals(Text.key(t)))) syns.add(t);
            }
            if (syns.size() > 8) throw Problem.bad("At most 8 other names.");
            // unique names: no other skill may use this name / symbol / synonym in any of its names
            List<String> mine = new ArrayList<>(syns);
            mine.add(name);
            mine.add(symbol);
            for (Skill o : store.skills.values()) {
                if (o == s || !o.active) continue;
                List<String> theirs = new ArrayList<>(o.synonyms);
                theirs.add(o.name);
                theirs.add(o.symbol);
                for (String a : mine) for (String t : theirs)
                    if (Text.key(a).equals(Text.key(t))) throw Problem.conflict("'" + a + "' is already used by " + o.name + ".");
            }
            if (id == null) {
                s.id = store.nextId("S", 101);
                int max = 0;
                for (Skill o : store.skills.values()) max = Math.max(max, o.number);
                s.number = max + 1;
                store.skills.put(s.id, s);
            }
            s.name = name;
            s.symbol = symbol;
            s.categoryId = cat;
            s.description = Text.clean(Json.str(b, "description", s.description), 300);
            s.halfLife = b.containsKey("halfLife") ? (Json.num(b, "halfLife", 0) == 0 ? 0 : halfLife(Json.num(b, "halfLife", 0))) : s.halfLife;
            s.synonyms = syns;
            if (b.containsKey("prereqs")) setPrereqs(s, b.get("prereqs"));
            store.log(by.id, id == null ? "skill.add" : "skill.edit", s.id, s.name);
            store.save("skills", "audit");
            return s;
        }
    }

    /** prereqs: {skillId: level}. Each new arrow is checked for loops. */
    private void setPrereqs(Skill s, Object raw) {
        if (!(raw instanceof Map<?, ?> m)) throw Problem.bad("Prerequisites must be a list of skill and level.");
        if (m.size() > 6) throw Problem.bad("At most 6 prerequisites.");
        Map<String, Integer> before = s.prereqs;
        s.prereqs = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String pid = String.valueOf(e.getKey());
            Skill p = store.skills.get(pid);
            if (p == null || !p.active) { s.prereqs = before; throw Problem.bad("Unknown prerequisite."); }
            int lv = e.getValue() instanceof Number n ? n.intValue() : 1;
            if (lv < 1 || lv > 5) { s.prereqs = before; throw Problem.bad("Levels go from 1 to 5."); }
            if (SkillGraph.wouldLoop(store.skills, s.id, pid)) {
                s.prereqs = before;
                throw Problem.conflict(p.name + " already needs " + s.name + " (directly or through other skills) - that would be a loop.");
            }
            s.prereqs.put(pid, lv);
        }
    }

    public Skill retire(User by, String id, boolean active) {
        Access.admin(by);
        synchronized (store) {
            Skill s = store.skills.get(id);
            if (s == null) throw Problem.notFound("Skill");
            if (!active) for (var r : store.roles.values()) for (var q : r.reqs)
                if (q.skillId().equals(id)) throw Problem.conflict("The role " + r.name + " still needs " + s.name + ". Remove it there first.");
            s.active = active;
            store.log(by.id, active ? "skill.restore" : "skill.retire", s.id, s.name);
            store.save("skills", "audit");
            return s;
        }
    }

    private static int halfLife(long v) {
        if (v < 3 || v > 120) throw Problem.bad("The half-life is 3 to 120 months (or 0 = use the category's).");
        return (int) v;
    }
}
