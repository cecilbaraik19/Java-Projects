package com.cecil.mail.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grades a reply draft for professionalism (0-100) and lists what to fix.
 * Starts at 100 and removes points for slang, shouting, too many "!", missing greeting
 * or sign-off, very long sentences and rude words. Also measures readability
 * using the Flesch Reading Ease formula.
 */
public class ToneChecker {

    public record Issue(String type, String message, String fix) { }

    public record Report(int score, String grade, String formality, double readability,
                         String readingLevel, int words, int sentences, List<Issue> issues) { }

    private static final Map<String, String> SLANG = Map.ofEntries(
            Map.entry("u", "you"), Map.entry("ur", "your"), Map.entry("pls", "please"), Map.entry("plz", "please"),
            Map.entry("thx", "thank you"), Map.entry("gonna", "going to"), Map.entry("wanna", "want to"),
            Map.entry("gotta", "have to"), Map.entry("asap", "as soon as possible"), Map.entry("btw", "by the way"),
            Map.entry("lol", "(remove)"), Map.entry("ok", "okay / certainly"), Map.entry("yeah", "yes"),
            Map.entry("dunno", "do not know"), Map.entry("coz", "because"), Map.entry("cuz", "because"),
            Map.entry("hey", "Hello"), Map.entry("bro", "(remove)"), Map.entry("idk", "I am not sure"),
            Map.entry("fyi", "for your information"), Map.entry("tmrw", "tomorrow"), Map.entry("msg", "message"));

    private static final List<String> HARSH = List.of("stupid", "useless", "nonsense", "rubbish", "idiot",
            "your fault", "you never", "you always", "ridiculous", "obviously", "whatever", "shut up");

    private static final Pattern GREETING = Pattern.compile("(?i)^\\s*(dear|hi|hello|good (morning|afternoon|evening)|greetings)\\b");
    private static final Pattern SIGNOFF = Pattern.compile("(?i)(regards|sincerely|best wishes|thanks|thank you|cheers|best|warmly)\\s*,?\\s*\\n");

    public Report check(String draft) {
        String text = draft == null ? "" : draft.trim();
        List<Issue> issues = new ArrayList<>();
        int score = 100;

        if (text.isEmpty()) {
            issues.add(new Issue("EMPTY", "The draft is empty.", "Pick a suggestion or write your reply."));
            return new Report(0, "F", "-", 0, "-", 0, 0, issues);
        }

        if (!GREETING.matcher(text).find()) {
            score -= 10;
            issues.add(new Issue("GREETING", "No greeting at the start.", "Begin with \"Dear <Name>,\" or \"Hi <Name>,\"."));
        }
        if (!SIGNOFF.matcher(text + "\n").find()) {
            score -= 10;
            issues.add(new Issue("SIGN_OFF", "No sign-off at the end.", "End with \"Regards,\" and your name."));
        }

        List<String> words = TextTokenizer.words(text);
        for (String w : words) {
            if (SLANG.containsKey(w)) {
                score -= 6;
                issues.add(new Issue("SLANG", "Informal word: \"" + w + "\".", "Use \"" + SLANG.get(w) + "\" instead."));
            }
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String h : HARSH) {
            if (lower.contains(h)) {
                score -= 15;
                issues.add(new Issue("HARSH", "Sounds rude or blaming: \"" + h + "\".", "Rephrase calmly and focus on the solution."));
            }
        }

        long bangs = text.chars().filter(c -> c == '!').count();
        if (bangs > 2) {
            score -= 8;
            issues.add(new Issue("EXCLAMATION", bangs + " exclamation marks.", "Keep it to one or none in professional mail."));
        }

        Matcher caps = Pattern.compile("\\b[A-Z]{4,}\\b").matcher(text);
        int capsCount = 0;
        while (caps.find()) capsCount++;
        if (capsCount >= 2) {
            score -= 10;
            issues.add(new Issue("CAPS", "Words in ALL CAPS feel like shouting.", "Use normal capitalisation; use bold if you must stress."));
        }

        String[] sentences = text.split("(?<=[.!?])\\s+");
        int sentenceCount = 0;
        for (String s : sentences) {
            int len = TextTokenizer.words(s).size();
            if (len == 0) continue;
            sentenceCount++;
            if (len > 35) {
                score -= 5;
                issues.add(new Issue("LONG_SENTENCE", "A sentence has " + len + " words.", "Split long sentences into two shorter ones."));
            }
        }
        if (words.size() > 220) {
            score -= 5;
            issues.add(new Issue("LENGTH", "The reply is quite long (" + words.size() + " words).", "Busy readers prefer under 150 words."));
        }
        if (text.contains("[Your Name]")) {
            score -= 5;
            issues.add(new Issue("PLACEHOLDER", "\"[Your Name]\" is still in the draft.", "Add your name in Settings or edit it in the draft."));
        }

        double flesch = flesch(words, Math.max(1, sentenceCount));
        String formality = formality(words);
        score = Math.max(0, Math.min(100, score));
        String grade = score >= 90 ? "A" : score >= 75 ? "B" : score >= 60 ? "C" : score >= 40 ? "D" : "F";
        String level = flesch >= 70 ? "Easy" : flesch >= 50 ? "Standard" : flesch >= 30 ? "Fairly difficult" : "Difficult";
        return new Report(score, grade, formality, Math.round(flesch * 10) / 10.0, level, words.size(), sentenceCount, issues);
    }

    /** Flesch Reading Ease = 206.835 - 1.015 * (words / sentences) - 84.6 * (syllables / words). */
    private double flesch(List<String> words, int sentences) {
        if (words.isEmpty()) return 0;
        int syll = 0;
        for (String w : words) syll += syllables(w);
        double v = 206.835 - 1.015 * ((double) words.size() / sentences) - 84.6 * ((double) syll / words.size());
        return Math.max(0, Math.min(100, v));
    }

    private int syllables(String w) {
        String s = w.replaceAll("e$", "");
        int count = 0;
        boolean prevVowel = false;
        for (char ch : s.toCharArray()) {
            boolean vowel = "aeiouy".indexOf(ch) >= 0;
            if (vowel && !prevVowel) count++;
            prevVowel = vowel;
        }
        return Math.max(1, count);
    }

    private String formality(List<String> words) {
        int formal = 0, casual = 0;
        for (String w : words) {
            if (List.of("kindly", "sincerely", "regarding", "therefore", "furthermore", "please", "appreciate", "assist", "apologise", "apologize", "dear").contains(w)) formal++;
            if (SLANG.containsKey(w) || List.of("awesome", "cool", "stuff", "guys", "super", "folks").contains(w)) casual++;
        }
        if (formal > casual + 1) return "Formal";
        if (casual > formal) return "Casual";
        return "Neutral";
    }
}
