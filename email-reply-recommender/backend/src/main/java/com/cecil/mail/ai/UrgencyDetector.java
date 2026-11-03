package com.cecil.mail.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides how quickly an email needs an answer: HIGH, MEDIUM or LOW.
 * Adds points for urgent phrases, near deadlines, ALL-CAPS shouting and many "!" marks.
 */
public class UrgencyDetector {

    public record Result(String level, int score, List<String> reasons) { }

    private static final Map<String, Integer> PHRASES = Map.ofEntries(
            Map.entry("urgent", 4), Map.entry("asap", 4), Map.entry("as soon as possible", 4),
            Map.entry("immediately", 4), Map.entry("emergency", 4), Map.entry("right away", 3),
            Map.entry("today", 2), Map.entry("tonight", 2), Map.entry("by tomorrow", 3), Map.entry("tomorrow", 1),
            Map.entry("eod", 3), Map.entry("end of day", 3), Map.entry("deadline", 2), Map.entry("overdue", 3),
            Map.entry("at the earliest", 2), Map.entry("priority", 2), Map.entry("critical", 3),
            Map.entry("still waiting", 2), Map.entry("reminder", 1), Map.entry("not working", 2),
            Map.entry("unable to", 1), Map.entry("final notice", 4), Map.entry("time sensitive", 3));

    public Result detect(String subject, String body) {
        String text = ((subject == null ? "" : subject) + " " + (body == null ? "" : body));
        String lower = text.toLowerCase(Locale.ROOT);
        int score = 0;
        List<String> reasons = new ArrayList<>();

        for (var e : PHRASES.entrySet()) {
            if (lower.matches("(?s).*\\b" + e.getKey() + "\\b.*")) {
                score += e.getValue();
                reasons.add("mentions \"" + e.getKey() + "\"");
            }
        }
        long bangs = text.chars().filter(c -> c == '!').count();
        if (bangs >= 2) { score += 2; reasons.add(bangs + " exclamation marks"); }

        int capsWords = 0;
        for (String w : text.split("\\s+")) if (w.length() > 3 && w.equals(w.toUpperCase(Locale.ROOT)) && w.matches(".*[A-Z].*")) capsWords++;
        if (capsWords >= 2) { score += 2; reasons.add("uses CAPITAL letters for emphasis"); }

        String level = score >= 6 ? "HIGH" : score >= 3 ? "MEDIUM" : "LOW";
        if (reasons.isEmpty()) reasons.add("no time pressure found");
        return new Result(level, score, reasons);
    }
}
