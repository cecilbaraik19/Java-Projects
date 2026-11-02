package com.cecil.rag.rag;

import com.cecil.rag.util.Text;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Query expansion: students and the college documents often use different words for the same thing
 * ("hostel" vs "accommodation", "re-check" vs "re-evaluation"). Each word in the question also brings
 * in its campus synonyms, with a lower weight (0.5) so the exact word still counts most.
 */
public final class Synonyms {
    private Synonyms() {}

    private static final String[][] GROUPS = {
            {"fee", "fees", "charges", "payment", "dues", "tuition", "cost"},
            {"hostel", "accommodation", "dorm", "dormitory", "residence"},
            {"exam", "examination", "test", "paper", "assessment"},
            {"marks", "grade", "grades", "score", "cgpa", "sgpa", "result"},
            {"re-evaluation", "revaluation", "recheck", "rechecking", "re-totalling", "retotal"},
            {"attendance", "present", "absent", "shortage"},
            {"leave", "absence", "holiday", "vacation"},
            {"library", "books", "borrow", "issue", "lend"},
            {"fine", "penalty", "late"},
            {"placement", "recruitment", "job", "company", "drive", "internship"},
            {"scholarship", "freeship", "concession", "waiver", "financial"},
            {"ragging", "harassment", "bullying"},
            {"bus", "transport", "shuttle", "route"},
            {"canteen", "cafeteria", "mess", "food", "meal"},
            {"wifi", "internet", "network", "lan"},
            {"timing", "timings", "hours", "time", "open", "close", "schedule"},
            {"deadline", "due"},
            {"hod", "head"},
            {"principal", "director"},
            {"backlog", "supplementary", "arrear", "reappear", "atkt"},
            {"id", "identity", "card"},
            {"refund", "withdrawal", "cancel"},
            {"syllabus", "curriculum", "course", "subjects", "units"},
            {"lab", "laboratory", "practical"},
            {"semester", "sem", "term"},
            {"hall", "admit", "ticket"},
            {"complaint", "grievance", "problem", "issue"},
            {"doctor", "medical", "health", "sick", "clinic"},
            {"phone", "mobile", "contact", "email"},
            {"dress", "uniform"},
            {"club", "society", "fest", "event"},
            {"certificate", "bonafide", "transcript"},
    };

    private static final Map<String, List<String>> LOOKUP = new HashMap<>();

    static {
        for (String[] group : GROUPS) {
            List<String> stems = new java.util.ArrayList<>();
            for (String w : group) for (String t : Text.tokens(w)) if (!stems.contains(t)) stems.add(t);
            for (String s : stems) LOOKUP.computeIfAbsent(s, k -> new java.util.ArrayList<>()).addAll(stems);
        }
    }

    /** Returns word -> weight. Words from the question get 1.0, added synonyms get 0.5. */
    public static Map<String, Double> expand(List<String> queryTokens, boolean enabled) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String t : queryTokens) out.merge(t, 1.0, Double::sum);
        if (!enabled) return out;
        for (String t : queryTokens) {
            for (String s : LOOKUP.getOrDefault(t, List.of())) {
                if (!out.containsKey(s)) out.put(s, 0.5);
            }
        }
        return out;
    }

    public static Set<String> groupOf(String token) {
        return Set.copyOf(LOOKUP.getOrDefault(token, List.of(token)));
    }
}
