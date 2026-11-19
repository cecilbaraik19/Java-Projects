package com.cecil.bijak.core;

import com.cecil.bijak.util.Money;
import com.cecil.bijak.util.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank matching: "which customer sent this money, and for which invoices?"
 *
 * A bank line has only a date, an amount and a narration such as
 *     "NEFT CR-HDFC0001234-TATA AUTOCOMP SYSTEMS LTD-KVP 0042"
 * For every customer with open invoices Bijak collects clues and adds up points (at most 100):
 *
 *   invoice number in the narration   "KVP 0042", "KVP/26-27/42", "KVP2627-0042"     +55
 *   GSTIN or PAN of the customer in the narration                                  +30
 *   customer name in the narration    share of the name's real words found, fuzzy   up to +30
 *   amount equals one invoice's balance                                             +30
 *   amount equals the balance minus the customer's TDS                              +28
 *   amount equals the sum of a few open invoices (subset sum, every combination)    +24
 *   amount equals everything the customer owes                                      +26
 *
 * 80+ = "Sure", 50-79 = "Likely", 30-49 = "Weak"; below 30 is not shown. Nothing is booked automatically:
 * a person accepts the suggestion (one click), so a wrong guess never reaches the books.
 */
public final class BankMatch {
    public static final int SURE = 80, LIKELY = 50, SHOW = 30;
    public static final int MAX_SUBSET = 12;   // try every combination of the 12 oldest open invoices: 2^12 = 4096

    /** An open invoice: balance and the part of the balance that is taxable value (TDS is taken on that). */
    public record Open(String invoiceId, String number, String customerId, String dueDate, long balance, long tdsBase) { }

    public record Cust(String id, String name, String gstin, int tdsBp) { }

    public record Suggestion(String customerId, List<String> invoiceIds, List<Long> amounts, long tds, int score,
                             String label, List<String> why) { }

    private static final Set<String> NOISE = Set.of("PVT", "PRIVATE", "LTD", "LIMITED", "LLP", "CO", "COMPANY", "THE", "AND",
            "OF", "INDIA", "M", "S", "MS", "INDUSTRIES", "ENTERPRISES", "CORPORATION", "CORP", "INC", "NEFT", "RTGS",
            "IMPS", "UPI", "CR", "TRF", "TRANSFER", "BY", "FROM", "TO", "PAYMENT", "PYMT", "INV", "INVOICE", "BILL");

    private BankMatch() { }

