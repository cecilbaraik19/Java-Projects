package com.cecil.edge.model;

import com.cecil.edge.model.Enums.Role;

/** A person who can sign in. The password is stored only as a salted PBKDF2 hash. */
public class User {
    public String username;
    public String displayName;
    public Role role = Role.VIEWER;
    public String salt;
    public String hash;
    public boolean active = true;
    public int failedLogins;
    public long lockedUntil;
    public long createdAt;
    public long lastLogin;
}
