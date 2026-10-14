package com.cecil.complaints.ai;

import com.cecil.complaints.model.Enums.Category;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multinomial Naive Bayes text classifier that decides the complaint category.
 *
 * Training: count how often each word appears in each category.
 * Prediction: for each category, add up log P(category) + log P(word | category)
 * for every word, then pick the highest. Laplace smoothing (+1) avoids zero probabilities.
 *
 * The classifier starts with built-in examples and keeps learning from resolved complaints.
 */
public final class ComplaintClassifier {

    public record Prediction(Category category, double confidence, Map<Category, Double> probabilities) {
    }

    private final Map<Category, Map<String, Integer>> wordCounts = new EnumMap<>(Category.class);
    private final Map<Category, Integer> totalWords = new EnumMap<>(Category.class);
    private final Map<Category, Integer> docCounts = new EnumMap<>(Category.class);
    private final Set<String> vocabulary = new HashSet<>();
    private int totalDocs;

    public ComplaintClassifier() {
        for (Category c : Category.values()) {
            wordCounts.put(c, new HashMap<>());
            totalWords.put(c, 0);
            docCounts.put(c, 0);
        }
        trainBuiltIn();
    }

    public synchronized void train(String text, Category category) {
        List<String> terms = TextProcessor.termsWithBigrams(text);
        if (terms.isEmpty()) {
            return;
        }
        Map<String, Integer> counts = wordCounts.get(category);
        for (String t : terms) {
            counts.merge(t, 1, Integer::sum);
            vocabulary.add(t);
        }
        totalWords.merge(category, terms.size(), Integer::sum);
        docCounts.merge(category, 1, Integer::sum);
        totalDocs++;
    }

    public synchronized Prediction predict(String text) {
        List<String> terms = TextProcessor.termsWithBigrams(text);
        Map<Category, Double> logScores = new EnumMap<>(Category.class);
        int vocab = Math.max(1, vocabulary.size());

        for (Category c : Category.values()) {
            // prior with smoothing so rare categories are still possible
            double score = Math.log((docCounts.get(c) + 1.0) / (totalDocs + Category.values().length));
            Map<String, Integer> counts = wordCounts.get(c);
            double denom = totalWords.get(c) + vocab;
            for (String t : terms) {
                if (!vocabulary.contains(t)) {
                    continue; // unknown words carry no information
                }
                score += Math.log((counts.getOrDefault(t, 0) + 1.0) / denom);
            }
            logScores.put(c, score);
        }

        // softmax: turn log scores into probabilities that add up to 1
        double max = logScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double sum = 0;
        Map<Category, Double> probs = new LinkedHashMap<>();
        for (Map.Entry<Category, Double> e : logScores.entrySet()) {
            double p = Math.exp(e.getValue() - max);
            probs.put(e.getKey(), p);
            sum += p;
        }
        Category best = Category.OTHER;
        double bestP = -1;
        for (Map.Entry<Category, Double> e : probs.entrySet()) {
            double p = e.getValue() / sum;
            e.setValue(Math.round(p * 1000) / 1000.0);
            if (p > bestP) {
                bestP = p;
                best = e.getKey();
            }
        }
        // if no known words at all, we cannot tell
        boolean anyKnown = terms.stream().anyMatch(vocabulary::contains);
        if (!anyKnown) {
            return new Prediction(Category.OTHER, 0.0, probs);
        }
        return new Prediction(best, bestP, probs);
    }

    public synchronized int trainingSize() {
        return totalDocs;
    }

    // ---------------------------------------------------------------- built-in training data

