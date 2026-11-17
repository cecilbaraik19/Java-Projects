package com.cecil.kaushal.service;

import com.cecil.kaushal.core.Levels;
import com.cecil.kaushal.core.Readiness;
import com.cecil.kaushal.core.SkillQuery;
import com.cecil.kaushal.core.Stats;
import com.cecil.kaushal.model.AuditEntry;
import com.cecil.kaushal.model.Category;
import com.cecil.kaushal.model.Cert;
import com.cecil.kaushal.model.Claim;
import com.cecil.kaushal.model.ClaimStatus;
import com.cecil.kaushal.model.Endorsement;
import com.cecil.kaushal.model.Goal;
import com.cecil.kaushal.model.JobRole;
import com.cecil.kaushal.model.Note;
import com.cecil.kaushal.model.Project;
import com.cecil.kaushal.model.Req;
import com.cecil.kaushal.model.Skill;
import com.cecil.kaushal.model.Snapshot;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything the website shows - read only. Each method returns plain maps and lists for JSON. */
public class ViewService {
    public static final int BUS_SAFE = 2;        // fewer than 2 people at level 3+ = a "bus factor" risk
    private final DataStore store;
    private final StaffingService staffing;
    private final GrowthService growth;

    public ViewService(DataStore store, StaffingService staffing, GrowthService growth) {
        this.store = store;
        this.staffing = staffing;
        this.growth = growth;
    }

    // ------------------------------------------------------------------ small cards

    public static Map<String, Object> person(User u) {
        return Json.obj("id", u.id, "name", u.name, "initials", u.initials(), "title", u.title, "dept", u.dept,
                "location", u.location, "role", u.role, "managerId", u.managerId, "jobRoleId", u.jobRoleId,
                "targetRoleId", u.targetRoleId, "active", u.active, "joined", u.joined);
    }

    /** All the fixed lists the website needs once (catalogue, roles, people). */
    public Map<String, Object> meta(User viewer) {
        synchronized (store) {
            List<Object> cats = new ArrayList<>(store.categories.values());
            List<Map<String, Object>> skills = new ArrayList<>();
            for (Skill s : store.skills.values()) {
                Map<String, Object> m = s.toJson();
                Category c = store.categories.get(s.categoryId);
                m.put("group", c == null ? "method" : c.group);
                m.put("halfLifeUsed", store.halfLife(s));
                skills.add(m);
            }
            List<Object> people = new ArrayList<>();
            for (User u : store.users.values()) if (u.active || viewer.admin()) people.add(person(u));
            return Json.obj("categories", cats, "skills", skills, "roles", new ArrayList<>(store.roles.values()), "people", people,
                    "levels", List.of(Levels.NAMES), "meaning", List.of(Levels.MEANING), "groups", List.of(CatalogService.GROUPS),
                    "today", Clock.today().toString(), "assessorLevel", Access.ASSESSOR_LEVEL, "readyAt", Readiness.READY_AT,
                    "busSafe", BUS_SAFE, "expiringDays", Cert.EXPIRING_DAYS);
        }
    }

    private Map<String, Object> claimView(Claim c, boolean full, User viewer, LocalDate today) {
        Skill s = store.skills.get(c.skillId);
        int hl = store.halfLife(s);
        int eff = Levels.effective(c.verified, c.lastUsed, hl, today);
        Map<String, Object> m = Json.obj("id", c.id, "skillId", c.skillId, "verified", c.verified, "effective", eff,
                "lastUsed", c.lastUsed, "monthsUnused", Levels.monthsSince(c.lastUsed, today),
                "fading", Levels.fading(c.verified, c.lastUsed, hl, today), "verifiedAt", c.verifiedAt,
                "verifiedBy", c.verifiedBy);
        int count = 0;
        double weighted = 0;
        String mine = null;
        for (Endorsement e : store.endorsements.values()) {
            if (!e.toId.equals(c.userId) || !e.skillId.equals(c.skillId)) continue;
            count++;
            weighted += ProfileService.weight(store, e, today);
            if (e.fromId.equals(viewer.id)) mine = e.id;
        }
        m.put("endorsements", count);
        m.put("endorseWeight", Stats.round1(weighted));
        m.put("myEndorsement", mine);
        boolean certified = false;
        for (Cert x : store.certs.values())
            if (x.userId.equals(c.userId) && c.skillId.equals(x.skillId) && !"EXPIRED".equals(x.status(today))) certified = true;
        m.put("certified", certified);
        if (full) {
            m.put("claimed", c.claimed);
            m.put("status", c.status);
            m.put("evidence", c.evidence);
            m.put("note", c.note);
            m.put("claimedAt", c.claimedAt);
            m.put("history", c.history);
        }
        return m;
    }

