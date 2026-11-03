package com.cecil.mail.ai;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One entry point for all the AI parts. The services call this class.
 *
 *   analyze()  -> intent (hybrid: Naive Bayes + cue words) + sentiment + urgency + details + keywords
 *   suggest()  -> three ready replies (Formal, Friendly, Brief), ranked by how well they fit
 *   learn()    -> adds a confirmed example so the model keeps improving
 *
 * Pure Java, no web code inside, so it is easy to test.
 */
public class EmailAiEngine {

    /** How the intent is decided. HYBRID is used by the app; the others exist for the Test bench. */
    public enum Mode { NAIVE_BAYES, CUES, HYBRID }

    public static final double NB_WEIGHT = 0.65;
    public static final double CUE_WEIGHT = 0.35;
    /** Below this, the app tells the user it is not sure and asks them to check the type. */
    public static final double UNSURE_BELOW = 0.40;

    public record IntentScore(String intent, String label, double probability) { }

    public record Explanation(List<String> cueWords, List<IntentScore> naiveBayes, List<IntentScore> cues) { }

    public record Analysis(String intent, String intentLabel, String intentDescription, double confidence,
                           boolean unsure, List<IntentScore> topIntents, Explanation why,
                           SentimentAnalyzer.Result sentiment, UrgencyDetector.Result urgency,
                           EntityExtractor.Entities entities, List<String> keywords, String recommendedStance) { }

    public record Suggestion(String tone, String stance, String subject, String body,
                             int fit, int professionalism, int words) { }

    private final NaiveBayesClassifier classifier = new NaiveBayesClassifier();
    private final SentimentAnalyzer sentimentAnalyzer = new SentimentAnalyzer();
    private final UrgencyDetector urgencyDetector = new UrgencyDetector();
    private final EntityExtractor entityExtractor = new EntityExtractor();
    private final ReplyGenerator replyGenerator = new ReplyGenerator();
    private final ToneChecker toneChecker = new ToneChecker();
    private int learnedCount = 0;

    public EmailAiEngine() {
        TrainingData.loadInto(classifier);
    }

    // ------------------------------------------------------------------ intent

    /** Probability for every intent, highest first. */
    public Map<EmailIntent, Double> classify(String subject, String body, Mode mode) {
        String text = join(subject, body);
        Map<EmailIntent, Double> nb = new EnumMap<>(EmailIntent.class);
        classifier.predict(text).forEach((k, v) -> nb.put(EmailIntent.valueOf(k), v));
        Map<EmailIntent, Double> cues = IntentCues.score(text);
        boolean anyCue = cues.values().stream().anyMatch(v -> v > 0);

        Map<EmailIntent, Double> out = new EnumMap<>(EmailIntent.class);
        for (EmailIntent i : EmailIntent.values()) {
            double n = nb.getOrDefault(i, 0.0);
            double c = cues.getOrDefault(i, 0.0);
            double v = switch (mode) {
                case NAIVE_BAYES -> n;
                case CUES -> anyCue ? c : 1.0 / EmailIntent.values().length;
                case HYBRID -> anyCue ? NB_WEIGHT * n + CUE_WEIGHT * c : n;
            };
            out.put(i, v);
        }
        return sortDesc(out);
    }

    public Analysis analyze(String subject, String body) {
        String text = join(subject, body);
        Map<EmailIntent, Double> probs = classify(subject, body, Mode.HYBRID);
        List<IntentScore> top = toScores(probs, 3);
        EmailIntent best = EmailIntent.valueOf(top.get(0).intent());
        double conf = top.get(0).probability();

        Explanation why = new Explanation(IntentCues.matched(text, best),
                toScores(classify(subject, body, Mode.NAIVE_BAYES), 3),
                toScores(classify(subject, body, Mode.CUES), 3));

        return new Analysis(best.name(), best.getLabel(), best.getDescription(), conf, conf < UNSURE_BELOW,
                top, why, sentimentAnalyzer.analyze(body), urgencyDetector.detect(subject, body),
                entityExtractor.extract(body), keywords(text), ReplyGenerator.recommendedStance(best).name());
    }

    // ------------------------------------------------------------------ replies

