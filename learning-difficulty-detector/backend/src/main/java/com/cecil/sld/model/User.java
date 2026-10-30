package com.cecil.sld.model;

/** A login. Students also have a roll number and a batch (class). */
public class User {
    public String id;
    public String username;
    public String name;
    public Role role;
    public String passwordHash;
    public boolean active = true;
    public String rollNo = "";
    public String batch = "";
    public String createdAt;

    public User() {}
}
