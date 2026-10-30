package com.cecil.sld.model;

/**
 * Settings the admin can change. The weights decide how much each signal adds
 * to the Difficulty Index; the limits decide where Easy / Moderate / Difficult /
 * Very difficult begin.
 */
public class Settings {
    public String institution = "Amity University Jharkhand";
    public String department = "Department of Information Technology";

    // weights of each signal (any positive numbers; they are scaled to add up to 1)
    public double wWrong = 45;          // wrong answers
    public double wSlow = 15;           // takes longer than expected
    public double wHints = 10;          // needed hints
    public double wSureWrong = 15;      // wrong while sure  -> misconception
    public double wSelf = 10;           // the student says it is hard
    public double wDecline = 5;         // getting worse lately

    // where each band starts (Difficulty Index 0-100)
    public int moderateFrom = 30;
    public int difficultFrom = 50;
    public int criticalFrom = 70;

    /** Fewer answered questions than this means "Not enough data". */
    public int minAnswers = 4;
    /** Older answers count less: an answer this many days old counts half. */
    public int halfLifeDays = 21;

    public Settings() {}
}
