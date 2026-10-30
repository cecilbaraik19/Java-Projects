package com.cecil.sld.model;

/** A student saying how hard a topic feels (1 = easy ... 5 = very hard). */
public class SelfReport {
    public String id;
    public String studentId;
    public String topicId;
    public int rating = 3;
    public String note = "";
    public String date;

    public SelfReport() {}
}
