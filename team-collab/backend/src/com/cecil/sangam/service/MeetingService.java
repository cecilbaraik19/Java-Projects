package com.cecil.sangam.service;

import com.cecil.sangam.core.EventBus;
import com.cecil.sangam.core.MeetingFinder;
import com.cecil.sangam.core.WorkHours;
import com.cecil.sangam.model.Meeting;
import com.cecil.sangam.model.Project;
import com.cecil.sangam.model.User;
import com.cecil.sangam.store.DataStore;
import com.cecil.sangam.util.Clock;
import com.cecil.sangam.util.Json;
import com.cecil.sangam.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Video calls across time zones: suggest fair times, book them, cancel them.
 * Booking adds each person's "cost" of the time to their pain points (MeetingFinder.charge); cancelling a future
 * call gives them back.
 */
public class MeetingService {
    public static final int MAX_PEOPLE = 12;
    public static final long REMIND_BEFORE = 10 * WorkHours.MIN;

    private final DataStore store;
    private final EventBus events;

    public MeetingService(DataStore store, EventBus events) {
        this.store = store;
        this.events = events;
    }

    public Map<String, Object> suggest(User u, Map<String, Object> b) {
        synchronized (store) {
            List<User> people = people(u, Json.strings(b, "attendeeIds"));
            int minutes = minutes(Json.num(b, "minutes", 30));
            int days = (int) Math.max(1, Math.min(7, Json.num(b, "days", 3)));
            long now = Clock.millis();
            long from = Math.max(now + 30 * WorkHours.MIN, Json.num(b, "from", 0));
            List<MeetingFinder.Option> opts = MeetingFinder.suggest(people, store.meetings.values(), from, from + days * WorkHours.DAY, minutes, 5);
            List<Map<String, Object>> out = new ArrayList<>();
            for (MeetingFinder.Option o : opts) out.add(MeetingFinder.describe(o, people));
            boolean async = opts.isEmpty() || opts.get(0).worst() >= MeetingFinder.ASYNC_HINT;
            String hint = null;
            if (async) {
                User hurt = null;
                if (!opts.isEmpty()) for (User x : people) if (opts.get(0).costs().getOrDefault(x.id, 0) >= MeetingFinder.ASYNC_HINT) { hurt = x; break; }
                hint = opts.isEmpty() ? "No free time for everybody in these days. Try a thread or a recorded update instead."
                        : "Even the best time is hard for " + (hurt == null ? "somebody" : hurt.name) + ". Could this be an async update (a thread, a short recorded video, a document) instead?";
            }
            return Json.obj("options", out, "asyncHint", hint, "minutes", minutes,
                    "people", people.stream().map(x -> PeopleService.card(x, now)).toList());
        }
    }