    /** Suggestions for one bank line, best first. */
    public static List<Suggestion> suggest(long amount, String narration, String reference, String seriesPrefix,
                                           List<Cust> customers, List<Open> open) {
        String text = ((narration == null ? "" : narration) + " " + (reference == null ? "" : reference)).toUpperCase(Locale.ROOT);
        String compact = text.replaceAll("[^A-Z0-9]", "");
        Set<String> words = words(text);
        Set<Long> numbersInText = invoiceNumbers(text, seriesPrefix);

        Map<String, List<Open>> byCustomer = new LinkedHashMap<>();
        for (Open o : open) byCustomer.computeIfAbsent(o.customerId(), k -> new ArrayList<>()).add(o);

        List<Suggestion> out = new ArrayList<>();
        for (Cust c : customers) {
            List<Open> mine = byCustomer.get(c.id());
            if (mine == null || mine.isEmpty()) continue;
            mine.sort(Comparator.comparing(Open::dueDate).thenComparing(Open::number));
            List<String> why = new ArrayList<>();
            int clues = 0;

            // 1. the customer's tax number
            if (c.gstin() != null && !c.gstin().isBlank()) {
                if (compact.contains(c.gstin())) { clues += 30; why.add("GSTIN " + c.gstin() + " in the narration"); }
                else if (compact.contains(Gstin.pan(c.gstin()))) { clues += 30; why.add("PAN " + Gstin.pan(c.gstin()) + " in the narration"); }
            }
            // 2. the name
            double nameShare = nameShare(c.name(), words);
            int namePts = (int) Math.round(nameShare * 30);
            if (namePts >= 10) { clues += namePts; why.add(Math.round(nameShare * 100) + "% of the name \"" + c.name() + "\" found"); }

            // 3. invoice numbers written in the narration
            List<Open> named = new ArrayList<>();
            for (Open o : mine) if (numbersInText.contains(runningNumber(o.number())) || compact.contains(o.number().replaceAll("[^A-Z0-9]", ""))) named.add(o);

            // 4. the amount: which invoices does it pay?
            Pick pick = pickByAmount(amount, mine, c.tdsBp(), named);
            int score = clues;
            if (!named.isEmpty()) {
                score += 55;
                why.add("invoice " + String.join(", ", named.stream().map(Open::number).toList()) + " named in the narration");
            }
            if (pick != null) {
                score += pick.points;
                why.add(pick.why);
            } else {
                if (score < SHOW) continue;   // no amount clue and few other clues: not worth showing
                pick = fifo(amount, named.isEmpty() ? mine : named);
                why.add("amount does not match exactly - paid towards the oldest invoices first");
            }
            score = Math.min(100, score);
            if (score < SHOW) continue;
            String label = score >= SURE ? "Sure" : score >= LIKELY ? "Likely" : "Weak";
            out.add(new Suggestion(c.id(), pick.ids, pick.amounts, pick.tds, score, label, why));
        }
        out.sort(Comparator.comparingInt(Suggestion::score).reversed());
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    private record Pick(List<String> ids, List<Long> amounts, long tds, int points, String why) { }

    /** Tries: one invoice exactly, one invoice minus TDS, all invoices, a combination (with or without TDS). */
    static Pick pickByAmount(long amount, List<Open> mine, int tdsBp, List<Open> named) {
        // first the invoices named in the narration, then everything
        List<List<Open>> pools = new ArrayList<>();
        if (!named.isEmpty()) pools.add(named);
        pools.add(mine);
        for (List<Open> pool : pools) {
            for (Open o : pool) {
                if (o.balance() == amount) return new Pick(List.of(o.invoiceId()), List.of(o.balance()), 0, 30, "amount equals the balance of " + o.number());
            }
            if (tdsBp > 0) {
                for (Open o : pool) {
                    long tds = Money.pct(o.tdsBase(), tdsBp / 100.0);
                    if (o.balance() - tds == amount) return new Pick(List.of(o.invoiceId()), List.of(o.balance()), tds, 28,
                            "amount equals " + o.number() + " less " + Money.percent(tdsBp) + " TDS (" + Money.format(tds) + ")");
                }
            }
        }
        long all = 0, allTds = 0;
        for (Open o : mine) { all += o.balance(); allTds += Money.pct(o.tdsBase(), tdsBp / 100.0); }
        if (mine.size() > 1 && (all == amount || (tdsBp > 0 && all - allTds == amount))) {
            boolean withTds = all != amount;
            return new Pick(ids(mine), balances(mine), withTds ? allTds : 0, 26,
                    "amount equals everything the customer owes (" + mine.size() + " invoices" + (withTds ? ", less TDS" : "") + ")");
        }
        List<Open> pool = mine.size() > MAX_SUBSET ? mine.subList(0, MAX_SUBSET) : mine;
        List<Open> best = subset(amount, pool, 0);
        long tds = 0;
        if (best == null && tdsBp > 0) {
            best = subset(amount, pool, tdsBp);
            if (best != null) for (Open o : best) tds += Money.pct(o.tdsBase(), tdsBp / 100.0);
        }
        if (best != null && best.size() > 1) {
            return new Pick(ids(best), balances(best), tds, 24, "amount equals " + best.size() + " invoices together: "
                    + String.join(" + ", best.stream().map(Open::number).toList()) + (tds > 0 ? " (less TDS)" : ""));
        }
        return null;
    }

    /**
     * Subset sum: the combination of invoices whose balances (less TDS when tdsBp > 0) add up exactly to the amount.
     * Every combination is tried (bit mask from 1 to 2^n - 1). Fewer invoices win, then the older ones.
     */
    static List<Open> subset(long amount, List<Open> pool, int tdsBp) {
        int n = pool.size();
        long[] value = new long[n];
        for (int i = 0; i < n; i++) value[i] = pool.get(i).balance() - (tdsBp > 0 ? Money.pct(pool.get(i).tdsBase(), tdsBp / 100.0) : 0);
        int bestMask = 0, bestCount = Integer.MAX_VALUE;
        long bestAge = Long.MAX_VALUE;
        for (int mask = 1; mask < (1 << n); mask++) {
            int count = Integer.bitCount(mask);
            if (count > bestCount) continue;
            long sum = 0, age = 0;
            for (int i = 0; i < n; i++) if ((mask & (1 << i)) != 0) { sum += value[i]; age += i; }
            if (sum != amount) continue;
            if (count < bestCount || age < bestAge) { bestMask = mask; bestCount = count; bestAge = age; }
        }
        if (bestMask == 0) return null;
        List<Open> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if ((bestMask & (1 << i)) != 0) out.add(pool.get(i));
        return out;
    }

    /** First-in first-out: pay the oldest invoice, then the next, until the money runs out. */
    static Pick fifo(long amount, List<Open> pool) {
        List<String> ids = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        long left = amount;
        for (Open o : pool) {
            if (left <= 0) break;
            long a = Math.min(left, o.balance());
            ids.add(o.invoiceId());
            amounts.add(a);
            left -= a;
        }
        return new Pick(ids, amounts, 0, 0, "");
    }

    /** Share (0..1) of the name's real words that appear in the narration; "AUTOCOMP" matches "AUTOCOMPS" too. */
    public static double nameShare(String name, Set<String> narrationWords) {
        Set<String> nameWords = words(name == null ? "" : name.toUpperCase(Locale.ROOT));
        if (nameWords.isEmpty()) return 0;
        int found = 0;
        for (String w : nameWords) {
            for (String n : narrationWords) {
                if (n.equals(w) || (w.length() >= 4 && n.length() >= 4 && (n.startsWith(w) || w.startsWith(n)))
                        || (w.length() >= 5 && Text.distance(w, n) <= 1)) { found++; break; }
            }
        }
        return (double) found / nameWords.size();
    }

    static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        for (String w : text.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")) {
            if (w.length() < 2 || NOISE.contains(w) || w.matches("\\d+")) continue;
            out.add(w);
        }
        return out;
    }

