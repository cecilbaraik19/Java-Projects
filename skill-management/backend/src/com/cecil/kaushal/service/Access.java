package com.cecil.kaushal.service;

import com.cecil.kaushal.model.Claim;
import com.cecil.kaushal.model.Project;
import com.cecil.kaushal.model.User;
import com.cecil.kaushal.store.DataStore;

/**
 * Who may see and do what. (Call while holding the store lock.)
 *
 *  - Everybody sees everybody's VERIFIED skills, endorsements and certificates (an internal talent directory).
 *  - The full profile (claims still waiting, verifier notes, learning goals, history) is for the person,
 *    their managers up the line, and HR (admin).
 *  - A claim can be verified by: the person's manager (or anyone above), HR, or an ASSESSOR - a colleague whose
 *    own level in that skill is 4+ (verified, counting decay) - and an assessor can confirm only up to their own level.
 *    Never by the person themselves (four eyes).
 */
public final class Access {
    public static final int ASSESSOR_LEVEL = 4;

    private Access() { }

    public static boolean full(DataStore s, User viewer, User target) {
        return viewer.id.equals(target.id) || viewer.admin() || s.manages(viewer, target);
    }

    /** Why this person may verify the claim, and up to which level: null when not allowed. */
    public record Right(String why, int maxLevel) { }

    public static Right verify(DataStore s, User verifier, Claim c) {
        if (verifier.id.equals(c.userId)) return null;
        User owner = s.users.get(c.userId);
        if (owner == null) return null;
        if (s.manages(verifier, owner)) return new Right(owner.managerId.equals(verifier.id) ? "your team" : "your wider team", 5);
        if (verifier.admin()) return new Right("HR", 5);
        Claim mine = s.claim(verifier.id, c.skillId);
        if (mine != null) {
            int lv = s.effective(mine, com.cecil.kaushal.util.Clock.today());
            if (lv >= ASSESSOR_LEVEL) return new Right("assessor (you are at " + lv + ")", lv);
        }
        return null;
    }

    public static boolean editProject(User u, Project p) {
        return u.admin() || u.id.equals(p.ownerId);
    }

    public static void admin(User u) {
        if (!u.admin()) throw Problem.forbidden("Only HR (admin) can do this.");
    }

    public static void manager(User u) {
        if (!u.manager()) throw Problem.forbidden("Only managers and HR can do this.");
    }
}
