package com.cecil.complaints.ai;

import com.cecil.complaints.model.Enums.Sentiment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lexicon-based sentiment analysis (similar in spirit to VADER).
 *
 * How it works:
 *  1. Every known word has a score from -3 (very bad) to +3 (very good).
 *  2. "very", "extremely" make the next word stronger.
 *  3. "not", "never" flip the next words ("not happy" becomes negative).
 *  4. Exclamation marks and ALL-CAPS words make negative feelings stronger.
 *  5. The total is squashed into the range -1..+1.
 *
 * It also detects emotions (anger, frustration, ...) from word lists.
 */
public class SentimentAnalyzer {

    public record Result(Sentiment label, double score, List<String> emotions) {
    }

    private static final Map<String, Double> LEXICON = new HashMap<>();
    private static final Set<String> NEGATORS = Set.of(
            "not", "no", "never", "dont", "don't", "didnt", "didn't", "doesnt", "doesn't", "isnt", "isn't",
            "wasnt", "wasn't", "cant", "can't", "cannot", "wont", "won't", "havent", "haven't", "hasnt",
            "hasn't", "nobody", "nothing", "neither", "nor", "without", "werent", "weren't", "aint");
    private static final Map<String, Double> INTENSIFIERS = Map.of(
            "very", 1.5, "extremely", 1.8, "really", 1.4, "so", 1.3, "totally", 1.5, "completely", 1.6,
            "absolutely", 1.7, "highly", 1.4, "super", 1.4, "incredibly", 1.7);

    private static final Map<String, Set<String>> EMOTIONS = new LinkedHashMap<>();

    static {
        add(-3, "worst", "horrible", "terrible", "awful", "pathetic", "disgusting", "scam", "fraud",
                "cheated", "useless", "furious", "outraged", "unacceptable", "nightmare", "disaster",
                "hate", "ridiculous", "shameful", "atrocious", "dangerous", "stolen", "hacked", "liar", "lied");
        add(-2, "bad", "poor", "broken", "damaged", "defective", "angry", "upset", "frustrated", "frustrating",
                "disappointed", "disappointing", "rude", "unhelpful", "incompetent", "wrong", "failed", "fail",
                "failure", "faulty", "annoyed", "annoying", "worse", "lost", "missing", "ignored", "overcharged",
                "unauthorized", "careless", "misleading", "cheat", "waste", "wasted", "error", "crash", "crashes",
                "crashed", "fake", "stuck", "refused", "denied", "harassed", "scared", "worried", "unfair");
        add(-1, "late", "delay", "delayed", "slow", "issue", "problem", "problems", "complaint", "trouble",
                "confused", "confusing", "unclear", "difficult", "hard", "inconvenient", "inconvenience",
                "unhappy", "charged", "cancel", "cancelled", "canceled", "pending", "waiting", "still",
                "again", "never", "leak", "leaking", "cracked", "scratched", "mistake", "glitch", "bug");
        add(1, "ok", "okay", "fine", "fair", "resolved", "fixed", "help", "helped", "quick", "polite", "thanks",
                "thank", "appreciate", "hope", "hopefully", "kindly", "patient");
        add(2, "good", "great", "happy", "nice", "helpful", "satisfied", "pleased", "love", "loved", "friendly",
                "fast", "smooth", "recommend", "glad", "awesome");
        add(3, "excellent", "amazing", "fantastic", "perfect", "outstanding", "wonderful", "brilliant", "superb");

        EMOTIONS.put("anger", Set.of("angry", "furious", "outraged", "hate", "ridiculous", "unacceptable",
                "pathetic", "disgusting", "shameful", "rude", "worst", "liar", "lied", "cheated"));
        EMOTIONS.put("frustration", Set.of("frustrated", "frustrating", "again", "still", "repeatedly", "multiple",
                "times", "ignored", "nobody", "waiting", "weeks", "annoyed", "annoying", "stuck", "tired"));
        EMOTIONS.put("disappointment", Set.of("disappointed", "disappointing", "expected", "unhappy", "sad",
                "poor", "bad", "waste", "wasted", "regret", "misleading"));
        EMOTIONS.put("anxiety", Set.of("worried", "scared", "afraid", "concerned", "anxious", "unauthorized",
                "hacked", "stolen", "fraud", "security", "unsafe", "dangerous"));
        EMOTIONS.put("urgency", Set.of("urgent", "urgently", "immediately", "asap", "emergency", "now",
                "today", "tonight", "critical", "deadline"));
        EMOTIONS.put("satisfaction", Set.of("happy", "satisfied", "pleased", "great", "excellent", "thanks",
                "appreciate", "love", "amazing"));
    }

    private static void add(double score, String... words) {
        for (String w : words) {
            LEXICON.put(w, score);
        }
    }

    public Result analyze(String text) {
        if (text == null || text.isBlank()) {
            return new Result(Sentiment.NEUTRAL, 0, List.of());
        }
        List<String> tokens = TextProcessor.rawTokens(text);
        List<String> originalWords = originalWords(text);

        double total = 0;
        int negateWindow = 0;
        double boost = 1.0;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (NEGATORS.contains(t)) {
                negateWindow = 3;
                continue;
            }
            if (INTENSIFIERS.containsKey(t)) {
                boost = INTENSIFIERS.get(t);
                continue;
            }
            Double s = LEXICON.get(t);
            if (s != null) {
                double v = s * boost;
                if (negateWindow > 0) {
                    // "not good" is negative; "not bad" is only mildly positive
                    v = v > 0 ? -v * 0.9 : -v * 0.4;
                }
                // ALL-CAPS shouting makes negative words stronger
                if (v < 0 && i < originalWords.size() && isShouting(originalWords.get(i))) {
                    v *= 1.4;
                }
                total += v;
            }
            boost = 1.0;
            if (negateWindow > 0) {
                negateWindow--;
            }
        }

        // exclamation marks amplify whatever the feeling is (capped)
        long bangs = text.chars().filter(ch -> ch == '!').count();
        if (total < 0) {
            total -= Math.min(bangs, 4) * 0.4;
        }

        double norm = total / Math.sqrt(total * total + 25);
        Sentiment label;
        if (norm <= -0.6) {
            label = Sentiment.VERY_NEGATIVE;
        } else if (norm < -0.15) {
            label = Sentiment.NEGATIVE;
        } else if (norm > 0.25) {
            label = Sentiment.POSITIVE;
        } else {
            label = Sentiment.NEUTRAL;
        }
        return new Result(label, norm, detectEmotions(tokens, bangs));
    }

    private List<String> detectEmotions(List<String> tokens, long bangs) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (String t : tokens) {
            for (Map.Entry<String, Set<String>> e : EMOTIONS.entrySet()) {
                if (e.getValue().contains(t)) {
                    hits.merge(e.getKey(), 1, Integer::sum);
                }
            }
        }
        if (bangs >= 3) {
            hits.merge("anger", 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        hits.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> out.add(e.getKey()));
        return out;
    }

    /** Words in original case, aligned with rawTokens (same splitting rules). */
    private static List<String> originalWords(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || ((c == '\'' || c == '’') && cur.length() > 0)) {
                cur.append(c);
            } else if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static boolean isShouting(String word) {
        if (word.length() < 3) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c) && !Character.isUpperCase(c)) {
                return false;
            }
        }
        return true;
    }
}
