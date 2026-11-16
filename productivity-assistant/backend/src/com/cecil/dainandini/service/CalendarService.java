package com.cecil.dainandini.service;

import com.cecil.dainandini.ai.PeakHours;
import com.cecil.dainandini.ai.QuickCapture;
import com.cecil.dainandini.ai.WorkTime;
import com.cecil.dainandini.model.CalEvent;
import com.cecil.dainandini.model.User;
import com.cecil.dainandini.store.DataStore;
import com.cecil.dainandini.util.Clock;
import com.cecil.dainandini.util.Json;
import com.cecil.dainandini.util.Text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The calendar: meetings, focus blocks and leave.
 *
 * SMART MEETING TIMES ("Find a time"): looks at the next working days of every person invited and lists the
 * slots where ALL of them are free. Each slot gets a cost:
 *     cost = Σ over people  (focus quality of that person at that time)   ← from their learned peak hours
 *          + 0.05 for every working day we wait
 * The cheapest slots come first, so meetings land where they hurt nobody's best focus hours.
 */
public class CalendarService {
    public static final int MAX_ATTENDEES = 15;

    private final DataStore store;
    private final Brain brain;

    public CalendarService(DataStore store, Brain brain) { this.store = store; this.brain = brain; }

    public Map<String, Object> week(User u, LocalDate start) {
        synchronized (store) {
            LocalDate mon = start.minusDays(start.getDayOfWeek().getValue() - 1);
            List<Object> evs = new ArrayList<>();
            for (CalEvent e : store.eventsOf(u.id, WorkTime.at(mon, 0), WorkTime.at(mon.plusDays(7), 0))) evs.add(eventJson(e, u));
            return Json.obj("monday", mon.toString(), "events", evs, "me", u.card());
        }
    }

    public Map<String, Object> eventJson(CalEvent e, User viewer) {
        List<Object> people = new ArrayList<>();
        people.add(store.users.get(e.ownerId) == null ? null : store.users.get(e.ownerId).card());
        for (String id : e.attendeeIds) if (store.users.get(id) != null) people.add(store.users.get(id).card());
        Map<String, Object> j = new LinkedHashMap<>(e.toJson());
        j.put("people", people);
        j.put("ownerName", store.userName(e.ownerId));
        j.put("mine", e.ownerId.equals(viewer.id));
        return j;
    }

    /** body: title, date (yyyy-mm-dd), start ("10:30"), minutes, kind, attendees [user ids], force (ignore clashes). */
    public CalEvent create(User u, Map<String, Object> b) {
        synchronized (store) {
            String title = Text.clean(Json.str(b, "title"), 100);
            CalEvent.Kind kind = TaskService.enumOf(CalEvent.Kind.class, Json.str(b, "kind", "MEETING"), "event kind");
            if (Text.blank(title)) title = kind == CalEvent.Kind.BLOCK ? "Focus time" : kind == CalEvent.Kind.LEAVE ? "Leave" : "";
            if (Text.blank(title)) throw Problem.bad("Please give the meeting a name.");
            LocalDate d = TaskService.date(Json.str(b, "date", ""));
            long start, end;
            if (kind == CalEvent.Kind.LEAVE) {
                start = WorkTime.at(d, 0);
                int days = (int) Json.num(b, "days", 1);
                if (days < 1 || days > 30) throw Problem.bad("Leave can be 1 to 30 days.");
                end = WorkTime.at(d.plusDays(days), 0);
            } else {
                int m = TaskService.minutes(Json.str(b, "start"));
                long mins = Json.num(b, "minutes", 30);
                if (mins < 5 || mins > 600) throw Problem.bad("A meeting can be 5 minutes to 10 hours long.");
                start = WorkTime.at(d, m);
                end = start + mins * WorkTime.MIN;
            }
            if (end < Clock.millis() - 30 * WorkTime.DAY) throw Problem.bad("That is too far in the past.");
            List<String> attendees = new ArrayList<>();
            if (kind == CalEvent.Kind.MEETING) {
                for (String id : Json.strings(b, "attendees")) {
                    User a = store.users.get(id);
                    if (a == null || !a.active) throw Problem.bad("Unknown person in the invite list.");
                    if (!a.id.equals(u.id) && !attendees.contains(a.id)) attendees.add(a.id);
                }
                if (attendees.size() > MAX_ATTENDEES) throw Problem.bad("At most " + MAX_ATTENDEES + " people per meeting.");
            }
            if (!Json.bool(b, "force", false)) {
                List<String> clashes = new ArrayList<>();
                List<String> all = new ArrayList<>(attendees);
                all.add(0, u.id);
                for (String id : all)
                    for (CalEvent x : store.eventsOf(id, start, end))
                        clashes.add(store.users.get(id).firstName() + ": " + x.title + " " + QuickCapture.hhmm(WorkTime.minuteOf(x.start)) + "–" + QuickCapture.hhmm(WorkTime.minuteOf(x.end)));
                if (!clashes.isEmpty()) throw Problem.conflict("This clashes with other events.").with(Json.obj("clashes", clashes));
            }
            CalEvent e = new CalEvent();
            e.id = store.nextId("E", 1);
            e.ownerId = u.id;
            e.attendeeIds = attendees;
            e.title = title;
            e.kind = kind;
            e.start = start;
            e.end = end;
            e.createdAt = Clock.millis();
            store.events.put(e.id, e);
            store.log(u.id, "event.create", e.id, title);
            store.save("events", "audit");
            return e;
        }
    }

