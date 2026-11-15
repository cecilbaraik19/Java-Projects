package com.cecil.sangam.service;

import com.cecil.sangam.core.DependencyGraph;
import com.cecil.sangam.core.MeetingFinder;
import com.cecil.sangam.core.TextDiff;
import com.cecil.sangam.core.WorkHours;
import com.cecil.sangam.model.AuditEntry;
import com.cecil.sangam.model.Channel;
import com.cecil.sangam.model.Doc;
import com.cecil.sangam.model.Handoff;
import com.cecil.sangam.model.Meeting;
import com.cecil.sangam.model.Message;
import com.cecil.sangam.model.Note;
import com.cecil.sangam.model.Project;
import com.cecil.sangam.model.Standup;
import com.cecil.sangam.model.Task;
import com.cecil.sangam.model.TaskStatus;
import com.cecil.sangam.model.User;
import com.cecil.sangam.store.DataStore;
import com.cecil.sangam.util.Clock;
import com.cecil.sangam.util.Json;
import com.cecil.sangam.util.Text;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds what the website shows. It only READS; every change is in the other services.
 * Everything here respects Access: nobody gets data about projects or channels they cannot see.
 */
public class ViewService {
    private final DataStore store;
    private final DocService docs;
    private final RhythmService rhythm;

    public ViewService(DataStore store, DocService docs, RhythmService rhythm) {
        this.store = store;
        this.docs = docs;
        this.rhythm = rhythm;
    }

    // ------------------------------------------------------------------ small pieces

    Map<String, Object> mini(String userId, long now) {
        User u = userId == null ? null : store.users.get(userId);
        if (u == null) return null;
        return Json.obj("id", u.id, "name", u.name, "username", u.username, "initials", PeopleService.initials(u.name),
                "city", u.city, "presence", PeopleService.presence(u, now), "role", u.role,
                "working", WorkHours.working(u, now));
    }

    /** People this person may see: everyone for members; for guests only people sharing a project. */
    List<User> visiblePeople(User viewer) {
        List<User> out = new ArrayList<>();
        for (User u : store.users.values()) {
            if (!u.active) continue;
            if (!viewer.guest() || u.id.equals(viewer.id)
                    || store.projects.values().stream().anyMatch(p -> p.has(viewer.id) && p.has(u.id))) out.add(u);
        }
        return out;
    }

    List<Project> visibleProjects(User u) {
        List<Project> out = new ArrayList<>();
        for (Project p : store.projects.values()) if (Access.seeProject(u, p) && !p.archived) out.add(p);
        return out;
    }

    public Map<String, Object> taskCard(Task t, long now) {
        List<String> waits = new ArrayList<>();
        for (String b : t.blockedBy) {
            Task x = store.tasks.get(b);
            if (x != null && x.status != TaskStatus.DONE) waits.add(x.key);
        }
        String today = Clock.today().toString();
        Project p = store.projects.get(t.projectId);
        return Json.obj("id", t.id, "key", t.key, "title", t.title, "status", t.status, "priority", t.priority,
                "assignee", mini(t.assigneeId, now), "due", t.due,
                "overdue", t.due != null && t.status != TaskStatus.DONE && t.due.compareTo(today) < 0,
                "dueToday", t.due != null && t.status != TaskStatus.DONE && t.due.equals(today),
                "waitingFor", waits, "estimate", t.estimate, "comments", t.comments.size(), "rank", t.rank,
                "version", t.version, "projectId", t.projectId, "projectKey", p == null ? "" : p.key,
                "projectColor", p == null ? "red" : p.color, "updatedAt", t.updatedAt);
    }

    // ------------------------------------------------------------------ home ("the desk")

