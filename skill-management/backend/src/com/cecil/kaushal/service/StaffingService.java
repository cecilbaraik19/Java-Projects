package com.cecil.kaushal.service;

import com.cecil.kaushal.core.Hungarian;
import com.cecil.kaushal.core.Readiness;
import com.cecil.kaushal.model.Project;
import com.cecil.kaushal.model.Req;
import com.cecil.kaushal.model.Role;
import com.cecil.kaushal.model.User;
import com.cecil.kaushal.store.DataStore;
import com.cecil.kaushal.util.Clock;
import com.cecil.kaushal.util.Json;
import com.cecil.kaushal.util.Text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects and staffing. A slot needs skills and a share of someone's week (pct).
 *
 * "Suggest a team" fills all open slots at once with the HUNGARIAN ALGORITHM:
 *   - fit(person, slot) = readiness % for the slot's skills (0..100)
 *   - a person can be picked only if they have enough free time and every must-have skill
 *   - one person per slot, and one slot per person in this project
 *   - cost = 100 - fit; extra "leave empty" columns cost 1000, so a slot stays empty only when nobody fits
 * The answer has the highest total fit. We also show what picking one slot at a time (greedy) would give.
 */
public class StaffingService {
    public static final double EMPTY = 1000, NO = 1_000_000;
    private final DataStore store;

    public StaffingService(DataStore store) { this.store = store; }

    public Project saveProject(User by, String id, Map<String, Object> b) {
        Access.manager(by);
        synchronized (store) {
            Project p = id == null ? new Project() : store.projects.get(id);
            if (p == null) throw Problem.notFound("Project");
            if (id != null && !Access.editProject(by, p)) throw Problem.forbidden("Only the project's owner or HR can change it.");
            String name = Text.clean(Json.str(b, "name", p.name), 60);
            if (Text.blank(name)) throw Problem.bad("Give the project a name.");
            LocalDate start = ProfileService.date(Json.str(b, "start", p.start), "Start");
            LocalDate end = ProfileService.date(Json.str(b, "end", p.end), "End");
            if (start == null || end == null || !end.isAfter(start)) throw Problem.bad("Give a start and an end date (end after start).");
            String status = Json.str(b, "status", p.status);
            if (!List.of("PLANNED", "ACTIVE", "DONE").contains(status)) throw Problem.bad("Unknown status.");
            if (id == null) {
                p.id = store.nextId("P", 1);
                p.ownerId = by.id;
                store.projects.put(p.id, p);
            }
            boolean reopened = "DONE".equals(p.status) && !"DONE".equals(status);
            p.name = name;
            p.client = Text.clean(Json.str(b, "client", p.client), 60);
            p.start = start.toString();
            p.end = end.toString();
            p.status = status;
            if (reopened) for (Project.Slot s : p.slots)                 // the people may be busy elsewhere by now
                if (s.assigneeId != null && free(s.assigneeId, p.id) < s.pct) s.assigneeId = null;
            store.log(by.id, id == null ? "project.add" : "project.edit", p.id, name + " " + status);
            store.save("projects", "audit");
            return p;
        }
    }

    public Project saveSlot(User by, String projectId, String slotId, Map<String, Object> b) {
        synchronized (store) {
            Project p = editable(by, projectId);
            Project.Slot s = slotId == null ? new Project.Slot() : p.slot(slotId);
            if (s == null) throw Problem.notFound("Slot");
            String title = Text.clean(Json.str(b, "title", s.title), 50);
            if (Text.blank(title)) throw Problem.bad("Give the slot a title, e.g. Backend developer.");
            int pct = (int) Json.num(b, "pct", s.pct);
            if (pct < 10 || pct > 100 || pct % 10 != 0) throw Problem.bad("Time is 10 % to 100 %, in steps of 10.");
            List<Req> reqs = b.containsKey("reqs") ? new GrowthService(store, null).reqs(Json.list(b, "reqs")) : s.reqs;
            if (reqs.isEmpty()) throw Problem.bad("A slot needs at least one skill.");
            if (slotId == null) {
                if (p.slots.size() >= 12) throw Problem.bad("At most 12 slots per project.");
                s.id = store.nextId("SL", 1);
                p.slots.add(s);
            }
            if (s.assigneeId != null && pct > s.pct && free(s.assigneeId, p.id) + s.pct < pct)
                throw Problem.conflict(store.users.get(s.assigneeId).name + " does not have " + pct + " % free.");
            s.title = title;
            s.pct = pct;
            s.reqs = reqs;
            store.save("projects");
            return p;
        }
    }

