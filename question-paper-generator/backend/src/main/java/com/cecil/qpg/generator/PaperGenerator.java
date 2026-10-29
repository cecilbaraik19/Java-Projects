package com.cecil.qpg.generator;

import com.cecil.qpg.model.Blueprint;
import com.cecil.qpg.model.Enums.Difficulty;
import com.cecil.qpg.model.Paper;
import com.cecil.qpg.model.Question;
import com.cecil.qpg.model.Subject;
import com.cecil.qpg.util.Json;
import com.cecil.qpg.util.Text;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The heart of the project: fills a blueprint with questions from the bank.
 *
 * For every place in a section it gives each possible question a "score" (lower is better) and takes the best one:
 *   - a small random number            -> different papers each time (same seed = same paper)
 *   - + 10 if it is not the difficulty still needed   -> keeps the easy / medium / hard mix
 *   - + 1.5 for each question already taken from the same unit -> spreads questions over all units
 *   - + 5 if an earlier set already has it             -> Set A, B, C get different questions
 *   - + 3 if it was in a released paper recently       -> avoids repeating last exam's questions
 *   - + 0.2 for each time it was used before           -> gives fresh questions a chance
 * Questions that are almost the same as one already chosen (similarity at or above the limit) are skipped.
 * Nothing here touches files: it only reads the lists it is given, so it is easy to test.
 */
public final class PaperGenerator {
    private PaperGenerator() {}

    public static final double W_DIFF = 10, W_UNIT = 1.5, W_OTHER_SET = 5, W_RECENT = 3, W_USED = 0.2;

    /** Extra choices for one generate run. */
    public static class Options {
        public int sets = 1;
        public long seed;
        public String title;
        public String examDate;
        public String institute;
        public LocalDate today = LocalDate.now();
        public double duplicateThreshold = 0.6;
        /** Questions to keep out if possible (for example those in the other sets of the same group). */
        public Set<String> avoidIds = new HashSet<>();
    }

    // ------------------------------------------------------------------ generate

