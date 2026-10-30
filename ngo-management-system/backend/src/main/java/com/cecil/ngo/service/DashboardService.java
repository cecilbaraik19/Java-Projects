package com.cecil.ngo.service;

import com.cecil.ngo.model.Activity;
import com.cecil.ngo.model.Donation;
import com.cecil.ngo.model.Donor;
import com.cecil.ngo.model.Enums.ActivityStatus;
import com.cecil.ngo.model.Enums.DonorType;
import com.cecil.ngo.model.Enums.ExpenseCategory;
import com.cecil.ngo.model.Enums.ProgramStatus;
import com.cecil.ngo.model.Enums.Review;
import com.cecil.ngo.model.Event;
import com.cecil.ngo.model.Expense;
import com.cecil.ngo.model.HoursLog;
import com.cecil.ngo.model.Program;
import com.cecil.ngo.model.Settings;
import com.cecil.ngo.model.User;
import com.cecil.ngo.model.Volunteer;
import com.cecil.ngo.store.DataStore;
import com.cecil.ngo.util.Json;
import com.cecil.ngo.util.Money;
import com.cecil.ngo.util.Text;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The home screen, the impact report, the history list and the NGO settings. */
public class DashboardService {
    private final DataStore store;
    private final ProgramService programs;
    private final VolunteerService volunteers;

    public DashboardService(DataStore store, ProgramService programs, VolunteerService volunteers) {
        this.store = store;
        this.programs = programs;
        this.volunteers = volunteers;
    }

    public Map<String, Object> dashboard(User me) {
        return AuthService.isStaff(me) ? staffDashboard(me) : volunteerDashboard(me);
    }

    private Map<String, Object> staffDashboard(User me) {
        synchronized (store) {
            LocalDate today = store.today();
            LocalDate fy = Money.fyStart(today);
            YearMonth thisMonth = YearMonth.from(today);
            long raisedFy = 0, spentFy = 0, inAll = 0, outAll = 0;
            for (Donation d : store.donations) { inAll += d.amount; if (Calc.inRange(d.date, fy, today)) raisedFy += d.amount; }
            for (Expense e : store.expenses) if (e.status == Review.APPROVED) { outAll += e.amount; if (Calc.inRange(e.date, fy, today)) spentFy += e.amount; }
            int reached = 0;
            for (Activity a : store.activities) if (a.status == ActivityStatus.DONE) reached += a.reached;
            double hoursMonth = 0;
            for (HoursLog h : store.hours)
                if (h.status == Review.APPROVED && YearMonth.from(LocalDate.parse(h.date)).equals(thisMonth)) hoursMonth += h.hours;
            int activePrograms = (int) store.programs.stream().filter(p -> p.status == ProgramStatus.ACTIVE).count();
            int activeVols = (int) store.volunteers.stream().filter(v -> v.active).count();

            // last 12 months: money in and out
            List<Object> months = new ArrayList<>();
            for (int i = 11; i >= 0; i--) {
                YearMonth ym = thisMonth.minusMonths(i);
                long in = 0, out = 0;
                for (Donation d : store.donations) if (YearMonth.from(LocalDate.parse(d.date)).equals(ym)) in += d.amount;
                for (Expense e : store.expenses)
                    if (e.status == Review.APPROVED && YearMonth.from(LocalDate.parse(e.date)).equals(ym)) out += e.amount;
                months.add(Json.map("month", ym.toString(), "in", in, "out", out));
            }

            // the next planned activities
            List<Object> upcoming = new ArrayList<>();
            store.activities.stream()
                    .filter(a -> a.status == ActivityStatus.PLANNED && !LocalDate.parse(a.date).isBefore(today))
                    .sorted(Comparator.comparing((Activity a) -> a.date).thenComparing(a -> a.time == null ? "" : a.time))
                    .limit(6).forEach(a -> upcoming.add(programs.activityView(a, me)));

            // things waiting for someone
            List<Object> todo = new ArrayList<>();
            if (AuthService.canFinance(me)) {
                long n = store.expenses.stream().filter(e -> e.status == Review.PENDING && !me.username.equals(e.submittedBy)).count();
                if (n > 0) todo.add(Json.map("kind", "expenses", "count", n, "text", n + " expense" + (n == 1 ? "" : "s") + " waiting for your approval", "link", "#/expenses?status=PENDING"));
            }
            if (AuthService.canPrograms(me)) {
                long n = store.hours.stream().filter(h -> h.status == Review.PENDING).count();
                if (n > 0) todo.add(Json.map("kind", "hours", "count", n, "text", n + " volunteer hour entr" + (n == 1 ? "y" : "ies") + " to approve", "link", "#/hours?status=PENDING"));
                for (Activity a : store.activities)
                    if (a.status == ActivityStatus.PLANNED && LocalDate.parse(a.date).isBefore(today))
                        todo.add(Json.map("kind", "close", "count", 1, "text", "\"" + a.title + "\" (" + nice(a.date) + ") is over - mark it done or cancelled", "link", "#/activity/" + a.id));
                for (Activity a : store.activities)
                    if (a.status == ActivityStatus.PLANNED && !LocalDate.parse(a.date).isBefore(today)
                            && !LocalDate.parse(a.date).isAfter(today.plusDays(7)) && a.seatsLeft() > 0)
                        todo.add(Json.map("kind", "seats", "count", a.seatsLeft(), "text", "\"" + a.title + "\" on " + nice(a.date) + " still needs " + a.seatsLeft() + " volunteer" + (a.seatsLeft() == 1 ? "" : "s"), "link", "#/activity/" + a.id));
            }
            for (Program p : store.programs) {
                if (p.status == ProgramStatus.COMPLETED || p.budget <= 0) continue;
                int used = Money.percent(Calc.spent(store, p.id, null, null), p.budget);
                if (used >= 90) todo.add(Json.map("kind", "budget", "count", used, "text", p.name + " has used " + used + "% of its budget", "link", "#/program/" + p.id));
            }

            List<Object> progs = new ArrayList<>();
            for (Program p : store.programs) if (p.status == ProgramStatus.ACTIVE) progs.add(Calc.programView(store, p));

            return Json.map(
                    "kpis", Json.map("raisedFy", raisedFy, "spentFy", spentFy, "balance", inAll - outAll, "activePrograms", activePrograms,
                            "reached", reached, "activeVolunteers", activeVols, "hoursMonth", Calc.round1(hoursMonth),
                            "financialYear", Money.financialYear(today)),
                    "months", months, "upcoming", upcoming, "todo", todo, "programs", progs,
                    "recent", events(null, 8, true));
        }
    }

