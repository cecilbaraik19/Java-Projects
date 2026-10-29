package com.cecil.qpg.service;

import com.cecil.qpg.model.Enums;
import com.cecil.qpg.model.Enums.Role;
import com.cecil.qpg.model.User;
import com.cecil.qpg.store.DataStore;
import com.cecil.qpg.util.Json;
import com.cecil.qpg.util.Passwords;
import com.cecil.qpg.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The team: add people, change roles, reset passwords, turn accounts off (admin only). */
public class UserService {
    private final DataStore store;
    private final AuthService auth;

    public UserService(DataStore store, AuthService auth) {
        this.store = store;
        this.auth = auth;
    }

    /** Everyone can see the list of names (to show who wrote what); only the admin changes it. */
    public List<Object> list() {
        synchronized (store) {
            List<Object> out = new ArrayList<>();
            for (User u : store.users) out.add(u.publicView());
            return out;
        }
    }

    public Map<String, Object> create(User me, Map<String, Object> b) {
        AuthService.need(me, Role.ADMIN, "add people");
        String un = Text.line(Json.str(b, "username"), "Username", 30, true).toLowerCase();
        if (!un.matches("[a-z][a-z0-9._-]{2,29}")) throw ApiError.bad("Username must be 3-30 letters, numbers, dot, dash or underscore, starting with a letter");
        String pw = Json.str(b, "password");
        try { Passwords.checkStrength(pw); } catch (IllegalArgumentException e) { throw ApiError.bad(e.getMessage()); }
        synchronized (store) {
            if (store.user(un) != null) throw ApiError.conflict("Username '" + un + "' is already taken");
            User u = new User();
            u.username = un;
            u.displayName = Text.line(Json.str(b, "displayName"), "Name", 60, true);
            u.department = Text.line(Json.str(b, "department"), "Department", 80, false);
            u.role = role(Json.str(b, "role", "FACULTY"));
            u.passwordHash = Passwords.hash(pw);
            u.createdAt = store.now();
            store.users.add(u);
            store.saveUsers();
            store.log(me.username, "user added", "Added " + u.displayName + " as " + u.role.label, "user:" + un);
            return u.publicView();
        }
    }

    public Map<String, Object> update(User me, String username, Map<String, Object> b) {
        AuthService.need(me, Role.ADMIN, "change people");
        synchronized (store) {
            User u = store.user(username);
            if (u == null) throw ApiError.notFound("No such user");
            if (b.containsKey("displayName")) u.displayName = Text.line(Json.str(b, "displayName"), "Name", 60, true);
            if (b.containsKey("department")) u.department = Text.line(Json.str(b, "department"), "Department", 80, false);
            if (b.containsKey("role")) {
                Role r = role(Json.str(b, "role"));
                if (u.role == Role.ADMIN && r != Role.ADMIN && activeAdmins() <= 1) throw ApiError.bad("There must be at least one admin");
                u.role = r;
            }
            if (b.containsKey("active")) {
                boolean a = Json.bool(b, "active", true);
                if (!a && u.username.equals(me.username)) throw ApiError.bad("You cannot turn off your own account");
                if (!a && u.role == Role.ADMIN && activeAdmins() <= 1) throw ApiError.bad("There must be at least one admin");
                u.active = a;
                if (!a) auth.endSessionsOf(u.username, null);
            }
            String pw = Json.str(b, "password");
            if (pw != null && !pw.isEmpty()) {
                try { Passwords.checkStrength(pw); } catch (IllegalArgumentException e) { throw ApiError.bad(e.getMessage()); }
                u.passwordHash = Passwords.hash(pw);
                auth.endSessionsOf(u.username, null);
            }
            store.saveUsers();
            store.log(me.username, "user changed", "Updated " + u.displayName, "user:" + u.username);
            return u.publicView();
        }
    }

    private int activeAdmins() {
        int n = 0;
        for (User u : store.users) if (u.active && u.role == Role.ADMIN) n++;
        return n;
    }

    private static Role role(String s) {
        try { return Enums.parse(Role.class, s, "Role"); } catch (IllegalArgumentException e) { throw ApiError.bad(e.getMessage()); }
    }
}
