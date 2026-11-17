package com.cecil.kaushal.model;

import com.cecil.kaushal.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The company's skills on the first day of a month: for every skill, how many people are at each effective level.
 * levels[0] = level 1 ... levels[4] = level 5. Used for the trend chart ("Kubernetes experts over the year").
 */
public class Snapshot implements Json.Writable {
    public String month;                                  // yyyy-mm
    public long takenAt;
    public Map<String, int[]> counts = new LinkedHashMap<>();

    /** People at this level or higher. */
    public int atLeast(String skillId, int level) {
        int[] c = counts.get(skillId);
        if (c == null) return 0;
        int n = 0;
        for (int i = Math.max(1, level) - 1; i < 5; i++) n += c[i];
        return n;
    }

    @Override public Map<String, Object> toJson() {
        Map<String, Object> c = new LinkedHashMap<>();
        counts.forEach((k, v) -> c.put(k, java.util.List.of(v[0], v[1], v[2], v[3], v[4])));
        return Json.obj("month", month, "takenAt", takenAt, "counts", c);
    }

    public static Snapshot fromJson(Map<String, Object> m) {
        Snapshot s = new Snapshot();
        s.month = Json.str(m, "month");
        s.takenAt = Json.num(m, "takenAt", 0);
        Object c = m.get("counts");
        if (c instanceof Map<?, ?> cm) for (Map.Entry<?, ?> e : cm.entrySet()) {
            int[] a = new int[5];
            java.util.List<?> l = (java.util.List<?>) e.getValue();
            for (int i = 0; i < 5 && i < l.size(); i++) a[i] = ((Number) l.get(i)).intValue();
            s.counts.put(String.valueOf(e.getKey()), a);
        }
        return s;
    }
}