    public static List<Paper> generate(Blueprint bp, Subject subject, Collection<Question> bank, Options o) {
        List<Question> pool = pool(bp, bank);
        List<Paper> out = new ArrayList<>();
        Set<String> inEarlierSets = new HashSet<>(o.avoidIds);
        for (int s = 0; s < o.sets; s++) {
            Random rng = new Random(o.seed + s * 7919L);
            Paper p = newPaper(bp, subject, o, s);
            p.seed = o.seed;
            Map<Integer, Integer> unitUse = new HashMap<>();
            List<Question> chosenAll = new ArrayList<>();
            int reused = 0, recentCount = 0;
            for (int si = 0; si < bp.sections.size(); si++) {
                Blueprint.Section sec = bp.sections.get(si);
                String secName = sectionName(sec, si);
                List<Question> cands = new ArrayList<>();
                for (Question q : pool) if (q.type == sec.type && q.marks == sec.marksEach) cands.add(q);
                Map<Difficulty, Integer> need = targets(bp.mix, sec.count);
                Map<Difficulty, Integer> wanted = new EnumMap<>(need);
                List<Question> picked = new ArrayList<>();
                for (int slot = 0; slot < sec.count; slot++) {
                    // questions still free to use in this slot
                    List<Question> free = new ArrayList<>();
                    for (Question q : cands)
                        if (!chosenAll.contains(q) && !tooSimilar(q, chosenAll, o.duplicateThreshold)) free.add(q);
                    if (free.isEmpty()) break; // nothing left
                    Difficulty desired = mostNeeded(need, free);
                    Question best = null;
                    double bestScore = Double.MAX_VALUE;
                    for (Question q : free) {
                        double diffCost = q.difficulty == desired ? 0 : need.getOrDefault(q.difficulty, 0) > 0 ? W_DIFF / 2 : W_DIFF;
                        double score = rng.nextDouble()
                                + diffCost
                                + unitUse.getOrDefault(q.unit, 0) * W_UNIT
                                + (inEarlierSets.contains(q.id) ? W_OTHER_SET : 0)
                                + (recentlyUsed(q, bp.avoidRecentDays, o.today) ? W_RECENT : 0)
                                + q.timesUsed * W_USED;
                        if (score < bestScore) { bestScore = score; best = q; }
                    }
                    picked.add(best);
                    chosenAll.add(best);
                    unitUse.merge(best.unit, 1, Integer::sum);
                    need.merge(best.difficulty, -1, Integer::sum); // may go below 0 when the bank ran short
                    if (inEarlierSets.contains(best.id)) reused++;
                    if (recentlyUsed(best, bp.avoidRecentDays, o.today)) recentCount++;
                }
                // how many places could not get the planned difficulty
                int off = 0;
                if (picked.size() == sec.count) for (Difficulty d : Difficulty.values()) off += Math.max(0, need.get(d));
                // tidy order: by unit, then easy before hard
                picked.sort(Comparator.comparingInt((Question q) -> q.unit).thenComparing(q -> q.difficulty));
                Paper.PSection ps = new Paper.PSection();
                ps.title = secName;
                ps.instructions = sec.instructions;
                ps.type = sec.type;
                ps.marksEach = sec.marksEach;
                ps.attempt = sec.attempt;
                for (Question q : picked) ps.items.add(Paper.Item.of(q, shuffledOptions(q, rng)));
                p.sections.add(ps);

                if (picked.size() < sec.count) {
                    p.warnings.add(secName + ": only " + picked.size() + " of " + sec.count + " questions found ("
                            + sec.type.label + ", " + sec.marksEach + " mark" + (sec.marksEach == 1 ? "" : "s")
                            + " each). Add more such questions to the bank.");
                    if (ps.attempt > picked.size()) ps.attempt = picked.size();
                }
                if (off > 0) p.warnings.add(secName + ": " + off + " question" + (off == 1 ? " has" : "s have")
                        + " a different difficulty than planned (" + mixText(wanted) + "), because the bank ran short.");
            }
            if (reused > 0) p.warnings.add(reused + " question" + (reused == 1 ? " is" : "s are")
                    + " also in another set of this exam. The bank is too small to keep every set fully different.");
            if (recentCount > 0) p.warnings.add(recentCount + " question" + (recentCount == 1 ? " was" : "s were")
                    + " used in a released paper in the last " + bp.avoidRecentDays + " days.");
            for (Question q : chosenAll) inEarlierSets.add(q.id);
            out.add(p);
        }
        return out;
    }

    private static Paper newPaper(Blueprint bp, Subject subject, Options o, int setIndex) {
        Paper p = new Paper();
        p.subjectCode = bp.subjectCode;
        p.blueprintId = bp.id;
        p.examType = bp.examType;
        p.durationMin = bp.durationMin;
        p.instructions = new ArrayList<>(bp.instructions);
        p.examDate = o.examDate;
        p.institute = o.institute;
        p.setLabel = String.valueOf((char) ('A' + setIndex));
        String base = o.title == null || o.title.isBlank()
                ? (bp.examType == null || bp.examType.isBlank() ? bp.name : bp.examType) + " - " + (subject == null ? bp.subjectCode : subject.name)
                : o.title;
        p.title = o.sets > 1 ? base + " (Set " + p.setLabel + ")" : base;
        Map<String, Object> tm = new LinkedHashMap<>();
        for (Difficulty d : Difficulty.values()) tm.put(d.name(), bp.mix.getOrDefault(d, 0));
        p.targetMix = tm;
        return p;
    }

    /** Questions the blueprint may use: right subject, not archived, in the chosen units. */
    public static List<Question> pool(Blueprint bp, Collection<Question> bank) {
        List<Question> pool = new ArrayList<>();
        for (Question q : bank) {
            if (q.archived || !q.subjectCode.equalsIgnoreCase(bp.subjectCode)) continue;
            if (!bp.units.isEmpty() && !bp.units.contains(q.unit)) continue;
            pool.add(q);
        }
        pool.sort(Comparator.comparing(q -> q.id)); // fixed order, so the same seed always gives the same paper
        return pool;
    }