    private Map<String, Object> volunteerDashboard(User me) {
        synchronized (store) {
            LocalDate today = store.today();
            String vid = me.volunteerId == null ? "-" : me.volunteerId;
            Volunteer v = store.volunteer(vid);
            List<Object> mine = new ArrayList<>(), open = new ArrayList<>();
            int done = 0;
            List<Activity> sorted = new ArrayList<>(store.activities);
            sorted.sort(Comparator.comparing((Activity a) -> a.date));
            for (Activity a : sorted) {
                boolean in = a.volunteerIds.contains(vid);
                if (in && a.status == ActivityStatus.DONE) done++;
                if (a.status != ActivityStatus.PLANNED || LocalDate.parse(a.date).isBefore(today)) continue;
                if (in) mine.add(programs.activityView(a, me));
                else if (a.seatsLeft() > 0 && open.size() < 6) open.add(programs.activityView(a, me));
            }
            return Json.map("volunteer", v == null ? null : Json.map("id", v.id, "name", v.name, "joinedOn", v.joinedOn, "skills", v.skills),
                    "hours", Calc.round1(Calc.volunteerHours(store, vid, Review.APPROVED, null, null)),
                    "pendingHours", Calc.round1(Calc.volunteerHours(store, vid, Review.PENDING, null, null)),
                    "activitiesDone", done, "mine", mine, "open", open,
                    "toLog", volunteers.unlogged(me, vid));
        }
    }

