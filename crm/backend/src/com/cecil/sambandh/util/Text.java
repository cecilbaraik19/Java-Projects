package com.cecil.sambandh.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cleaning and comparing text: names, e-mails and phone numbers.
 * Used by the duplicate finder and by the input checks.
 */
public final class Text {
    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9\\-]{1,63}(\\.[A-Za-z0-9\\-]{1,63})+$");
    private static final Set<String> TITLES = Set.of("mr", "mrs", "ms", "miss", "dr", "shri", "sri", "smt", "kumari", "prof", "er");
    private static final Set<String> COMPANY_WORDS = Set.of("pvt", "private", "ltd", "limited", "llp", "inc", "co", "company", "the", "and");

    private Text() { }

    public static boolean blank(String s) { return s == null || s.isBlank(); }

    /** Trims, squeezes runs of spaces into one, and removes control characters. null stays null. */
    public static String clean(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replaceAll("[ \\t]+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    public static String clean(String s, int maxLength) {
        String t = clean(s);
        if (t != null && t.length() > maxLength) t = t.substring(0, maxLength);
        return t;
    }

    public static boolean validEmail(String email) { return email != null && email.length() <= 254 && EMAIL.matcher(email).matches(); }

    /** E-mail for comparing: lower case, no spaces. */
    public static String emailKey(String email) {
        if (blank(email)) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Indian phone numbers are written many ways: "98351 23456", "+91-9835123456", "09835123456".
     * They all become "+919835123456". Returns null if it is not a valid 10-digit mobile / landline.
     */
    public static String normalizePhone(String phone) {
        if (blank(phone)) return null;
        String d = phone.replaceAll("[^0-9]", "");
        if (d.length() == 12 && d.startsWith("91")) d = d.substring(2);
        else if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        if (d.length() != 10 || d.startsWith("0")) return null;
        return "+91" + d;
    }

    /** Lower case, accents removed, only letters/digits/spaces, titles like "Mr" and "Dr" dropped. */
    public static String nameKey(String name) {
        if (blank(name)) return "";
        String t = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        StringBuilder sb = new StringBuilder();
        for (String w : t.split(" ")) {
            if (w.isEmpty() || TITLES.contains(w)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
    }

    /** Company for comparing: "Hazaribagh Steel Pvt. Ltd." -> "hazaribagh steel". */
    public static String companyKey(String company) {
        if (blank(company)) return "";
        StringBuilder sb = new StringBuilder();
        for (String w : nameKey(company).split(" ")) {
            if (w.isEmpty() || COMPANY_WORDS.contains(w)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
    }

    /** Edit distance: how many single-letter inserts, deletes or changes turn a into b. */
    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /**
     * Jaro-Winkler similarity between 0 (nothing alike) and 1 (same).
     * Good for short names: it forgives swapped letters and rewards a shared start ("Ankit" / "Ankita" = 0.97).
     */
    public static double jaroWinkler(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int range = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] aMatch = new boolean[a.length()];
        boolean[] bMatch = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int from = Math.max(0, i - range), to = Math.min(i + range + 1, b.length());
            for (int j = from; j < to; j++) {
                if (bMatch[j] || a.charAt(i) != b.charAt(j)) continue;
                aMatch[i] = true; bMatch[j] = true; matches++;
                break;
            }
        }
        if (matches == 0) return 0.0;
        int transpositions = 0, k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (!aMatch[i]) continue;
            while (!bMatch[k]) k++;
            if (a.charAt(i) != b.charAt(k)) transpositions++;
            k++;
        }
        double m = matches;
        double jaro = (m / a.length() + m / b.length() + (m - transpositions / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(a.length(), b.length())); i++) {
            if (a.charAt(i) == b.charAt(i)) prefix++; else break;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    /**
     * Name similarity, word by word, so the word order does not matter ("Kumar Rahul" = "Rahul Kumar").
     * Words are paired up one-to-one, most similar pair first (Jaro-Winkler); a word left without a partner scores 0.
     * The score is the average over the longer name. So "Sneha Sinha" / "Sneha Jha" scores low (the surnames differ),
     * while "Rani Hussain" / "Rani Hussai" (a typo) scores high.
     */
    public static double nameSimilarity(String a, String b) {
        String x = nameKey(a), y = nameKey(b);
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        if (x.equals(y)) return 1.0;
        String[] wx = x.split(" "), wy = y.split(" ");
        boolean[] usedX = new boolean[wx.length], usedY = new boolean[wy.length];
        double total = 0;
        for (int round = 0; round < Math.min(wx.length, wy.length); round++) {
            double best = -1;
            int bi = -1, bj = -1;
            for (int i = 0; i < wx.length; i++) {
                if (usedX[i]) continue;
                for (int j = 0; j < wy.length; j++) {
                    if (usedY[j]) continue;
                    double sim = jaroWinkler(wx[i], wy[j]);
                    if (sim > best) { best = sim; bi = i; bj = j; }
                }
            }
            usedX[bi] = true; usedY[bj] = true;
            total += best;
        }
        return total / Math.max(wx.length, wy.length);
    }

    /** Masks an e-mail for people who may not see it: "ankit.verma@gmail.com" -> "an•••@gmail.com". */
    public static String maskEmail(String email) {
        if (blank(email) || !email.contains("@")) return email;
        int at = email.indexOf('@');
        String user = email.substring(0, at);
        return user.substring(0, Math.min(2, user.length())) + "•••" + email.substring(at);
    }

    /** Masks a phone number: "+919835123456" -> "+91 •••••• 3456". */
    public static String maskPhone(String phone) {
        if (blank(phone) || phone.length() < 4) return phone;
        return "+91 •••••• " + phone.substring(phone.length() - 4);
    }
}