    private void trainBuiltIn() {
        Map<Category, List<String>> data = new LinkedHashMap<>();
        data.put(Category.BILLING, List.of(
                "I was charged twice for the same order on my credit card",
                "double charge on my bank statement please reverse the extra payment",
                "my invoice shows the wrong amount and extra fees",
                "I was overcharged the bill amount is higher than the price shown",
                "payment deducted from my account but order not placed",
                "money debited twice upi transaction failed but amount deducted",
                "subscription renewed automatically and charged without my permission",
                "hidden charges added to my bill gst invoice incorrect",
                "promo code coupon discount not applied at checkout charged full price",
                "emi charges and interest applied wrongly to my payment"));
        data.put(Category.DELIVERY, List.of(
                "my order has not been delivered yet it is late by a week",
                "package shows delivered but I never received it",
                "the courier did not come delivery delayed again",
                "tracking number not updating shipment stuck in transit",
                "delivery person was rude and left the parcel outside in the rain",
                "wrong address delivery my package went to someone else",
                "order still not shipped after ten days expected delivery date passed",
                "parcel lost by the delivery partner no update on shipping",
                "delivery boy asked for extra money cash on delivery",
                "only part of my order arrived items missing from the package"));
        data.put(Category.PRODUCT_QUALITY, List.of(
                "the product arrived damaged and broken screen cracked",
                "item is defective and stopped working after two days",
                "received the wrong item different colour and size than ordered",
                "poor quality material fabric tore after one wash",
                "the product is fake not original counterfeit brand",
                "food item was expired and smelled bad",
                "the phone heats up and the battery drains fast faulty unit",
                "missing parts in the box accessories not included",
                "the shoes are of very bad quality sole came off",
                "product does not match the description and photos on the website",
                "ordered size m but received xl wrong size and wrong colour",
                "the appliance started smoking and gave a burning smell on first use",
                "charger overheated and sparked unsafe product caught fire",
                "the toy has sharp edges and small parts dangerous for children"));
        data.put(Category.REFUND, List.of(
                "I returned the item but have not received my refund yet",
                "refund is pending for three weeks money not credited",
                "return request rejected without reason I want my money back",
                "cancelled the order but the refund has not been processed",
                "pickup for return not scheduled return window closing",
                "replacement not delivered I want a full refund instead",
                "refund credited to wallet instead of original payment method",
                "partial refund received full amount not returned",
                "exchange request denied the product is unused",
                "how long will my refund take it has been many days"));
        data.put(Category.ACCOUNT, List.of(
                "I cannot log in to my account password reset link not working",
                "my account was hacked and someone placed orders",
                "unauthorized login and my personal details were changed",
                "account blocked suspended without any reason",
                "otp not received on my mobile number cannot verify",
                "I want to delete my account and personal data privacy",
                "email address on my account changed without my permission",
                "two factor authentication not working locked out of account",
                "reward points disappeared from my account wallet balance missing",
                "profile update failed cannot change phone number"));
        data.put(Category.TECHNICAL, List.of(
                "the app keeps crashing when I open it",
                "website shows an error at checkout page not loading",
                "the payment page freezes and the button does not work",
                "app is very slow and the screen goes blank after update",
                "cannot upload documents the upload fails with error 500",
                "the search feature is broken no results shown",
                "notifications not working in the mobile app",
                "the device firmware update failed and now it will not turn on",
                "wifi connection keeps dropping internet not working since morning",
                "software bug the feature stopped working after latest version"));
        data.put(Category.SERVICE, List.of(
                "customer care agent was rude and disconnected my call",
                "nobody responds to my emails support is ignoring me",
                "I have called many times and waited on hold for an hour",
                "the staff behaved badly at the store unprofessional",
                "my complaint ticket was closed without solving the problem",
                "chat support gives copy paste replies and no solution",
                "the technician did not show up for the scheduled visit appointment",
                "the manager refused to listen and was very arrogant",
                "promised a callback but no one called back",
                "support transferred me to five different people nobody helped"));
        data.put(Category.OTHER, List.of(
                "I have a suggestion for improving your website design",
                "general feedback about your company policy",
                "question about your opening hours and store location",
                "I want to know about job openings and partnerships",
                "your advertisement is misleading and offensive"));
        for (Map.Entry<Category, List<String>> e : data.entrySet()) {
            for (String text : e.getValue()) {
                train(text, e.getKey());
            }
        }
    }

    /** Words most strongly linked to a category, for explaining the decision. */
    public synchronized List<String> topEvidence(String text, Category category, int limit) {
        List<String> terms = TextProcessor.terms(text);
        Map<String, Integer> counts = wordCounts.get(category);
        List<String> out = new ArrayList<>();
        terms.stream().distinct()
                .filter(t -> counts.getOrDefault(t, 0) > 0)
                .sorted((a, b) -> counts.get(b) - counts.get(a))
                .limit(limit)
                .forEach(out::add);
        return out;
    }
}