    public Project deleteSlot(User by, String projectId, String slotId) {
        synchronized (store) {
            Project p = editable(by, projectId);
            if (!p.slots.removeIf(s -> s.id.equals(slotId))) throw Problem.notFound("Slot");
            store.save("projects");
            return p;
        }
    }

    /** Free time of a person in % (100 minus all busy slots), not counting one project (so it can be re-planned). */
    public int free(String userId, String exceptProject) {
        int used = 0;
        for (Project p : store.projects.values()) {
            if (!p.busy() || p.id.equals(exceptProject)) continue;
            for (Project.Slot s : p.slots) if (userId.equals(s.assigneeId)) used += s.pct;
        }
        return 100 - used;
    }

    /** Free time used by THIS project, other than the given slot. */
    private int usedHere(Project p, String userId, String exceptSlot) {
        int n = 0;
        for (Project.Slot s : p.slots) if (userId.equals(s.assigneeId) && !s.id.equals(exceptSlot)) n += s.pct;
        return n;
    }

    public Project assign(User by, String projectId, String slotId, String userId, boolean stretch) {
        synchronized (store) {
            Project p = editable(by, projectId);
            Project.Slot s = p.slot(slotId);
            if (s == null) throw Problem.notFound("Slot");
            User u = store.users.get(userId);
            if (u == null || !u.active) throw Problem.notFound("Person");
            int free = p.busy() ? free(u.id, p.id) - usedHere(p, u.id, s.id) : 100;
            if (free < s.pct) throw Problem.conflict(u.name + " has only " + Math.max(0, free) + " % free time; this slot needs " + s.pct + " %.");
            Readiness.Result r = Readiness.of(store.levels(u.id, false, Clock.today()), s.reqs);
            if (r.mustMissing() > 0 && !stretch)
                throw Problem.conflict(u.name + " is missing " + r.mustMissing() + " must-have skill" + (r.mustMissing() == 1 ? "" : "s")
                        + ". Assign as a stretch (growth) role?").with(Json.obj("stretchPossible", true, "percent", r.percent()));
            String before = s.assigneeId;
            s.assigneeId = u.id;
            s.stretch = r.mustMissing() > 0;
            if (before != null && !before.equals(u.id)) store.notify(before, "You were moved off " + s.title + " in " + p.name + ".", "#/projects/" + p.id);
            store.notify(u.id, by.name + " staffed you as " + s.title + " (" + s.pct + " %) in " + p.name + (s.stretch ? " - a stretch role to grow into." : "."), "#/projects/" + p.id);
            store.log(by.id, "slot.assign", p.id + "/" + s.id, u.name + " fit " + r.percent() + "%" + (s.stretch ? " stretch" : ""));
            store.save("projects", "notes", "audit");
            return p;
        }
    }

    public Project unassign(User by, String projectId, String slotId) {
        synchronized (store) {
            Project p = editable(by, projectId);
            Project.Slot s = p.slot(slotId);
            if (s == null) throw Problem.notFound("Slot");
            if (s.assigneeId != null) store.notify(s.assigneeId, "You were taken off " + s.title + " in " + p.name + ".", "#/projects/" + p.id);
            s.assigneeId = null;
            s.stretch = false;
            store.save("projects", "notes");
            return p;
        }
    }

    // ------------------------------------------------------------------ suggestions

