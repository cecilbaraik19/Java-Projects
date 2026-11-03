package com.cecil.mail.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls useful details out of an email with regular expressions:
 * dates, times, money amounts, order / invoice / ticket numbers, emails, phones and the sender's name.
 * The reply generator puts these details into the suggested reply so it feels personal.
 */
public class EntityExtractor {

    public record Entities(List<String> dates, List<String> times, List<String> amounts,
                           List<String> references, List<String> emails, List<String> phones,
                           String senderName) { }

    private static final String MONTHS = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";

    private static final Pattern DATE = Pattern.compile(
            "\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}"
            + "|\\d{1,2}(?:st|nd|rd|th)?\\s+" + MONTHS + "(?:\\s+\\d{4})?"
            + "|" + MONTHS + "\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+\\d{4})?"
            + "|(?:next\\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)"
            + "|tomorrow|today|next week|this week|end of the week)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TIME = Pattern.compile(
            "\\b(\\d{1,2}(?::\\d{2})?\\s?(?:am|pm|a\\.m\\.|p\\.m\\.)|\\d{1,2}:\\d{2}\\s?(?:hrs)?|noon|afternoon|morning|evening)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AMOUNT = Pattern.compile(
            "((?:₹|rs\\.?|inr|\\$|usd|€)\\s?\\d[\\d,]*(?:\\.\\d{1,2})?|\\d[\\d,]*(?:\\.\\d{1,2})?\\s?(?:rupees|dollars|inr|rs))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern REFERENCE = Pattern.compile(
            "(#\\s?\\d{3,}|\\b(?:ord|inv|tkt|ticket|order|invoice|ref|txn)[\\s:#-]*(?:no\\.?|number)?[\\s:#-]*[a-z]*-?\\d{3,}\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.+-]+@[\\w-]+\\.[\\w.]+\\b");

    private static final Pattern PHONE = Pattern.compile("(\\+?\\d{1,3}[\\s-]?)?\\b\\d{10}\\b|\\b\\d{3,5}[\\s-]\\d{3,4}[\\s-]\\d{3,4}\\b");

    private static final Pattern SIGN_OFF = Pattern.compile(
            "(?im)^\\s*(?:regards|best regards|warm regards|kind regards|thanks|thank you|sincerely|yours sincerely|yours truly|cheers|best)\\s*,?\\s*$\\s*^\\s*([A-Z][a-zA-Z.]+(?:\\s+[A-Z][a-zA-Z.]+){0,2})\\s*$");

    public Entities extract(String body) {
        String t = body == null ? "" : body;
        return new Entities(
                find(DATE, t), find(TIME, t), find(AMOUNT, t), find(REFERENCE, t),
                find(EMAIL, t), find(PHONE, t), findSignature(t));
    }

    private List<String> find(Pattern p, String text) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Matcher m = p.matcher(text);
        while (m.find()) found.add(m.group().trim());
        return new ArrayList<>(found);
    }

    private String findSignature(String text) {
        Matcher m = SIGN_OFF.matcher(text);
        String name = null;
        while (m.find()) name = m.group(1).trim();   // keep the last sign-off in the email
        return name;
    }
}