    public void delete(User u, String id) {
        synchronized (store) {
            CalEvent e = store.events.get(id);
            if (e == null || !e.involves(u.id)) throw Problem.notFound("Event");
            if (e.ownerId.equals(u.id)) {
                store.events.remove(id);
                store.log(u.id, "event.delete", id, e.title);
            } else {
                e.attendeeIds.remove(u.id);      // an invited person can only leave the meeting
                store.log(u.id, "event.decline", id, e.title);
            }
            store.save("events", "audit");
        }
    }

    /** Free times when everybody in the list can meet. body: attendees, minutes, from (date, optional). */
    public Map<String, Object> findTimes(User u, Map<String, Object> b) {
        synchronized (store) {
            long mins = Json.num(b, "minutes", 30);
            if (mins < 15 || mins > 240) throw Problem.bad("Look for 15 minutes to 4 hours.");
            List<User> people = new ArrayList<>();
            people.add(u);
            for (String id : Json.strings(b, "attendees")) {
                User a = store.users.get(id);
                if (a != null && a.active && !people.contains(a)) people.add(a);
            }
            long now = Clock.millis();
            LocalDate d = Text.blank(Json.str(b, "from")) ? WorkTime.dateOf(now) : TaskService.date(Json.str(b, "from"));
            List<PeakHours.Profile> peaks = new ArrayList<>();
            for (User p : people) peaks.add(brain.peak(p));
            record Option(long start, double cost, List<String> why) { }
            List<Option> options = new ArrayList<>();
            int dayIndex = 0;
            for (int i = 0; i < 14 && dayIndex < 5; i++, d = d.plusDays(1)) {
                LocalDate day = d;
                boolean all = true;
                for (User p : people) if (!WorkTime.workday(p, day)) all = false;
                if (!all) continue;
                long from = 0, to = Long.MAX_VALUE;
                List<long[]> busy = new ArrayList<>();
                for (User p : people) {
                    from = Math.max(from, WorkTime.at(day, p.workStart));
                    to = Math.min(to, WorkTime.at(day, p.workEnd));
                    for (CalEvent e : brain.eventsOn(p, day)) busy.add(new long[] { e.start, e.end });
                    long ls = WorkTime.at(day, p.lunchStart);
                    busy.add(new long[] { ls, ls + p.lunchMin * WorkTime.MIN });
                }
                from = Math.max(from, WorkTime.ceil5(now) + 15 * WorkTime.MIN);
                for (long[] s : WorkTime.free(from, to, busy)) {
                    long half = 30 * WorkTime.MIN;
                    for (long st = (s[0] + half - 1) / half * half; st + mins * WorkTime.MIN <= s[1]; st += half) {
                        double cost = 0.05 * dayIndex;
                        List<String> why = new ArrayList<>();
                        for (int k = 0; k < people.size(); k++) {
                            double q = peaks.get(k).scoreFor(st, st + mins * WorkTime.MIN);
                            cost += q;
                            if (q >= 0.8) why.add("inside " + people.get(k).firstName() + "'s best focus time");
                        }
                        options.add(new Option(st, cost, why));
                    }
                }
                dayIndex++;
            }
            options.sort(Comparator.comparingDouble(Option::cost).thenComparingLong(Option::start));
            List<Object> out = new ArrayList<>();
            Map<LocalDate, Integer> perDay = new LinkedHashMap<>();
            for (Option o : options) {
                if (out.size() >= 6) break;
                if (perDay.merge(WorkTime.dateOf(o.start()), 1, Integer::sum) > 2) continue;   // at most 2 a day, so there is a choice
                out.add(Json.obj("start", o.start(), "end", o.start() + mins * WorkTime.MIN, "cost", Math.round(o.cost() * 100) / 100.0,
                        "note", o.why().isEmpty() ? "outside everyone's best focus time" : String.join("; ", o.why())));
            }
            List<Object> who = new ArrayList<>();
            for (User p : people) who.add(p.card());
            return Json.obj("options", out, "people", who, "minutes", mins);
        }
    }
}
