package com.cecil.ngo.service;

import com.cecil.ngo.model.Enums;
import com.cecil.ngo.model.Enums.Role;
import com.cecil.ngo.model.User;
import com.cecil.ngo.model.Volunteer;
import com.cecil.ngo.store.DataStore;
import com.cecil.ngo.util.Json;
import com.cecil.ngo.util.Passwords;
import com.cecil.ngo.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Logins for the team (admin only). A volunteer login is linked to a volunteer record. */
public class UserService {
    private final DataStore store;
    private final AuthService auth;

    public UserService(DataStore store, AuthService auth) {
        this.store = store;
        this.auth = auth;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) { for (User u : store.users) out.add(u.publicView()); }
        return out;
    }

    public Map<String, Object> create(User me, Map<String, Object> b) {
        AuthService.needAdmin(me, "add logins");
        synchronized (store) {
            String username = Text.line(Json.str(b, "username"), "Username", 20, true).toLowerCase(Locale.ROOT);
            if (!username.matches("[a-z][a-z0-9._]{2,19}")) throw ApiError.bad("Username: 3-20 small letters, digits, dot or underscore, starting with a letter");
            if (store.user(username) != null) throw ApiError.conflict("That username is taken");
            String pw = Json.str(b, "password");
            try { Passwords.checkStrength(pw); } catch (IllegalArgumentException e) { throw ApiError.bad(e.getMessage()); }
            User u = new User();
            u.username = username;
            u.role = Enums.parse(Role.class, Json.str(b, "role"), "Role");
            u.displayName = Text.line(Json.str(b, "displayName"), "Name", 60, true);
            linkVolunteer(u, Json.str(b, "volunteerId"));
            u.passwordHash = Passwords.hash(pw);
            u.createdAt = store.now();
            store.users.add(u);
            store.saveUsers();
            store.log(me.username, "added login", u.username + " (" + u.role.label + ")", null);
            return u.publicView();
        }
    }

    public Map<String, Object> update(User me, String username, Map<String, Object> b) {
        AuthService.needAdmin(me, "change logins");
        synchronized (store) {
            User u = store.user(username);
            if (u == null) throw ApiError.notFound("User not found");
            Role role = b.containsKey("role") ? Enums.parse(Role.class, Json.str(b, "role"), "Role") : u.role;
            boolean active = Json.bool(b, "active", u.active);
            if (u == me && (role != Role.ADMIN || !active)) throw ApiError.conflict("You cannot remove your own admin access");
            if (u.role == Role.ADMIN && u.active && (role != Role.ADMIN || !active)) {
                long admins = store.users.stream().filter(x -> x.role == Role.ADMIN && x.active).count();
                if (admins <= 1) throw ApiError.conflict("There must be at least one active admin");
            }
            String pw = Json.str(b, "password");
            if (pw != null && !pw.isEmpty()) {
                try { Passwords.checkStrength(pw); } catch (IllegalArgumentException e) { throw ApiError.bad(e.getMessage()); }
            }
            if (b.containsKey("displayName")) u.displayName = Text.line(Json.str(b, "displayName"), "Name", 60, true);
            u.role = role;
            if (role == Role.VOLUNTEER) linkVolunteer(u, b.containsKey("volunteerId") ? Json.str(b, "volunteerId") : u.volunteerId);
            else u.volunteerId = null;
            u.active = active;
            if (pw != null && !pw.isEmpty()) u.passwordHash = Passwords.hash(pw);
            store.saveUsers();
            if (!active || (pw != null && !pw.isEmpty())) auth.endSessionsOf(u.username, null);
            store.log(me.username, "changed login", u.username, null);
            return u.publicView();
        }
    }

    private void linkVolunteer(User u, String volunteerId) {
        if (u.role != Role.VOLUNTEER) { u.volunteerId = null; return; }
        Volunteer v = store.volunteer(volunteerId);
        if (v == null) throw ApiError.bad("A volunteer login must be linked to a volunteer record");
        for (User o : store.users)
            if (o != u && v.id.equals(o.volunteerId)) throw ApiError.conflict(v.name + " already has a login (" + o.username + ")");
        u.volunteerId = v.id;
    }
}
