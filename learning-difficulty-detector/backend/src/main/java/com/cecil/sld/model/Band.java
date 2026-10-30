package com.cecil.sld.model;

/**
 * How hard a topic is for someone, worked out from the Difficulty Index (0-100).
 * On the map these are the height zones: valley, foothills, highlands, summit.
 */
public enum Band {
    NO_DATA("Not enough data", "Not surveyed"),
    EASY("Easy", "Valley"),
    MODERATE("Moderate", "Foothills"),
    DIFFICULT("Difficult", "Highlands"),
    CRITICAL("Very difficult", "Summit");

    public final String label;
    public final String zone;
    Band(String label, String zone) { this.label = label; this.zone = zone; }
}
