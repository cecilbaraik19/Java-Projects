package com.cecil.kaushal.model;

import com.cecil.kaushal.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One skill that a job role or a project slot needs: "Java at level 3, must have, weight 3".
 * must = without it the person is not ready / cannot be staffed. weight = how much it counts in the readiness score.
 */
public record Req(String skillId, int level, boolean must, int weight) implements Json.Writable {

    public Req {
        level = Math.max(1, Math.min(5, level));
        weight = Math.max(1, Math.min(5, weight));
    }

    @Override public Map<String, Object> toJson() {
        return Json.obj("skillId", skillId, "level", level, "must", must, "weight", weight);
    }

    @SuppressWarnings("unchecked")
    public static Req fromJson(Object o) {
        Map<String, Object> m = (Map<String, Object>) o;
        return new Req(Json.str(m, "skillId"), (int) Json.num(m, "level", 1), Json.bool(m, "must", false),
                (int) Json.num(m, "weight", 1));
    }

    public static List<Req> list(List<Object> raw) {
        List<Req> out = new ArrayList<>();
        for (Object o : raw) if (o instanceof Map) out.add(fromJson(o));
        return out;
    }
}