    /** The impact report for a date range (default: this financial year so far). */
    public Map<String, Object> report(User me, Map<String, String> q) {
        AuthService.needStaff(me, "see reports");
        synchronized (store) {
            LocalDate today = store.today();
            LocalDate from = Text.date(q.get("from"), "From", false), to = Text.date(q.get("to"), "To", false);
            if (from == null) from = Money.fyStart(today);
            if (to == null) to = today;
            if (to.isBefore(from)) throw ApiError.bad("'To' date is before 'From' date");

            List<Object> rows = new ArrayList<>();
            long tBudget = 0, tRaised = 0, tSpent = 0; int tDone = 0, tReached = 0; double tHours = 0;
            for (Program p : store.programs) {
                long raised = Calc.raised(store, p.id, from, to), spent = Calc.spent(store, p.id, from, to);
                int done = Calc.activities(store, p.id, ActivityStatus.DONE, from, to), reached = Calc.reached(store, p.id, from, to);
                double hrs = Calc.programHours(store, p.id, from, to);
                rows.add(Json.map("id", p.id, "name", p.name, "cause", p.cause, "status", p.status, "budget", p.budget,
                        "raised", raised, "spent", spent, "used", Money.percent(Calc.spent(store, p.id, null, null), p.budget),
                        "done", done, "reached", reached, "target", p.targetBeneficiaries, "hours", Calc.round1(hrs),
                        "enrolled", Calc.enrolled(store, p.id)));
                tBudget += p.budget; tRaised += raised; tSpent += spent; tDone += done; tReached += reached; tHours += hrs;
            }

            long general = 0, totalIn = 0;
            Map<String, Long> byType = new LinkedHashMap<>();
            for (DonorType t : DonorType.values()) byType.put(t.name(), 0L);
            Map<String, Long> donorTotals = new HashMap<>();
            for (Donation d : store.donations) {
                if (!Calc.inRange(d.date, from, to)) continue;
                totalIn += d.amount;
                if (d.programId == null) general += d.amount;
                Donor dn = store.donor(d.donorId);
                if (dn != null) byType.merge(dn.type.name(), d.amount, Long::sum);
                donorTotals.merge(d.donorId, d.amount, Long::sum);
            }
            List<Object> topDonors = new ArrayList<>();
            donorTotals.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5).forEach(e -> {
                Donor dn = store.donor(e.getKey());
                topDonors.add(Json.map("id", e.getKey(), "name", dn == null ? e.getKey() : dn.name, "type", dn == null ? null : dn.type, "total", e.getValue()));
            });

            Map<String, Long> byCat = new LinkedHashMap<>();
            for (ExpenseCategory c : ExpenseCategory.values()) byCat.put(c.name(), 0L);
            long totalOut = 0;
            for (Expense e : store.expenses)
                if (e.status == Review.APPROVED && Calc.inRange(e.date, from, to)) { byCat.merge(e.category.name(), e.amount, Long::sum); totalOut += e.amount; }

            Map<String, Double> volHours = new HashMap<>();
            for (HoursLog h : store.hours) if (h.status == Review.APPROVED && Calc.inRange(h.date, from, to)) volHours.merge(h.volunteerId, h.hours, Double::sum);
            List<Object> topVols = new ArrayList<>();
            volHours.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(5).forEach(e -> {
                Volunteer v = store.volunteer(e.getKey());
                topVols.add(Json.map("id", e.getKey(), "name", v == null ? e.getKey() : v.name, "hours", Calc.round1(e.getValue())));
            });

            Settings s = store.settings;
            return Json.map("from", from.toString(), "to", to.toString(), "org", s,
                    "programs", rows,
                    "totals", Json.map("budget", tBudget, "raised", tRaised, "spent", tSpent, "done", tDone, "reached", tReached, "hours", Calc.round1(tHours)),
                    "income", Json.map("total", totalIn, "general", general, "earmarked", totalIn - general, "byType", byType),
                    "spending", Json.map("total", totalOut, "byCategory", byCat),
                    "topDonors", topDonors, "topVolunteers", topVols,
                    "volunteers", (int) store.volunteers.stream().filter(v -> v.active).count(),
                    "volunteersActive", volHours.size());
        }
    }

    private static final java.time.format.DateTimeFormatter NICE = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH);

    /** "Sun 1 Nov" */
    static String nice(String iso) { return LocalDate.parse(iso).format(NICE); }

    public List<Map<String, Object>> events(String ref, int limit) { return events(ref, limit, false); }

    /** Newest first. skipSignIns leaves out "signed in" lines (used on the home page). */
    public List<Map<String, Object>> events(String ref, int limit, boolean skipSignIns) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) {
            for (int i = store.events.size() - 1; i >= 0 && out.size() < limit; i--) {
                Event e = store.events.get(i);
                if (ref != null && !ref.equals(e.ref)) continue;
                if (skipSignIns && "signed in".equals(e.action)) continue;
                User u = store.user(e.user);
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) e.toJson();
                m.put("userName", u == null ? e.user : u.displayName);
                out.add(m);
            }
        }
        return out;
    }

    public Map<String, Object> settings() {
        synchronized (store) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) store.settings.toJson();
            return m;
        }
    }

    public Map<String, Object> saveSettings(User me, Map<String, Object> b) {
        AuthService.needAdmin(me, "change NGO details");
        synchronized (store) {
            Settings s = store.settings;
            s.orgName = Text.line(Json.str(b, "orgName"), "NGO name", 100, true);
            s.regNo = Text.line(Json.str(b, "regNo"), "Registration number", 40, false);
            s.address = Text.line(Json.str(b, "address"), "Address", 200, false);
            s.email = Text.email(Json.str(b, "email"));
            s.receiptNote = Text.line(Json.str(b, "receiptNote"), "Receipt note", 300, false);
            store.saveSettings();
            store.log(me.username, "changed NGO details", s.orgName, null);
            return settings();
        }
    }
}