    // ------------------------------------------------------------------ home

    public Map<String, Object> home(User u) {
        synchronized (store) {
            LocalDate today = Clock.today();
            int verified = 0, waiting = 0, returned = 0;
            List<Map<String, Object>> fading = new ArrayList<>();
            for (Claim c : store.claimsOf(u.id)) {
                if (c.verified > 0) verified++;
                if (c.status == ClaimStatus.CLAIMED) waiting++;
                if (c.status == ClaimStatus.RETURNED) returned++;
                Skill s = store.skills.get(c.skillId);
                if (s.active && Levels.fading(c.verified, c.lastUsed, store.halfLife(s), today)) fading.add(claimView(c, true, u, today));
            }
            List<Map<String, Object>> certs = new ArrayList<>();
            for (Cert c : store.certs.values())
                if (c.userId.equals(u.id) && !"VALID".equals(c.status(today))) certs.add(certView(c, today));
            Map<String, Object> target = null;
            JobRole tr = u.targetRoleId == null ? growth.nextRole(u) : store.roles.get(u.targetRoleId);
            if (tr != null) target = readinessView(u, tr, today);
            Map<String, Object> current = null;
            JobRole cr = u.jobRoleId == null ? null : store.roles.get(u.jobRoleId);
            if (cr != null) current = readinessView(u, cr, today);
            List<Object> goals = new ArrayList<>();
            for (Goal g : store.goals.values()) if (g.userId.equals(u.id) && "OPEN".equals(g.status)) goals.add(goalView(g, today));
            List<Map<String, Object>> endorsed = new ArrayList<>();
            List<Endorsement> es = new ArrayList<>();
            for (Endorsement e : store.endorsements.values()) if (e.toId.equals(u.id)) es.add(e);
            es.sort(Comparator.comparingLong((Endorsement e) -> -e.at));
            for (Endorsement e : es.subList(0, Math.min(5, es.size()))) endorsed.add(e.toJson());
            List<Map<String, Object>> slots = mySlots(u.id);
            int unread = 0;
            for (Note n : store.notes.values()) if (n.userId.equals(u.id) && !n.read) unread++;
            Map<String, Object> team = null;
            if (u.manager()) team = teamSummary(u, today);
            return Json.obj("me", person(u), "verified", verified, "waiting", waiting, "returned", returned, "fading", fading,
                    "certs", certs, "current", current, "target", target, "goals", goals, "endorsed", endorsed, "slots", slots,
                    "free", staffing.free(u.id, null), "unread", unread, "team", team);
        }
    }

    private Map<String, Object> teamSummary(User boss, LocalDate today) {
        List<User> team = scopePeople(boss, "team");
        int pending = 0, ready = 0, withRole = 0;
        Set<String> ids = new HashSet<>();
        for (User t : team) ids.add(t.id);
        for (Claim c : store.claims.values()) if (ids.contains(c.userId) && c.status == ClaimStatus.CLAIMED) pending++;
        for (User t : team) {
            JobRole r = t.jobRoleId == null ? null : store.roles.get(t.jobRoleId);
            if (r == null) continue;
            withRole++;
            if (Readiness.of(store.levels(t.id, false, today), r.reqs).ready()) ready++;
        }
        List<Map<String, Object>> risks = busRisks(team, today);
        return Json.obj("size", team.size(), "pending", pending, "readyInRole", ready, "withRole", withRole,
                "risks", risks.subList(0, Math.min(4, risks.size())));
    }

