package com.cecil.sld.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One topic inside a subject, for example "Deadlocks".
 * Prerequisites are topics a student should know first; they are used to
 * find the real cause when a topic is hard ("start with Paging first").
 */
public class Topic {
    public String id;
    public String subjectId;
    public String name;
    public int unit = 1;
    public int order = 1;
    public List<String> prerequisiteIds = new ArrayList<>();
    /** How many seconds an average student needs for one question of this topic. */
    public int expectedSeconds = 60;
    /** A short study tip shown in the study plan. */
    public String tip = "";
    public boolean active = true;

    public Topic() {}
}
