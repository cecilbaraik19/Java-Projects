package com.cecil.complaints.ai;

import com.cecil.complaints.model.Enums.Category;
import com.cecil.complaints.model.Enums.Channel;
import com.cecil.complaints.model.Enums.CustomerTier;
import com.cecil.complaints.model.Enums.Priority;
import com.cecil.complaints.model.Enums.Sentiment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out how urgent a complaint is, using simple, explainable points.
 * Every point added comes with a reason the agent can read.
 *
 *   points >= 7 -> CRITICAL, >= 4 -> HIGH, >= 2 -> MEDIUM, else LOW
 */
public class PriorityEngine {

    public record Result(Priority priority, int points, List<String> reasons,
                         boolean autoEscalate, String escalationReason) {
    }

    private static final Set<String> LEGAL = Set.of("lawyer", "legal", "sue", "court", "lawsuit", "consumer",
            "forum", "police", "fir", "ombudsman", "notice");
    private static final Set<String> SAFETY = Set.of("injury", "injured", "fire", "smoke", "burn", "burnt",
            "shock", "explode", "exploded", "dangerous", "unsafe", "poison", "poisoning", "hospital", "allergic");
    private static final Set<String> SECURITY = Set.of("hacked", "fraud", "unauthorized", "unauthorised",
            "stolen", "phishing", "scam");
    private static final Set<String> URGENT = Set.of("urgent", "urgently", "immediately", "asap", "emergency",
            "today", "tonight", "critical");
    private static final Set<String> SOCIAL_THREAT = Set.of("twitter", "facebook", "instagram", "viral",
            "review", "reviews", "media", "public", "linkedin", "youtube");

    public Result evaluate(String text, Sentiment sentiment, Category category, CustomerTier tier,
                           Channel channel, int recentComplaintsBySameCustomer) {
        int points = 0;
        List<String> reasons = new ArrayList<>();
        Set<String> words = new HashSet<>(TextProcessor.rawTokens(text));

        if (sentiment == Sentiment.VERY_NEGATIVE) {
            points += 3;
            reasons.add("Customer is very upset (+3)");
        } else if (sentiment == Sentiment.NEGATIVE) {
            points += 1;
            reasons.add("Negative tone (+1)");
        }

        boolean legal = containsAny(words, LEGAL);
        boolean safety = containsAny(words, SAFETY);
        boolean security = containsAny(words, SECURITY);

        if (safety) {
            points += 6;
            reasons.add("Possible safety risk mentioned (+6)");
        }
        if (legal) {
            points += 3;
            reasons.add("Legal action mentioned (+3)");
        }
        if (security) {
            points += 3;
            reasons.add("Fraud or account security concern (+3)");
        }
        if (containsAny(words, URGENT)) {
            points += 2;
            reasons.add("Customer says it is urgent (+2)");
        }
        if (containsAny(words, SOCIAL_THREAT) || channel == Channel.SOCIAL) {
            points += 1;
            reasons.add("Public / social media visibility (+1)");
        }
        if (tier == CustomerTier.VIP) {
            points += 2;
            reasons.add("VIP customer (+2)");
        } else if (tier == CustomerTier.PREMIUM) {
            points += 1;
            reasons.add("Premium customer (+1)");
        }
        if (recentComplaintsBySameCustomer > 0) {
            int add = Math.min(recentComplaintsBySameCustomer, 2);
            points += add;
            reasons.add("Repeat complaint: " + recentComplaintsBySameCustomer + " earlier in 30 days (+" + add + ")");
        }
        if (category == Category.BILLING || category == Category.REFUND) {
            points += 1;
            reasons.add("Money is involved (+1)");
        }

        Priority p;
        if (points >= 7) {
            p = Priority.CRITICAL;
        } else if (points >= 4) {
            p = Priority.HIGH;
        } else if (points >= 2) {
            p = Priority.MEDIUM;
        } else {
            p = Priority.LOW;
        }
        if (reasons.isEmpty()) {
            reasons.add("No urgency signals found");
        }

        boolean escalate = false;
        String why = null;
        if (safety) {
            escalate = true;
            why = "Safety risk - needs supervisor review";
        } else if (legal) {
            escalate = true;
            why = "Legal threat - needs supervisor review";
        } else if (security && p == Priority.CRITICAL) {
            escalate = true;
            why = "Critical security / fraud case";
        }
        return new Result(p, points, reasons, escalate, why);
    }

    private static boolean containsAny(Set<String> words, Set<String> targets) {
        for (String t : targets) {
            if (words.contains(t)) {
                return true;
            }
        }
        return false;
    }
}
