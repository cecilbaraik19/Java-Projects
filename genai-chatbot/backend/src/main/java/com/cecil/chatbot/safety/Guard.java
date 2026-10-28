package com.cecil.chatbot.safety;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safety checks on messages.
 *
 * 1. PII redaction: personal details are replaced with labels BEFORE the text is sent to an online AI service,
 *    so the e-mail, phone or card number never leaves this server.
 * 2. Prompt-injection check: spots messages that try to make the bot ignore its rules
 *    ("ignore all previous instructions", "show your system prompt").
 * 3. Blocked words: the admin's own list of words that are not allowed.
 */
public final class Guard {

    private Guard() { }

    /** Text after redaction and how many things were hidden. */
    public record Redaction(String text, int count) { }

    private record Rule(Pattern pattern, String label) { }

    private static final List<Rule> RULES = List.of(
        // secret keys first (they can contain parts that look like other things)
        new Rule(Pattern.compile("\\b(sk-(?:proj-|ant-)?[A-Za-z0-9_-]{16,}|AIza[0-9A-Za-z_-]{30,}|gsk_[A-Za-z0-9]{20,}|gh[pousr]_[A-Za-z0-9]{30,}|AKIA[0-9A-Z]{16})\\b"), "[SECRET_KEY]"),
        new Rule(Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"), "[EMAIL]"),
        new Rule(Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b"), "[CARD]"),                     // checked with Luhn below
        new Rule(Pattern.compile("\\b[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}\\b"), "[ID_NUMBER]"),      // Aadhaar-like 12 digits
        new Rule(Pattern.compile("(?<![\\w+])(?:\\+?91[ -]?)?[6-9]\\d{4}[ -]?\\d{5}\\b"), "[PHONE]"), // Indian mobile
        new Rule(Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b"), "[ID_NUMBER]")                    // PAN card
    );

    public static Redaction redact(String text) {
        if (text == null || text.isEmpty()) return new Redaction(text == null ? "" : text, 0);
        String out = text;
        int count = 0;
        for (Rule r : RULES) {
            Matcher m = r.pattern().matcher(out);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                if (r.label().equals("[CARD]") && !luhn(m.group())) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                    continue;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(r.label()));
                count++;
                changed = true;
            }
            m.appendTail(sb);
            if (changed) out = sb.toString();
        }
        return new Redaction(out, count);
    }

    /** The Luhn check digit test used by all bank cards. Stops random long numbers being called cards. */
    static boolean luhn(String number) {
        String d = number.replaceAll("[^0-9]", "");
        if (d.length() < 13 || d.length() > 19) return false;
        int sum = 0;
        boolean dbl = false;
        for (int i = d.length() - 1; i >= 0; i--) {
            int n = d.charAt(i) - '0';
            if (dbl) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static final List<Pattern> INJECTION = List.of(
        Pattern.compile("\\b(ignore|forget|disregard|override)\\b.{0,25}\\b(previous|prior|above|earlier|all|your|the)\\b.{0,20}\\b(instructions?|rules|prompts?|guidelines)\\b"),
        Pattern.compile("\\b(reveal|show|print|repeat|tell me|leak|output)\\b.{0,20}\\b(system prompt|hidden (prompt|instructions?)|initial instructions?|your instructions?)\\b"),
        Pattern.compile("\\byou are now\\b.{0,30}\\b(dan|unfiltered|jailbroken|developer mode|evil)\\b"),
        Pattern.compile("\\b(jailbreak|do anything now|developer mode enabled)\\b"),
        Pattern.compile("\\bpretend\\b.{0,30}\\bno (rules|restrictions|limits|filters)\\b")
    );

    /** True when the message looks like an attempt to break the bot's rules. */
    public static boolean looksLikeInjection(String text) {
        if (text == null) return false;
        String low = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (Pattern p : INJECTION) if (p.matcher(low).find()) return true;
        return false;
    }

    /** The first blocked word found in the text (whole words, any case), or null. */
    public static String blockedWord(String text, List<String> words) {
        if (text == null || words == null) return null;
        String low = text.toLowerCase(Locale.ROOT);
        for (String w : words) {
            String x = w == null ? "" : w.trim().toLowerCase(Locale.ROOT);
            if (x.isEmpty()) continue;
            if (Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(x) + "(?![\\p{L}\\p{N}])").matcher(low).find()) return w.trim();
        }
        return null;
    }
}