    public static String sectionName(Blueprint.Section sec, int index) {
        String letter = "Section " + (char) ('A' + index);
        return sec.title == null || sec.title.isBlank() ? letter : sec.title;
    }

    /**
     * Turns percentages into whole question counts that add up exactly ("largest remainder" method).
     * Example: 7 questions at 30/50/20 -> 2.1, 3.5, 1.4 -> 2, 3, 1 (=6) -> the biggest leftover (.5) gets the last one -> 2, 4, 1.
     */
    public static Map<Difficulty, Integer> targets(Map<Difficulty, Integer> mix, int count) {
        Map<Difficulty, Integer> out = new EnumMap<>(Difficulty.class);
        int totalPct = 0;
        for (Difficulty d : Difficulty.values()) totalPct += mix.getOrDefault(d, 0);
        if (totalPct <= 0) { // no mix given: all medium
            for (Difficulty d : Difficulty.values()) out.put(d, d == Difficulty.MEDIUM ? count : 0);
            return out;
        }
        Map<Difficulty, Double> rem = new EnumMap<>(Difficulty.class);
        int given = 0;
        for (Difficulty d : Difficulty.values()) {
            double exact = count * mix.getOrDefault(d, 0) / (double) totalPct;
            int fl = (int) Math.floor(exact);
            out.put(d, fl);
            rem.put(d, exact - fl);
            given += fl;
        }
        List<Difficulty> order = new ArrayList<>(List.of(Difficulty.values()));
        order.sort((a, b) -> Double.compare(rem.get(b), rem.get(a)));
        for (int i = 0; given < count; i++, given++) out.merge(order.get(i % order.size()), 1, Integer::sum);
        return out;
    }

    /** The difficulty with the most places still to fill, among those the free questions can still give. */
    private static Difficulty mostNeeded(Map<Difficulty, Integer> need, List<Question> free) {
        Difficulty best = null;
        int max = 0;
        for (Difficulty d : Difficulty.values()) {
            int n = need.getOrDefault(d, 0);
            if (n <= max) continue;
            boolean available = false;
            for (Question q : free) if (q.difficulty == d) { available = true; break; }
            if (available) { max = n; best = d; }
        }
        if (best != null) return best;
        // nothing needed is available: aim for the nearest level that still has questions
        for (Difficulty d : new Difficulty[]{Difficulty.MEDIUM, Difficulty.EASY, Difficulty.HARD})
            for (Question q : free) if (q.difficulty == d) return d;
        return Difficulty.MEDIUM;
    }

    private static String mixText(Map<Difficulty, Integer> m) {
        return m.get(Difficulty.EASY) + " easy, " + m.get(Difficulty.MEDIUM) + " medium, " + m.get(Difficulty.HARD) + " hard";
    }

    public static boolean tooSimilar(Question q, List<Question> chosen, double threshold) {
        for (Question c : chosen) if (Text.similarity(q.text, c.text) >= threshold) return true;
        return false;
    }

    public static boolean recentlyUsed(Question q, int days, LocalDate today) {
        if (days <= 0 || q.lastUsedOn == null || q.lastUsedOn.isBlank()) return false;
        try {
            LocalDate d = LocalDate.parse(q.lastUsedOn.substring(0, 10));
            return ChronoUnit.DAYS.between(d, today) <= days;
        } catch (DateTimeParseException | IndexOutOfBoundsException e) {
            return false;
        }
    }

    /** MCQ choices in a new order for this paper (so Set A and Set B do not share an answer pattern). */
    private static List<String> shuffledOptions(Question q, Random rng) {
        List<String> opts = new ArrayList<>(q.options);
        if (q.type == com.cecil.qpg.model.Enums.QType.MCQ && opts.size() > 1) {
            boolean lastIsAll = !opts.isEmpty() && opts.get(opts.size() - 1).toLowerCase().matches("(all|none) of (the )?above");
            if (lastIsAll) {
                String last = opts.remove(opts.size() - 1);
                Collections.shuffle(opts, rng);
                opts.add(last); // "All of the above" must stay last
            } else Collections.shuffle(opts, rng);
        }
        return opts;
    }

