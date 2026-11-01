package com.cecil.invoice.extract;

import com.cecil.invoice.model.CategoryModel;

import java.util.*;

/**
 * Guesses the expense category of an invoice (Travel, IT Hardware ...) from its words.
 * It is a Multinomial Naive Bayes classifier - a classic machine-learning method:
 *
 *   P(category | words)  is proportional to  P(category) x  product of  P(word | category)
 *
 * P(category)        = invoices of that category / all invoices it has seen
 * P(word | category) = (times the word appeared in that category + 1) / (all words in that category + vocabulary size)
 *                      (the "+1" is Laplace smoothing, so an unseen word does not make the chance 0)
 *
 * Logarithms are added instead of multiplying tiny numbers. The model learns every time an
 * invoice is approved, so it gets better as people use the app.
 */
public final class Classifier {
    public static final List<String> CATEGORIES = List.of(
            "Office Supplies", "IT Hardware", "Software & Cloud", "Telecom & Internet", "Travel",
            "Food & Catering", "Repairs & Maintenance", "Professional Services", "Utilities", "Rent & Facilities");

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "per", "from", "inr", "rs", "nos", "pcs", "qty",
            "rate", "amount", "total", "invoice", "tax", "gst", "cgst", "sgst", "igst", "hsn", "sac", "pvt", "ltd", "llp", "limited",
            "private", "month", "charges", "charge", "item", "items", "set", "pack", "box", "unit", "units", "only", "rupees");

    private final CategoryModel m;

    public Classifier(CategoryModel model) { this.m = model; }

    /** Split text into simple words: lower case, letters only, 3+ letters, no plural "s". */
    public static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (w.length() < 3 || STOP.contains(w)) continue;
            if (w.length() > 4 && w.endsWith("ies")) w = w.substring(0, w.length() - 3) + "y";
            else if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) w = w.substring(0, w.length() - 1);
            out.add(w);
        }
        return out;
    }

    /** Learn from one invoice. */
    public synchronized void train(String text, String category) {
        if (category == null || category.isBlank()) return;
        m.docs.merge(category, 1.0, Double::sum);
        Map<String, Double> counts = m.words.computeIfAbsent(category, k -> new LinkedHashMap<>());
        for (String w : words(text)) counts.merge(w, 1.0, Double::sum);
        m.trainedOn++;
    }

    /** Forget everything (before training again from all approved invoices). */
    public synchronized void reset() {
        m.docs.clear();
        m.words.clear();
        m.trainedOn = 0;
    }

    /** Chance of each category (adds up to 1), best first. Empty if the model has learned nothing. */
    public synchronized LinkedHashMap<String, Double> predict(String text) {
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        if (m.docs.isEmpty()) return out;
        List<String> ws = words(text);
        Set<String> vocab = new HashSet<>();
        for (Map<String, Double> c : m.words.values()) vocab.addAll(c.keySet());
        double totalDocs = 0;
        for (double d : m.docs.values()) totalDocs += d;
        Map<String, Double> logs = new HashMap<>();
        for (String cat : m.docs.keySet()) {
            Map<String, Double> counts = m.words.getOrDefault(cat, Map.of());
            double catWords = 0;
            for (double c : counts.values()) catWords += c;
            double lp = Math.log(m.docs.get(cat) / totalDocs);
            for (String w : ws) lp += Math.log((counts.getOrDefault(w, 0.0) + 1) / (catWords + vocab.size()));
            logs.put(cat, lp);
        }
        // softmax: turn log scores into chances that add up to 1
        double max = Collections.max(logs.values());
        double sum = 0;
        for (double v : logs.values()) sum += Math.exp(v - max);
        List<Map.Entry<String, Double>> list = new ArrayList<>();
        for (Map.Entry<String, Double> e : logs.entrySet()) list.add(Map.entry(e.getKey(), Math.exp(e.getValue() - max) / sum));
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Double> e : list) out.put(e.getKey(), Math.round(e.getValue() * 1000) / 1000.0);
        return out;
    }

    /** The words that point most strongly to each category (for the "How the AI works" page). */
    public synchronized Map<String, List<String>> topWords(int n) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Map<String, Double> all = new HashMap<>();
        for (Map<String, Double> c : m.words.values()) for (Map.Entry<String, Double> e : c.entrySet()) all.merge(e.getKey(), e.getValue(), Double::sum);
        for (String cat : m.words.keySet()) {
            Map<String, Double> c = m.words.get(cat);
            List<String> ws = new ArrayList<>(c.keySet());
            // words common in this category but rare elsewhere
            ws.sort((a, b) -> Double.compare(c.get(b) * c.get(b) / all.get(b), c.get(a) * c.get(a) / all.get(a)));
            out.put(cat, ws.subList(0, Math.min(n, ws.size())));
        }
        return out;
    }

    public CategoryModel model() { return m; }
}
