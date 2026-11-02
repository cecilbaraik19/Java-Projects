package com.cecil.rag.rag;

import com.cecil.rag.util.Text;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Follow-up questions: "What is the hostel fee?" then "and for girls?".
 * The second question alone has almost no useful words, so it is joined with the important words
 * of the question before it: "and for girls? (hostel fee)". This is called making a standalone question.
 */
public final class QueryRewriter {
    private QueryRewriter() {}

    // pronouns are matched in lower case only, so "B.Sc. IT" is not mistaken for "it"
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "^(?i:and|also|what about|how about|then|so|but)\\b|\\b(?:it|its|that|this|they|them|those|these|there|same|he|she|his|her)\\b");

    /** Returns the standalone question, or the same question when it already stands alone. */
    public static String rewrite(String question, String previous) {
        if (previous == null || previous.isBlank()) return question;
        List<String> now = Text.tokens(question);
        boolean shortQ = now.size() <= 1;
        boolean refers = FOLLOW_UP.matcher(question).find() && now.size() <= 4;
        if (!shortQ && !refers) return question;

        Set<String> nowSet = Set.copyOf(now);
        Set<String> carry = new LinkedHashSet<>();
        for (String w : Text.rawWords(previous)) {
            if (Text.STOP.contains(w) || w.length() < 3) continue;
            if (nowSet.contains(Text.stem(w))) continue;
            carry.add(w);
        }
        if (carry.isEmpty()) return question;
        List<String> words = new ArrayList<>(carry);
        if (words.size() > 6) words = words.subList(0, 6);
        return question.strip() + " (" + String.join(" ", words) + ")";
    }
}
