package com.cecil.docqa.service.answer;

import com.cecil.docqa.service.Tokenizer;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Offline answerer — no API key needed.
 * It picks the sentences from the best chunks that share the most words with the question.
 */
@Component
public class ExtractiveAnswerGenerator implements AnswerGenerator {

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\n{2,}|\\n(?=[-•*\\d])");
    private static final int MAX_SENTENCES = 3;

    private record Scored(int contextNumber, int contextRank, int position, String sentence, double score) {}

    @Override
    public Answer generate(String question, List<Context> contexts) {
        Set<String> queryTerms = new HashSet<>(Tokenizer.tokens(question));
        if (queryTerms.isEmpty() || contexts.isEmpty()) {
            return new Answer("I couldn't find anything about that in your documents.", "offline");
        }

        List<Scored> scored = new ArrayList<>();
        for (int rank = 0; rank < contexts.size(); rank++) {
            Context ctx = contexts.get(rank);
            String[] sentences = SENTENCE_END.split(separateHeadings(ctx.text()));
            for (int i = 0; i < sentences.length; i++) {
                String s = sentences[i].replaceAll("\\s+", " ").trim();
                if (s.length() < 15) continue;
                Set<String> words = new HashSet<>(Tokenizer.tokens(s));
                long hits = queryTerms.stream().filter(words::contains).count();
                if (hits == 0) continue;
                double coverage = (double) hits / queryTerms.size();
                // favour sentences from higher-ranked chunks, and not too long
                double score = coverage + 0.15 / (rank + 1) - Math.min(0.1, s.length() / 4000.0);
                scored.add(new Scored(ctx.number(), rank, i, s, score));
            }
        }

        if (scored.isEmpty()) {
            return new Answer("I couldn't find anything about that in your documents.", "offline");
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Scored> picked = new ArrayList<>(scored.subList(0, Math.min(MAX_SENTENCES, scored.size())));
        // keep only sentences that are reasonably close to the best one
        double best = picked.get(0).score();
        picked.removeIf(s -> s.score() < best * 0.5);
        // show them in reading order
        picked.sort(Comparator.comparingInt(Scored::contextRank).thenComparingInt(Scored::position));

        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (Scored s : picked) {
            if (!seen.add(s.sentence())) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(s.sentence()).append(" [").append(s.contextNumber()).append(']');
        }
        return new Answer(sb.toString(), "offline");
    }

    /**
     * A short line with no full stop (like "2. Library") is a heading.
     * Put a blank line after it so it is not glued to the next sentence.
     */
    static String separateHeadings(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String t = line.strip();
            out.append(line);
            boolean heading = !t.isEmpty() && t.length() <= 40 && !t.matches(".*[.!?:;,]$")
                    || t.matches("^\\d+(\\.\\d+)*\\.?\\s+\\S.{0,40}$") && !t.matches(".*[.!?]$");
            out.append(heading ? "\n\n" : "\n");
        }
        return out.toString();
    }
}
