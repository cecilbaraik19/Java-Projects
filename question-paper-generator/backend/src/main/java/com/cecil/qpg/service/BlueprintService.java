package com.cecil.qpg.service;

import com.cecil.qpg.generator.PaperGenerator;
import com.cecil.qpg.model.Blueprint;
import com.cecil.qpg.model.Enums;
import com.cecil.qpg.model.Enums.Difficulty;
import com.cecil.qpg.model.Enums.QType;
import com.cecil.qpg.model.Enums.Role;
import com.cecil.qpg.model.Paper;
import com.cecil.qpg.model.Subject;
import com.cecil.qpg.model.User;
import com.cecil.qpg.store.DataStore;
import com.cecil.qpg.util.Json;
import com.cecil.qpg.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Blueprints (paper patterns): add, edit, copy, delete and check them against the bank. */
public class BlueprintService {
    private final DataStore store;

    public BlueprintService(DataStore store) { this.store = store; }

    public List<Object> list(String subject) {
        synchronized (store) {
            List<Object> out = new ArrayList<>();
            for (Blueprint b : store.blueprints) {
                if (subject != null && !subject.isBlank() && !b.subjectCode.equalsIgnoreCase(subject)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) b.toJson();
                int used = 0;
                for (Paper p : store.papers) if (b.id.equals(p.blueprintId)) used++;
                m.put("papersMade", used);
                m.put("check", PaperGenerator.check(b, store.questions));
                out.add(m);
            }
            return out;
        }
    }

