package com.cecil.pipeline.model;

import java.util.Locale;

/** All fixed lists used in the app, kept in one place. */
public final class Enums {

    private Enums() { }

    /** ADMIN manages the team; MANAGER can edit every deal; SALES_REP edits only their own deals. */
    public enum Role { ADMIN, MANAGER, SALES_REP }

    /** Pipeline stages, in order. Each has a default chance (%) that the deal will close. */
    public enum Stage {
        LEAD("Lead", 10),
        QUALIFIED("Qualified", 25),
        PROPOSAL("Proposal sent", 50),
        NEGOTIATION("Negotiation", 75),
        WON("Won", 100),
        LOST("Lost", 0);

        public final String label;
        public final int probability;

        Stage(String label, int probability) {
            this.label = label;
            this.probability = probability;
        }

        public boolean isOpen() { return this != WON && this != LOST; }
        public boolean isClosed() { return !isOpen(); }

        /** The open stages in pipeline order (LEAD to NEGOTIATION). */
        public static Stage[] openStages() { return new Stage[] { LEAD, QUALIFIED, PROPOSAL, NEGOTIATION }; }
    }

    public enum ActivityType {
        CALL("Call"), EMAIL("Email"), MEETING("Meeting"), DEMO("Demo"), TASK("Task"), FOLLOW_UP("Follow-up");

        public final String label;
        ActivityType(String label) { this.label = label; }
    }

    public enum LeadSource {
        WEBSITE("Website"), REFERRAL("Referral"), COLD_CALL("Cold call"), EMAIL_CAMPAIGN("Email campaign"),
        TRADE_SHOW("Trade show"), LINKEDIN("LinkedIn"), PARTNER("Partner"), OTHER("Other");

        public final String label;
        LeadSource(String label) { this.label = label; }
    }

    public enum Priority { LOW, MEDIUM, HIGH }

    /** Common reasons a deal is lost (free text is also allowed). */
    public static final String[] LOST_REASONS = {
        "Price too high", "Chose a competitor", "No budget", "No decision / went silent",
        "Timing not right", "Missing features", "Other"
    };

    public static final String[] INDUSTRIES = {
        "Technology", "Manufacturing", "Retail", "Healthcare", "Education", "Finance",
        "Logistics", "Hospitality", "Real estate", "Government", "Other"
    };

    /** Reads an enum value leniently ("sales rep", "Sales_Rep" etc.). Returns def when blank or unknown. */
    public static <E extends Enum<E>> E parse(Class<E> type, String value, E def) {
        if (value == null || value.isBlank()) return def;
        String v = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
