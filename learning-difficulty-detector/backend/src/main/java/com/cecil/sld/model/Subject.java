package com.cecil.sld.model;

/** A subject, for example "Operating Systems". It is split into topics. */
public class Subject {
    public String id;
    public String code;
    public String name;
    public String description = "";
    /** One of the map colours: moss, river, clay, heather, ochre, slate */
    public String color = "moss";
    public boolean active = true;

    public Subject() {}
}
