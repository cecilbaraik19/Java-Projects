package com.cecil.mail.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lexicon-based sentiment analysis.
 * Sign-off lines ("Thanks,", "Regards,") are removed first.
 * Every known word has a score (+ good, - bad). We add the scores, flip them after
 * "not / never / no", boost them after "very / extremely", and squash the total to -1..+1.
 */
public class SentimentAnalyzer {

    public record Result(double score, String label, String mood, List<String> positiveWords, List<String> negativeWords) { }

    private static final Map<String, Integer> LEXICON = Map.ofEntries(
            // positive
            Map.entry("thank", 2), Map.entry("thanks", 2), Map.entry("grateful", 3), Map.entry("appreciate", 3),
            Map.entry("great", 3), Map.entry("good", 2), Map.entry("excellent", 3), Map.entry("wonderful", 3),
            Map.entry("happy", 3), Map.entry("pleased", 3), Map.entry("glad", 2), Map.entry("love", 3),
            Map.entry("helpful", 2), Map.entry("useful", 2), Map.entry("amazing", 3), Map.entry("perfect", 3),
            Map.entry("kind", 2), Map.entry("quick", 1), Map.entry("success", 2), Map.entry("excited", 3),
            Map.entry("interested", 1), Map.entry("opportunity", 1), Map.entry("welcome", 2), Map.entry("nice", 2),
            Map.entry("resolved", 2), Map.entry("smooth", 2), Map.entry("valuable", 2), Map.entry("congratulations", 3),
            // negative
            Map.entry("disappointed", -3), Map.entry("poor", -2), Map.entry("bad", -2), Map.entry("terrible", -3),
            Map.entry("worst", -3), Map.entry("unacceptable", -3), Map.entry("angry", -3), Map.entry("frustrated", -3),
            Map.entry("rude", -3), Map.entry("damaged", -2), Map.entry("broken", -2), Map.entry("late", -1),
            Map.entry("delayed", -2), Map.entry("delay", -2), Map.entry("wrong", -2), Map.entry("error", -1),
            Map.entry("problem", -1), Map.entry("issue", -1), Map.entry("fail", -2), Map.entry("failed", -2),
            Map.entry("fails", -2), Map.entry("unable", -1), Map.entry("complain", -2), Map.entry("complaint", -2),
            Map.entry("refund", -1), Map.entry("cancel", -1), Map.entry("cancelling", -1), Map.entry("crash", -2),
            Map.entry("crashes", -2), Map.entry("slow", -1), Map.entry("unhappy", -3), Map.entry("annoyed", -2),
            Map.entry("waste", -2), Map.entry("overdue", -1), Map.entry("pending", -1), Map.entry("sick", -1),
            Map.entry("unwell", -1), Map.entry("emergency", -2), Map.entry("demand", -2), Map.entry("twice", -1),
            Map.entry("ignored", -2), Map.entry("worried", -2), Map.entry("worrying", -2),
            Map.entry("concerned", -2), Map.entry("upset", -2), Map.entry("torn", -1), Map.entry("locked", -1), Map.entry("sorry", -1), Map.entry("mismatch", -1));

    private static final Set<String> NEGATORS = Set.of("not", "never", "no", "dont", "didnt", "isnt", "wasnt",
            "cant", "cannot", "wont", "hasnt", "havent", "nobody", "nothing");

    private static final Set<String> BOOSTERS = Set.of("very", "extremely", "really", "so", "totally", "highly", "absolutely");

    /** Sign-off lines like "Thanks," or "Regards," are polite habits, not feelings, so they are ignored. */
    private static final java.util.regex.Pattern SIGN_OFF_LINE = java.util.regex.Pattern.compile(
            "(?im)^\\s*(thanks|thank you|thanks and regards|regards|best regards|warm regards|kind regards|best|"
            + "best wishes|cheers|sincerely|yours sincerely|yours truly|warmly)\\s*[,.!]*\\s*$");

    public Result analyze(String text) {
        text = text == null ? "" : SIGN_OFF_LINE.matcher(text).replaceAll("");
        List<String> words = TextTokenizer.words(text);
        double total = 0;
        List<String> pos = new ArrayList<>();
        List<String> neg = new ArrayList<>();

        for (int i = 0; i < words.size(); i++) {
            Integer base = LEXICON.get(words.get(i));
            if (base == null) continue;
            double s = base;
            // look back two words for "not" or "very"
            for (int j = Math.max(0, i - 2); j < i; j++) {
                if (NEGATORS.contains(words.get(j))) s = -s * 0.8;
                if (BOOSTERS.contains(words.get(j))) s = s * 1.5;
            }
            total += s;
            if (s > 0 && !pos.contains(words.get(i))) pos.add(words.get(i));
            if (s < 0 && !neg.contains(words.get(i))) neg.add(words.get(i));
        }
        // exclamation marks add strength in whatever direction the text already leans
        long bangs = text == null ? 0 : text.chars().filter(c -> c == '!').count();
        if (bangs > 1 && total != 0) total += Math.signum(total) * Math.min(bangs, 4);

        double score = total / Math.sqrt(total * total + 15);   // normalise to -1..+1
        score = Math.round(score * 100) / 100.0;

        String label = score > 0.15 ? "POSITIVE" : score < -0.15 ? "NEGATIVE" : "NEUTRAL";
        String mood;
        if (score <= -0.6) mood = "Frustrated";
        else if (score < -0.15) mood = "Concerned";
        else if (score >= 0.6) mood = "Delighted";
        else if (score > 0.15) mood = "Friendly";
        else mood = "Matter-of-fact";
        return new Result(score, label, mood, pos, neg);
    }
}
