package com.cecil.invoice.model;

/** A person who can sign in. The password is stored only as a salted hash. */
public class User {
    public String id;
    public String username;
    public String name;
    public Role role;
    public String passwordHash;
    public boolean active = true;
    public String createdAt;
}
