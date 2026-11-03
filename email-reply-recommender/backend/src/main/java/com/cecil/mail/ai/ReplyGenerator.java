package com.cecil.mail.ai;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Builds professional replies by joining small, well-written pieces:
 *
 *   greeting + opening + (urgency line) + CORE MESSAGE + closing + sign-off
 *
 * The CORE MESSAGE depends on the email intent and the stance the user picks
 * (accept / ask for more info / politely decline). The other pieces depend on the tone
 * (Formal, Friendly, Brief) and on the sender's mood and urgency found by the analyzers.
 * Details like dates, times and order numbers are filled into {placeholders}.
 */
public class ReplyGenerator {

    public enum Tone { FORMAL, FRIENDLY, BRIEF }

    public enum Stance { POSITIVE, NEED_INFO, DECLINE }

    public record Context(EmailIntent intent, Stance stance, String senderName, String subject,
                          EntityExtractor.Entities entities, String sentiment, String urgency,
                          String myName, String myRole) { }

    // index 0 = formal, 1 = friendly, 2 = brief
    private static final Map<EmailIntent, Map<Stance, String[]>> CORE = new EnumMap<>(EmailIntent.class);

    static {
        put(EmailIntent.MEETING_REQUEST,
            new String[]{"I would be glad to meet. {when} works well for me, and I will send a calendar invitation shortly to confirm.",
                         "I'd love to catch up! {when} works great for me. I'll drop a calendar invite so it's locked in.",
                         "{when} works for me. Invite on the way."},
            new String[]{"I would be happy to arrange a meeting. Could you please share a short agenda and two or three time slots that suit you, so that I can prepare accordingly?",
                         "Happy to meet! Could you send over a quick agenda and a couple of time slots that work for you?",
                         "Sure. Please share an agenda and a few time slots."},
            new String[]{"Unfortunately, I am not available at {when}. Could we consider an alternative time later in the week, or would an email update be sufficient for now?",
                         "Sadly I can't make {when}. Could we try another time later this week, or sort it out over email?",
                         "I can't make {when}. Could we pick another time?"});

        put(EmailIntent.COMPLAINT,
            new String[]{"I have personally escalated {ref} to the concerned team. We will arrange a replacement or a full refund, whichever you prefer, and you will receive an update within 24 hours.",
                         "I've already flagged {ref} with our team. We'll sort out a replacement or a refund, whichever you'd like, and I'll update you within a day.",
                         "{ref} is escalated. Replacement or refund, your choice. Update within 24 hours."},
            new String[]{"To resolve this quickly, could you please share {refAsk}, the date of purchase and, if possible, a photo of the issue? This will help us investigate without delay.",
                         "So I can fix this fast, could you send {refAsk}, when you bought it, and a photo if you have one?",
                         "Please send {refAsk}, the purchase date and a photo so we can act quickly."},
            new String[]{"After reviewing your concern, we are unable to offer a refund in this case as per our policy. However, I would be glad to offer a free service check or a discount on your next purchase.",
                         "I've checked, and sadly a refund isn't possible under our policy this time. I'd still love to make it up to you with a free check-up or a discount on your next order.",
                         "A refund isn't possible under our policy, but we can offer a free check or a discount."});

        put(EmailIntent.SUPPORT_ISSUE,
            new String[]{"Please try clearing your browser cache, signing out and back in, and using the latest version of the application. If the issue continues, our technical team will connect with you and resolve it on priority.",
                         "Could you try clearing your cache, logging out and back in, and updating to the latest version? If it still acts up, our tech team will jump in right away.",
                         "Please clear your cache, sign in again and update the app. If it persists, we'll step in."},
            new String[]{"To help us diagnose the issue, could you please share a screenshot of the error, the device and browser you are using, and the steps that led to the problem?",
                         "To get to the bottom of it, could you send a screenshot of the error, your device and browser, and what you clicked just before it happened?",
                         "Please send a screenshot, your device/browser and the steps you took."},
            new String[]{"This request falls outside the scope of our support service. I would recommend contacting the official vendor, who will be better placed to assist you.",
                         "This one's outside what our support covers, unfortunately. The official vendor will be the best people to help you out.",
                         "This is outside our support scope. Please contact the vendor."});

        put(EmailIntent.INFO_REQUEST,
            new String[]{"Please find the requested details attached. I have also included a short summary of the key points for your quick reference.",
                         "I've attached everything you asked for, plus a quick summary of the main points so you don't have to dig through it all.",
                         "Details attached, with a short summary."},
            new String[]{"I would be happy to help. Could you please let me know which specific details you need, so that I can send you the most relevant information?",
                         "Happy to help! Which details matter most to you? That way I can send exactly what's useful.",
                         "Happy to help. Which details do you need exactly?"},
            new String[]{"Unfortunately, this information is confidential and cannot be shared externally. I hope you will understand, and I am glad to help with anything else.",
                         "Sorry, that info is confidential, so I can't share it outside the team. Happy to help with anything else though!",
                         "Sorry, this information is confidential and can't be shared."});

        put(EmailIntent.FOLLOW_UP,
            new String[]{"Thank you for your patience. I have reviewed the matter, and it has now been processed. You should receive the final update by {when}.",
                         "Thanks for bearing with me! I've looked into it and it's done. You'll have the final update by {when}.",
                         "It's been processed. Final update by {when}."},
            new String[]{"I apologise for the delay in responding. Could you please resend the original details, so that I can review them and get back to you promptly?",
                         "Sorry for the slow reply! Could you resend the original details? I'll get back to you quickly.",
                         "Sorry for the delay. Please resend the details."},
            new String[]{"After careful review, we have decided not to proceed further at this stage. Thank you for your understanding, and we will reach out if anything changes.",
                         "We've had a good look and have decided not to go ahead for now. Thanks for understanding, and we'll ping you if things change.",
                         "We won't proceed for now. We'll get in touch if that changes."});

        put(EmailIntent.JOB_APPLICATION,
            new String[]{"Thank you for your interest. I am pleased to confirm the next step: an interview on {when}. Please bring a copy of your resume and a valid ID proof.",
                         "Thanks for applying! Great news: we'd like to invite you to an interview on {when}. Just bring your resume and an ID.",
                         "Interview confirmed for {when}. Please bring your resume and ID."},
            new String[]{"Thank you for your application. Could you please share your updated resume, your expected joining date and links to any relevant projects?",
                         "Thanks for applying! Could you send your latest resume, when you could join, and links to a few projects you're proud of?",
                         "Please share your resume, joining date and project links."},
            new String[]{"Thank you for your interest in the position. After careful review, we will not be moving forward with your application at this time. We will keep your profile on record for future openings.",
                         "Thanks so much for applying. We've decided to go with other candidates this time, but we'll keep your profile for future roles.",
                         "We won't move forward this time, but we'll keep your profile on file."});

        put(EmailIntent.PAYMENT_INVOICE,
            new String[]{"I confirm that the payment {amountOf}for {ref} has been processed. It should reflect in your account within 3 to 5 working days.",
                         "Good news: the payment {amountOf}for {ref} has gone through. It should show up in 3 to 5 working days.",
                         "Payment {amountOf}for {ref} is processed. Expect it in 3-5 working days."},
            new String[]{"To process this, could you please share the invoice copy, the transaction ID and the date of payment for {ref}?",
                         "To sort this out, could you send the invoice copy, the transaction ID and the payment date for {ref}?",
                         "Please share the invoice copy, transaction ID and payment date."},
            new String[]{"After verification, we found that {ref} does not match our records, so we are unable to process it at this time. Please review the details and resend the corrected invoice.",
                         "I checked {ref}, and it doesn't match what we have on file, so we can't process it yet. Could you double-check and send a corrected copy?",
                         "{ref} doesn't match our records. Please send a corrected invoice."});

        put(EmailIntent.LEAVE_REQUEST,
            new String[]{"Your leave request for {when} has been approved. Please ensure your pending work is handed over before you leave.",
                         "Your leave for {when} is approved. Just make sure your tasks are handed over before you go. Take care!",
                         "Leave for {when} approved. Please hand over your work."},
            new String[]{"Before approving your leave, could you please confirm the exact dates and who will handle your responsibilities in your absence?",
                         "Before I approve it, could you confirm the exact dates and who'll cover for you while you're away?",
                         "Please confirm the exact dates and who will cover for you."},
            new String[]{"Unfortunately, I am unable to approve leave for {when} due to critical deadlines. Could we consider alternative dates after the current deliverables are complete?",
                         "I'm sorry, I can't approve leave for {when} because of some big deadlines. Could we look at other dates once this is wrapped up?",
                         "I can't approve {when} due to deadlines. Could you pick other dates?"});

        put(EmailIntent.DEADLINE_EXTENSION,
            new String[]{"Considering the circumstances, I am happy to grant the extension. The revised deadline is {when}. Please let me know if you face any further difficulties.",
                         "No problem, I can give you the extra time. The new deadline is {when}. Shout if anything else comes up.",
                         "Extension granted. New deadline: {when}."},
            new String[]{"Could you please share the current progress and a realistic completion date? This will help me decide on the extension.",
                         "Could you tell me how far along you are and when you think you can realistically finish? That'll help me decide.",
                         "Please share your progress and a realistic finish date."},
            new String[]{"Unfortunately, the deadline cannot be extended as it is linked to other commitments. Please submit whatever you have completed by the original date, and we can discuss the remaining part.",
                         "I'm sorry, I can't move the deadline because other things depend on it. Please send what you have by the original date, and we'll talk about the rest.",
                         "The deadline can't move. Please submit what you have on time."});

        put(EmailIntent.THANK_YOU,
            new String[]{"It was a pleasure to assist you, and I truly appreciate your kind words. I look forward to working with you again.",
                         "It was a real pleasure, and your message made my day! Looking forward to working together again.",
                         "My pleasure. Glad I could help."},
            new String[]{"I am glad it was helpful. If you have any feedback on how we could do even better, I would be keen to hear it.",
                         "So glad it helped! If you have any ideas on how we could do even better, I'd love to hear them.",
                         "Glad it helped. Any feedback is welcome."},
            new String[]{"Thank you for your kind message. I would like to give the credit to the whole team, who made this possible.",
                         "Thank you! Honestly, the whole team deserves the credit for this one.",
                         "Thank you. The credit goes to the team."});

        put(EmailIntent.SALES_PITCH,
            new String[]{"Your proposal sounds interesting. I would like to schedule a short call to understand the pricing, the timeline and the support you offer.",
                         "This sounds interesting! Could we set up a quick call to chat about pricing, timelines and support?",
                         "Interested. Let's set up a short call on pricing and support."},
            new String[]{"Thank you for sharing this. Could you please send a detailed brochure, the pricing and a few client references for our review?",
                         "Thanks for sharing! Could you send a brochure, pricing and a few client references for us to look at?",
                         "Please send a brochure, pricing and references."},
            new String[]{"Thank you for reaching out. We are not looking for this service at the moment, but we will contact you if our requirements change.",
                         "Thanks for thinking of us! We're not looking for this right now, but we'll reach out if that changes.",
                         "Thanks, but we're not interested at the moment."});
    }