    private List<Map<String, Object>> mySlots(String userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Project p : store.projects.values())
            for (Project.Slot s : p.slots)
                if (userId.equals(s.assigneeId)) out.add(Json.obj("projectId", p.id, "project", p.name, "status", p.status,
                        "slotId", s.id, "title", s.title, "pct", s.pct, "stretch", s.stretch, "end", p.end));
        return out;
    }

    private Map<String, Object> readinessView(User u, JobRole r, LocalDate today) {
        Readiness.Result res = Readiness.of(store.levels(u.id, false, today), r.reqs);
        List<Map<String, Object>> gaps = new ArrayList<>(), met = new ArrayList<>();
        for (Readiness.Line l : res.gaps()) gaps.add(line(u, l));
        for (Readiness.Line l : res.met()) met.add(line(u, l));
        return Json.obj("roleId", r.id, "percent", res.percent(), "ready", res.ready(), "mustMissing", res.mustMissing(),
                "gaps", gaps, "met", met);
    }

    private Map<String, Object> line(User u, Readiness.Line l) {
        Claim c = store.claim(u.id, l.skillId());
        return Json.obj("skillId", l.skillId(), "have", l.have(), "need", l.need(), "must", l.must(), "weight", l.weight(),
                "pending", c != null && c.status == ClaimStatus.CLAIMED ? c.claimed : 0);
    }

    private Map<String, Object> goalView(Goal g, LocalDate today) {
        Map<String, Object> m = g.toJson();
        m.put("done", g.stepsDone());
        m.put("overdue", "OPEN".equals(g.status) && LocalDate.parse(g.due).isBefore(today));
        m.put("now", store.levels(g.userId, false, today).getOrDefault(g.skillId, 0));
        Claim c = store.claim(g.userId, g.skillId);
        m.put("pending", c != null && c.status == ClaimStatus.CLAIMED && c.claimed >= g.target);
        return m;
    }

    private Map<String, Object> certView(Cert c, LocalDate today) {
        Map<String, Object> m = c.toJson();
        m.remove("reminded");
        m.put("status", c.status(today));
        m.put("daysLeft", c.expires == null ? null : c.daysLeft(today));
        return m;
    }

    // ------------------------------------------------------------------ profile

    public Map<String, Object> profile(User viewer, String userId) {
        synchronized (store) {
            User u = store.users.get(userId);
            if (u == null || (!u.active && !viewer.admin())) throw Problem.notFound("Person");
            LocalDate today = Clock.today();
            boolean full = Access.full(store, viewer, u);
            List<Map<String, Object>> skills = new ArrayList<>();
            for (Claim c : store.claimsOf(u.id)) {
                if (!store.skills.get(c.skillId).active) continue;
                if (!full && c.verified == 0) continue;
                skills.add(claimView(c, full, viewer, today));
            }
            List<Map<String, Object>> certs = new ArrayList<>();
            for (Cert c : store.certs.values()) if (c.userId.equals(u.id)) certs.add(certView(c, today));
            certs.sort(Comparator.comparing(m -> String.valueOf(m.get("expires"))));
            List<Map<String, Object>> endorsements = new ArrayList<>();
            for (Endorsement e : store.endorsements.values()) if (e.toId.equals(u.id)) endorsements.add(e.toJson());
            endorsements.sort(Comparator.comparing((Map<String, Object> m) -> -((Number) m.get("at")).longValue()));
            Map<String, Object> out = Json.obj("person", person(u), "full", full, "skills", skills, "certs", certs,
                    "endorsements", endorsements, "slots", mySlots(u.id), "free", staffing.free(u.id, null),
                    "self", viewer.id.equals(u.id));
            if (full) {
                List<Object> goals = new ArrayList<>();
                for (Goal g : store.goals.values()) if (g.userId.equals(u.id)) goals.add(goalView(g, today));
                out.put("goals", goals);
                JobRole cr = u.jobRoleId == null ? null : store.roles.get(u.jobRoleId);
                if (cr != null) out.put("current", readinessView(u, cr, today));
                List<Map<String, Object>> canVerify = new ArrayList<>();
                for (Claim c : store.claimsOf(u.id))
                    if (c.status == ClaimStatus.CLAIMED && Access.verify(store, viewer, c) != null) canVerify.add(Json.obj("claimId", c.id, "max", Access.verify(store, viewer, c).maxLevel()));
                out.put("canVerify", canVerify);
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ growth

    public Map<String, Object> growth(User viewer, String userId) {
        synchronized (store) {
            User u = store.users.get(userId == null ? viewer.id : userId);
            if (u == null) throw Problem.notFound("Person");
            if (!Access.full(store, viewer, u)) throw Problem.forbidden("You can see the career page of yourself and your team.");
            LocalDate today = Clock.today();
            JobRole cr = u.jobRoleId == null ? null : store.roles.get(u.jobRoleId);
            JobRole next = growth.nextRole(u);
            JobRole target = u.targetRoleId == null ? null : store.roles.get(u.targetRoleId);
            List<Map<String, Object>> matches = growth.matches(u.id);
            List<Object> goals = new ArrayList<>();
            List<Goal> gl = new ArrayList<>();
            for (Goal g : store.goals.values()) if (g.userId.equals(u.id)) gl.add(g);
            gl.sort(Comparator.comparing((Goal g) -> !"OPEN".equals(g.status)).thenComparing(g -> g.due));
            for (Goal g : gl) goals.add(goalView(g, today));
            Map<String, Object> roles = new LinkedHashMap<>();
            for (JobRole r : store.roles.values()) roles.put(r.id, readinessView(u, r, today));
            return Json.obj("person", person(u), "current", cr == null ? null : roles.get(cr.id),
                    "next", next == null ? null : roles.get(next.id), "target", target == null ? null : roles.get(target.id),
                    "matches", matches, "goals", goals, "roles", roles, "levels", store.levels(u.id, false, today));
        }
    }

    // ------------------------------------------------------------------ periodic table

    public Map<String, Object> table(User viewer) {
        synchronized (store) {
            LocalDate today = Clock.today();
            Map<String, int[]> dist = new HashMap<>();
            for (User u : store.users.values()) {
                if (!u.active) continue;
                for (Map.Entry<String, Integer> e : store.levels(u.id, false, today).entrySet())
                    dist.computeIfAbsent(e.getKey(), k -> new int[6])[e.getValue()]++;
            }
            Map<String, Integer> demand = demand(today);
            Map<String, Integer> mine = store.levels(viewer.id, false, today);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Skill s : store.skills.values()) {
                if (!s.active) continue;
                int[] d = dist.getOrDefault(s.id, new int[6]);
                int any = 0, three = 0, four = 0;
                for (int i = 1; i <= 5; i++) { any += d[i]; if (i >= 3) three += d[i]; if (i >= 4) four += d[i]; }
                Claim c = store.claim(viewer.id, s.id);
                out.add(Json.obj("skillId", s.id, "people", any, "practitioners", three, "experts", four,
                        "dist", List.of(d[1], d[2], d[3], d[4], d[5]), "demand", demand.getOrDefault(s.id, 0),
                        "mine", mine.getOrDefault(s.id, 0), "myStatus", c == null ? null : c.status));
            }
            return Json.obj("skills", out);
        }
    }

    /** People who know a skill (for the skill's detail panel). Verified only. */
    public Map<String, Object> skill(User viewer, String skillId) {
        synchronized (store) {
            Skill s = store.skills.get(skillId);
            if (s == null) throw Problem.notFound("Skill");
            LocalDate today = Clock.today();
            List<Map<String, Object>> people = new ArrayList<>();
            for (Claim c : store.claims.values()) {
                if (!c.skillId.equals(skillId) || c.verified == 0) continue;
                User u = store.users.get(c.userId);
                if (u == null || !u.active) continue;
                people.add(Json.obj("userId", u.id, "effective", store.effective(c, today), "verified", c.verified,
                        "fading", Levels.fading(c.verified, c.lastUsed, store.halfLife(s), today), "lastUsed", c.lastUsed));
            }
            people.sort(Comparator.comparing((Map<String, Object> m) -> -((Number) m.get("effective")).intValue()));
            List<Map<String, Object>> roles = new ArrayList<>();
            for (JobRole r : store.roles.values()) for (Req q : r.reqs) if (q.skillId().equals(skillId))
                roles.add(Json.obj("roleId", r.id, "level", q.level(), "must", q.must()));
            List<String> neededBy = new ArrayList<>();
            for (Skill o : store.skills.values()) if (o.active && o.prereqs.containsKey(skillId)) neededBy.add(o.id);
            List<Map<String, Object>> trend = new ArrayList<>();
            for (Snapshot sn : lastSnapshots(12)) trend.add(Json.obj("month", sn.month, "n", sn.atLeast(skillId, 3), "any", sn.atLeast(skillId, 1)));
            return Json.obj("skillId", skillId, "people", people, "roles", roles, "neededBy", neededBy, "trend", trend);
        }
    }

    /**
     * How much each skill is needed: people whose CURRENT role needs it but who are below the level,
     * plus open slots in planned/active projects.
     */
    private Map<String, Integer> demand(LocalDate today) {
        Map<String, Integer> d = new HashMap<>();
        for (User u : store.users.values()) {
            if (!u.active || u.jobRoleId == null) continue;
            JobRole r = store.roles.get(u.jobRoleId);
            if (r == null) continue;
            Map<String, Integer> lv = store.levels(u.id, false, today);
            for (Req q : r.reqs) if (lv.getOrDefault(q.skillId(), 0) < q.level()) d.merge(q.skillId(), 1, Integer::sum);
        }
        for (Project p : store.projects.values()) if (p.busy())
            for (Project.Slot s : p.slots) if (s.assigneeId == null) for (Req q : s.reqs) d.merge(q.skillId(), 1, Integer::sum);
        return d;
    }

    // ------------------------------------------------------------------ team matrix and bus factor

    /** team = my reports (for managers) or my manager's team (for others); dept = my department; all = everyone. */
    List<User> scopePeople(User viewer, String scope) {
        List<User> out = new ArrayList<>();
        for (User u : store.users.values()) {
            if (!u.active) continue;
            boolean in = switch (scope == null ? "team" : scope) {
                case "all" -> true;
                case "dept" -> viewer.dept != null && viewer.dept.equals(u.dept);
                default -> viewer.manager()
                        ? (u.id.equals(viewer.id) || store.manages(viewer, u))
                        : (u.id.equals(viewer.id) || (viewer.managerId != null && (viewer.managerId.equals(u.managerId) || viewer.managerId.equals(u.id))));
            };
            if (in) out.add(u);
        }
        out.sort(Comparator.comparing(u -> u.name));
        return out;
    }

    public Map<String, Object> matrix(User viewer, String scope, String categoryId) {
        synchronized (store) {
            LocalDate today = Clock.today();
            List<User> people = scopePeople(viewer, scope);
            Map<String, Map<String, Integer>> lv = new HashMap<>();
            Map<String, Map<String, Integer>> pending = new HashMap<>();
            Set<String> used = new HashSet<>();
            for (User u : people) {
                lv.put(u.id, store.levels(u.id, false, today));
                used.addAll(lv.get(u.id).keySet());
                boolean full = Access.full(store, viewer, u);
                Map<String, Integer> pm = new HashMap<>();
                if (full) for (Claim c : store.claimsOf(u.id)) if (c.status == ClaimStatus.CLAIMED) { pm.put(c.skillId, c.claimed); used.add(c.skillId); }
                pending.put(u.id, pm);
            }
            List<Skill> cols = new ArrayList<>();
            for (Skill s : store.skills.values())
                if (s.active && used.contains(s.id) && (categoryId == null || categoryId.isBlank() || categoryId.equals(s.categoryId))) cols.add(s);
            cols.sort(Comparator.comparing((Skill s) -> store.categories.get(s.categoryId).order).thenComparingInt(s -> s.number));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (User u : people) {
                Map<String, Object> cells = new LinkedHashMap<>();
                for (Skill s : cols) {
                    int e = lv.get(u.id).getOrDefault(s.id, 0);
                    int p = pending.get(u.id).getOrDefault(s.id, 0);
                    if (e > 0 || p > 0) cells.put(s.id, List.of(e, p));
                }
                rows.add(Json.obj("userId", u.id, "cells", cells));
            }
            List<Map<String, Object>> summary = new ArrayList<>();
            for (Skill s : cols) {
                int three = 0;
                for (User u : people) if (lv.get(u.id).getOrDefault(s.id, 0) >= 3) three++;
                summary.add(Json.obj("skillId", s.id, "practitioners", three));
            }
            return Json.obj("scope", scope == null ? "team" : scope, "columns", cols.stream().map(s -> s.id).toList(), "rows", rows,
                    "summary", summary, "risks", busRisks(people, today));
        }
    }

    /**
     * BUS FACTOR: for every skill this group NEEDS (their current roles, and project slots they fill), how many of them
     * are at level 3 or more? 0 = nobody can do it, 1 = one person (if they are away, the work stops).
     */
    List<Map<String, Object>> busRisks(List<User> people, LocalDate today) {
        Map<String, Integer> needLevel = new HashMap<>();
        Map<String, Integer> needers = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (User u : people) ids.add(u.id);
        for (User u : people) {
            JobRole r = u.jobRoleId == null ? null : store.roles.get(u.jobRoleId);
            if (r == null) continue;
            for (Req q : r.reqs) if (q.must()) { needLevel.merge(q.skillId(), 3, Math::max); needers.merge(q.skillId(), 1, Integer::sum); }
        }
        for (Project p : store.projects.values()) if (p.busy())
            for (Project.Slot s : p.slots) if (s.assigneeId != null && ids.contains(s.assigneeId))
                for (Req q : s.reqs) if (q.must()) { needLevel.merge(q.skillId(), 3, Math::max); needers.merge(q.skillId(), 1, Integer::sum); }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String sid : needLevel.keySet()) {
            Skill s = store.skills.get(sid);
            if (s == null || !s.active) continue;
            List<String> holders = new ArrayList<>();
            for (User u : people) if (store.levels(u.id, false, today).getOrDefault(sid, 0) >= 3) holders.add(u.id);
            if (holders.size() < BUS_SAFE)
                out.add(Json.obj("skillId", sid, "holders", holders, "count", holders.size(), "neededBy", needers.get(sid)));
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> ((Number) m.get("count")).intValue())
                .thenComparing(m -> -((Number) m.get("neededBy")).intValue()));
        return out;
    }

    // ------------------------------------------------------------------ search

    public SkillQuery.Names names() {
        return new SkillQuery.Names() {
            public String resolve(String name) { Skill s = store.skillByName(name); return s == null ? null : s.id; }
            public List<String> suggest(String name) {
                String k = Text.key(name);
                List<Skill> c = new ArrayList<>();
                for (Skill s : store.skills.values()) {
                    if (!s.active) continue;
                    int d = Text.distance(k, Text.key(s.name));
                    for (String syn : s.synonyms) d = Math.min(d, Text.distance(k, Text.key(syn)));
                    boolean prefix = k.length() >= 3 && Text.key(s.name).startsWith(k);
                    if (d <= Math.max(1, k.length() / 4) || prefix) c.add(s);
                }
                c.sort(Comparator.comparingInt(s -> Text.distance(k, Text.key(s.name))));
                return c.stream().limit(3).map(s -> s.name).toList();
            }
            public String label(String id) { Skill s = store.skills.get(id); return s == null ? id : s.name; }
        };
    }

    public Map<String, Object> search(User viewer, String q, boolean includeClaimed) {
        synchronized (store) {
            SkillQuery.Node node;
            try {
                node = SkillQuery.parse(q, names());
            } catch (SkillQuery.Error e) {
                throw Problem.bad(e.getMessage()).with(Json.obj("suggestions", e.suggestions));
            }
            LocalDate today = Clock.today();
            Set<String> asked = SkillQuery.skillsOf(node);
            List<Map<String, Object>> hits = new ArrayList<>();
            for (User u : store.users.values()) {
                if (!u.active) continue;
                Map<String, Integer> sure = store.levels(u.id, false, today);
                Map<String, Integer> maybe = includeClaimed ? store.levels(u.id, true, today) : sure;
                if (!node.test(maybe)) continue;
                boolean unverified = includeClaimed && !node.test(sure);
                int score = 0;
                Map<String, Object> show = new LinkedHashMap<>();
                for (String sid : asked) {
                    int v = maybe.getOrDefault(sid, 0);
                    score += v;
                    show.put(sid, List.of(v, sure.getOrDefault(sid, 0)));
                }
                hits.add(Json.obj("userId", u.id, "score", score, "levels", show, "unverified", unverified,
                        "free", staffing.free(u.id, null)));
            }
            hits.sort(Comparator.comparing((Map<String, Object> m) -> (Boolean) m.get("unverified"))
                    .thenComparing(m -> -((Number) m.get("score")).intValue())
                    .thenComparing(m -> -((Number) m.get("free")).intValue()));
            store.log(viewer.id, "search", null, Text.clean(q, 120));
            return Json.obj("understood", node.show(names()), "skills", new ArrayList<>(asked), "hits", hits);
        }
    }

    // ------------------------------------------------------------------ projects

    public Map<String, Object> projects(User viewer) {
        synchronized (store) {
            List<Map<String, Object>> out = new ArrayList<>();
            List<Project> ps = new ArrayList<>(store.projects.values());
            ps.sort(Comparator.comparing((Project p) -> "DONE".equals(p.status)).thenComparing(p -> p.start));
            for (Project p : ps) out.add(projectView(viewer, p));
            return Json.obj("projects", out);
        }
    }

    public Map<String, Object> project(User viewer, String id) {
        synchronized (store) {
            Project p = store.projects.get(id);
            if (p == null) throw Problem.notFound("Project");
            return projectView(viewer, p);
        }
    }

    private Map<String, Object> projectView(User viewer, Project p) {
        LocalDate today = Clock.today();
        Map<String, Object> m = p.toJson();
        m.put("canEdit", Access.editProject(viewer, p));
        List<Map<String, Object>> slots = new ArrayList<>();
        int filled = 0;
        for (Project.Slot s : p.slots) {
            Map<String, Object> sm = s.toJson();
            if (s.assigneeId != null) {
                filled++;
                Readiness.Result r = Readiness.of(store.levels(s.assigneeId, false, today), s.reqs);
                sm.put("fit", r.percent());
                sm.put("mustMissing", r.mustMissing());
            }
            slots.add(sm);
        }
        m.put("slots", slots);
        m.put("filled", filled);
        return m;
    }

    // ------------------------------------------------------------------ verification queue

    public Map<String, Object> queue(User viewer, ProfileService profiles) {
        List<Map<String, Object>> q = profiles.queue(viewer);
        synchronized (store) {
            LocalDate today = Clock.today();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> item : q) {
                Claim c = (Claim) item.get("claim");
                Map<String, Object> v = claimView(c, true, viewer, today);
                v.put("userId", c.userId);
                v.put("why", item.get("why"));
                v.put("maxLevel", item.get("maxLevel"));
                v.put("ageHours", (Clock.millis() - c.claimedAt) / 3_600_000);
                Claim mine = store.claim(viewer.id, c.skillId);
                v.put("myLevel", mine == null ? 0 : store.effective(mine, today));
                out.add(v);
            }
            return Json.obj("items", out);
        }
    }

    // ------------------------------------------------------------------ reports

    public Map<String, Object> reports(User viewer) {
        Access.manager(viewer);
        synchronized (store) {
            LocalDate today = Clock.today();
            List<User> people = viewer.admin() ? scopePeople(viewer, "all") : scopePeople(viewer, "team");
            Set<String> ids = new HashSet<>();
            for (User u : people) ids.add(u.id);

            // inventory per category
            List<Map<String, Object>> inv = new ArrayList<>();
            List<Category> cats = new ArrayList<>(store.categories.values());
            cats.sort(Comparator.comparingInt(c -> c.order));
            for (Category c : cats) {
                int skills = 0, levels = 0, practitioners = 0;
                for (Skill s : store.skills.values()) if (s.active && s.categoryId.equals(c.id)) skills++;
                for (User u : people) for (Map.Entry<String, Integer> e : store.levels(u.id, false, today).entrySet()) {
                    Skill s = store.skills.get(e.getKey());
                    if (s.categoryId.equals(c.id)) { levels += e.getValue(); if (e.getValue() >= 3) practitioners++; }
                }
                inv.add(Json.obj("categoryId", c.id, "skills", skills, "levels", levels, "practitioners", practitioners));
            }

            // gaps in current roles, per skill
            Map<String, Integer> gaps = new HashMap<>();
            int ready = 0, withRole = 0;
            List<Integer> pcts = new ArrayList<>();
            for (User u : people) {
                JobRole r = u.jobRoleId == null ? null : store.roles.get(u.jobRoleId);
                if (r == null) continue;
                withRole++;
                Readiness.Result res = Readiness.of(store.levels(u.id, false, today), r.reqs);
                if (res.ready()) ready++;
                pcts.add(res.percent());
                for (Readiness.Line l : res.gaps()) gaps.merge(l.skillId(), 1, Integer::sum);
            }
            Map<String, Integer> open = new HashMap<>();
            for (Project p : store.projects.values()) if (p.busy())
                for (Project.Slot s : p.slots) if (s.assigneeId == null) for (Req q : s.reqs) open.merge(q.skillId(), 1, Integer::sum);
            Set<String> gapSkills = new HashSet<>(gaps.keySet());
            gapSkills.addAll(open.keySet());
            List<Map<String, Object>> shortages = new ArrayList<>();
            for (String sid : gapSkills) {
                int supply = 0;
                for (User u : people) if (store.levels(u.id, false, today).getOrDefault(sid, 0) >= 3) supply++;
                shortages.add(Json.obj("skillId", sid, "roleGaps", gaps.getOrDefault(sid, 0), "openSlots", open.getOrDefault(sid, 0), "supply", supply));
            }
            shortages.sort(Comparator.comparing((Map<String, Object> m) -> -(((Number) m.get("roleGaps")).intValue() + ((Number) m.get("openSlots")).intValue())));

            // certificates
            int valid = 0, expiring = 0, expired = 0;
            List<Map<String, Object>> soon = new ArrayList<>();
            for (Cert c : store.certs.values()) {
                if (!ids.contains(c.userId)) continue;
                switch (c.status(today)) {
                    case "EXPIRED" -> expired++;
                    case "EXPIRING" -> expiring++;
                    default -> valid++;
                }
                if (c.expires != null && c.daysLeft(today) <= 90) soon.add(certView(c, today));
            }
            soon.sort(Comparator.comparing(m -> String.valueOf(m.get("expires"))));

            // verification turnaround (last 90 days)
            long since = Clock.millis() - 90L * 24 * 3_600_000;
            List<Double> hours = new ArrayList<>();
            int pending = 0;
            long oldest = 0;
            for (Claim c : store.claims.values()) {
                if (!ids.contains(c.userId)) continue;
                if (c.status == ClaimStatus.VERIFIED && c.verifiedAt >= since && c.claimedAt > 0 && c.verifiedAt >= c.claimedAt)
                    hours.add((c.verifiedAt - c.claimedAt) / 3_600_000.0);
                if (c.status == ClaimStatus.CLAIMED) {
                    pending++;
                    oldest = Math.max(oldest, Clock.millis() - c.claimedAt);
                }
            }

            // goals
            int goalsOpen = 0, goalsDone = 0, overdue = 0, dropped = 0;
            for (Goal g : store.goals.values()) {
                if (!ids.contains(g.userId)) continue;
                if ("OPEN".equals(g.status)) { goalsOpen++; if (LocalDate.parse(g.due).isBefore(today)) overdue++; }
                else if (g.doneAt >= since) { if ("DONE".equals(g.status)) goalsDone++; else dropped++; }
            }

            // trend: the most demanded skills over the last 12 snapshots
            List<String> top = new ArrayList<>();
            for (Map<String, Object> s : shortages) { if (top.size() >= 5) break; top.add((String) s.get("skillId")); }
            List<Map<String, Object>> trend = new ArrayList<>();
            for (Snapshot sn : lastSnapshots(12)) {
                Map<String, Object> pt = Json.obj("month", sn.month);
                Map<String, Object> vals = new LinkedHashMap<>();
                for (String sid : top) vals.put(sid, sn.atLeast(sid, 3));
                int total = 0;
                for (String sid : sn.counts.keySet()) total += sn.atLeast(sid, 3);
                pt.put("values", vals);
                pt.put("total", total);
                trend.add(pt);
            }

            return Json.obj("scope", viewer.admin() ? "company" : "team", "people", people.size(), "inventory", inv,
                    "shortages", shortages.subList(0, Math.min(10, shortages.size())), "risks", busRisks(people, today),
                    "readyInRole", ready, "withRole", withRole, "readinessMedian", Stats.round1(Stats.median(pcts)),
                    "certs", Json.obj("valid", valid, "expiring", expiring, "expired", expired, "soon", soon),
                    "verify", Json.obj("count", hours.size(), "medianHours", Stats.round1(Stats.median(hours)),
                            "p85Hours", Stats.round1(Stats.percentile(hours, 85)), "pending", pending, "oldestHours", oldest / 3_600_000),
                    "goals", Json.obj("open", goalsOpen, "done", goalsDone, "dropped", dropped, "overdue", overdue),
                    "trendSkills", top, "trend", trend);
        }
    }

    private List<Snapshot> lastSnapshots(int n) {
        List<Snapshot> all = new ArrayList<>(store.snapshots.values());
        all.sort(Comparator.comparing(s -> s.month));
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    // ------------------------------------------------------------------ directory, notes, admin

    public Map<String, Object> directory(User viewer) {
        synchronized (store) {
            LocalDate today = Clock.today();
            List<Map<String, Object>> out = new ArrayList<>();
            for (User u : store.users.values()) {
                if (!u.active) continue;
                Map<String, Integer> lv = store.levels(u.id, false, today);
                List<Map.Entry<String, Integer>> top = new ArrayList<>(lv.entrySet());
                top.sort(Comparator.comparing((Map.Entry<String, Integer> e) -> -e.getValue()).thenComparing(e -> store.skills.get(e.getKey()).number));
                List<Object> best = new ArrayList<>();
                for (Map.Entry<String, Integer> e : top.subList(0, Math.min(6, top.size()))) best.add(List.of(e.getKey(), e.getValue()));
                out.add(Json.obj("userId", u.id, "top", best, "count", lv.size(), "free", staffing.free(u.id, null)));
            }
            return Json.obj("people", out);
        }
    }

    public List<Object> notes(User u) {
        synchronized (store) {
            List<Note> mine = new ArrayList<>();
            for (Note n : store.notes.values()) if (n.userId.equals(u.id)) mine.add(n);
            mine.sort(Comparator.comparingLong((Note n) -> -n.at));
            return new ArrayList<>(mine.subList(0, Math.min(40, mine.size())));
        }
    }

    public int read(User u, String id) {
        synchronized (store) {
            int n = 0;
            for (Note x : store.notes.values())
                if (x.userId.equals(u.id) && !x.read && (id == null || x.id.equals(id))) { x.read = true; n++; }
            if (n > 0) store.save("notes");
            return n;
        }
    }

    public Map<String, Object> admin(User viewer) {
        Access.admin(viewer);
        synchronized (store) {
            List<Object> log = new ArrayList<>();
            for (int i = store.audit.size() - 1; i >= 0 && log.size() < 250; i--) {
                AuditEntry a = store.audit.get(i);
                if (!"search".equals(a.action())) log.add(a);
            }
            List<Object> people = new ArrayList<>();
            for (User u : store.users.values()) {
                Map<String, Object> m = person(u);
                m.put("username", u.username);
                m.put("lastSeen", u.lastSeen);
                m.put("locked", u.lockedUntil > Clock.millis());
                people.add(m);
            }
            return Json.obj("people", people, "audit", log, "snapshots", store.snapshots.size());
        }
    }
}
