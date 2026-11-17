package com.cecil.kaushal.core;

import com.cecil.kaushal.model.Req;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * GAP ANALYSIS: how close is a person to what a job role (or project slot) needs?
 *
 *   readiness % = 100 × Σ weight × min(have ÷ need, 1)  ÷  Σ weight
 *
 * Example: needs Java 4 (weight 3) and SQL 3 (weight 1); has Java 3 and SQL 3 ->
 *   (3 × 0.75 + 1 × 1) ÷ 4 = 81 %.
 * "Ready" = every must-have is met AND readiness is 80 % or more.
 */
public final class Readiness {
    public static final int READY_AT = 80;

    private Readiness() { }

    public record Line(String skillId, int have, int need, boolean must, int weight) {
        public boolean met() { return have >= need; }
        public int missing() { return Math.max(0, need - have); }
    }

    public record Result(int percent, boolean ready, int mustMissing, List<Line> gaps, List<Line> met) { }

    /** levels: skillId -> the person's effective level (missing = 0). */
    public static Result of(Map<String, Integer> levels, List<Req> reqs) {
        if (reqs.isEmpty()) return new Result(100, true, 0, List.of(), List.of());
        double got = 0, total = 0;
        int mustMissing = 0;
        List<Line> gaps = new ArrayList<>(), met = new ArrayList<>();
        for (Req r : reqs) {
            int have = levels.getOrDefault(r.skillId(), 0);
            Line l = new Line(r.skillId(), have, r.level(), r.must(), r.weight());
            got += r.weight() * Math.min(1.0, have / (double) r.level());
            total += r.weight();
            if (l.met()) met.add(l);
            else {
                gaps.add(l);
                if (r.must()) mustMissing++;
            }
        }
        // biggest problems first: must-haves, then the heaviest and widest gaps
        gaps.sort(Comparator.comparing((Line l) -> !l.must()).thenComparing(l -> -l.weight() * l.missing()));
        int pct = (int) Math.round(100 * got / total);
        return new Result(pct, mustMissing == 0 && pct >= READY_AT, mustMissing, gaps, met);
    }
}
