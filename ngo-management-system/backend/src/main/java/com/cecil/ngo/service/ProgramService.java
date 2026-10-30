package com.cecil.ngo.service;

import com.cecil.ngo.model.Activity;
import com.cecil.ngo.model.Enums;
import com.cecil.ngo.model.Enums.ActivityKind;
import com.cecil.ngo.model.Enums.ActivityStatus;
import com.cecil.ngo.model.Enums.Cause;
import com.cecil.ngo.model.Enums.ProgramStatus;
import com.cecil.ngo.model.Enums.Role;
import com.cecil.ngo.model.Expense;
import com.cecil.ngo.model.HoursLog;
import com.cecil.ngo.model.Program;
import com.cecil.ngo.model.User;
import com.cecil.ngo.model.Volunteer;
import com.cecil.ngo.store.DataStore;
import com.cecil.ngo.util.Json;
import com.cecil.ngo.util.Money;
import com.cecil.ngo.util.Text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Programs (long-running work) and their activities (single events on the ground),
 * including volunteer sign-up with the "no two activities on the same day" rule.
 */
public class ProgramService {
    private final DataStore store;

    public ProgramService(DataStore store) { this.store = store; }

    // ================================================================== programs

    public List<Map<String, Object>> list(User me, Map<String, String> q) {
        AuthService.needStaff(me, "see program budgets");
        String status = q.getOrDefault("status", ""), cause = q.getOrDefault("cause", "");
        String text = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) {
            for (Program p : store.programs) {
                if (!status.isEmpty() && !p.status.name().equals(status)) continue;
                if (!cause.isEmpty() && !p.cause.name().equals(cause)) continue;
                if (!text.isEmpty() && !(p.name + " " + p.location + " " + p.id).toLowerCase(Locale.ROOT).contains(text)) continue;
                out.add(Calc.programView(store, p));
            }
        }
        return out;
    }

    public Map<String, Object> get(User me, String id) {
        AuthService.needStaff(me, "see program details");
        synchronized (store) {
            Program p = mustProgram(id);
            Map<String, Object> m = Calc.programView(store, p);
            List<Object> acts = new ArrayList<>();
            store.activities.stream().filter(a -> a.programId.equals(id))
                    .sorted(Comparator.comparing((Activity a) -> a.date).reversed())
                    .forEach(a -> acts.add(activityView(a, me)));
            m.put("activities", acts);
            List<Object> exp = new ArrayList<>();
            store.expenses.stream().filter(e -> e.programId.equals(id))
                    .sorted(Comparator.comparing((Expense e) -> e.date).reversed()).limit(12).forEach(exp::add);
            m.put("expenses", exp);
            // spending by category, for the small bar list on the program page
            Map<String, Object> byCat = new java.util.LinkedHashMap<>();
            for (Enums.ExpenseCategory c : Enums.ExpenseCategory.values()) {
                long t = 0;
                for (Expense e : store.expenses)
                    if (e.programId.equals(id) && e.category == c && e.status == Enums.Review.APPROVED) t += e.amount;
                if (t > 0) byCat.put(c.name(), t);
            }
            m.put("byCategory", byCat);
            return m;
        }
    }

    public Map<String, Object> create(User me, Map<String, Object> b) {
        AuthService.needPrograms(me, "add programs");
        synchronized (store) {
            Program p = new Program();
            fill(p, b, true);
            p.id = store.newId("P", 2);
            p.createdAt = store.now();
            store.programs.add(p);
            store.savePrograms();
            store.log(me.username, "added program", p.name, "program:" + p.id);
            return Calc.programView(store, p);
        }
    }

    public Map<String, Object> update(User me, String id, Map<String, Object> b) {
        AuthService.needPrograms(me, "edit programs");
        synchronized (store) {
            Program p = mustProgram(id);
            ProgramStatus before = p.status;
            Program copy = Program.fromJson(asMap(p));
            fill(copy, b, false);
            long spent = Calc.spent(store, id, null, null);
            if (copy.budget < spent)
                throw ApiError.bad("Budget cannot be less than the money already spent (" + Money.format(spent) + ")");
            if (copy.status == ProgramStatus.COMPLETED && before != ProgramStatus.COMPLETED) {
                int open = Calc.activities(store, id, ActivityStatus.PLANNED, null, null);
                if (open > 0) throw ApiError.conflict("Finish or cancel the " + open + " planned activit" + (open == 1 ? "y" : "ies") + " before completing this program");
            }
            fill(p, b, false);
            store.savePrograms();
            store.log(me.username, before != p.status ? "program " + p.status.label.toLowerCase() : "edited program", p.name, "program:" + p.id);
            return Calc.programView(store, p);
        }
    }

    public void delete(User me, String id) {
        AuthService.needAdmin(me, "delete programs");
        synchronized (store) {
            Program p = mustProgram(id);
            boolean used = store.activities.stream().anyMatch(a -> a.programId.equals(id))
                    || store.expenses.stream().anyMatch(e -> e.programId.equals(id))
                    || store.donations.stream().anyMatch(d -> id.equals(d.programId))
                    || store.beneficiaries.stream().anyMatch(x -> x.programIds.contains(id));
            if (used) throw ApiError.conflict("This program has activities, money or beneficiaries linked to it. Mark it Completed or On hold instead.");
            store.programs.remove(p);
            store.savePrograms();
            store.log(me.username, "deleted program", p.name, null);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Program p) { return (Map<String, Object>) p.toJson(); }

    private void fill(Program p, Map<String, Object> b, boolean isNew) {
        if (isNew || b.containsKey("name")) p.name = Text.line(Json.str(b, "name"), "Program name", 100, true);
        if (p.name.length() < 3) throw ApiError.bad("Program name is too short");
        for (Program o : store.programs)
            if (o != p && !o.id.equals(p.id) && Text.key(o.name).equals(Text.key(p.name))) throw ApiError.conflict("A program with this name already exists");
        if (isNew || b.containsKey("cause")) p.cause = Enums.parse(Cause.class, Json.str(b, "cause"), "Cause");
        if (isNew || b.containsKey("location")) p.location = Text.line(Json.str(b, "location"), "Location", 100, true);
        if (isNew || b.containsKey("description")) p.description = Text.clean(Json.str(b, "description"), "Description", 1000, false);
        if (isNew || b.containsKey("startDate")) p.startDate = Text.date(Json.str(b, "startDate"), "Start date", true).toString();
        if (isNew || b.containsKey("endDate")) {
            LocalDate e = Text.date(Json.str(b, "endDate"), "End date", false);
            p.endDate = e == null ? null : e.toString();
        }
        if (p.endDate != null && LocalDate.parse(p.endDate).isBefore(LocalDate.parse(p.startDate)))
            throw ApiError.bad("End date cannot be before the start date");
        if (isNew || b.containsKey("budget")) {
            Long v = Json.longOrNull(b, "budget");
            if (v == null || v < 0) throw ApiError.bad("Budget must be zero or more");
            if (v > Money.MAX_AMOUNT) throw ApiError.bad("Budget is too large (max ₹10 crore)");
            p.budget = v;
        }
        if (isNew || b.containsKey("targetBeneficiaries"))
            p.targetBeneficiaries = Text.range(Json.integer(b, "targetBeneficiaries", 0), "Target people", 0, 1_000_000);
        if (isNew || b.containsKey("status"))
            p.status = b.get("status") == null && isNew ? ProgramStatus.PLANNED : Enums.parse(ProgramStatus.class, Json.str(b, "status"), "Status");
        if (isNew || b.containsKey("lead")) {
            String lead = Json.str(b, "lead");
            User u = store.user(lead);
            if (u == null || !u.active || !(u.role == Role.MANAGER || u.role == Role.ADMIN))
                throw ApiError.bad("Lead must be an active program manager or the admin");
            p.lead = u.username;
        }
    }

    Program mustProgram(String id) {
        Program p = store.program(id);
        if (p == null) throw ApiError.notFound("Program not found");
        return p;
    }

    // ================================================================== activities

    /** An activity as the screen shows it. Volunteers see who else is coming only as a number. */
    public Map<String, Object> activityView(Activity a, User me) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) a.toJson();
        Program p = store.program(a.programId);
        m.put("programName", p == null ? a.programId : p.name);
        m.put("cause", p == null ? null : p.cause);
        m.put("signedUp", a.volunteerIds.size());
        if (AuthService.isStaff(me)) {
            List<Object> vs = new ArrayList<>();
            for (String vid : a.volunteerIds) {
                Volunteer v = store.volunteer(vid);
                vs.add(Json.map("id", vid, "name", v == null ? vid : v.name));
            }
            m.put("volunteers", vs);
        } else {
            m.remove("volunteerIds");
            m.put("iAmIn", me.volunteerId != null && a.volunteerIds.contains(me.volunteerId));
        }
        return m;
    }

    /**
     * Filters: program, status, from, to, q (text), open=1 (planned, not past, seats left), mine=1 (volunteer's own).
     * Volunteers only see planned activities from today on, plus the ones they joined.
     */
    public List<Map<String, Object>> activities(User me, Map<String, String> q) {
        String program = q.getOrDefault("program", ""), status = q.getOrDefault("status", "");
        String text = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        LocalDate from = Text.date(q.get("from"), "From", false), to = Text.date(q.get("to"), "To", false);
        boolean open = "1".equals(q.get("open")), mine = "1".equals(q.get("mine"));
        boolean vol = !AuthService.isStaff(me);
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) {
            LocalDate today = store.today();
            List<Activity> sorted = new ArrayList<>(store.activities);
            sorted.sort(Comparator.comparing((Activity a) -> a.date).thenComparing(a -> a.time == null ? "" : a.time));
            for (Activity a : sorted) {
                boolean joined = me.volunteerId != null && a.volunteerIds.contains(me.volunteerId);
                boolean isOpen = a.status == ActivityStatus.PLANNED && !LocalDate.parse(a.date).isBefore(today);
                if (vol && !joined && !isOpen) continue;
                if (mine && !joined) continue;
                if (open && !(isOpen && a.seatsLeft() > 0)) continue;
                if (!program.isEmpty() && !a.programId.equals(program)) continue;
                if (!status.isEmpty() && !a.status.name().equals(status)) continue;
                if (!Calc.inRange(a.date, from, to)) continue;
                if (!text.isEmpty() && !(a.title + " " + a.place + " " + a.id).toLowerCase(Locale.ROOT).contains(text)) continue;
                out.add(activityView(a, me));
            }
        }
        return out;
    }

    public Map<String, Object> activity(User me, String id) {
        synchronized (store) {
            Activity a = mustActivity(id);
            boolean joined = me.volunteerId != null && a.volunteerIds.contains(me.volunteerId);
            if (!AuthService.isStaff(me) && !joined && a.status != ActivityStatus.PLANNED) throw ApiError.notFound("Activity not found");
            Map<String, Object> m = activityView(a, me);
            if (AuthService.isStaff(me)) {
                List<Object> hrs = new ArrayList<>();
                for (HoursLog h : store.hours) if (h.activityId.equals(id)) hrs.add(h);
                m.put("hours", hrs);
            }
            return m;
        }
    }

    public Map<String, Object> createActivity(User me, Map<String, Object> b) {
        AuthService.needPrograms(me, "plan activities");
        synchronized (store) {
            Activity a = new Activity();
            fillActivity(a, b, true);
            a.id = store.newId("A", 3);
            a.createdBy = me.username;
            a.createdAt = store.now();
            store.activities.add(a);
            store.saveActivities();
            store.log(me.username, "planned activity", a.title + " on " + a.date, "activity:" + a.id);
            return activityView(a, me);
        }
    }

    public Map<String, Object> updateActivity(User me, String id, Map<String, Object> b) {
        AuthService.needPrograms(me, "edit activities");
        synchronized (store) {
            Activity a = mustActivity(id);
            if (a.status != ActivityStatus.PLANNED) throw ApiError.conflict("Only planned activities can be edited");
            Activity copy = Activity.fromJson(asMap(a));
            fillActivity(copy, b, false);           // check everything first ...
            fillActivity(a, b, false);              // ... then change the real one
            store.saveActivities();
            store.log(me.username, "edited activity", a.title, "activity:" + a.id);
            return activityView(a, me);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Activity a) { return (Map<String, Object>) a.toJson(); }

    private void fillActivity(Activity a, Map<String, Object> b, boolean isNew) {
        if (isNew || b.containsKey("programId")) {
            Program p = store.program(Json.str(b, "programId"));
            if (p == null) throw ApiError.bad("Choose a program");
            if (p.status == ProgramStatus.COMPLETED) throw ApiError.conflict("This program is completed. New activities cannot be added.");
            a.programId = p.id;
        }
        if (isNew || b.containsKey("title")) a.title = Text.line(Json.str(b, "title"), "Title", 100, true);
        if (isNew || b.containsKey("kind")) a.kind = Enums.parse(ActivityKind.class, Json.str(b, "kind"), "Kind");
        if (isNew || b.containsKey("date")) {
            LocalDate d = Text.date(Json.str(b, "date"), "Date", true);
            if (d.isBefore(store.today())) throw ApiError.bad("A new or changed activity cannot be in the past");
            a.date = d.toString();
        }
        Program p = store.program(a.programId);
        LocalDate d = LocalDate.parse(a.date);
        if (d.isBefore(LocalDate.parse(p.startDate)) || (p.endDate != null && d.isAfter(LocalDate.parse(p.endDate))))
            throw ApiError.bad("The date must be inside the program dates (" + p.startDate + (p.endDate == null ? " onwards" : " to " + p.endDate) + ")");
        if (isNew || b.containsKey("time")) {
            String t = Text.line(Json.str(b, "time"), "Time", 5, false);
            if (!t.isEmpty() && !t.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) throw ApiError.bad("Time must look like 09:30");
            a.time = t;
        }
        if (isNew || b.containsKey("place")) a.place = Text.line(Json.str(b, "place"), "Place", 120, true);
        if (isNew || b.containsKey("volunteersNeeded")) {
            int n = Text.range(Json.integer(b, "volunteersNeeded", 0), "Volunteers needed", 0, 200);
            if (n < a.volunteerIds.size()) throw ApiError.conflict(a.volunteerIds.size() + " volunteers already signed up. Remove some first.");
            a.volunteersNeeded = n;
        }
    }

    Activity mustActivity(String id) {
        Activity a = store.activity(id);
        if (a == null) throw ApiError.notFound("Activity not found");
        return a;
    }

    /**
     * Puts a volunteer on an activity. A volunteer can only sign themselves up; a manager can add anyone.
     * Rules: activity is planned and not in the past, the volunteer is active, there is a free seat,
     * and the volunteer is not already on another planned activity that same day.
     */
    public Map<String, Object> signUp(User me, String id, Map<String, Object> b) {
        synchronized (store) {
            Activity a = mustActivity(id);
            String vid = volunteerFor(me, Json.str(b, "volunteerId"));
            Volunteer v = store.volunteer(vid);
            if (v == null) throw ApiError.notFound("Volunteer not found");
            if (!v.active) throw ApiError.conflict(v.name + " is not active");
            if (a.status != ActivityStatus.PLANNED) throw ApiError.conflict("This activity is " + a.status.label.toLowerCase());
            if (LocalDate.parse(a.date).isBefore(store.today())) throw ApiError.conflict("This activity is already over");
            if (a.volunteerIds.contains(vid)) throw ApiError.conflict(v.name + " is already signed up");
            if (a.seatsLeft() <= 0) throw ApiError.conflict("No seats left on this activity");
            for (Activity o : store.activities)
                if (o != a && o.status == ActivityStatus.PLANNED && o.date.equals(a.date) && o.volunteerIds.contains(vid))
                    throw ApiError.conflict(v.name + " is already on \"" + o.title + "\" that day");
            a.volunteerIds.add(vid);
            store.saveActivities();
            store.log(me.username, "signed up", v.name + " for " + a.title, "activity:" + a.id);
            return activityView(a, me);
        }
    }

    public Map<String, Object> withdraw(User me, String id, Map<String, Object> b) {
        synchronized (store) {
            Activity a = mustActivity(id);
            String vid = volunteerFor(me, Json.str(b, "volunteerId"));
            if (a.status != ActivityStatus.PLANNED) throw ApiError.conflict("You can only leave a planned activity");
            if (!a.volunteerIds.remove(vid)) throw ApiError.conflict("Not signed up");
            store.saveActivities();
            Volunteer v = store.volunteer(vid);
            store.log(me.username, "left activity", (v == null ? vid : v.name) + " left " + a.title, "activity:" + a.id);
            return activityView(a, me);
        }
    }

    /** A volunteer always acts for themselves; staff choose the volunteer. */
    private String volunteerFor(User me, String asked) {
        if (me.role == Role.VOLUNTEER) {
            if (me.volunteerId == null) throw ApiError.forbidden("Your login is not linked to a volunteer record");
            if (asked != null && !asked.isBlank() && !asked.equals(me.volunteerId)) throw ApiError.forbidden("You can only sign yourself up");
            return me.volunteerId;
        }
        AuthService.needPrograms(me, "assign volunteers");
        if (asked == null || asked.isBlank()) throw ApiError.bad("Choose a volunteer");
        return asked;
    }

    /** Marks an activity as done and records how many people it reached. */
    public Map<String, Object> complete(User me, String id, Map<String, Object> b) {
        AuthService.needPrograms(me, "close activities");
        synchronized (store) {
            Activity a = mustActivity(id);
            if (a.status != ActivityStatus.PLANNED) throw ApiError.conflict("This activity is already " + a.status.label.toLowerCase());
            if (LocalDate.parse(a.date).isAfter(store.today())) throw ApiError.conflict("This activity has not happened yet");
            a.reached = Text.range(Json.intOrNull(b, "reached"), "People reached", 0, 100_000);
            a.outcome = Text.clean(Json.str(b, "outcome"), "What happened", 1000, false);
            a.status = ActivityStatus.DONE;
            store.saveActivities();
            store.log(me.username, "activity done", a.title + " - reached " + a.reached + " people", "activity:" + a.id);
            return activityView(a, me);
        }
    }

    public Map<String, Object> cancel(User me, String id, Map<String, Object> b) {
        AuthService.needPrograms(me, "cancel activities");
        synchronized (store) {
            Activity a = mustActivity(id);
            if (a.status != ActivityStatus.PLANNED) throw ApiError.conflict("Only planned activities can be cancelled");
            String why = Text.line(Json.str(b, "reason"), "Reason", 300, true);
            a.status = ActivityStatus.CANCELLED;
            a.outcome = "Cancelled: " + why;
            store.saveActivities();
            store.log(me.username, "cancelled activity", a.title + " (" + why + ")", "activity:" + a.id);
            return activityView(a, me);
        }
    }

    public void deleteActivity(User me, String id) {
        AuthService.needPrograms(me, "delete activities");
        synchronized (store) {
            Activity a = mustActivity(id);
            if (a.status == ActivityStatus.DONE) throw ApiError.conflict("A done activity is part of the record and cannot be deleted");
            if (store.hours.stream().anyMatch(h -> h.activityId.equals(id))) throw ApiError.conflict("Hours are logged for this activity");
            store.activities.remove(a);
            store.saveActivities();
            store.log(me.username, "deleted activity", a.title, null);
        }
    }

    /** Small list of every program (no money), used to fill drop-downs for all roles. */
    public List<Map<String, Object>> minis() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) { for (Program p : store.programs) out.add(Calc.mini(p)); }
        return out;
    }
}