    public Meeting schedule(User u, Map<String, Object> b) {
        synchronized (store) {
            if (u.guest()) throw Problem.forbidden("Guests cannot organise calls. Ask a team member.");
            List<User> people = people(u, Json.strings(b, "attendeeIds"));
            int minutes = minutes(Json.num(b, "minutes", 30));
            long start = Json.num(b, "start", 0);
            long now = Clock.millis();
            if (start <= now) throw Problem.bad("Pick a time in the future.");
            if (start % (15 * WorkHours.MIN) != 0) throw Problem.bad("Calls start on a quarter hour.");
            if (start > now + 60 * WorkHours.DAY) throw Problem.bad("At most 60 days ahead.");
            long end = start + minutes * WorkHours.MIN;
            User busy = MeetingFinder.busy(people, store.meetings.values(), start, end);
            if (busy != null) throw Problem.conflict(busy.name + " is already in another call then.");
            String title = Text.clean(Json.str(b, "title"), 80);
            if (Text.blank(title) || title.length() < 3) throw Problem.bad("Give the call a title.");
            String projectId = Json.str(b, "projectId");
            if (!Text.blank(projectId)) Access.project(store, u, projectId); else projectId = null;
            Meeting m = new Meeting();
            m.id = store.nextId("MT", 1);
            m.title = title;
            m.organizerId = u.id;
            for (User x : people) if (!x.id.equals(u.id)) m.attendeeIds.add(x.id);
            m.start = start;
            m.end = end;
            m.projectId = projectId;
            m.agenda = WorkService.clip(Json.str(b, "agenda", ""), 1000);
            m.createdAt = now;
            for (User x : people) m.costs.put(x.id, WorkHours.cost(x, start, end));
            MeetingFinder.charge(people, m.costs);
            store.meetings.put(m.id, m);
            for (User x : people) {
                if (x.id.equals(u.id)) continue;
                var z = WorkHours.local(x, start);
                int c = m.costs.get(x.id);
                store.notify(x.id, u.name + " invited you to \"" + title + "\" – " + String.format("%s %02d:%02d your time", z.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH), z.getHour(), z.getMinute())
                        + (c > 0 ? " (" + WorkHours.costLabel(c) + " for you – thank you)" : ""), "#/meetings", false);
            }
            store.log(u.id, "meeting.create", title, people.size() + " people");
            store.save("meetings", "users", "notes", "audit");
            events.publish("meeting", m.everyone(), null, null, Json.obj("id", m.id));
            return m;
        }
    }

    public Meeting cancel(User u, String id) {
        synchronized (store) {
            Meeting m = store.meetings.get(id);
            if (m == null || !(m.involves(u.id) || u.admin())) throw Problem.notFound("Call");
            if (!m.organizerId.equals(u.id) && !u.admin()) throw Problem.forbidden("Only the organiser can cancel the call.");
            if (m.cancelled) return m;
            long now = Clock.millis();
            if (m.end <= now) throw Problem.bad("This call is over.");
            m.cancelled = true;
            List<User> people = new ArrayList<>();
            for (String x : m.everyone()) if (store.users.containsKey(x)) people.add(store.users.get(x));
            if (m.start > now) MeetingFinder.refund(people, m.costs);
            for (User x : people) if (!x.id.equals(u.id)) store.notify(x.id, u.name + " cancelled \"" + m.title + "\"", "#/meetings", false);
            store.log(u.id, "meeting.cancel", m.title, null);
            store.save("meetings", "users", "notes", "audit");
            events.publish("meeting", m.everyone(), null, null, Json.obj("id", m.id));
            return m;
        }
    }

    /** Background job: a reminder 10 minutes before a call (rings through quiet hours – they agreed to the time). */
    public void reminders() {
        synchronized (store) {
            long now = Clock.millis();
            boolean any = false;
            for (Meeting m : store.meetings.values()) {
                if (m.cancelled || m.start <= now || m.start - now > REMIND_BEFORE) continue;
                String flag = "remind." + m.id;
                if (store.meta.containsKey(flag)) continue;
                store.meta.put(flag, true);
                for (String x : m.everyone()) store.notify(x, "\"" + m.title + "\" starts in " + Math.max(1, (m.start - now) / WorkHours.MIN) + " minutes", "#/meetings", true);
                any = true;
            }
            if (any) store.save("notes");
        }
    }

    private List<User> people(User u, List<String> ids) {
        List<User> people = new ArrayList<>();
        people.add(u);
        for (String id : ids) {
            User x = store.users.get(id);
            if (x == null || !x.active) throw Problem.bad("Unknown person " + id + ".");
            if (!people.contains(x)) people.add(x);
        }
        if (people.size() < 2) throw Problem.bad("Add at least one other person.");
        if (people.size() > MAX_PEOPLE) throw Problem.bad("At most " + MAX_PEOPLE + " people in a call.");
        if (u.guest()) {
            for (User x : people) {
                boolean share = x.id.equals(u.id) || store.projects.values().stream().anyMatch(p -> p.has(u.id) && p.has(x.id));
                if (!share) throw Problem.forbidden("You can only invite people from your projects.");
            }
        }
        return people;
    }

    private static int minutes(long m) {
        if (m < 15 || m > 120 || m % 15 != 0) throw Problem.bad("Calls are 15 to 120 minutes, in quarter hours.");
        return (int) m;
    }

    /** Is the project id visible to this person (helper for views)? */
    boolean seesProject(User u, String id) {
        Project p = id == null ? null : store.projects.get(id);
        return p == null || Access.seeProject(u, p);
    }
}