    public Map<String, Object> home(User u) {
        synchronized (store) {
            long now = Clock.millis();
            Set<String> mine = new LinkedHashSet<>();
            for (Project p : visibleProjects(u)) mine.add(p.id);
            List<Task> myTasks = new ArrayList<>(), review = new ArrayList<>();
            for (Task t : store.tasks.values()) {
                if (!mine.contains(t.projectId)) continue;
                if (u.id.equals(t.assigneeId) && t.status != TaskStatus.DONE) myTasks.add(t);
                Project p = store.projects.get(t.projectId);
                if (t.status == TaskStatus.REVIEW && !u.id.equals(t.assigneeId) && p.has(u.id) && !u.guest()) review.add(t);
            }
            myTasks.sort(Comparator.comparing((Task t) -> t.status.ordinal() == 1 ? 0 : t.status.ordinal() == 2 ? 1 : 2)
                    .thenComparing(t -> t.due == null ? "9999" : t.due).thenComparing(t -> -t.priority.ordinal()));
            List<Map<String, Object>> tasks = new ArrayList<>();
            for (Task t : myTasks) tasks.add(taskCard(t, now));
            List<Map<String, Object>> reviews = new ArrayList<>();
            for (Task t : review) reviews.add(taskCard(t, now));

            // mentions of me in the last 7 days, and whether I have answered (written there after it)
            List<Map<String, Object>> mentions = new ArrayList<>();
            List<Message> all = new ArrayList<>(store.messages.values());
            for (int i = all.size() - 1; i >= 0 && mentions.size() < 8; i--) {
                Message m = all.get(i);
                if (m.deleted || !m.mentions.contains(u.id) || now - m.at > 7 * WorkHours.DAY) continue;
                Channel c = store.channels.get(m.channelId);
                if (!Access.seeChannel(store, u, c)) continue;
                boolean answered = false;
                for (int j = i + 1; j < all.size(); j++) {
                    Message r = all.get(j);
                    if (r.channelId.equals(m.channelId) && u.id.equals(r.authorId)) { answered = true; break; }
                }
                mentions.add(Json.obj("message", message(m, u, now), "channel", channelName(c, u), "channelId", c.id, "answered", answered));
            }

            List<Map<String, Object>> meetings = new ArrayList<>();
            List<Meeting> ms = new ArrayList<>();
            for (Meeting m : store.meetings.values()) if (!m.cancelled && m.involves(u.id) && m.end > now) ms.add(m);
            ms.sort(Comparator.comparingLong(m -> m.start));
            for (Meeting m : ms.subList(0, Math.min(4, ms.size()))) meetings.add(meeting(m, u, now));

            List<Map<String, Object>> handoffs = new ArrayList<>();
            for (Handoff h : store.handoffs.values())
                if (h.toId.equals(u.id) && h.ackAt == 0 && h.deliverAt <= now) handoffs.add(handoff(h, now));

            String today = WorkHours.local(u, now).toLocalDate().toString();
            Standup st = null;
            for (Standup s : store.standups.values()) if (s.userId.equals(u.id) && s.date.equals(today)) st = s;

            List<Map<String, Object>> team = new ArrayList<>();
            List<User> people = visiblePeople(u);
            people.sort(Comparator.comparing((User x) -> !WorkHours.working(x, now)).thenComparingLong(x -> WorkHours.nextStart(x, now)));
            for (User x : people) team.add(PeopleService.card(x, now));
            int[] counts = WorkHours.counts(people.stream().filter(x -> !x.guest()).toList(), LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneOffset.UTC));
            int covered = 0;
            for (int c : counts) if (c > 0) covered++;

            return Json.obj("me", PeopleService.card(u, now), "tasks", tasks, "reviews", reviews, "mentions", mentions,
                    "meetings", meetings, "handoffs", handoffs, "standup", st == null ? null : st.toJson(),
                    "standupDate", today, "team", team, "coverageHours", covered / 4.0,
                    "workingNow", people.stream().filter(x -> WorkHours.working(x, now)).count());
        }
    }

    // ------------------------------------------------------------------ people and time zones

    public Map<String, Object> zones(User u, LocalDate day, String projectId) {
        synchronized (store) {
            long now = Clock.millis();
            List<User> people = new ArrayList<>();
            Project p = null;
            if (!Text.blank(projectId)) {
                p = Access.project(store, u, projectId);
                for (String id : p.memberIds) { User x = store.users.get(id); if (x != null && x.active) people.add(x); }
            } else people = visiblePeople(u);
            // sort by UTC offset, east to west, so the strip looks like the map
            long base = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            people.sort(Comparator.comparingInt((User x) -> -WorkHours.local(x, base + 12 * WorkHours.HOUR).getOffset().getTotalSeconds()).thenComparing(x -> x.name));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (User x : people) {
                List<Integer> costs = new ArrayList<>();
                List<Integer> localHours = new ArrayList<>();
                for (int i = 0; i < WorkHours.SLOTS; i++) {
                    long t = base + i * 15 * WorkHours.MIN;
                    costs.add(WorkHours.stepCost(x, t));
                    if (i % 4 == 0) localHours.add(WorkHours.local(x, t).getHour());
                }
                rows.add(Json.obj("person", PeopleService.card(x, now), "costs", costs, "localHours", localHours));
            }
            List<User> team = people.stream().filter(x -> !x.guest()).toList();
            int[] counts = WorkHours.counts(team, day);
            int[] all = WorkHours.longestRun(counts, team.size());
            int max = 0;
            for (int c : counts) max = Math.max(max, c);
            int[] most = WorkHours.longestRun(counts, max);
            int covered = 0;
            for (int c : counts) if (c > 0) covered++;
            List<Map<String, Object>> pairs = new ArrayList<>();
            LocalDate monday = day.with(DayOfWeek.MONDAY);
            for (int i = 0; i < team.size(); i++)
                for (int j = i + 1; j < team.size(); j++)
                    pairs.add(Json.obj("a", team.get(i).id, "b", team.get(j).id,
                            "hours", Math.round(WorkHours.weeklyOverlapHours(team.get(i), team.get(j), monday) * 4) / 4.0));
            return Json.obj("date", day.toString(), "dayStart", base, "now", now, "rows", rows,
                    "counts", ints(counts), "teamSize", team.size(),
                    "allTogether", Json.obj("startSlot", all[0], "slots", all[1], "start", all[0] < 0 ? 0 : base + all[0] * 15 * WorkHours.MIN),
                    "most", Json.obj("people", max, "startSlot", most[0], "slots", most[1]),
                    "coverageHours", covered / 4.0, "pairs", pairs,
                    "project", p == null ? null : Json.obj("id", p.id, "name", p.name));
        }
    }

    private static List<Integer> ints(int[] a) {
        List<Integer> l = new ArrayList<>();
        for (int x : a) l.add(x);
        return l;
    }

    // ------------------------------------------------------------------ projects and the board

    public List<Map<String, Object>> projects(User u) {
        synchronized (store) {
            long now = Clock.millis();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Project p : visibleProjects(u)) {
                int[] n = new int[4];
                for (Task t : store.tasks.values()) if (t.projectId.equals(p.id)) n[t.status.ordinal()]++;
                List<Map<String, Object>> members = new ArrayList<>();
                int awake = 0;
                for (String id : p.memberIds) {
                    Map<String, Object> m = mini(id, now);
                    if (m != null) { members.add(m); if (Boolean.TRUE.equals(m.get("working"))) awake++; }
                }
                out.add(Json.obj("id", p.id, "key", p.key, "name", p.name, "description", p.description, "color", p.color,
                        "counts", ints(n), "members", members, "workingNow", awake, "channelId", p.channelId));
            }
            return out;
        }
    }

    public Map<String, Object> board(User u, String projectId) {
        synchronized (store) {
            Project p = Access.project(store, u, projectId);
            long now = Clock.millis();
            List<Task> list = new ArrayList<>();
            for (Task t : store.tasks.values()) if (t.projectId.equals(p.id)) list.add(t);
            Map<String, List<Map<String, Object>>> cols = new LinkedHashMap<>();
            for (TaskStatus s : TaskStatus.values()) cols.put(s.name(), new ArrayList<>());
            list.sort(Comparator.comparingDouble(t -> t.rank));
            for (Task t : list) {
                if (t.status == TaskStatus.DONE && now - t.doneAt > 14 * WorkHours.DAY) continue;   // old done work is hidden
                cols.get(t.status.name()).add(taskCard(t, now));
            }
            List<Map<String, Object>> columns = new ArrayList<>();
            for (TaskStatus s : TaskStatus.values()) {
                int limit = s == TaskStatus.DOING ? p.wipDoing : s == TaskStatus.REVIEW ? p.wipReview : 0;
                long count = list.stream().filter(t -> t.status == s).count();
                columns.add(Json.obj("status", s, "label", s.label, "limit", limit, "count", count, "tasks", cols.get(s.name())));
            }
            List<Task> path = DependencyGraph.criticalPath(list);
            int hours = 0;
            List<String> keys = new ArrayList<>();
            for (Task t : path) { hours += Math.max(1, t.estimate); keys.add(t.key); }
            List<Map<String, Object>> members = new ArrayList<>();
            for (String id : p.memberIds) { User x = store.users.get(id); if (x != null && x.active) members.add(PeopleService.card(x, now)); }
            List<Map<String, Object>> handoffs = new ArrayList<>();
            List<Handoff> hs = new ArrayList<>();
            for (Handoff h : store.handoffs.values()) if (h.projectId.equals(p.id)) hs.add(h);
            hs.sort(Comparator.comparingLong((Handoff h) -> h.at).reversed());
            for (Handoff h : hs.subList(0, Math.min(8, hs.size()))) handoffs.add(handoff(h, now));
            return Json.obj("project", Json.obj("id", p.id, "key", p.key, "name", p.name, "description", p.description,
                            "color", p.color, "wipDoing", p.wipDoing, "wipReview", p.wipReview, "reviewRequired", p.reviewRequired,
                            "channelId", p.channelId, "memberIds", p.memberIds),
                    "columns", columns, "critical", Json.obj("keys", keys, "hours", hours),
                    "members", members, "canWork", Access.workOn(u, p), "personalWip", WorkService.PERSONAL_WIP,
                    "handoffs", handoffs, "batonOrder", u.guest() ? List.of() : rhythm.candidates(p, u, now),
                    "docs", docList(u, p, now));
        }
    }

    public Map<String, Object> task(User u, Task t) {
        synchronized (store) {
            long now = Clock.millis();
            Project p = store.projects.get(t.projectId);
            Map<String, Object> out = taskCard(t, now);
            out.put("description", t.description);
            out.put("createdBy", mini(t.createdBy, now));
            out.put("createdAt", t.createdAt);
            out.put("startedAt", t.startedAt);
            out.put("doneAt", t.doneAt);
            out.put("updatedBy", mini(t.updatedBy, now));
            out.put("assigneeId", t.assigneeId);
            List<Map<String, Object>> blockers = new ArrayList<>();
            for (String b : t.blockedBy) { Task x = store.tasks.get(b); if (x != null) blockers.add(Json.obj("id", x.id, "key", x.key, "title", x.title, "status", x.status)); }
            out.put("blockedBy", blockers);
            List<Map<String, Object>> dependents = new ArrayList<>();
            for (Task x : store.tasks.values()) if (x.blockedBy.contains(t.id)) dependents.add(Json.obj("id", x.id, "key", x.key, "title", x.title, "status", x.status));
            out.put("dependents", dependents);
            List<Map<String, Object>> comments = new ArrayList<>();
            for (Map<String, Object> c : t.comments) comments.add(Json.obj("by", mini(Json.str(c, "by"), now), "at", c.get("at"), "text", c.get("text")));
            out.put("commentList", comments);
            List<Map<String, Object>> hist = new ArrayList<>();
            for (Map<String, Object> h : t.history) hist.add(Json.obj("by", store.userName(Json.str(h, "by")), "at", h.get("at"), "what", h.get("what")));
            Collections.reverse(hist);
            out.put("history", hist);
            List<Map<String, Object>> options = new ArrayList<>();
            for (Task x : store.tasks.values())
                if (x.projectId.equals(p.id) && !x.id.equals(t.id)) options.add(Json.obj("id", x.id, "key", x.key, "title", x.title, "status", x.status));
            out.put("taskOptions", options);
            List<Map<String, Object>> members = new ArrayList<>();
            for (String id : p.memberIds) { User x = store.users.get(id); if (x != null && x.active && !x.guest()) members.add(mini(id, now)); }
            out.put("members", members);
            out.put("canWork", Access.workOn(u, p));
            out.put("project", Json.obj("id", p.id, "key", p.key, "name", p.name, "reviewRequired", p.reviewRequired));
            return out;
        }
    }

    // ------------------------------------------------------------------ chat

    String channelName(Channel c, User viewer) {
        if (c.kind == Channel.Kind.DIRECT) {
            for (String id : c.memberIds) if (!id.equals(viewer.id)) return store.userName(id);
            return "Direct message";
        }
        return "#" + c.name;
    }

    public List<Map<String, Object>> channels(User u) {
        synchronized (store) {
            long now = Clock.millis();
            Map<String, long[]> counts = new LinkedHashMap<>();   // channelId -> {unread, mentions, lastAt}
            for (Message m : store.messages.values()) {
                long[] c = counts.computeIfAbsent(m.channelId, k -> new long[3]);
                c[2] = Math.max(c[2], m.at);
                if (m.deleted || u.id.equals(m.authorId) || m.seq <= u.readUpTo.getOrDefault(m.channelId, 0L)) continue;
                c[0]++;
                if (m.mentions.contains(u.id)) c[1]++;
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Channel c : store.channels.values()) {
                if (!Access.seeChannel(store, u, c)) continue;
                if (c.kind == Channel.Kind.PROJECT) { Project p = store.projects.get(c.projectId); if (p == null || p.archived) continue; }
                long[] n = counts.getOrDefault(c.id, new long[3]);
                Map<String, Object> other = null;
                if (c.kind == Channel.Kind.DIRECT) for (String id : c.memberIds) if (!id.equals(u.id)) other = mini(id, now);
                out.add(Json.obj("id", c.id, "name", channelName(c, u), "kind", c.kind, "topic", c.topic, "unread", n[0],
                        "mentions", n[1], "lastAt", n[2], "other", other, "projectId", c.projectId));
            }
            return out;
        }
    }

    public Map<String, Object> message(Message m, User viewer, long now) {
        List<Map<String, Object>> reactions = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : m.reactions.entrySet()) {
            List<String> names = new ArrayList<>();
            for (String id : e.getValue()) names.add(store.userName(id));
            reactions.add(Json.obj("emoji", e.getKey(), "count", e.getValue().size(), "mine", e.getValue().contains(viewer.id), "names", names));
        }
        int replies = 0;
        long lastReply = 0;
        if (m.parentId == null)
            for (Message r : store.messages.values()) if (m.id.equals(r.parentId) && !r.deleted) { replies++; lastReply = Math.max(lastReply, r.at); }
        User a = store.users.get(m.authorId);
        String authorLocal = null;
        if (a != null) { var z = WorkHours.local(a, m.at); authorLocal = String.format("%02d:%02d", z.getHour(), z.getMinute()); }
        return Json.obj("id", m.id, "seq", m.seq, "author", mini(m.authorId, now), "text", m.deleted ? "" : m.text, "at", m.at,
                "authorLocal", authorLocal, "authorCity", a == null ? "" : a.city,
                "editedAt", m.editedAt, "deleted", m.deleted, "urgent", m.urgent, "kind", m.kind, "parentId", m.parentId,
                "mentionsMe", m.mentions.contains(viewer.id), "reactions", reactions, "replies", replies, "lastReplyAt", lastReply,
                "mine", viewer.id.equals(m.authorId), "canEdit", viewer.id.equals(m.authorId) && !m.deleted && now - m.at < ChatService.EDIT_WINDOW);
    }

    public Map<String, Object> channel(User u, String id, long beforeSeq) {
        synchronized (store) {
            Channel c = Access.channel(store, u, id);
            long now = Clock.millis();
            List<Message> top = new ArrayList<>();
            for (Message m : store.messages.values()) if (m.channelId.equals(c.id) && m.parentId == null && (beforeSeq <= 0 || m.seq < beforeSeq)) top.add(m);
            top.sort(Comparator.comparingLong(m -> m.seq));
            boolean more = top.size() > 80;
            if (more) top = top.subList(top.size() - 80, top.size());
            List<Map<String, Object>> msgs = new ArrayList<>();
            for (Message m : top) msgs.add(message(m, u, now));
            List<Map<String, Object>> members = new ArrayList<>();
            List<User> mem = Access.members(store, c);
            mem.sort(Comparator.comparing((User x) -> !WorkHours.working(x, now)).thenComparingLong(x -> WorkHours.nextStart(x, now)));
            int awake = 0;
            for (User x : mem) { members.add(PeopleService.card(x, now)); if (WorkHours.working(x, now)) awake++; }
            Project p = c.projectId == null ? null : store.projects.get(c.projectId);
            return Json.obj("channel", Json.obj("id", c.id, "name", channelName(c, u), "kind", c.kind, "topic", c.topic,
                            "projectId", c.projectId, "projectName", p == null ? null : p.name),
                    "messages", msgs, "more", more, "lastRead", u.readUpTo.getOrDefault(c.id, 0L),
                    "members", members, "workingNow", awake, "urgentLeft", ChatService.URGENT_PER_DAY - urgentToday(u, now),
                    "reactions", ChatService.REACTIONS);
        }
    }

    private int urgentToday(User u, long now) {
        int n = 0;
        for (Message m : store.messages.values()) if (m.urgent && u.id.equals(m.authorId) && now - m.at < WorkHours.DAY) n++;
        return n;
    }

    public Map<String, Object> thread(User u, String messageId) {
        synchronized (store) {
            Message m = store.messages.get(messageId);
            if (m == null || !Access.seeChannel(store, u, store.channels.get(m.channelId))) throw Problem.notFound("Message");
            if (m.parentId != null) m = store.messages.get(m.parentId);
            long now = Clock.millis();
            List<Map<String, Object>> replies = new ArrayList<>();
            for (Message r : store.messages.values()) if (m.id.equals(r.parentId)) replies.add(message(r, u, now));
            return Json.obj("parent", message(m, u, now), "replies", replies);
        }
    }

    // ------------------------------------------------------------------ documents

    List<Map<String, Object>> docList(User u, Project p, long now) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Doc d : store.docs.values()) {
            if (!d.projectId.equals(p.id)) continue;
            List<String> eds = new ArrayList<>();
            for (String id : docs.editors(d.id, u.id)) eds.add(store.userName(id));
            out.add(Json.obj("id", d.id, "title", d.title, "version", d.version, "updatedAt", d.updatedAt,
                    "updatedBy", store.userName(d.updatedBy), "lines", TextDiff.lines(d.body).size(), "editing", eds));
        }
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("updatedAt")).reversed());
        return out;
    }

    public List<Map<String, Object>> allDocs(User u) {
        synchronized (store) {
            long now = Clock.millis();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Project p : visibleProjects(u)) {
                for (Map<String, Object> d : docList(u, p, now)) {
                    d.put("projectId", p.id);
                    d.put("projectName", p.name);
                    d.put("projectColor", p.color);
                    out.add(d);
                }
            }
            out.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("updatedAt")).reversed());
            return out;
        }
    }

    public Map<String, Object> doc(User u, String id) {
        synchronized (store) {
            Doc d = docs.doc(u, id);
            Project p = store.projects.get(d.projectId);
            List<Map<String, Object>> versions = new ArrayList<>();
            versions.add(Json.obj("v", d.version, "by", store.userName(d.updatedBy), "at", d.updatedAt, "note", d.note));
            for (int i = d.history.size() - 1; i >= 0; i--) {
                Map<String, Object> h = d.history.get(i);
                versions.add(Json.obj("v", h.get("v"), "by", store.userName(Json.str(h, "by")), "at", h.get("at"), "note", Json.str(h, "note", "")));
            }
            List<String> eds = new ArrayList<>();
            for (String e : docs.editors(d.id, u.id)) eds.add(store.userName(e));
            return Json.obj("id", d.id, "title", d.title, "body", d.body, "version", d.version, "updatedAt", d.updatedAt,
                    "updatedBy", store.userName(d.updatedBy), "versions", versions, "editing", eds,
                    "project", Json.obj("id", p.id, "name", p.name, "key", p.key, "color", p.color),
                    "canEdit", Access.workOn(u, p));
        }
    }

    public Map<String, Object> docDiff(User u, String id, int from, int to) {
        synchronized (store) {
            Doc d = docs.doc(u, id);
            String a = d.bodyAt(from), b = d.bodyAt(to);
            if (a == null || b == null) throw Problem.notFound("Version");
            List<String> la = TextDiff.lines(a), lb = TextDiff.lines(b);
            int[] st = TextDiff.stats(la, lb);
            return Json.obj("from", from, "to", to, "lines", TextDiff.diff(la, lb), "added", st[0], "removed", st[1]);
        }
    }

    // ------------------------------------------------------------------ meetings

    Map<String, Object> meeting(Meeting m, User viewer, long now) {
        List<Map<String, Object>> who = new ArrayList<>();
        for (String id : m.everyone()) {
            User x = store.users.get(id);
            if (x == null) continue;
            var z = WorkHours.local(x, m.start);
            int c = m.costs.getOrDefault(id, 0);
            who.add(Json.obj("user", mini(id, now), "local", String.format("%s %02d:%02d", z.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH), z.getHour(), z.getMinute()),
                    "city", x.city, "cost", c, "label", WorkHours.costLabel(c)));
        }
        Project p = m.projectId == null ? null : store.projects.get(m.projectId);
        return Json.obj("id", m.id, "title", m.title, "start", m.start, "end", m.end, "agenda", m.agenda, "cancelled", m.cancelled,
                "organizer", mini(m.organizerId, now), "people", who, "mine", m.organizerId.equals(viewer.id),
                "project", p == null ? null : Json.obj("id", p.id, "name", p.name), "live", m.start <= now && now < m.end && !m.cancelled);
    }

    public Map<String, Object> meetings(User u) {
        synchronized (store) {
            long now = Clock.millis();
            List<Meeting> up = new ArrayList<>(), past = new ArrayList<>();
            for (Meeting m : store.meetings.values()) {
                if (!m.involves(u.id) && !u.admin()) continue;
                if (!m.involves(u.id) && u.admin() && m.end <= now - 14 * WorkHours.DAY) continue;
                if (m.end > now) { if (m.involves(u.id)) up.add(m); }
                else if (m.end > now - 21 * WorkHours.DAY && m.involves(u.id)) past.add(m);
            }
            up.sort(Comparator.comparingLong(m -> m.start));
            past.sort(Comparator.comparingLong((Meeting m) -> m.start).reversed());
            List<Map<String, Object>> a = new ArrayList<>(), b = new ArrayList<>();
            for (Meeting m : up) a.add(meeting(m, u, now));
            for (Meeting m : past.subList(0, Math.min(15, past.size()))) b.add(meeting(m, u, now));
            List<Map<String, Object>> ledger = new ArrayList<>();
            List<User> people = new ArrayList<>(visiblePeople(u));
            people.removeIf(User::guest);
            people.sort(Comparator.comparingDouble((User x) -> -x.pain));
            for (User x : people) {
                int count = 0, bad = 0;
                for (Meeting m : store.meetings.values()) {
                    if (m.cancelled || !m.involves(x.id) || m.start < now - 28 * WorkHours.DAY || m.start > now) continue;
                    count++;
                    if (m.costs.getOrDefault(x.id, 0) > 0) bad++;
                }
                ledger.add(Json.obj("user", mini(x.id, now), "city", x.city, "pain", Math.round(x.pain * 10) / 10.0, "calls", count, "outside", bad));
            }
            return Json.obj("upcoming", a, "past", b, "ledger", ledger, "painWeight", MeetingFinder.PAIN_WEIGHT);
        }
    }

    // ------------------------------------------------------------------ handoffs

    Map<String, Object> handoff(Handoff h, long now) {
        Project p = store.projects.get(h.projectId);
        return Json.obj("id", h.id, "from", mini(h.fromId, now), "to", mini(h.toId, now), "text", h.text, "at", h.at,
                "deliverAt", h.deliverAt, "ackAt", h.ackAt, "projectId", h.projectId, "projectName", p == null ? "" : p.name);
    }

    // ------------------------------------------------------------------ notifications

    public Map<String, Object> notes(User u) {
        synchronized (store) {
            long now = Clock.millis();
            List<Map<String, Object>> out = new ArrayList<>();
            int unread = 0, waiting = 0;
            List<Note> list = new ArrayList<>();
            for (Note n : store.notes.values()) {
                if (!u.id.equals(n.userId)) continue;
                if (n.deliverAt > now) { waiting++; continue; }   // still in quiet hours / focus time
                list.add(n);
                if (!n.read) unread++;
            }
            list.sort(Comparator.comparingLong((Note n) -> n.deliverAt).reversed());
            for (Note n : list.subList(0, Math.min(40, list.size())))
                out.add(Json.obj("id", n.id, "text", n.text, "link", n.link, "at", n.at, "deliverAt", n.deliverAt, "read", n.read,
                        "urgent", n.urgent, "held", n.deliverAt - n.at > 5 * WorkHours.MIN));
            return Json.obj("notes", out, "unread", unread, "waiting", waiting);
        }
    }

    // ------------------------------------------------------------------ search

    public Map<String, Object> search(User u, String q) {
        String s = q == null ? "" : q.trim().toLowerCase();
        if (s.length() < 2) throw Problem.bad("Type at least 2 letters.");
        if (s.length() > 60) s = s.substring(0, 60);
        synchronized (store) {
            long now = Clock.millis();
            List<Map<String, Object>> tasks = new ArrayList<>(), msgs = new ArrayList<>(), dl = new ArrayList<>();
            for (Task t : store.tasks.values()) {
                if (tasks.size() >= 12) break;
                if (!Access.seeProject(u, store.projects.get(t.projectId))) continue;
                if (t.key.toLowerCase().equals(s) || t.title.toLowerCase().contains(s) || t.description.toLowerCase().contains(s)) tasks.add(taskCard(t, now));
            }
            List<Message> all = new ArrayList<>(store.messages.values());
            for (int i = all.size() - 1; i >= 0 && msgs.size() < 15; i--) {
                Message m = all.get(i);
                if (m.deleted || !m.text.toLowerCase().contains(s)) continue;
                Channel c = store.channels.get(m.channelId);
                if (!Access.seeChannel(store, u, c)) continue;
                msgs.add(Json.obj("message", message(m, u, now), "channel", channelName(c, u), "channelId", c.id, "snippet", snippet(m.text, s)));
            }
            for (Doc d : store.docs.values()) {
                if (dl.size() >= 10) break;
                if (!Access.seeProject(u, store.projects.get(d.projectId))) continue;
                if (d.title.toLowerCase().contains(s) || d.body.toLowerCase().contains(s))
                    dl.add(Json.obj("id", d.id, "title", d.title, "snippet", snippet(d.body, s), "project", store.projects.get(d.projectId).name));
            }
            return Json.obj("q", q, "tasks", tasks, "messages", msgs, "docs", dl);
        }
    }

    static String snippet(String text, String q) {
        String low = text.toLowerCase();
        int i = low.indexOf(q);
        if (i < 0) return Text.clean(text, 120);
        int a = Math.max(0, i - 50), b = Math.min(text.length(), i + q.length() + 70);
        return (a > 0 ? "…" : "") + Text.clean(text.substring(a, b), 200) + (b < text.length() ? "…" : "");
    }

    // ------------------------------------------------------------------ reports

    public Map<String, Object> report(User u, String projectId) {
        synchronized (store) {
            Project p = Access.project(store, u, projectId);
            if (u.guest()) throw Problem.forbidden("Reports are for team members.");
            long now = Clock.millis();
            long from = now - 28 * WorkHours.DAY;
            List<Task> list = new ArrayList<>();
            for (Task t : store.tasks.values()) if (t.projectId.equals(p.id)) list.add(t);
            List<User> team = new ArrayList<>();
            for (String id : p.memberIds) { User x = store.users.get(id); if (x != null && x.active && !x.guest()) team.add(x); }

            // flow: done per week (4 weeks, Monday to Sunday, UTC)
            LocalDate thisMonday = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneOffset.UTC).with(DayOfWeek.MONDAY);
            List<Map<String, Object>> weeks = new ArrayList<>();
            for (int w = 3; w >= 0; w--) {
                LocalDate mon = thisMonday.minusWeeks(w);
                long a = mon.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), b = a + 7 * WorkHours.DAY;
                int done = 0, started = 0;
                for (Task t : list) {
                    if (t.status == TaskStatus.DONE && t.doneAt >= a && t.doneAt < b) done++;
                    if (t.startedAt >= a && t.startedAt < b) started++;
                }
                weeks.add(Json.obj("week", mon.toString(), "done", done, "started", started));
            }
            List<Double> cycle = new ArrayList<>();
            for (Task t : list) if (t.status == TaskStatus.DONE && t.doneAt >= from && t.startedAt > 0) cycle.add((t.doneAt - t.startedAt) / (double) WorkHours.HOUR);
            int[] cols = new int[4];
            int overdue = 0, blocked = 0;
            String today = Clock.today().toString();
            for (Task t : list) {
                cols[t.status.ordinal()]++;
                if (t.status != TaskStatus.DONE && t.due != null && t.due.compareTo(today) < 0) overdue++;
                if (t.status != TaskStatus.DONE && DependencyGraph.blocked(store.tasks, t)) blocked++;
            }

            // mentions in the project channel: how long until the person wrote back?
            List<Double> wall = new ArrayList<>(), inHours = new ArrayList<>();
            int mentions = 0, offHours = 0, unanswered = 0;
            List<Message> msgs = new ArrayList<>();
            for (Message m : store.messages.values()) if (m.channelId.equals(p.channelId) && !m.deleted) msgs.add(m);
            msgs.sort(Comparator.comparingLong(m -> m.seq));
            Map<String, List<Double>> perPerson = new LinkedHashMap<>();
            for (int i = 0; i < msgs.size(); i++) {
                Message m = msgs.get(i);
                if (m.at < from) continue;
                for (String who : m.mentions) {
                    if (who.equals(m.authorId)) continue;
                    User x = store.users.get(who);
                    if (x == null) continue;
                    mentions++;
                    if (!WorkHours.working(x, m.at)) offHours++;
                    long reply = 0;
                    for (int j = i + 1; j < msgs.size(); j++) if (who.equals(msgs.get(j).authorId)) { reply = msgs.get(j).at; break; }
                    if (reply == 0) { unanswered++; continue; }
                    wall.add((reply - m.at) / (double) WorkHours.HOUR);
                    double ih = WorkHours.workingMillisBetween(x, m.at, reply) / (double) WorkHours.HOUR;
                    inHours.add(ih);
                    perPerson.computeIfAbsent(who, k -> new ArrayList<>()).add(ih);
                }
            }

            // stand-ups: share of each person's last 10 working days with a stand-up
            List<Map<String, Object>> people = new ArrayList<>();
            for (User x : team) {
                LocalDate d = WorkHours.local(x, now).toLocalDate();
                int days = 0, posted = 0;
                for (int k = 1; k <= 30 && days < 10; k++) {
                    LocalDate day = d.minusDays(k);
                    if (!x.workDays.contains(day.getDayOfWeek().getValue())) continue;
                    days++;
                    for (Standup s : store.standups.values()) if (s.userId.equals(x.id) && s.date.equals(day.toString())) { posted++; break; }
                }
                int doing = 0, done = 0;
                for (Task t : list) {
                    if (x.id.equals(t.assigneeId) && t.status.active()) doing++;
                    if (x.id.equals(t.assigneeId) && t.status == TaskStatus.DONE && t.doneAt >= from) done++;
                }
                people.add(Json.obj("user", mini(x.id, now), "city", x.city, "standups", days == 0 ? 0 : Math.round(100.0 * posted / days),
                        "doing", doing, "done", done, "pain", Math.round(x.pain * 10) / 10.0,
                        "reply", median(perPerson.getOrDefault(x.id, List.of()))));
            }

            int hand = 0, acked = 0;
            List<Double> ackHours = new ArrayList<>();
            for (Handoff h : store.handoffs.values()) {
                if (!h.projectId.equals(p.id) || h.at < from) continue;
                hand++;
                if (h.ackAt > 0) { acked++; ackHours.add(Math.max(0, h.ackAt - h.deliverAt) / (double) WorkHours.HOUR); }
            }
            int meetings = 0, seats = 0, hurt = 0;
            for (Meeting m : store.meetings.values()) {
                if (m.cancelled || !p.id.equals(m.projectId) || m.start < from || m.start > now) continue;
                meetings++;
                for (int c : m.costs.values()) { seats++; if (c > 0) hurt++; }
            }
            int[] counts = WorkHours.counts(team, LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneOffset.UTC));
            int covered = 0;
            for (int c : counts) if (c > 0) covered++;
            int[] together = WorkHours.longestRun(counts, team.size());

            return Json.obj("project", Json.obj("id", p.id, "name", p.name, "key", p.key, "color", p.color),
                    "flow", Json.obj("weeks", weeks, "cycleMedianHours", median(cycle), "cycleP85Hours", percentile(cycle, 0.85),
                            "columns", ints(cols), "limits", List.of(0, p.wipDoing, p.wipReview, 0),
                            "overdue", overdue, "blocked", blocked),
                    "talk", Json.obj("mentions", mentions, "offHoursShare", mentions == 0 ? 0 : Math.round(100.0 * offHours / mentions),
                            "wallMedianHours", median(wall), "inHoursMedianHours", median(inHours), "unanswered", unanswered),
                    "handoffs", Json.obj("count", hand, "acked", acked, "ackMedianHours", median(ackHours)),
                    "meetings", Json.obj("count", meetings, "outsideShare", seats == 0 ? 0 : Math.round(100.0 * hurt / seats)),
                    "time", Json.obj("coverageHours", covered / 4.0, "togetherHours", together[1] / 4.0, "teamSize", team.size(), "counts", ints(counts)),
                    "people", people);
        }
    }

    static double median(List<Double> v) { return percentile(v, 0.5); }

    static double percentile(List<Double> v, double q) {
        if (v.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        double pos = q * (s.size() - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        double val = s.get(lo) + (s.get(hi) - s.get(lo)) * (pos - lo);
        return Math.round(val * 10) / 10.0;
    }

    // ------------------------------------------------------------------ admin

    public List<Map<String, Object>> audit(User u, int max) {
        Access.admin(u);
        synchronized (store) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = store.audit.size() - 1; i >= 0 && out.size() < max; i--) {
                AuditEntry a = store.audit.get(i);
                out.add(Json.obj("at", a.at(), "who", a.userId() == null ? "—" : store.userName(a.userId()), "action", a.action(),
                        "target", a.target(), "detail", a.detail()));
            }
            return out;
        }
    }

    public List<Map<String, Object>> people(User u) {
        synchronized (store) {
            long now = Clock.millis();
            List<Map<String, Object>> out = new ArrayList<>();
            for (User x : visiblePeople(u)) out.add(PeopleService.card(x, now));
            return out;
        }
    }
}
