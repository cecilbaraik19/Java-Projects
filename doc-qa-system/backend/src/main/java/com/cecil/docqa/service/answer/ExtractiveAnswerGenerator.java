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

    private record Sentence(int contextNumber, int order, String text, List<String> tokens) {}

    /**
     * Offline summary for "tell me everything" type questions.
     * For each document it picks the sentences that use the document's most common
     * (important) words, and shows them in reading order.
     *
     * @param byDoc all chunks of each document, keyed by file name
     */
    public Answer summarize(Map<String, List<Context>> byDoc, int sentencesPerDoc) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Context>> doc : byDoc.entrySet()) {
            List<Sentence> picked = topSentences(doc.getValue(), sentencesPerDoc);
            if (picked.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append("\n\n");
            if (byDoc.size() > 1) sb.append(doc.getKey()).append(":\n");
            StringBuilder lines = new StringBuilder();
            for (Sentence s : picked) {
                lines.append("\u2022 ").append(s.text()).append(" [").append(s.contextNumber()).append("]\n");
            }
            sb.append(lines.toString().trim());
        }
        if (sb.isEmpty()) return new Answer("These documents don't have enough text to summarize.", "offline");
        return new Answer("Here are the main points:\n\n" + sb.toString().trim(), "offline");
    }

    private List<Sentence> topSentences(List<Context> contexts, int count) {
        // 1. split into sentences, skipping the overlap that neighbouring chunks share
        List<Sentence> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int order = 0;
        for (Context ctx : contexts) {
            for (String raw : SENTENCE_END.split(separateHeadings(ctx.text()))) {
                String s = raw.replaceAll("\\s+", " ").trim();
                if (!seen.add(s.toLowerCase(Locale.ROOT))) continue;
                List<String> tokens = Tokenizer.tokens(s);
                int words = s.split(" ").length;
                if (words < 6 || words > 60 || tokens.size() < 3) continue;
                all.add(new Sentence(ctx.number(), order++, s, tokens));
            }
        }
        if (all.isEmpty()) return List.of();
        // headings and titles have no full stop — leave them out when real sentences exist
        List<Sentence> full = all.stream().filter(s -> s.text().matches(".*[.!?]$")).toList();
        if (full.size() >= count) all = new ArrayList<>(full);

        // 2. how often each word appears in the whole document
        Map<String, Integer> freq = new HashMap<>();
        for (Sentence s : all) for (String t : new HashSet<>(s.tokens())) freq.merge(t, 1, Integer::sum);
        double maxFreq = Collections.max(freq.values());

        // 3. score = average importance of the sentence's words, small bonus for the opening sentences
        Map<Sentence, Double> score = new HashMap<>();
        for (Sentence s : all) {
            double sum = 0;
            for (String t : new HashSet<>(s.tokens())) sum += freq.get(t) / maxFreq;
            double value = sum / Math.sqrt(s.tokens().size());
            if (s.order() < 2) value *= 1.25;
            score.put(s, value);
        }

        // 4. best sentences, but spread over the document, then back into reading order
        List<Sentence> ranked = new ArrayList<>(all);
        ranked.sort(Comparator.comparingDouble((Sentence s) -> score.get(s)).reversed());
        List<Sentence> picked = new ArrayList<>();
        Map<Integer, Integer> perChunk = new HashMap<>();
        // limit how many come from one chunk, so the summary covers the whole document
        int perChunkLimit = Math.max(2, (int) Math.ceil((double) count / contexts.size()) + 1);
        for (Sentence s : ranked) {
            if (picked.size() >= count) break;
            if (perChunk.getOrDefault(s.contextNumber(), 0) >= perChunkLimit) continue;
            picked.add(s);
            perChunk.merge(s.contextNumber(), 1, Integer::sum);
        }
        picked.sort(Comparator.comparingInt(Sentence::order));
        return picked;
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