    /** Best team for the open slots. */
    public Map<String, Object> suggest(User by, String projectId) {
        synchronized (store) {
            Project p = editable(by, projectId);
            LocalDate today = Clock.today();
            List<Project.Slot> open = new ArrayList<>();
            for (Project.Slot s : p.slots) if (s.assigneeId == null) open.add(s);
            Set<String> taken = new HashSet<>();
            for (Project.Slot s : p.slots) if (s.assigneeId != null) taken.add(s.assigneeId);
            List<User> people = new ArrayList<>();
            for (User u : store.users.values()) if (u.active && u.role != Role.ADMIN && !taken.contains(u.id)) people.add(u);
            int n = open.size(), m = people.size();
            double[][] cost = new double[n][m + n];
            Map<String, Readiness.Result> fit = new HashMap<>();
            Map<String, Integer> freeOf = new HashMap<>();
            for (User u : people) freeOf.put(u.id, p.busy() ? free(u.id, p.id) : 100);
            for (int i = 0; i < n; i++) {
                Project.Slot s = open.get(i);
                for (int j = 0; j < m; j++) {
                    User u = people.get(j);
                    Readiness.Result r = Readiness.of(store.levels(u.id, false, today), s.reqs);
                    fit.put(s.id + "|" + u.id, r);
                    boolean ok = freeOf.get(u.id) >= s.pct && r.mustMissing() == 0;
                    cost[i][j] = ok ? 100 - r.percent() : NO;
                }
                for (int j = m; j < m + n; j++) cost[i][j] = EMPTY;
            }
            int[] pick = n == 0 ? new int[0] : Hungarian.solve(cost);
            int[] greedy = greedy(cost, m);
            List<Map<String, Object>> slots = new ArrayList<>();
            int best = 0, greedyTotal = 0, filled = 0, greedyFilled = 0;
            for (int i = 0; i < n; i++) {
                Project.Slot s = open.get(i);
                Map<String, Object> row = Json.obj("slotId", s.id, "title", s.title, "pct", s.pct);
                if (pick[i] < m && cost[i][pick[i]] < NO) {
                    User u = people.get(pick[i]);
                    Readiness.Result r = fit.get(s.id + "|" + u.id);
                    row.put("pick", cand(u, r, freeOf.get(u.id)));
                    best += r.percent();
                    filled++;
                }
                if (greedy[i] >= 0) { greedyTotal += (int) (100 - cost[i][greedy[i]]); greedyFilled++; }
                // other good people for this slot (eligible ones first, then stretch candidates)
                List<User> others = new ArrayList<>(people);
                final int ii = i;
                others.sort(Comparator.comparing((User u) -> cost[ii][people.indexOf(u)] >= NO)
                        .thenComparing(u -> -fit.get(s.id + "|" + u.id).percent()));
                List<Map<String, Object>> alt = new ArrayList<>();
                for (User u : others) {
                    if (alt.size() >= 4) break;
                    if (pick[i] < m && people.get(pick[i]) == u) continue;
                    Readiness.Result r = fit.get(s.id + "|" + u.id);
                    if (r.percent() == 0) continue;
                    Map<String, Object> c = cand(u, r, freeOf.get(u.id));
                    c.put("eligible", cost[i][people.indexOf(u)] < NO);
                    alt.add(c);
                }
                row.put("others", alt);
                slots.add(row);
            }
            return Json.obj("projectId", p.id, "slots", slots, "total", best, "filled", filled,
                    "greedyTotal", greedyTotal, "greedyFilled", greedyFilled, "candidates", m);
        }
    }

    private Map<String, Object> cand(User u, Readiness.Result r, int free) {
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (Readiness.Line l : r.gaps()) gaps.add(Json.obj("skillId", l.skillId(), "have", l.have(), "need", l.need(), "must", l.must()));
        return Json.obj("userId", u.id, "name", u.name, "title", u.title, "fit", r.percent(), "free", free,
                "mustMissing", r.mustMissing(), "gaps", gaps);
    }

    /** Slot by slot, take the best free person (for comparison). Returns column per row, -1 = empty. */
    static int[] greedy(double[][] cost, int people) {
        int n = cost.length;
        int[] pick = new int[n];
        boolean[] used = new boolean[people];
        for (int i = 0; i < n; i++) {
            pick[i] = -1;
            double bestCost = NO;
            for (int j = 0; j < people; j++) if (!used[j] && cost[i][j] < bestCost) { bestCost = cost[i][j]; pick[i] = j; }
            if (pick[i] >= 0) used[pick[i]] = true;
        }
        return pick;
    }

    private Project editable(User by, String projectId) {
        Project p = store.projects.get(projectId);
        if (p == null) throw Problem.notFound("Project");
        if (!Access.editProject(by, p)) throw Problem.forbidden("Only the project's owner or HR can staff it.");
        return p;
    }
}
