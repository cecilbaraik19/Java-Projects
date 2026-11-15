package com.cecil.sangam.service;

import com.cecil.sangam.model.Channel;
import com.cecil.sangam.model.Project;
import com.cecil.sangam.model.User;
import com.cecil.sangam.store.DataStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Who may see and do what. Call these while holding the store lock.
 *
 *  - The admin sees every project, but NOT other people's private channels or direct messages.
 *  - A guest sees only the projects they were added to (and those projects' channels).
 *  - Guests may read and comment, but not create or move tasks.
 */
public final class Access {
    private Access() { }

    public static boolean seeProject(User u, Project p) {
        return p != null && u != null && (u.admin() || p.has(u.id));
    }

    public static boolean workOn(User u, Project p) {
        return seeProject(u, p) && !u.guest() && !p.archived;
    }

    public static boolean seeChannel(DataStore store, User u, Channel c) {
        if (c == null || u == null) return false;
        return switch (c.kind) {
            case PUBLIC -> !u.guest();
            case PROJECT -> seeProject(u, store.projects.get(c.projectId));
            case PRIVATE, DIRECT -> c.memberIds.contains(u.id);
        };
    }

    /** The people in a channel (for mentions and "who will see this"). */
    public static List<User> members(DataStore store, Channel c) {
        List<User> out = new ArrayList<>();
        for (User u : store.users.values()) {
            if (!u.active) continue;
            boolean in = switch (c.kind) {
                case PUBLIC -> !u.guest();
                case PROJECT -> {
                    Project p = store.projects.get(c.projectId);
                    yield p != null && p.has(u.id);
                }
                case PRIVATE, DIRECT -> c.memberIds.contains(u.id);
            };
            if (in) out.add(u);
        }
        return out;
    }

    public static Project project(DataStore store, User u, String id) {
        Project p = store.projects.get(id);
        if (!seeProject(u, p)) throw Problem.notFound("Project");
        return p;
    }

    public static Channel channel(DataStore store, User u, String id) {
        Channel c = store.channels.get(id);
        if (!seeChannel(store, u, c)) throw Problem.notFound("Channel");
        return c;
    }

    public static void admin(User u) {
        if (u == null || !u.admin()) throw Problem.forbidden("Only the workspace admin can do that.");
    }
}
