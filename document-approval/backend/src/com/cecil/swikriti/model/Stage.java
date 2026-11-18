package com.cecil.swikriti.model;

import com.cecil.swikriti.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One step of a workflow, e.g. "Finance check".
 *
 * A stage has one or more SEATS. A seat is a rule that says who may sign it:
 *   MANAGER          the author's line manager
 *   MANAGER2         the manager's manager
 *   GROUP:Finance    anyone in the group "Finance"
 *   USER:U-1003      one named person
 * A seat is filled when ONE of its people approves.
 *
 * The mode says how many seats must be filled:  ALL seats · ANY one seat · QUORUM (at least `quorum` seats).
 * Two seats with mode ALL = two reviews in parallel (e.g. Legal and Finance at the same time).
 * The condition (optional) decides if the stage is needed at all, e.g.  amount > 50000 .
 */
public class Stage implements Json.Writable {
    public enum Mode { ALL, ANY, QUORUM;
        public static Mode of(String s) {
            try { return Mode.valueOf(s == null ? "ALL" : s.trim().toUpperCase()); } catch (IllegalArgumentException e) { return null; }
        }
    }

    public String name;
    public List<String> seats = new ArrayList<>();
    public Mode mode = Mode.ALL;
    public int quorum = 1;
    public String condition = "";
    public int slaHours = 16;        // business hours (Mon-Fri 09:30-18:00, holidays skipped)

    /** How many seats must say yes. */
    public int needed() {
        return switch (mode) {
            case ALL -> seats.size();
            case ANY -> 1;
            case QUORUM -> Math.max(1, Math.min(quorum, seats.size()));
        };
    }

    public Stage copy() {
        Stage s = new Stage();
        s.name = name; s.seats = new ArrayList<>(seats); s.mode = mode; s.quorum = quorum;
        s.condition = condition; s.slaHours = slaHours;
        return s;
    }

    @Override public Map<String, Object> toJson() {
        return Json.obj("name", name, "seats", seats, "mode", mode, "quorum", quorum, "condition", condition, "slaHours", slaHours);
    }

    public static Stage fromJson(Map<String, Object> m) {
        Stage s = new Stage();
        s.name = Json.str(m, "name", "Stage");
        s.seats = Json.strings(m, "seats");
        Mode md = Mode.of(Json.str(m, "mode"));
        s.mode = md == null ? Mode.ALL : md;
        s.quorum = (int) Json.num(m, "quorum", 1);
        s.condition = Json.str(m, "condition", "");
        s.slaHours = (int) Json.num(m, "slaHours", 16);
        return s;
    }
}
