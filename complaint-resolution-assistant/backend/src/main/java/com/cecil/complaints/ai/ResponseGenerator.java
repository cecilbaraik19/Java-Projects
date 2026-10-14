package com.cecil.complaints.ai;

import com.cecil.complaints.model.Complaint;
import com.cecil.complaints.model.Enums.Priority;
import com.cecil.complaints.model.Enums.Sentiment;
import com.cecil.complaints.model.Solution;

/**
 * Writes a draft reply to the customer.
 *
 * The tone adapts to the customer's mood (a very upset customer gets a stronger apology),
 * the body comes from the chosen solution's template, and the closing promises a time
 * frame based on the priority SLA.
 */
public class ResponseGenerator {

    public String generate(Complaint c, Solution solution, String agentName) {
        String name = firstName(c.customerName);
        String agent = agentName == null || agentName.isBlank() ? "Customer Support Team" : agentName;
        StringBuilder sb = new StringBuilder();

        sb.append("Dear ").append(name).append(",\n\n");
        sb.append(opening(c)).append("\n\n");

        String about = c.orderId != null && !c.orderId.isBlank()
                ? "your order " + c.orderId
                : "your complaint";
        sb.append("I have reviewed ").append(about).append(" (reference ").append(c.id).append(")");
        if (c.subject != null && !c.subject.isBlank()) {
            sb.append(" regarding \"").append(c.subject.trim()).append("\"");
        }
        sb.append(".\n\n");

        if (solution != null && solution.responseTemplate != null && !solution.responseTemplate.isBlank()) {
            sb.append(fill(solution.responseTemplate, c, agent)).append("\n\n");
        } else {
            sb.append("Our team is looking into this and will get back to you with a solution as soon as possible.\n\n");
        }

        sb.append(closing(c, solution != null)).append("\n\n");
        sb.append("Warm regards,\n").append(agent).append("\nCustomer Support");
        return sb.toString();
    }

    private String opening(Complaint c) {
        Sentiment s = c.sentiment;
        boolean repeat = c.emotions.contains("frustration");
        if (s == Sentiment.VERY_NEGATIVE) {
            return "I am truly sorry for the experience you have had. This is not the standard of service you "
                    + "deserve, and I completely understand your frustration. I am personally taking ownership of this.";
        }
        if (s == Sentiment.NEGATIVE) {
            return repeat
                    ? "I am sorry that you have had to contact us about this again. Thank you for your patience - let me get this sorted for you."
                    : "I am sorry for the trouble this has caused you, and thank you for bringing it to our attention.";
        }
        if (s == Sentiment.POSITIVE) {
            return "Thank you for your kind words and for reaching out to us.";
        }
        return "Thank you for contacting us. I am happy to help with this.";
    }

    private String closing(Complaint c, boolean hasSolution) {
        if (hasSolution) {
            return "I will keep an eye on your case until everything is sorted. If you need anything else, simply "
                    + "reply to this message and quote reference " + c.id + ".";
        }
        Priority p = c.priority;
        String when = switch (p) {
            case CRITICAL -> "within the next 4 hours";
            case HIGH -> "within 24 hours";
            case MEDIUM -> "within 2 business days";
            case LOW -> "within 3 business days";
        };
        return "You will hear back from us " + when + ". If you have any other questions, simply reply to this "
                + "message and quote reference " + c.id + ".";
    }

    /** Replace {placeholders} in a template. */
    public static String fill(String template, Complaint c, String agent) {
        return template
                .replace("{customerName}", firstName(c.customerName))
                .replace("{orderId}", c.orderId == null || c.orderId.isBlank() ? "your order" : c.orderId)
                .replace("{complaintId}", c.id == null ? "" : c.id)
                .replace("{agentName}", agent == null ? "" : agent);
    }

    static String firstName(String full) {
        if (full == null || full.isBlank()) {
            return "Customer";
        }
        return full.trim().split("\\s+")[0];
    }
}