    private static void put(EmailIntent intent, String[] positive, String[] needInfo, String[] decline) {
        Map<Stance, String[]> m = new EnumMap<>(Stance.class);
        m.put(Stance.POSITIVE, positive);
        m.put(Stance.NEED_INFO, needInfo);
        m.put(Stance.DECLINE, decline);
        CORE.put(intent, m);
    }

    /** The stance the AI recommends by default for each intent. */
    public static Stance recommendedStance(EmailIntent intent) {
        return intent == EmailIntent.SALES_PITCH ? Stance.NEED_INFO : Stance.POSITIVE;
    }

    /** Generates the reply for one tone. */
    public String generate(Context c, Tone tone) {
        int i = tone.ordinal();
        List<String> parts = new ArrayList<>();
        parts.add(greeting(c, tone));

        String opening = opening(c, tone);
        String urgency = "HIGH".equals(c.urgency()) && tone != Tone.BRIEF
                ? (tone == Tone.FORMAL ? "I understand this is time-sensitive, so I am treating it as a priority."
                                       : "I know this is urgent, so I'm on it right away.")
                : "";
        String core = fill(CORE.get(c.intent()).get(c.stance())[i], c);
        parts.add(String.join(" ", nonEmpty(opening, urgency, core)));

        String closing = switch (tone) {
            case FORMAL -> "Please feel free to contact me if you need any further information.";
            case FRIENDLY -> "Let me know if there's anything else I can do!";
            case BRIEF -> "";
        };
        if (!closing.isEmpty()) parts.add(closing);
        parts.add(signOff(c, tone));
        return String.join("\n\n", parts);
    }

