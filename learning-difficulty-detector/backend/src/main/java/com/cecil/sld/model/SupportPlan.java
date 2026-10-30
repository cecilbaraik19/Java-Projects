package com.cecil.sld.model;

/**
 * A teacher's help plan for one student on one difficult topic
 * ("extra class", "worksheet", "peer tutor"...). It remembers the Difficulty
 * Index when it was made, so the teacher can see if the plan helped.
 */
public class SupportPlan {
    public String id;
    public String studentId;
    public String topicId;
    public String action;
    public String note = "";
    public String dueDate;
    /** OPEN or DONE */
    public String status = "OPEN";
    public double indexAtStart;
    public String createdBy;
    public String createdAt;
    public String doneAt;

    public SupportPlan() {}
}
