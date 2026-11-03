package com.cecil.mail.ai;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written "cue words" for each intent - the second half of the hybrid classifier.
 *
 * Naive Bayes learns only from the training examples, so with a small data set it can miss obvious
 * words it has not seen (for example "money back" for a payment email). These cues add common
 * sense: each matching cue adds its weight to that intent. Words are stemmed the same way as in
 * TextTokenizer, and two-word cues ("money back") are matched on the original text.
 *
 * The final intent score (see EmailAiEngine) = 0.65 x Naive Bayes + 0.35 x cue score.
 */
public final class IntentCues {

    private IntentCues() { }

    private static final Map<EmailIntent, Map<String, Double>> CUES = new EnumMap<>(EmailIntent.class);

    static {
        cue(EmailIntent.MEETING_REQUEST, "meet:2", "meeting:2", "call:1.5", "schedule:1.5", "slot:1.5", "appointment:2",
                "available:1", "free for:1.5", "minutes:1", "discuss:1", "calendar:1.5", "zoom:1.5", "google meet:2",
                "teams meeting:2", "catch up:1.5", "sync:1.5", "talk about:1", "session:1");
        cue(EmailIntent.COMPLAINT, "disappointed:2", "unacceptable:2", "complain:2", "complaint:2", "rude:2",
                "badly:1.5", "faulty:2", "defective:2", "damaged:2", "broken:1.5", "missing:1.5", "stale:2",
                "poor:1.5", "worst:2", "terrible:2", "refused:1.5", "not happy:2", "unhappy:2", "report it:1.5",
                "torn:1.5", "wrong item:2", "not acceptable:2");
        cue(EmailIntent.SUPPORT_ISSUE, "error:2", "login:2", "log in:2", "sign in:2", "password:1.5", "otp:2",
                "not loading:2", "not working:1.5", "crash:2", "upload:1.5", "portal:1", "app:1", "bug:2",
                "offline:1.5", "blank:1.5", "wifi:1.5", "wi-fi:1.5", "not connecting:2", "browser:1.5", "install:1.5");
        cue(EmailIntent.INFO_REQUEST, "information:1.5", "details:1.5", "share the:1", "know:1", "tell me:1.5",
                "what are:1.5", "what is:1.5", "is there:1", "timings:1.5", "fees:1", "fee structure:2",
                "eligibility:2", "brochure:2", "syllabus:1.5", "routes:1.5", "procedure:1.5", "documents needed:2",
                "available for:1", "charges:1");
        cue(EmailIntent.FOLLOW_UP, "follow up:2", "following up:2.5", "reminder:2", "any update:2.5", "update me:2",
                "still waiting:2.5", "not heard:2", "heard back:2", "hear back:2", "previous email:2", "earlier mail:2",
                "status:1.5", "where things stand:2.5", "bumping:2.5", "checking in:2", "circling back:2.5",
                "no response:2", "not received any response:2.5");
        cue(EmailIntent.JOB_APPLICATION, "apply:2", "application for:1.5", "resume:2", "cv:2", "internship:2",
                "intern:1.5", "interview:2", "job:2", "vacancy:2", "position:1.5", "hiring:2", "recruitment:2",
                "offer you:2", "post of:1.5", "role:1", "experience:1", "fresher:1.5", "shortlisted:2");
        cue(EmailIntent.PAYMENT_INVOICE, "invoice:2.5", "payment:2", "paid:1.5", "pay:1.5", "refund:2", "money back:2.5",
                "dues:2.5", "unpaid:2.5", "bill:2", "amount:1.5", "debited:2", "credited:2", "transaction:2",
                "receipt:1.5", "rs:1", "₹:1", "outstanding:2", "overdue:2", "bank:1", "upi:1.5");
        cue(EmailIntent.LEAVE_REQUEST, "leave:2.5", "day off:2.5", "days off:2.5", "absence:2", "absent:2",
                "sick:1.5", "not come:1.5", "will not come:2", "out of station:2", "travelling:1", "half day:2",
                "work from home:2", "excuse my:1.5", "doctor:1", "family function:1.5", "maternity:2.5", "vacation:2");
        cue(EmailIntent.DEADLINE_EXTENSION, "extension:2.5", "extend:2.5", "deadline:2", "more time:2.5",
                "extra time:2.5", "one more week:2", "due date:2", "submit:1", "submission:1.5", "instead of:1",
                "late submission:2.5", "postpone:2", "push the:1", "shift the:1.5", "hand in:1.5", "allow:1");
        cue(EmailIntent.THANK_YOU, "thank you:2", "thanks:1.5", "grateful:2", "appreciate:2", "gratitude:2",
                "means a lot:2", "wonderful:1", "kind support:1.5", "many thanks:2.5", "thankful:2");
        cue(EmailIntent.SALES_PITCH, "offer:1.5", "discount:2.5", "free trial:2.5", "free demo:2.5", "demo:1",
                "partnership:2", "collaborate:2", "sponsor:2", "our services:2", "our agency:2.5", "our company:1.5",
                "introducing:2", "buy:1.5", "subscription:1.5", "limited time:2", "affordable:2", "boost:1.5", "deal:1.5");
    }

    private static void cue(EmailIntent intent, String... items) {
        Map<String, Double> m = new HashMap<>();
        for (String it : items) {
            int c = it.lastIndexOf(':');
            m.put(it.substring(0, c), Double.parseDouble(it.substring(c + 1)));
        }
        CUES.put(intent, m);
    }

    /** Returns a score 0..1 for every intent (they add up to 1). All zero if no cue matched. */
    public static Map<EmailIntent, Double> score(String text) {
        String lower = " " + (text == null ? "" : text.toLowerCase()).replaceAll("[^a-z0-9₹\\- ]+", " ") + " ";
        lower = lower.replaceAll("\\s+", " ");
        List<String> stems = TextTokenizer.tokenize(text);
        Map<EmailIntent, Double> raw = new EnumMap<>(EmailIntent.class);
        double total = 0;
        for (var e : CUES.entrySet()) {
            double s = 0;
            for (var c : e.getValue().entrySet()) {
                String cue = c.getKey();
                boolean hit = cue.contains(" ")
                        ? lower.contains(" " + cue + " ") || lower.contains(" " + cue)
                        : stems.contains(TextTokenizer.stem(cue)) || lower.contains(" " + cue + " ");
                if (hit) s += c.getValue();
            }
            raw.put(e.getKey(), s);
            total += s;
        }
        if (total > 0) for (var e : raw.entrySet()) e.setValue(e.getValue() / total);
        return raw;
    }

    /** Which cue words matched for one intent (shown in the "Why this type?" panel). */
    public static List<String> matched(String text, EmailIntent intent) {
        String lower = " " + (text == null ? "" : text.toLowerCase()).replaceAll("[^a-z0-9₹\\- ]+", " ").replaceAll("\\s+", " ") + " ";
        List<String> stems = TextTokenizer.tokenize(text);
        return CUES.get(intent).keySet().stream()
                .filter(cue -> cue.contains(" ")
                        ? lower.contains(" " + cue + " ") || lower.contains(" " + cue)
                        : stems.contains(TextTokenizer.stem(cue)) || lower.contains(" " + cue + " "))
                .sorted().toList();
    }
}
