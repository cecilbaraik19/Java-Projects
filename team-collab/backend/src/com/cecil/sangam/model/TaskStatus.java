package com.cecil.sangam.model;

/** The columns of the task board, in order. */
public enum TaskStatus {
    TODO("To do"), DOING("Doing"), REVIEW("Review"), DONE("Done");

    public final String label;

    TaskStatus(String label) { this.label = label; }

    /** Started work counts for "Doing" and "Review". */
    public boolean active() { return this == DOING || this == REVIEW; }
}
