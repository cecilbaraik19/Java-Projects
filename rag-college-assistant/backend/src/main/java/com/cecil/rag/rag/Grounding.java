package com.cecil.rag.rag;

import com.cecil.rag.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A safety check on the answer ("is it grounded?").
 * Every sentence of the answer must be supported by the sources:
 *   - at least 60 % of its important words appear in one source, and
 *   - every number or amount in it also appears in the sources.
 * Sentences that fail are listed, so a made-up claim ("hallucination") is easy to spot.
 */
public final class Grounding {
    private Grounding() {}

    private static final Pattern CITE = Pattern.compile("\\[\\d+]");
    private static final Pattern DIGITS = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    public static void check(Answer a, List<String> sourceTexts) {
        List<Set<String>> srcTokens = new ArrayList<>();
        StringBuilder allText = new StringBuilder();
        for (String s : sourceTexts) {
            srcTokens.add(Text.tokenSet(s));
            allText.append(' ').append(s.replace(",", ""));
        }
        String all = allText.toString();
        int total = 0, ok = 0;
        a.unsupported.clear();
        for (String sentence : Text.sentences(a.text)) {
            String plain = CITE.matcher(sentence).replaceAll("").strip();
            Set<String> toks = Text.tokenSet(plain);
            if (toks.size() < 3) continue; // too short to judge ("Yes.")
            total++;
            double best = 0;
            for (Set<String> st : srcTokens) {
                int hit = 0;
                for (String t : toks) if (st.contains(t)) hit++;
                best = Math.max(best, (double) hit / toks.size());
            }
            boolean numbersOk = true;
            Matcher m = DIGITS.matcher(plain);
            while (m.find()) if (!all.contains(m.group().replace(",", ""))) numbersOk = false;
            if (best >= 0.6 && numbersOk) ok++;
            else a.unsupported.add(Text.clip(plain, 160));
        }
        a.grounded = total == 0 ? 1 : (double) ok / total;
    }
}
