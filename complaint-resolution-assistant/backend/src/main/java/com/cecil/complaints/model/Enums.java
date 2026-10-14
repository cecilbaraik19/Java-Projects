package com.cecil.complaints.model;

/**
 * All enum types used by the application, kept in one place so they are easy to find.
 */
public final class Enums {

    private Enums() {
    }

    /** What the complaint is about. */
    public enum Category {
        BILLING("Billing & Payments"),
        DELIVERY("Delivery & Shipping"),
        PRODUCT_QUALITY("Product Quality"),
        REFUND("Refunds & Returns"),
        ACCOUNT("Account & Security"),
        TECHNICAL("Technical Issue"),
        SERVICE("Customer Service"),
        OTHER("Other");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    /** Where the complaint workflow stands. */
    public enum Status {
        OPEN, IN_PROGRESS, ESCALATED, RESOLVED, CLOSED;

        public boolean isActive() {
            return this == OPEN || this == IN_PROGRESS || this == ESCALATED;
        }
    }

    /** How urgent the complaint is. Each level has an SLA (hours to resolve). */
    public enum Priority {
        LOW(72), MEDIUM(48), HIGH(24), CRITICAL(4);

        public final int slaHours;

        Priority(int slaHours) {
            this.slaHours = slaHours;
        }
    }

    /** How the customer feels, as detected by the sentiment analyzer. */
    public enum Sentiment {
        VERY_NEGATIVE, NEGATIVE, NEUTRAL, POSITIVE
    }

    /** How the complaint reached us. */
    public enum Channel {
        WEB, EMAIL, PHONE, CHAT, SOCIAL
    }

    /** Customer loyalty tier. Higher tiers get more priority. */
    public enum CustomerTier {
        STANDARD, PREMIUM, VIP
    }

    /** User roles for the agent console. */
    public enum Role {
        ADMIN, AGENT
    }

    /** Safe enum parsing: returns the default when the text is null or unknown. */
    public static <E extends Enum<E>> E parse(Class<E> type, String text, E def) {
        if (text == null || text.isBlank()) {
            return def;
        }
        try {
            return Enum.valueOf(type, text.trim().toUpperCase().replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }
}
