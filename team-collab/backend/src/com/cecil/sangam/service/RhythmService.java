package com.cecil.sangam.service;

import com.cecil.sangam.core.EventBus;
import com.cecil.sangam.core.Mentions;
import com.cecil.sangam.core.WorkHours;
import com.cecil.sangam.model.Handoff;
import com.cecil.sangam.model.Note;
import com.cecil.sangam.model.Project;
import com.cecil.sangam.model.Standup;
import com.cecil.sangam.model.User;
import com.cecil.sangam.store.DataStore;
import com.cecil.sangam.util.Clock;
import com.cecil.sangam.util.Json;
import com.cecil.sangam.util.Text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The daily rhythm of a team that is never all awake together:
 *
 *  ASYNC STAND-UP – each person answers "yesterday / today / blockers" once in their own working day.
 *    The digest shows who posted, who is late (their day began 2+ hours ago and nothing yet), who has not
 *    started their day yet, and every blocker in one list.
 *
 *  FOLLOW-THE-SUN HANDOFF – at the end of my day I write what is done, what is next and what to watch out for.
 *    The baton goes to the colleague who can carry on soonest:
 *      1. someone working right now – the one with the MOST hours left today, otherwise
 *      2. the one whose working day STARTS next.
 *    They get it at the start of their day and press "Got it".
 */
public class RhythmService {
    public static final long LATE_AFTER = 2 * WorkHours.HOUR;

    private final DataStore store;
    private final EventBus events;
    private final ChatService chat;

    public RhythmService(DataStore store, EventBus events, ChatService chat) {
        this.store = store;
        this.events = events;
        this.chat = chat;
    }

    // ------------------------------------------------------------------ stand-ups

    public Standup postStandup(User u, Map<String, Object> b) {
        if (u.guest()) throw Problem.forbidden("Stand-ups are for team members.");
        synchronized (store) {
            long now = Clock.millis();
            String date = WorkHours.local(u, now).toLocalDate().toString();
            String y = WorkService.clip(Json.str(b, "yesterday", ""), 600);
            String t = WorkService.clip(Json.str(b, "today", ""), 600);
            String bl = WorkService.clip(Json.str(b, "blockers", ""), 600);
            if (Text.blank(t)) throw Problem.bad("Say at least what you will do today.");
            Standup s = find(u.id, date);
            boolean isNew = s == null;
            if (isNew) {
                s = new Standup();
                s.id = store.nextId("S", 1);
                s.userId = u.id;
                s.date = date;
                store.standups.put(s.id, s);
            }
            s.yesterday = y;
            s.today = t;
            s.blockers = bl;
            s.at = now;
            if (s.blocked())
                for (String name : Mentions.names(bl)) {
                    User x = store.userByName(name);
                    if (x != null && !x.id.equals(u.id) && !x.guest())
                        store.notify(x.id, u.name + " is blocked and needs you: " + Text.clean(bl, 90), "#/standups", false);
                }
            store.save("standups", "notes");
            events.publish("standup", null, null, null, Json.obj("userId", u.id, "date", date));
            return s;
        }
    }

    private Standup find(String userId, String date) {
        for (Standup s : store.standups.values()) if (s.userId.equals(userId) && s.date.equals(date)) return s;
        return null;
    }

    /** The person's most recent stand-up before a date (shown on days without one). */
    private Standup latestBefore(String userId, String date) {
        Standup best = null;
        for (Standup s : store.standups.values())
            if (s.userId.equals(userId) && s.date.compareTo(date) < 0 && (best == null || s.date.compareTo(best.date) > 0)) best = s;
        return best;
    }

    /** One row per team member: their local "today", their stand-up (if any) and a state. */
    public Map<String, Object> digest(User viewer, String projectId) {
        synchronized (store) {
            List<User> people = new ArrayList<>();
            Project p = null;
            if (!Text.blank(projectId)) {
                p = Access.project(store, viewer, projectId);
                for (String id : p.memberIds) { User x = store.users.get(id); if (x != null && x.active && !x.guest()) people.add(x); }
            } else {
                if (viewer.guest()) throw Problem.forbidden("Stand-ups are for team members.");
                for (User x : store.users.values()) if (x.active && !x.guest()) people.add(x);
            }
            long now = Clock.millis();
            List<Map<String, Object>> rows = new ArrayList<>();
            List<Map<String, Object>> blockers = new ArrayList<>();
            int posted = 0, expected = 0;
            for (User x : people) {
                LocalDate d = WorkHours.local(x, now).toLocalDate();
                Standup s = find(x.id, d.toString());
                boolean workday = x.workDays.contains(d.getDayOfWeek().getValue());
                long start = WorkHours.startOn(x, d);
                String state;
                if (s != null) state = "posted";
                else if (!workday) state = "day-off";
                else if (now < start) state = "not-started";
                else if (now - start >= LATE_AFTER) state = "late";
                else state = "waiting";
                if (workday && now >= start) expected++;
                if (s != null) posted++;
                Standup prev = s != null ? null : latestBefore(x.id, d.toString());
                rows.add(Json.obj("user", PeopleService.card(x, now), "date", d.toString(), "state", state,
                        "standup", s == null ? null : s.toJson(), "previous", prev == null ? null : prev.toJson()));
                if (s != null && s.blocked()) blockers.add(Json.obj("userId", x.id, "name", x.name, "text", s.blockers, "at", s.at));
            }
            rows.sort(Comparator.comparing(r -> order(String.valueOf(r.get("state")))));
            return Json.obj("project", p == null ? null : Json.obj("id", p.id, "name", p.name, "key", p.key),
                    "rows", rows, "blockers", blockers, "posted", posted, "expected", expected);
        }
    }

