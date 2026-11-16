package com.cecil.dainandini.model;

/** How much the task matters (the person's own choice, or read from words like "urgent" / "no rush"). */
public enum Importance {
    HIGH(40), MEDIUM(25), LOW(10);

    /** Points this importance adds to the priority score. */
    public final int points;

    Importance(int points) { this.points = points; }
}
