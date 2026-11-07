package com.cecil.sambandh.model;

/** Where a customer is in their journey with the business (the "customer lifecycle"). */
public enum Stage {
    LEAD("Lead", "Showed interest, not talked to properly yet"),
    PROSPECT("Prospect", "Talking, may buy soon"),
    CUSTOMER("Customer", "Has bought at least once"),
    LOYAL("Loyal", "Buys again and again"),
    CHURNED("Churned", "Stopped buying / left");

    public final String label;
    public final String meaning;

    Stage(String label, String meaning) { this.label = label; this.meaning = meaning; }

    public static Stage parse(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim().toUpperCase();
        for (Stage st : values()) if (st.name().equals(t) || st.label.equalsIgnoreCase(s.trim())) return st;
        return null;
    }
}