    public String replySubject(String subject) {
        if (subject == null || subject.isBlank()) return "Re: Your email";
        return subject.trim().toLowerCase().startsWith("re:") ? subject.trim() : "Re: " + subject.trim();
    }

    // ---------- pieces ----------

    private String greeting(Context c, Tone tone) {
        String full = c.senderName();
        String first = full == null ? null : full.split("\\s+")[0];
        return switch (tone) {
            case FORMAL -> full == null ? "Dear Sir/Madam," : "Dear " + full + ",";
            case FRIENDLY -> first == null ? "Hi there," : "Hi " + first + ",";
            case BRIEF -> first == null ? "Hello," : "Hi " + first + ",";
        };
    }

    private String opening(Context c, Tone tone) {
        if (tone == Tone.BRIEF) return c.intent() == EmailIntent.COMPLAINT ? "Sorry about this." : "";
        boolean negative = "NEGATIVE".equals(c.sentiment());
        String topic = topic(c.subject());
        if (c.intent() == EmailIntent.THANK_YOU) {
            return tone == Tone.FORMAL ? "Thank you very much for your thoughtful message." : "Thank you so much for your lovely note!";
        }
        if (negative) {
            return tone == Tone.FORMAL
                    ? "Thank you for bringing this to my attention, and I sincerely apologise for the inconvenience caused."
                    : "Thanks for letting me know, and I'm really sorry about the trouble.";
        }
        return tone == Tone.FORMAL
                ? "Thank you for your email" + (topic.isEmpty() ? "." : " regarding " + topic + ".")
                : "Thanks for reaching out" + (topic.isEmpty() ? "!" : " about " + topic + "!");
    }