    /**
     * @param intentOverride use this intent instead of the predicted one (null = predicted)
     * @param stance         POSITIVE, NEED_INFO or DECLINE (null = the AI's recommendation)
     */
    public List<Suggestion> suggest(String senderName, String subject, String body, String intentOverride,
                                    String stance, String myName, String myRole) {
        Analysis a = analyze(subject, body);
        EmailIntent intent = EmailIntent.valueOf(blank(intentOverride) ? a.intent() : intentOverride);
        ReplyGenerator.Stance st = ReplyGenerator.Stance.valueOf(blank(stance) ? a.recommendedStance() : stance);

        String name = !blank(senderName) ? senderName.trim() : a.entities().senderName();
        ReplyGenerator.Context ctx = new ReplyGenerator.Context(intent, st, name, subject, a.entities(),
                a.sentiment().label(), a.urgency().level(), myName, myRole);

        List<Suggestion> list = new ArrayList<>();
        for (ReplyGenerator.Tone tone : ReplyGenerator.Tone.values()) {
            String reply = replyGenerator.generate(ctx, tone);
            ToneChecker.Report r = toneChecker.check(reply);
            list.add(new Suggestion(tone.name(), st.name(), replyGenerator.replySubject(subject), reply,
                    fit(a, intent, tone), r.score(), r.words()));
        }
        list.sort((x, y) -> Integer.compare(y.fit(), x.fit()));
        return list;
    }

    public ToneChecker.Report checkTone(String draft) { return toneChecker.check(draft); }

    // ------------------------------------------------------------------ learning

    /** Online learning: a confirmed (email, intent) pair becomes new training data. */
    public synchronized void learn(String subject, String body, String intent) {
        EmailIntent.valueOf(intent);                    // refuse unknown labels
        classifier.train(join(subject, body), intent);
        learnedCount++;
    }

    public synchronized Map<String, Object> modelInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("algorithm", "Hybrid: Naive Bayes (words + word pairs) 65% + cue words 35%");
        m.put("trainingExamples", classifier.getTrainingSize());
        m.put("builtInExamples", classifier.getTrainingSize() - learnedCount);
        m.put("learnedExamples", learnedCount);
        m.put("vocabularySize", classifier.getVocabularySize());
        m.put("intents", EmailIntent.values().length);
        return m;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * How well a tone fits this email (0-100).
     * Upset or urgent senders -> Formal ranks higher. Thank-you notes -> Friendly. Short meeting/follow-up -> Brief.
     * It also falls when we are not sure about the intent.
     */
    private int fit(Analysis a, EmailIntent intent, ReplyGenerator.Tone tone) {
        double intentConf = intent.name().equals(a.intent()) ? Math.min(1, a.confidence() + 0.3) : 0.85;
        double fit = 0.6;
        boolean negative = "NEGATIVE".equals(a.sentiment().label());
        boolean urgent = "HIGH".equals(a.urgency().level());
        switch (tone) {
            case FORMAL -> fit += (negative ? 0.3 : 0.1) + (urgent ? 0.05 : 0)
                    + (intent == EmailIntent.JOB_APPLICATION || intent == EmailIntent.PAYMENT_INVOICE ? 0.15 : 0);
            case FRIENDLY -> fit += ("POSITIVE".equals(a.sentiment().label()) ? 0.3 : 0.05)
                    + (intent == EmailIntent.THANK_YOU ? 0.2 : 0) - (negative ? 0.15 : 0);
            case BRIEF -> fit += (intent == EmailIntent.FOLLOW_UP || intent == EmailIntent.MEETING_REQUEST ? 0.2 : 0)
                    + (urgent ? 0.1 : 0) - (negative ? 0.2 : 0);
        }
        double score = 100 * (0.45 * intentConf + 0.55 * Math.min(1, fit));
        return (int) Math.max(35, Math.min(98, Math.round(score)));
    }

    /** Most frequent meaningful words. */
    private List<String> keywords(String text) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String w : TextTokenizer.words(text)) {
            if (w.length() < 4 || TextTokenizer.tokenize(w).isEmpty()) continue;
            freq.merge(w, 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        freq.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(6).forEach(e -> out.add(e.getKey()));
        return out;
    }

    private static List<IntentScore> toScores(Map<EmailIntent, Double> probs, int n) {
        List<IntentScore> out = new ArrayList<>();
        probs.entrySet().stream().limit(n).forEach(e -> out.add(new IntentScore(e.getKey().name(), e.getKey().getLabel(), round(e.getValue()))));
        return out;
    }

    private static Map<EmailIntent, Double> sortDesc(Map<EmailIntent, Double> m) {
        Map<EmailIntent, Double> out = new LinkedHashMap<>();
        m.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static String join(String subject, String body) {
        return (subject == null ? "" : subject + ". ") + (body == null ? "" : body);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static double round(double d) { return Math.round(d * 1000) / 1000.0; }
}
