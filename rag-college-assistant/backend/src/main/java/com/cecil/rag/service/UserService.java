package com.cecil.rag.service;

import com.cecil.rag.model.Role;
import com.cecil.rag.model.User;
import com.cecil.rag.store.DataStore;
import com.cecil.rag.util.Clock;
import com.cecil.rag.util.Json;
import com.cecil.rag.util.Passwords;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Admin: add people, change their role, reset a password, switch an account off. */
public final class UserService {
    private final DataStore store;
    private final AuthService auth;

    public UserService(DataStore store, AuthService auth) {
        this.store = store;
        this.auth = auth;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) {
            for (User u : store.users.values()) {
                Map<String, Object> m = u.publicJson();
                long asked = store.records.stream().filter(r -> r.userId.equals(u.id)).count();
                m.put("questions", asked);
                out.add(m);
            }
        }
        return out;
    }

    public User create(Map<String, Object> body) {
        String username = Json.str(body, "username", "").strip().toLowerCase();
        String name = Json.str(body, "name", "").strip();
        String password = Json.str(body, "password", "");
        if (!username.matches("[a-z][a-z0-9._-]{2,29}")) throw ApiError.bad("Username: 3-30 letters, numbers, dot, dash; start with a letter.");
        if (name.isEmpty() || name.length() > 60) throw ApiError.bad("Please enter a name (up to 60 characters).");
        String problem = Passwords.problem(password);
        if (problem != null) throw ApiError.bad(problem);
        synchronized (store) {
            if (store.userByName(username) != null) throw ApiError.bad("This username is already taken.");
            User u = new User();
            u.id = store.nextId("u");
            u.username = username;
            u.name = name;
            u.role = Role.parse(Json.str(body, "role", "STUDENT"));
            u.dept = clip(Json.str(body, "dept", ""), 60);
            u.programme = clip(Json.str(body, "programme", ""), 60);
            u.passwordHash = Passwords.hash(password);
            u.createdAt = Clock.now();
            store.users.put(u.id, u);
            store.saveUsers();
            return u;
        }
    }

    public User update(User admin, String id, Map<String, Object> body) {
        synchronized (store) {
            User u = store.users.get(id);
            if (u == null) throw ApiError.notFound("User");
            if (body.containsKey("name")) {
                String n = Json.str(body, "name", "").strip();
                if (n.isEmpty() || n.length() > 60) throw ApiError.bad("Please enter a name (up to 60 characters).");
                u.name = n;
            }
            if (body.containsKey("dept")) u.dept = clip(Json.str(body, "dept", ""), 60);
            if (body.containsKey("programme")) u.programme = clip(Json.str(body, "programme", ""), 60);
            if (body.containsKey("role")) {
                Role r = Role.parse(Json.str(body, "role", "STUDENT"));
                if (u.id.equals(admin.id) && r != Role.ADMIN) throw ApiError.bad("You cannot remove your own admin role.");
                u.role = r;
            }
            if (body.containsKey("active")) {
                boolean a = Json.bool(body, "active", true);
                if (u.id.equals(admin.id) && !a) throw ApiError.bad("You cannot switch off your own account.");
                u.active = a;
                if (!a) auth.logoutEverywhere(u.id);
            }
            if (body.containsKey("password") && !Json.str(body, "password", "").isEmpty()) {
                String problem = Passwords.problem(Json.str(body, "password", ""));
                if (problem != null) throw ApiError.bad(problem);
                u.passwordHash = Passwords.hash(Json.str(body, "password", ""));
                u.failed = 0;
                u.lockedUntil = 0;
                auth.logoutEverywhere(u.id);
            }
            store.saveUsers();
            return u;
        }
    }

    private static String clip(String s, int max) {
        s = s.strip();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