    private String signOff(Context c, Tone tone) {
        String me = (c.myName() == null || c.myName().isBlank()) ? "[Your Name]" : c.myName().trim();
        String role = (c.myRole() == null || c.myRole().isBlank()) ? "" : "\n" + c.myRole().trim();
        return switch (tone) {
            case FORMAL -> "Yours sincerely,\n" + me + role;
            case FRIENDLY -> "Best wishes,\n" + me;
            case BRIEF -> "Thanks,\n" + me;
        };
    }

    private String topic(String subject) {
        if (subject == null) return "";
        String s = subject.replaceFirst("(?i)^\\s*(re|fwd?)\\s*:\\s*", "").replaceAll("[?!.]+$", "").trim();
        if (s.isEmpty() || s.length() > 60) return "";
        return "\"" + s + "\"";
    }

    private String fill(String template, Context c) {
        EntityExtractor.Entities e = c.entities();
        String date = e.dates().isEmpty() ? null : e.dates().get(0);
        String time = e.times().isEmpty() ? null : e.times().get(0);
        String when;
        if (date != null && time != null) when = date + " at " + time;
        else if (date != null) when = date;
        else if (time != null) when = time;
        else when = defaultWhen(c.intent());
        String ref = e.references().isEmpty() ? defaultRef(c.intent()) : e.references().get(0);
        String amount = e.amounts().isEmpty() ? "" : "of " + e.amounts().get(0) + " ";

        String out = template
                .replace("{when}", when)
                .replace("{ref}", ref)
                .replace("{refAsk}", e.references().isEmpty() ? "your order number" : "any photos related to " + ref)
                .replace("{amountOf}", amount);
        return capitalise(out);
    }

    private String defaultWhen(EmailIntent intent) {
        return switch (intent) {
            case MEETING_REQUEST -> "the time you suggested";
            case LEAVE_REQUEST -> "the requested dates";
            case DEADLINE_EXTENSION -> "the end of this week";
            case JOB_APPLICATION -> "a date we will share shortly";
            default -> "the end of this week";
        };
    }

    private String defaultRef(EmailIntent intent) {
        return switch (intent) {
            case PAYMENT_INVOICE -> "the invoice";
            case COMPLAINT -> "your concern";
            default -> "your request";
        };
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static List<String> nonEmpty(String... items) {
        List<String> out = new ArrayList<>();
        for (String s : items) if (s != null && !s.isBlank()) out.add(s);
        return out;
    }
}