    // ------------------------------------------------------------------ swap one question

    /**
     * Picks a replacement for one question: same kind and marks, not already in the paper,
     * same difficulty if possible, then same unit, preferring questions not in other sets.
     * Returns null if there is nothing to swap with.
     */
    public static Question replacement(Paper paper, int sectionIndex, int itemIndex, Collection<Question> bank,
                                       Set<String> otherSetIds, double threshold, Random rng, List<Integer> units) {
        Paper.PSection sec = paper.sections.get(sectionIndex);
        Paper.Item cur = sec.items.get(itemIndex);
        Set<String> inPaper = new HashSet<>(paper.questionIds());
        List<Question> others = new ArrayList<>();
        for (Paper.PSection s : paper.sections) for (Paper.Item it : s.items) {
            if (it == cur) continue;
            Question tmp = new Question();
            tmp.text = it.text;
            others.add(tmp);
        }
        Question best = null;
        double bestScore = Double.MAX_VALUE;
        List<Question> sorted = new ArrayList<>(bank);
        sorted.sort(Comparator.comparing(q -> q.id));
        for (Question q : sorted) {
            if (q.archived || !q.subjectCode.equalsIgnoreCase(paper.subjectCode)) continue;
            if (q.type != sec.type || q.marks != sec.marksEach || inPaper.contains(q.id)) continue;
            if (units != null && !units.isEmpty() && !units.contains(q.unit)) continue; // stay inside the syllabus part of the blueprint
            if (tooSimilar(q, others, threshold)) continue;
            double score = rng.nextDouble()
                    + (q.difficulty == cur.difficulty ? 0 : W_DIFF)
                    + (q.unit == cur.unit ? 0 : 2)
                    + (otherSetIds.contains(q.id) ? W_OTHER_SET : 0)
                    + q.timesUsed * W_USED;
            if (score < bestScore) { bestScore = score; best = q; }
        }
        return best;
    }

    // ------------------------------------------------------------------ check a blueprint before generating

    /** For each section: how many matching questions the bank has, split by difficulty, and how many sets it can fill. */
    public static Map<String, Object> check(Blueprint bp, Collection<Question> bank) {
        List<Question> pool = pool(bp, bank);
        List<Object> secs = new ArrayList<>();
        int maxSets = Integer.MAX_VALUE;
        boolean ok = true;
        for (int i = 0; i < bp.sections.size(); i++) {
            Blueprint.Section s = bp.sections.get(i);
            Map<String, Object> byDiff = new LinkedHashMap<>();
            int total = 0;
            for (Difficulty d : Difficulty.values()) {
                int c = 0;
                for (Question q : pool) if (q.type == s.type && q.marks == s.marksEach && q.difficulty == d) c++;
                byDiff.put(d.name(), c);
                total += c;
            }
            Map<Difficulty, Integer> need = targets(bp.mix, s.count);
            Map<String, Object> needOut = new LinkedHashMap<>();
            for (Difficulty d : Difficulty.values()) needOut.put(d.name(), need.get(d));
            boolean enough = total >= s.count;
            boolean mixOk = true;
            for (Difficulty d : Difficulty.values()) if ((int) byDiff.get(d.name()) < need.get(d)) mixOk = false;
            if (!enough) ok = false;
            int sets = s.count == 0 ? 99 : total / s.count;
            maxSets = Math.min(maxSets, sets);
            secs.add(Json.map("title", sectionName(s, i), "type", s.type, "typeLabel", s.type.label,
                    "marksEach", s.marksEach, "count", s.count, "available", total, "byDifficulty", byDiff,
                    "need", needOut, "enough", enough, "mixOk", mixOk, "setsPossible", sets));
        }
        if (bp.sections.isEmpty()) maxSets = 0;
        return Json.map("ok", ok, "sections", secs, "poolSize", pool.size(),
                "setsWithoutRepeats", maxSets == Integer.MAX_VALUE ? 0 : maxSets,
                "totalMarks", bp.totalMarks(), "questionCount", bp.questionCount());
    }

    // ------------------------------------------------------------------ analysis of a paper