    /**
     * Running numbers written after the series prefix, in the many ways people type them:
     * "KVP/26-27/0042", "KVP 2627 42", "KVP-0042", "KVP42" all give 42.
     */
    public static Set<Long> invoiceNumbers(String text, String prefix) {
        Set<Long> out = new HashSet<>();
        if (prefix == null || prefix.isBlank()) return out;
        Pattern p = Pattern.compile(Pattern.quote(prefix) + "[\\s/\\-_.]*(?:(\\d{2})[\\s/\\-_.]*(\\d{2})[\\s/\\-_.]+)?(\\d{1,4})(?!\\d)");
        Matcher m = p.matcher(text);
        while (m.find()) out.add(Long.parseLong(m.group(3)));
        // "KVP26270042" (no separators at all)
        Matcher m2 = Pattern.compile(Pattern.quote(prefix) + "(\\d{4})(\\d{4})(?!\\d)").matcher(text.replaceAll("[\\s/\\-_.]", ""));
        while (m2.find()) out.add(Long.parseLong(m2.group(2)));
        return out;
    }

    static long runningNumber(String number) {
        if (number == null) return -1;
        int i = number.lastIndexOf('/');
        try { return Long.parseLong(number.substring(i + 1)); } catch (NumberFormatException e) { return -1; }
    }

    private static List<String> ids(List<Open> l) { return l.stream().map(Open::invoiceId).toList(); }

    private static List<Long> balances(List<Open> l) { return l.stream().map(Open::balance).toList(); }
}
