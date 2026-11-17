package com.cecil.kaushal.service;

import com.cecil.kaushal.core.Levels;
import com.cecil.kaushal.core.Readiness;
import com.cecil.kaushal.core.SkillGraph;
import com.cecil.kaushal.model.Claim;
import com.cecil.kaushal.model.Goal;
import com.cecil.kaushal.model.JobRole;
import com.cecil.kaushal.model.Req;
import com.cecil.kaushal.model.Skill;
import com.cecil.kaushal.model.User;
import com.cecil.kaushal.store.DataStore;
import com.cecil.kaushal.util.Clock;
import com.cecil.kaushal.util.Json;
import com.cecil.kaushal.util.Text;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Careers: job roles (what each role needs), readiness for a role, and learning goals.
 * "Make a plan" turns the gaps for a role into goals in a safe order (prerequisites first, see SkillGraph).
 */
public class GrowthService {
    public static final int WEEKS_PER_GOAL = 6;
    private final DataStore store;
    private final ProfileService profiles;

    public GrowthService(DataStore store, ProfileService profiles) {
        this.store = store;
        this.profiles = profiles;
    }

    // ------------------------------------------------------------------ job roles (HR)

    public JobRole saveRole(User by, String id, Map<String, Object> b) {
        Access.admin(by);
        synchronized (store) {
            JobRole r = id == null ? new JobRole() : store.roles.get(id);
            if (r == null) throw Problem.notFound("Role");
            String name = Text.clean(Json.str(b, "name", r.name), 60);
            if (Text.blank(name)) throw Problem.bad("Give the role a name.");
            for (JobRole o : store.roles.values())
                if (o != r && Text.key(o.name).equals(Text.key(name))) throw Problem.conflict("There is already a role called " + o.name + ".");
            List<Req> reqs = b.containsKey("reqs") ? reqs(Json.list(b, "reqs")) : r.reqs;
            if (reqs.isEmpty()) throw Problem.bad("A role needs at least one skill.");
            if (id == null) {
                r.id = store.nextId("R", 1);
                store.roles.put(r.id, r);
            }
            r.name = name;
            r.ladder = Text.clean(Json.str(b, "ladder", r.ladder), 30);
            r.step = (int) Math.max(1, Math.min(6, Json.num(b, "step", r.step == 0 ? 1 : r.step)));
            r.description = Text.clean(Json.str(b, "description", r.description), 300);
            r.reqs = reqs;
            store.log(by.id, id == null ? "role.add" : "role.edit", r.id, name);
            store.save("roles", "audit");
            return r;
        }
    }