    private static int order(String state) {
        return switch (state) { case "late" -> 0; case "waiting" -> 1; case "posted" -> 2; case "not-started" -> 3; default -> 4; };
    }

    // ------------------------------------------------------------------ handoffs

    /** Who would get the baton from `from` now, best first (each with the reason). */
    public List<Map<String, Object>> candidates(Project p, User from, long now) {
        List<Object[]> list = new ArrayList<>();
        for (String id : p.memberIds) {
            User x = store.users.get(id);
            if (x == null || !x.active || x.guest() || x.id.equals(from.id)) continue;
            boolean w = WorkHours.working(x, now);
            long key = w ? -(WorkHours.endOfToday(x, now) - now) : WorkHours.nextStart(x, now) - now;
            String why;
            if (w) why = "working now, " + hours(WorkHours.endOfToday(x, now) - now) + " left today";
            else why = "starts in " + hours(WorkHours.nextStart(x, now) - now);
            list.add(new Object[] { key, x, why, w });
        }
        list.sort(Comparator.comparingLong(o -> (Long) o[0]));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] o : list) {
            User x = (User) o[1];
            out.add(Json.obj("user", PeopleService.card(x, now), "why", o[2], "workingNow", o[3]));
        }
        return out;
    }

    static String hours(long ms) {
        long m = Math.max(0, ms / WorkHours.MIN);
        if (m < 60) return m + " min";
        return (m / 60) + " h" + (m % 60 >= 15 ? " " + (m % 60) + " min" : "");
    }

    public Handoff handoff(User u, String projectId, Map<String, Object> b) {
        synchronized (store) {
            Project p = Access.project(store, u, projectId);
            if (!Access.workOn(u, p)) throw Problem.forbidden("Handoffs are for team members.");
            String text = WorkService.clip(Json.str(b, "text", ""), 2000);
            if (Text.blank(text) || text.length() < 10) throw Problem.bad("Write a few words: what is done, what is next, what to watch out for.");
            long now = Clock.millis();
            String to = Json.str(b, "toId");
            if (Text.blank(to)) {
                List<Map<String, Object>> c = candidates(p, u, now);
                if (c.isEmpty()) throw Problem.bad("There is nobody else in this project to hand over to.");
                to = String.valueOf(((Map<?, ?>) c.get(0).get("user")).get("id"));
            }
            User r = store.users.get(to);
            if (r == null || !p.has(r.id) || r.guest() || r.id.equals(u.id)) throw Problem.bad("Choose a team member of this project.");
            Handoff h = new Handoff();
            h.id = store.nextId("H", 1);
            h.projectId = p.id;
            h.fromId = u.id;
            h.toId = r.id;
            h.text = text;
            h.at = now;
            Note n = store.notify(r.id, u.name + " handed " + p.name + " over to you: " + Text.clean(text, 80), "#/project/" + p.id + "/handoff", false);
            h.deliverAt = n == null ? now : n.deliverAt;
            store.handoffs.put(h.id, h);
            store.log(u.id, "handoff", p.key, "to " + r.name);
            store.save("handoffs", "notes", "audit");
            chat.post(u, p.channelId, "🔁 Handoff to @" + r.username + ":\n" + text, null, false, "handoff");
            events.publish("handoff", null, null, p.id, Json.obj("id", h.id));
            return h;
        }
    }

    public Handoff ack(User u, String id) {
        synchronized (store) {
            Handoff h = store.handoffs.get(id);
            if (h == null || !h.toId.equals(u.id)) throw Problem.notFound("Handoff");
            if (h.ackAt == 0) {
                h.ackAt = Clock.millis();
                store.notify(h.fromId, u.name + " picked up your handoff.", "#/project/" + h.projectId + "/handoff", false);
                store.save("handoffs", "notes");
                events.publish("handoff", null, null, h.projectId, Json.obj("id", h.id));
            }
            return h;
        }
    }
}
