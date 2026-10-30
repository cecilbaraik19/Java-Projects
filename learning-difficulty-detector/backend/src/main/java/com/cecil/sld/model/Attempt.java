package com.cecil.sld.model;

import java.util.ArrayList;
import java.util.List;

/** One quiz taken by a student: either a full TEST of a subject or a PRACTICE set. */
public class Attempt {
    public String id;
    public String studentId;
    public String subjectId;
    /** TEST or PRACTICE */
    public String kind = "TEST";
    public String startedAt;
    public String finishedAt;
    public List<AttemptItem> items = new ArrayList<>();
    public int score;
    public int total;

    public Attempt() {}
}