    /** Checks a list of requirements from the browser: known skills, no skill twice, at most 15. */
    List<Req> reqs(List<Object> raw) {
        List<Req> out = Req.list(raw);
        if (out.size() > 15) throw Problem.bad("At most 15 skills.");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Req q : out) {
            Skill s = store.skills.get(q.skillId());
            if (s == null || !s.active) throw Problem.bad("Unknown skill in the list.");
            if (!seen.add(q.skillId())) throw Problem.bad(s.name + " is listed twice.");
        }
        return out;
    }

    // ------------------------------------------------------------------ readiness

    public Readiness.Result readiness(String userId, JobRole r) {
        synchronized (store) {
            return Readiness.of(store.levels(userId, false, Clock.today()), r.reqs);
        }
    }

    public User setTarget(User u, String roleId) {
        synchronized (store) {
            if (roleId != null && !store.roles.containsKey(roleId)) throw Problem.notFound("Role");
            u.targetRoleId = roleId;
            store.log(u.id, "target.set", roleId, null);
            store.save("users", "audit");
            return u;
        }
    }

    // ------------------------------------------------------------------ goals

    public Goal addGoal(User by, Map<String, Object> b) {
        synchronized (store) {
            User u = store.users.get(Json.str(b, "userId", by.id));
            if (u == null) throw Problem.notFound("Person");
            if (!Access.full(store, by, u)) throw Problem.forbidden("You can set goals for yourself and your team.");
            Skill s = store.skills.get(Json.str(b, "skillId"));
            if (s == null || !s.active) throw Problem.bad("Pick a skill.");
            int target = (int) Json.num(b, "target", 0);
            LocalDate due = ProfileService.date(Json.str(b, "due"), "Due");
            List<String> steps = Json.strings(b, "steps");
            return goal(by, u, s, target, due, steps.isEmpty() ? defaultSteps(s, target) : steps, Text.clean(Json.str(b, "reason"), 80));
        }
    }

    private Goal goal(User by, User u, Skill s, int target, LocalDate due, List<String> steps, String reason) {
        LocalDate today = Clock.today();
        int now = store.levels(u.id, false, today).getOrDefault(s.id, 0);
        if (target < 1 || target > 5) throw Problem.bad("Target level is 1 to 5.");
        if (target <= now) throw Problem.bad(u.name.split(" ")[0] + " is already at level " + now + " in " + s.name + ".");
        if (due == null || !due.isAfter(today) || due.isAfter(today.plusYears(2))) throw Problem.bad("Pick a due date in the next 2 years.");
        for (Goal g : store.goals.values())
            if (g.userId.equals(u.id) && g.skillId.equals(s.id) && "OPEN".equals(g.status)) throw Problem.conflict("There is already an open goal for " + s.name + ".");
        if (steps.size() > 10) throw Problem.bad("At most 10 steps.");
        Goal g = new Goal();
        g.id = store.nextId("G", 1);
        g.userId = u.id;
        g.skillId = s.id;
        g.fromLevel = now;
        g.target = target;
        g.due = due.toString();
        g.reason = reason;
        g.createdBy = by.id;
        g.createdAt = Clock.millis();
        for (String t : steps) {
            String c = Text.clean(t, 140);
            if (!Text.blank(c)) g.steps.add(Json.obj("text", c, "done", false));
        }
        store.goals.put(g.id, g);
        if (!by.id.equals(u.id)) store.notify(u.id, by.name + " set you a learning goal: " + s.name + " to level " + target + " by " + g.due + ".", "#/growth");
        store.log(by.id, "goal.add", g.id, s.name + " -> " + target);
        store.save("goals", "notes", "audit");
        return g;
    }

    static List<String> defaultSteps(Skill s, int target) {
        List<String> st = new ArrayList<>();
        st.add("Learn: " + Levels.MEANING[target]);
        st.add("Use " + s.name + " on a real task at work");
        st.add("Ask someone at level " + Math.min(5, target + 1) + "+ to review your work");
        st.add("Claim level " + target + " (" + Levels.name(target) + ") on your profile");
        return st;
    }

    /**
     * Turns the gaps for a role into learning goals, prerequisites first, one every 6 weeks.
     * Returns the new goals (gaps that already have an open goal are skipped).
     */
    public List<Goal> planForRole(User by, String userId, String roleId) {
        synchronized (store) {
            User u = store.users.get(userId);
            JobRole r = store.roles.get(roleId);
            if (u == null || r == null) throw Problem.notFound(u == null ? "Person" : "Role");
            if (!Access.full(store, by, u)) throw Problem.forbidden("You can plan for yourself and your team.");
            LocalDate today = Clock.today();
            Map<String, Integer> have = store.levels(u.id, false, today);
            Map<String, Integer> targets = new LinkedHashMap<>();
            for (Req q : r.reqs) if (have.getOrDefault(q.skillId(), 0) < q.level()) targets.put(q.skillId(), q.level());
            LinkedHashMap<String, Integer> path = SkillGraph.learningPath(store.skills, have, targets);
            List<Goal> made = new ArrayList<>();
            int i = 0;
            for (Map.Entry<String, Integer> e : path.entrySet()) {
                boolean open = false;
                for (Goal g : store.goals.values())
                    if (g.userId.equals(u.id) && g.skillId.equals(e.getKey()) && "OPEN".equals(g.status)) open = true;
                if (open) continue;
                i++;
                Skill s = store.skills.get(e.getKey());
                String why = targets.containsKey(s.id) ? "Gap for " + r.name : "Needed first (prerequisite)";
                made.add(goal(by, u, s, e.getValue(), today.plusWeeks((long) WEEKS_PER_GOAL * i), defaultSteps(s, e.getValue()), why));
            }
            return made;
        }
    }

    public Goal step(User by, String goalId, int index, boolean done) {
        synchronized (store) {
            Goal g = goalFor(by, goalId);
            if (index < 0 || index >= g.steps.size()) throw Problem.bad("No such step.");
            if (!"OPEN".equals(g.status)) throw Problem.conflict("This goal is closed.");
            g.steps.get(index).put("done", done);
            store.save("goals");
            return g;
        }
    }

    /** I finished: this makes a claim at the target level, which then goes to someone for checking. */
    public Goal finish(User u, String goalId) {
        synchronized (store) {
            Goal g = goalFor(u, goalId);
            if (!g.userId.equals(u.id)) throw Problem.forbidden("Only " + store.users.get(g.userId).name + " can finish this goal.");
            if (!"OPEN".equals(g.status)) throw Problem.conflict("This goal is closed.");
            Skill s = store.skills.get(g.skillId);
            Claim c = store.claim(u.id, g.skillId);
            String evidence = (c != null && c.evidence != null && c.evidence.length() >= 15 ? c.evidence + " · " : "")
                    + "Finished the learning goal " + s.name + " " + g.fromLevel + " -> " + g.target + " (" + g.stepsDone() + "/" + g.steps.size() + " steps).";
            profiles.claim(u, Json.obj("skillId", g.skillId, "level", g.target, "lastUsed", YearMonth.from(Clock.today()).toString(),
                    "evidence", evidence));
            g.status = "DONE";
            g.doneAt = Clock.millis();
            store.log(u.id, "goal.done", g.id, s.name);
            store.save("goals", "audit");
            return g;
        }
    }

    public Goal drop(User by, String goalId) {
        synchronized (store) {
            Goal g = goalFor(by, goalId);
            if (!"OPEN".equals(g.status)) throw Problem.conflict("This goal is closed.");
            g.status = "DROPPED";
            g.doneAt = Clock.millis();
            store.log(by.id, "goal.drop", g.id, null);
            store.save("goals", "audit");
            return g;
        }
    }

    private Goal goalFor(User by, String goalId) {
        Goal g = store.goals.get(goalId);
        if (g == null) throw Problem.notFound("Goal");
        if (!Access.full(store, by, store.users.get(g.userId))) throw Problem.forbidden("Not your goal.");
        return g;
    }

    /** The best-matching roles for a person (for the career page), best first. */
    public List<Map<String, Object>> matches(String userId) {
        synchronized (store) {
            Map<String, Integer> have = store.levels(userId, false, Clock.today());
            List<Map<String, Object>> out = new ArrayList<>();
            for (JobRole r : store.roles.values()) {
                Readiness.Result res = Readiness.of(have, r.reqs);
                out.add(Json.obj("roleId", r.id, "percent", res.percent(), "ready", res.ready(), "mustMissing", res.mustMissing()));
            }
            out.sort(Comparator.comparing((Map<String, Object> m) -> -((Number) m.get("percent")).intValue()));
            return out;
        }
    }

    /** The next step on the same ladder as the person's current role, or null. */
    public JobRole nextRole(User u) {
        synchronized (store) {
            JobRole cur = u.jobRoleId == null ? null : store.roles.get(u.jobRoleId);
            if (cur == null) return null;
            JobRole best = null;
            for (JobRole r : store.roles.values())
                if (r.ladder != null && r.ladder.equals(cur.ladder) && r.step > cur.step && (best == null || r.step < best.step)) best = r;
            return best;
        }
    }

    /** Unused helper kept small: levels map for one person (verified only). */
    public Map<String, Integer> levels(String userId) {
        synchronized (store) { return new HashMap<>(store.levels(userId, false, Clock.today())); }
    }
}