    /** Marks by unit, difficulty, Bloom level and kind, and how close the paper is to the planned mix. */
    public static Map<String, Object> analyse(Paper p, Subject subject) {
        Map<Integer, int[]> byUnit = new java.util.TreeMap<>();
        Map<String, int[]> byDiff = new LinkedHashMap<>();
        Map<String, int[]> byBloom = new LinkedHashMap<>();
        Map<String, int[]> byType = new LinkedHashMap<>();
        for (Difficulty d : Difficulty.values()) byDiff.put(d.name(), new int[2]);
        for (com.cecil.qpg.model.Enums.Bloom b : com.cecil.qpg.model.Enums.Bloom.values()) byBloom.put(b.name(), new int[2]);
        if (subject != null) for (Subject.Unit u : subject.units) byUnit.put(u.no, new int[2]);
        int printed = 0, count = 0;
        for (Paper.PSection s : p.sections) for (Paper.Item it : s.items) {
            add(byUnit.computeIfAbsent(it.unit, k -> new int[2]), it.marks);
            add(byDiff.get(it.difficulty.name()), it.marks);
            add(byBloom.get(it.bloom.name()), it.marks);
            add(byType.computeIfAbsent(it.type.name(), k -> new int[2]), it.marks);
            printed += it.marks;
            count++;
        }
        List<Object> units = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : byUnit.entrySet()) {
            String title = subject == null ? "Unit " + e.getKey() : subject.unitTitle(e.getKey());
            units.add(Json.map("unit", e.getKey(), "title", title, "count", e.getValue()[0], "marks", e.getValue()[1]));
            if (e.getValue()[0] == 0) missing.add("Unit " + e.getKey() + " (" + title + ")");
        }
        List<Object> diffs = new ArrayList<>();
        int maxGap = 0;
        for (Difficulty d : Difficulty.values()) {
            int c = byDiff.get(d.name())[0];
            int pct = count == 0 ? 0 : Math.round(c * 100f / count);
            int target = p.targetMix == null ? 0 : Json.integer(p.targetMix, d.name(), 0);
            maxGap = Math.max(maxGap, Math.abs(pct - target));
            diffs.add(Json.map("id", d.name(), "label", d.label, "count", c, "marks", byDiff.get(d.name())[1], "pct", pct, "target", target));
        }
        List<Object> blooms = new ArrayList<>();
        int higher = 0;
        for (com.cecil.qpg.model.Enums.Bloom b : com.cecil.qpg.model.Enums.Bloom.values()) {
            int[] v = byBloom.get(b.name());
            blooms.add(Json.map("id", b.name(), "label", b.label, "level", b.level, "count", v[0], "marks", v[1]));
            if (b.ordinal() >= 2) higher += v[1];
        }
        List<Object> types = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byType.entrySet()) {
            com.cecil.qpg.model.Enums.QType t = com.cecil.qpg.model.Enums.QType.valueOf(e.getKey());
            types.add(Json.map("id", t.name(), "label", t.label, "count", e.getValue()[0], "marks", e.getValue()[1]));
        }
        // balance score out of 100: 50 for matching the mix, 30 for covering every unit, 20 for higher-order thinking
        int mixPts = Math.max(0, 50 - maxGap);
        int unitsCovered = units.size() - missing.size();
        int unitPts = units.isEmpty() ? 30 : Math.round(30f * unitsCovered / units.size());
        int higherPct = printed == 0 ? 0 : Math.round(higher * 100f / printed);
        int bloomPts = Math.min(20, Math.round(higherPct / 2f));
        return Json.map("units", units, "missingUnits", missing, "difficulty", diffs, "bloom", blooms, "types", types,
                "printedMarks", printed, "totalMarks", p.totalMarks(), "questionCount", count,
                "higherOrderPct", higherPct, "maxMixGap", maxGap,
                "balanceScore", mixPts + unitPts + bloomPts,
                "balanceParts", Json.map("mix", mixPts, "units", unitPts, "thinking", bloomPts));
    }

    private static void add(int[] a, int marks) { a[0]++; a[1] += marks; }
}