    public Map<String, Object> get(String id) {
        synchronized (store) {
            Blueprint b = find(id);
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) b.toJson();
            m.put("check", PaperGenerator.check(b, store.questions));
            return m;
        }
    }

    /** Checks a blueprint from the form before it is saved (the editor shows this live). */
    public Map<String, Object> checkDraft(Map<String, Object> body) {
        synchronized (store) {
            Blueprint b = parse(new Blueprint(), body);
            return PaperGenerator.check(b, store.questions);
        }
    }

    public Map<String, Object> create(User me, Map<String, Object> body) {
        synchronized (store) {
            Blueprint b = parse(new Blueprint(), body);
            b.id = store.newBlueprintId();
            b.createdBy = me.username;
            b.createdAt = store.now();
            b.updatedAt = b.createdAt;
            store.blueprints.add(b);
            store.saveBlueprints();
            store.log(me.username, "blueprint added", "Added blueprint " + b.name, "blueprint:" + b.id);
            return get(b.id);
        }
    }

    public Map<String, Object> update(User me, String id, Map<String, Object> body) {
        synchronized (store) {
            Blueprint b = find(id);
            canEdit(me, b);
            Blueprint fresh = parse(new Blueprint(), body);
            b.name = fresh.name; b.subjectCode = fresh.subjectCode; b.examType = fresh.examType;
            b.durationMin = fresh.durationMin; b.instructions = fresh.instructions; b.sections = fresh.sections;
            b.mix = fresh.mix; b.units = fresh.units; b.avoidRecentDays = fresh.avoidRecentDays;
            b.updatedAt = store.now();
            store.saveBlueprints();
            store.log(me.username, "blueprint changed", "Edited blueprint " + b.name, "blueprint:" + b.id);
            return get(b.id);
        }
    }

    public Map<String, Object> copy(User me, String id) {
        synchronized (store) {
            Blueprint src = find(id);
            Blueprint b = Blueprint.fromJson(Json.parseObject(Json.write(src)));
            b.id = store.newBlueprintId();
            b.name = (src.name + " (copy)").length() > 80 ? src.name : src.name + " (copy)";
            b.createdBy = me.username;
            b.createdAt = store.now();
            b.updatedAt = b.createdAt;
            store.blueprints.add(b);
            store.saveBlueprints();
            store.log(me.username, "blueprint added", "Copied blueprint " + src.name, "blueprint:" + b.id);
            return get(b.id);
        }
    }

    public void delete(User me, String id) {
        synchronized (store) {
            Blueprint b = find(id);
            canEdit(me, b);
            store.blueprints.remove(b);
            store.saveBlueprints();
            store.log(me.username, "blueprint deleted", "Deleted blueprint " + b.name, null);
        }
    }

    private void canEdit(User me, Blueprint b) {
        if (!me.role.atLeast(Role.HOD) && !me.username.equals(b.createdBy))
            throw ApiError.forbidden("You can only change blueprints you made. Make a copy instead.");
    }

    Blueprint find(String id) {
        Blueprint b = store.blueprint(id);
        if (b == null) throw ApiError.notFound("No such blueprint");
        return b;
    }

    /** Reads and checks the blueprint form. Every number is range-checked. */
    @SuppressWarnings("unchecked")
    Blueprint parse(Blueprint b, Map<String, Object> m) {
        try {
            b.name = Text.line(Json.str(m, "name"), "Blueprint name", 80, true);
            Subject s = store.subject(Json.str(m, "subjectCode"));
            if (s == null) throw new IllegalArgumentException("Choose a valid subject");
            b.subjectCode = s.code;
            b.examType = Text.line(Json.str(m, "examType"), "Exam name", 60, true);
            b.durationMin = Text.range(Json.intOrNull(m, "durationMin"), "Duration (minutes)", 10, 360);
            b.instructions = new ArrayList<>();
            for (String line : Json.strings(m, "instructions")) {
                String t = Text.line(line, "Instruction", 200, false);
                if (!t.isEmpty()) b.instructions.add(t);
            }
            if (b.instructions.size() > 12) throw new IllegalArgumentException("At most 12 general instructions");
            List<Object> secs = Json.list(m, "sections");
            if (secs == null || secs.isEmpty()) throw new IllegalArgumentException("Add at least one section");
            if (secs.size() > 8) throw new IllegalArgumentException("At most 8 sections");
            b.sections = new ArrayList<>();
            int i = 0;
            for (Object o : secs) {
                Map<String, Object> x = (Map<String, Object>) o;
                String label = "Section " + (char) ('A' + i++);
                Blueprint.Section sec = new Blueprint.Section();
                sec.title = Text.line(Json.str(x, "title"), label + " title", 40, false);
                if (sec.title.isEmpty()) sec.title = label;
                sec.instructions = Text.line(Json.str(x, "instructions"), label + " instructions", 200, false);
                sec.type = Enums.parse(QType.class, Json.str(x, "type"), label + " question type");
                sec.count = Text.range(Json.intOrNull(x, "count"), label + ": number of questions", 1, 50);
                Integer att = Json.intOrNull(x, "attempt");
                sec.attempt = Text.range(att == null ? sec.count : att, label + ": questions to attempt", 1, sec.count);
                sec.marksEach = Text.range(Json.intOrNull(x, "marksEach"), label + ": marks each", 1, 50);
                if (sec.type.objective && sec.marksEach > 5) throw new IllegalArgumentException(label + ": " + sec.type.label + " questions carry at most 5 marks");
                b.sections.add(sec);
            }
            Map<String, Object> mix = Json.obj(m, "mix");
            int sum = 0;
            for (Difficulty d : Difficulty.values()) {
                int v = Text.range(mix == null ? 0 : Json.integer(mix, d.name(), 0), d.label + " %", 0, 100);
                b.mix.put(d, v);
                sum += v;
            }
            if (sum != 100) throw new IllegalArgumentException("Easy + Medium + Hard must add up to 100% (now " + sum + "%)");
            b.units = new ArrayList<>();
            for (Object o : Json.list(m, "units") == null ? List.of() : Json.list(m, "units")) {
                int u;
                try { u = Integer.parseInt(String.valueOf(o).replace(".0", "")); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("Bad unit number"); }
                if (s.unit(u) == null) throw new IllegalArgumentException("Unit " + u + " does not exist in " + s.code);
                if (!b.units.contains(u)) b.units.add(u);
            }
            b.units.sort(Integer::compare);
            if (b.units.size() == s.units.size()) b.units.clear(); // all units = no filter
            b.avoidRecentDays = Text.range(Json.integer(m, "avoidRecentDays", 0), "Avoid recent questions (days)", 0, 730);
            if (b.totalMarks() > 300) throw new IllegalArgumentException("Total marks can be at most 300 (now " + b.totalMarks() + ")");
            return b;
        } catch (IllegalArgumentException e) {
            throw ApiError.bad(e.getMessage());
        }
    }
}
