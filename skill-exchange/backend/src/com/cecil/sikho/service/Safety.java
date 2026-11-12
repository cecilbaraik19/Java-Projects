package com.cecil.sikho.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks chat messages to keep members safe:
 *
 *  1. Phone numbers and e-mail addresses are hidden until the exchange is accepted. Until then you only know
 *     a neighbour's first name and locality - this stops people collecting numbers from strangers.
 *  2. Money talk ("pay 500 rupees", "UPI", "fees") gets a friendly warning: this is a time bank, lessons are paid
 *     with time credits only. Asking for money is a common way scams start.
 *  3. Moving a first meeting to a private home gets a tip to meet in a public place first.
 */
public final class Safety {
    private Safety() { }

    static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?91[\\s-]?)?[6-9](?:[\\s-]?\\d){9}(?!\\d)");
    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    static final Pattern MONEY = Pattern.compile("(₹\\s?\\d|\\brs\\.?\\s?\\d|\\b\\d+\\s?(rs|rupees?|inr)\\b|\\brupees?\\b|\\bupi\\b|\\bpaytm\\b|"
            + "\\bgpay\\b|\\bphonepe\\b|\\bbank transfer\\b|\\bfees?\\b|\\bcharge(s|d)?\\b|\\bpay (me|you)\\b|\\badvance\\b|\\bcash\\b)");
    static final List<String> PRIVATE_PLACE = List.of("my room", "my flat", "come home", "my house", "come to my place", "alone");

    /** The result: the text to store (maybe with hidden contact details) and a flag for the website (or null). */
    public record Checked(String text, String flag) { }

    public static Checked check(String text, boolean accepted) {
        String out = text;
        String flag = null;
        if (!accepted) {
            Matcher m = PHONE.matcher(out);
            if (m.find()) { out = PHONE.matcher(out).replaceAll("[number hidden until accepted]"); flag = "CONTACT_HIDDEN"; }
            Matcher e = EMAIL.matcher(out);
            if (e.find()) { out = EMAIL.matcher(out).replaceAll("[e-mail hidden until accepted]"); flag = "CONTACT_HIDDEN"; }
        }
        String lower = out.toLowerCase(Locale.ROOT);
        if (MONEY.matcher(lower).find()) flag = "MONEY";
        else if (flag == null) for (String p : PRIVATE_PLACE) if (lower.contains(p)) { flag = "PRIVATE_PLACE"; break; }
        return new Checked(out, flag);
    }

    public static String advice(String flag) {
        if (flag == null) return null;
        return switch (flag) {
            case "MONEY" -> "Reminder: Sikho-Sikhao lessons are paid only with time credits. Never send money for a lesson. Report anyone who asks.";
            case "CONTACT_HIDDEN" -> "Phone numbers and e-mails are hidden until the request is accepted. Then both of you can see each other's number.";
            case "PRIVATE_PLACE" -> "Tip: for a first meeting, choose a public place (library, community hall, park or café).";
            default -> null;
        };
    }
}
