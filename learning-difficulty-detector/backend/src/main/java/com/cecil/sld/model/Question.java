package com.cecil.sld.model;

import java.util.ArrayList;
import java.util.List;

/** A multiple-choice question that belongs to one topic. */
public class Question {
    public String id;
    public String topicId;
    public String text;
    public List<String> options = new ArrayList<>();
    /** Index (0-3) of the right option. Never sent to students before they answer. */
    public int answer;
    /** 1 = easy, 2 = medium, 3 = hard */
    public int level = 2;
    public String hint = "";
    public String explanation = "";
    public boolean active = true;

    public Question() {}
}
