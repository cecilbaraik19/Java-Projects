package com.cecil.sld.service;

import com.cecil.sld.model.Role;
import com.cecil.sld.model.User;
import com.cecil.sld.store.DataStore;
import com.cecil.sld.util.Passwords;
import com.cecil.sld.util.Text;

import java.util.*;

/** Logins: the admin adds teachers and students; everyone can change their own password. */
public class UserService {
    private final DataStore db;
    private final AuthService auth;

    public UserService(DataStore db, AuthService auth) {
        this.db = db;
        this.auth = auth;
    }

    /** A user as sent to the browser - never includes the password hash. */
    public static Map<String, Object> view(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id);
        m.put("username", u.username);
        m.put("name", u.name);
        m.put("role", u.role);
        m.put("roleLabel", u.role.label);
        m.put("active", u.active);
        m.put("rollNo", u.rollNo);
        m.put("batch", u.batch);
        return m;
    }

    public synchronized List<Map<String, Object>> list(Role role) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<User> sorted = new ArrayList<>(db.users);
        sorted.sort(Comparator.comparing((User u) -> u.role).thenComparing(u -> u.rollNo).thenComparing(u -> u.name));
        for (User u : sorted) if (role == null || u.role == role) out.add(view(u));
        return out;
    }

    public synchronized List<String> batches() {
        TreeSet<String> set = new TreeSet<>();
        for (User u : db.users) if (u.role == Role.STUDENT && !u.batch.isBlank()) set.add(u.batch);
        return new ArrayList<>(set);
    }

    public synchronized User save(User by, String id, Map<String, Object> in) {
        String username = CatalogService.str(in.get("username")).toLowerCase();
        String name = Text.cut(CatalogService.str(in.get("name")), 60);
        Role role;
        try { role = Role.valueOf(CatalogService.str(in.get("role"))); }
        catch (IllegalArgumentException e) { throw ApiError.bad("Choose a role."); }
        if (!Text.validUsername(username)) throw ApiError.bad("Username: 3-30 small letters, numbers, dot, dash or underscore.");
        if (name.length() < 2) throw ApiError.bad("Please enter the full name.");
        User other = db.userByName(username);
        if (other != null && !other.id.equals(id)) throw ApiError.conflict("The username " + username + " is already taken.");
        String rollNo = Text.cut(CatalogService.str(in.get("rollNo")), 30);
        String batch = Text.cut(CatalogService.str(in.get("batch")), 30);
        if (role == Role.STUDENT && rollNo.isEmpty()) throw ApiError.bad("A student needs a roll number.");
        if (role == Role.STUDENT)
            for (User u : db.users)
                if (u.role == Role.STUDENT && u.rollNo.equalsIgnoreCase(rollNo) && !u.id.equals(id))
                    throw ApiError.conflict("Roll number " + rollNo + " already belongs to " + u.name + ".");

        User u;
        String password = CatalogService.str(in.get("password"));
        if (id == null) {
            String problem = Passwords.problem(password);
            if (problem != null) throw ApiError.bad(problem);
            u = new User();
            u.id = db.nextId("U");
            u.createdAt = db.nowText();
            u.passwordHash = Passwords.hash(password);
            db.users.add(u);
        } else {
            u = db.user(id);
            if (u == null) throw ApiError.notFound("User");
            if (u.id.equals(by.id) && role != Role.ADMIN) throw ApiError.bad("You cannot remove your own admin role.");
            if (u.role == Role.ADMIN && role != Role.ADMIN && activeAdmins() <= 1) throw ApiError.bad("There must always be at least one admin.");
            if (!password.isEmpty()) {
                String problem = Passwords.problem(password);
                if (problem != null) throw ApiError.bad(problem);
                u.passwordHash = Passwords.hash(password);
                auth.endSessionsOf(u.id);
            }
        }
        u.username = username;
        u.name = name;
        u.role = role;
        u.rollNo = role == Role.STUDENT ? rollNo : "";
        u.batch = role == Role.STUDENT ? batch : "";
        db.log(by.name, id == null ? "Added login" : "Edited login", name + " (" + role.label + ")");
        db.save("users", "events");
        return u;
    }

    public synchronized User setActive(User by, String id, boolean active) {
        User u = db.user(id);
        if (u == null) throw ApiError.notFound("User");
        if (u.id.equals(by.id) && !active) throw ApiError.bad("You cannot turn off your own login.");
        if (!active && u.role == Role.ADMIN && activeAdmins() <= 1) throw ApiError.bad("There must always be at least one admin.");
        u.active = active;
        if (!active) auth.endSessionsOf(u.id);
        db.log(by.name, active ? "Turned on login" : "Turned off login", u.name);
        db.save("users", "events");
        return u;
    }

    public synchronized void changeOwnPassword(User u, String current, String next) {
        if (!Passwords.verify(current, u.passwordHash)) throw ApiError.bad("Your current password is wrong.");
        String problem = Passwords.problem(next);
        if (problem != null) throw ApiError.bad(problem);
        if (current.equals(next)) throw ApiError.bad("The new password must be different.");
        u.passwordHash = Passwords.hash(next);
        db.log(u.name, "Changed own password", "");
        db.save("users", "events");
    }

    private int activeAdmins() {
        int n = 0;
        for (User u : db.users) if (u.role == Role.ADMIN && u.active) n++;
        return n;
    }
}
