package com.cecil.dainandini.store;

import com.cecil.dainandini.ai.WorkTime;
import com.cecil.dainandini.model.CalEvent;
import com.cecil.dainandini.model.Role;
import com.cecil.dainandini.model.Task;
import com.cecil.dainandini.model.User;
import com.cecil.dainandini.service.Problem;
import com.cecil.dainandini.util.Clock;
import com.cecil.dainandini.util.Json;
import com.cecil.dainandini.util.Passwords;
import com.cecil.dainandini.web.ApiServer;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The demo office: TARANG TECH, a made-up software company in Ranchi with 8 people in two teams.
 *
 * The 4 weeks of history are not typed in. They are PLAYED THROUGH THE REAL SERVICES with the clock moved back:
 * people create tasks, run focus sessions in the hours that suit them, get interrupted, finish tasks (some late),
 * sit in meetings and write meeting notes. So every number on the Insights page – the estimate factor, the peak
 * hours, the on-time rate – was learned by the app from that history, exactly as it would be from real use.
 * Everything is dated relative to today. All people are made up.
 */
public final class SeedData {
    private SeedData() { }

    record Tpl(String title, String tag, int est, boolean deep) { }

    /** One made-up person: who they are, when they work, when they focus best and how they (mis)estimate. */
    record Person(String username, String name, String title, String team, Role role, String password, int ws, int we,
                  int peakFrom, int peakTo, double factor, Map<String, Double> tagFactor, double interruptRate, List<Tpl> work) { }

    static final String[] FEATURES = { "onboarding", "checkout", "profile page", "search", "notifications", "settings", "dashboard", "invoices" };
    static final String[] BUGS = { "login timeout", "date picker", "PDF export", "cart total", "image upload", "search ranking" };
    static final String[] TOPICS = { "AI at work", "remote work tips", "product launch", "customer story", "Diwali offer", "hiring drive" };

    static List<Person> people() {
        List<Person> p = new ArrayList<>();
        p.add(new Person("neha", "Neha Kujur", "Product designer", "Product", Role.MEMBER, "member123", 570, 1080, 570, 690, 1.15,
                Map.of("design", 1.5, "research", 1.1), 0.5, List.of(
                new Tpl("Design %f screens", "design", 120, true), new Tpl("Review %f prototype with dev", "design", 60, true),
                new Tpl("Export icons for %f", "design", 30, false), new Tpl("Reply to feedback on %f", "email", 20, false),
                new Tpl("Write user interview notes – %f", "research", 60, true), new Tpl("Update style guide for %f", "design", 45, true))));
        p.add(new Person("ravi", "Ravi Oraon", "Backend developer", "Product", Role.MEMBER, "member123", 660, 1170, 900, 1050, 1.05,
                Map.of("bug", 1.4, "backend", 1.1), 0.7, List.of(
                new Tpl("Fix %b bug", "bug", 60, true), new Tpl("Build %f API", "backend", 150, true), new Tpl("Code review for Meera", "review", 30, false),
                new Tpl("Write unit tests for %f", "testing", 60, true), new Tpl("Deploy %f to staging", "devops", 30, false))));
        p.add(new Person("meera", "Meera Soren", "Frontend developer", "Product", Role.MEMBER, "member123", 540, 1050, 540, 660, 0.92,
                Map.of("frontend", 0.9), 0.4, List.of(
                new Tpl("Build %f page", "frontend", 120, true), new Tpl("Fix %b bug", "bug", 60, true), new Tpl("Write docs for %f", "docs", 45, true),
                new Tpl("Code review for Ravi", "review", 30, false))));
        p.add(new Person("sana", "Sana Parveen", "QA analyst", "Product", Role.MEMBER, "member123", 570, 1080, 840, 960, 1.0,
                Map.of("testing", 1.05), 0.6, List.of(
                new Tpl("Test %f on Android", "testing", 60, true), new Tpl("Write test cases for %f", "testing", 45, true),
                new Tpl("Sprint bug report", "report", 30, false), new Tpl("Check %b fix", "testing", 20, false))));
        p.add(new Person("arjun", "Arjun Mahto", "Product team lead", "Product", Role.LEAD, "lead123", 570, 1110, 540, 630, 1.2,
                Map.of("planning", 1.3), 1.1, List.of(
                new Tpl("Prepare sprint plan", "planning", 60, true), new Tpl("Reply to client emails", "email", 30, false),
                new Tpl("Update roadmap slide", "planning", 45, true), new Tpl("Budget check for the quarter", "finance", 60, true),
                new Tpl("Review CVs for the frontend role", "hiring", 45, false))));
        p.add(new Person("tenzin", "Tenzin Lepcha", "Content writer", "Marketing", Role.MEMBER, "member123", 600, 1110, 600, 720, 1.2,
                Map.of("content", 1.3), 0.5, List.of(
                new Tpl("Write blog post: %t", "content", 120, true), new Tpl("Edit the newsletter", "content", 60, true),
                new Tpl("Proofread %t landing page", "content", 30, false), new Tpl("Draft social captions for %t", "social", 30, false))));
        p.add(new Person("farhan", "Farhan Ansari", "Marketing lead", "Marketing", Role.LEAD, "lead123", 570, 1080, 570, 660, 1.1,
                Map.of(), 0.9, List.of(
                new Tpl("Campaign report: %t", "report", 60, true), new Tpl("Call the agency about %t", "calls", 30, false),
                new Tpl("Plan %t campaign", "planning", 90, true), new Tpl("Approve ad budget", "finance", 20, false))));
        p.add(new Person("ishita", "Ishita Minz", "Social media executive", "Marketing", Role.MEMBER, "member123", 570, 1080, 660, 780, 1.1,
                Map.of("video", 1.35), 1.3, List.of(
                new Tpl("Schedule posts for the week", "social", 45, false), new Tpl("Reply to comments", "social", 20, false),
                new Tpl("Make reel: %t", "video", 90, true), new Tpl("Monthly social stats", "report", 40, true))));
        return p;
    }

    public static void load(ApiServer.Parts p) {
        DataStore store = p.store();
        Instant before = Clock.fixed();
        long realNow = Clock.millis();
        LocalDate today = WorkTime.dateOf(realNow);
        Random rnd = new Random(2026);
        store.batch = true;
        try {
            Clock.fix(Instant.ofEpochMilli(WorkTime.at(today.minusDays(40), 600)));
            Map<String, User> u = new LinkedHashMap<>();
            Map<String, Person> who = new LinkedHashMap<>();
            User admin = addUser(store, "admin", "Kavya Tirkey", "Office admin", "", Role.ADMIN, "admin123", 570, 1080);
            u.put("admin", admin);
            for (Person x : people()) { u.put(x.username(), addUser(store, x.username(), x.name(), x.title(), x.team(), x.role(), x.password(), x.ws(), x.we())); who.put(x.username(), x); }
            store.setSetting("claude", "OFF");

            // ---- the calendar: 4 weeks back, 2 weeks ahead
            for (LocalDate d = today.minusDays(28); d.isBefore(today.plusDays(15)); d = d.plusDays(1)) meetings(p, u, d, today);
            Clock.fix(Instant.ofEpochMilli(WorkTime.at(today.minusDays(3), 600)));
            p.calendar().create(u.get("sana"), Json.obj("title", "Sana – leave (family wedding)", "kind", "LEAVE", "date", WorkTime.nextWorkday(u.get("sana"), today.plusDays(6)).toString(), "days", 2, "force", true));
            p.calendar().create(u.get("neha"), Json.obj("title", "Doctor's appointment", "kind", "BLOCK", "date", WorkTime.nextWorkday(u.get("neha"), today.plusDays(2)).toString(), "start", "16:30", "minutes", 60, "force", true));

            // ---- 4 weeks of finished work
            for (Person x : who.values()) history(p, u.get(x.username()), x, today, rnd);

            // ---- meeting notes
            notes(p, u, today);

            // ---- what is open now (typed with quick capture, a few days ago)
            openWork(p, u, today, realNow);
        } finally {
            Clock.fix(before);
            store.saveAll();
        }
    }

    public static User addUser(DataStore s, String username, String name, String title, String team, Role role, String pw, int ws, int we) {
        User u = new User();
        u.id = s.nextId("U", 1);
        u.username = username; u.name = name; u.title = title; u.team = team; u.role = role;
        u.passwordHash = Passwords.hash(pw);
        u.workStart = ws; u.workEnd = we;
        u.lunchStart = 13 * 60 + 15; u.lunchMin = 45;
        u.focusGoalMin = role == Role.LEAD ? 150 : 240;
        u.createdAt = Clock.millis();
        s.users.put(u.id, u);
        return u;
    }

    // ------------------------------------------------------------------ meetings

    static void meet(ApiServer.Parts p, User owner, LocalDate d, int start, int minutes, String title, User... others) {
        Clock.fix(Instant.ofEpochMilli(WorkTime.at(d.minusDays(3), 600)));
        List<String> ids = new ArrayList<>();
        for (User o : others) ids.add(o.id);
        try {
            p.calendar().create(owner, Json.obj("title", title, "date", d.toString(), "start", String.format("%02d:%02d", start / 60, start % 60),
                    "minutes", minutes, "attendees", ids, "force", true));
        } catch (Problem ignored) { /* never happens with force = true */ }
    }

    static void meetings(ApiServer.Parts p, Map<String, User> u, LocalDate d, LocalDate today) {
        DayOfWeek dow = d.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return;
        User arjun = u.get("arjun"), neha = u.get("neha"), ravi = u.get("ravi"), meera = u.get("meera"), sana = u.get("sana");
        User farhan = u.get("farhan"), tenzin = u.get("tenzin"), ishita = u.get("ishita");
        meet(p, arjun, d, 600, 15, "Product stand-up", neha, ravi, meera, sana);
        meet(p, farhan, d, 630, 15, "Marketing stand-up", tenzin, ishita);
        switch (dow) {
            case MONDAY -> {
                meet(p, arjun, d, 690, 60, "Sprint planning", neha, ravi, meera, sana);
                meet(p, farhan, d, 900, 45, "Content calendar", tenzin, ishita);
            }
            case TUESDAY -> {
                meet(p, arjun, d, 870, 60, "Client call – Kestrel Foods", neha, u.get("farhan"));
                meet(p, farhan, d, 720, 45, "Marketing weekly sync", tenzin, ishita, arjun);
                meet(p, arjun, d, 1020, 30, "Hiring panel", meera);
            }
            case WEDNESDAY -> {
                meet(p, neha, d, 900, 60, "Design review", arjun, ravi, meera);
                meet(p, arjun, d, 660, 30, "1:1 Arjun / Neha", neha);
                meet(p, arjun, d, 720, 30, "1:1 Arjun / Ravi", ravi);
                meet(p, ishita, d, 990, 30, "Reel ideas", tenzin);
            }
            case THURSDAY -> {
                meet(p, arjun, d, 660, 30, "1:1 Arjun / Meera", meera);
                meet(p, arjun, d, 720, 30, "1:1 Arjun / Sana", sana);
                meet(p, farhan, d, 900, 60, "Campaign review", tenzin, ishita, arjun);
                meet(p, ravi, d, 1080, 45, "Database migration dry run", meera);
            }
            case FRIDAY -> {
                meet(p, arjun, d, 990, 45, "Sprint demo", neha, ravi, meera, sana, farhan);
                meet(p, u.get("admin"), d, 1050, 30, "Friday team chai", arjun, neha, ravi, meera, sana, farhan, tenzin, ishita);
            }
            default -> { }
        }
        // a few extra one-off meetings make some days heavier (Tuesdays for Neha)
        if (dow == DayOfWeek.TUESDAY) meet(p, neha, d, 690, 45, "Accessibility check with Sana", sana);
        if (dow == DayOfWeek.TUESDAY) meet(p, arjun, d, 960, 30, "Vendor demo – analytics tool", neha);
    }

    // ------------------------------------------------------------------ history

    static String fill(String title, Random r) {
        return title.replace("%f", FEATURES[r.nextInt(FEATURES.length)]).replace("%b", BUGS[r.nextInt(BUGS.length)])
                .replace("%t", TOPICS[r.nextInt(TOPICS.length)]);
    }

    /** Plays 4 weeks of finished work for one person. */
    static void history(ApiServer.Parts p, User u, Person x, LocalDate today, Random rnd) {
        for (LocalDate d = today.minusDays(28); d.isBefore(today); d = d.plusDays(1)) {
            if (!WorkTime.workday(u, d)) continue;
            int n = 2 + (rnd.nextInt(10) < 3 ? 1 : 0);
            // the free 15-minute pieces of the day, best focus hours first
            List<long[]> free = freeQuarters(p, u, x, d);
            for (int k = 0; k < n; k++) {
                Tpl t = x.work().get(rnd.nextInt(x.work().size()));
                String title = fill(t.title(), rnd);
                int createdDaysBefore = rnd.nextInt(6);
                LocalDate created = d.minusDays(createdDaysBefore);
                long createdAt = WorkTime.at(created, createdDaysBefore == 0 ? u.workStart + 5 : u.workStart + 20 + rnd.nextInt(60));
                Clock.fix(Instant.ofEpochMilli(createdAt));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("title", title);
                body.put("tags", List.of(t.tag()));
                body.put("energy", t.deep() ? "DEEP" : "SHALLOW");
                body.put("importance", rnd.nextInt(10) < 3 ? "HIGH" : rnd.nextInt(10) < 2 ? "LOW" : "MEDIUM");
                boolean noEstimate = rnd.nextInt(100) < 12;
                int est = (int) Math.max(10, Math.round(t.est() * (0.8 + rnd.nextDouble() * 0.5) / 5.0) * 5);
                if (!noEstimate) body.put("estimateMin", est);
                int dueRoll = rnd.nextInt(100);
                if (dueRoll < 22) body.put("dueDate", d.minusDays(1).toString());          // finished one day late
                else if (dueRoll < 75) body.put("dueDate", d.plusDays(rnd.nextInt(4)).toString());
                if (body.containsKey("dueDate") && LocalDate.parse((String) body.get("dueDate")).isBefore(created)) body.put("dueDate", d.toString());
                Task task = p.tasks().create(u, body);
                // real time = estimate × personal factor × tag factor × noise
                double f = x.factor() * x.tagFactor().getOrDefault(t.tag(), 1.0) * (0.8 + rnd.nextDouble() * 0.45);
                int actual = (int) Math.max(10, Math.round(task.estimateMin * f));
                int left = actual;
                long lastEnd = WorkTime.at(d, u.workStart) + 30 * WorkTime.MIN;
                while (left > 0 && !free.isEmpty()) {
                    long[] slot = takeChunk(free, Math.min(left, t.deep() ? 50 : 30));
                    if (slot == null) break;
                    int len = (int) ((slot[1] - slot[0]) / WorkTime.MIN);
                    boolean finished = rnd.nextInt(100) < 82;
                    int planned = len;
                    int ran = finished ? len : Math.max(5, (int) (len * (0.5 + rnd.nextDouble() * 0.4)));
                    Clock.fix(Instant.ofEpochMilli(slot[0]));
                    p.focus().start(u, task.id, Math.max(5, planned));
                    int hour = WorkTime.minuteOf(slot[0]) / 60;
                    boolean inPeak = WorkTime.minuteOf(slot[0]) >= x.peakFrom() && WorkTime.minuteOf(slot[0]) < x.peakTo();
                    double rate = x.interruptRate() * (inPeak ? 0.4 : 1.0) * (hour >= 14 ? 1.3 : 1.0);
                    int ints = (int) Math.floor(rate * rnd.nextDouble() * 2.2);
                    for (int i = 0; i < ints; i++) p.focus().interrupt(u);
                    Clock.fix(Instant.ofEpochMilli(slot[0] + ran * WorkTime.MIN));
                    p.focus().stop(u, false);
                    left -= ran;
                    lastEnd = slot[0] + ran * WorkTime.MIN;
                }
                // Ravi sometimes works late in the evening (shows up as after-hours work)
                if (left > 0 && u.username.equals("ravi") && rnd.nextInt(3) == 0) {
                    long s = WorkTime.at(d, 20 * 60 + 30);
                    Clock.fix(Instant.ofEpochMilli(s));
                    p.focus().start(u, task.id, Math.min(60, Math.max(10, left)));
                    Clock.fix(Instant.ofEpochMilli(s + Math.min(60, Math.max(10, left)) * WorkTime.MIN));
                    p.focus().stop(u, false);
                    lastEnd = s + Math.min(60, left) * WorkTime.MIN;
                    left -= Math.min(60, left);
                }
                Clock.fix(Instant.ofEpochMilli(Math.max(Math.max(lastEnd, createdAt + 10 * WorkTime.MIN), WorkTime.at(d, u.workStart + 60))));
                if (left > 0) p.tasks().logTime(u, task.id, Math.min(600, left));
                p.tasks().done(u, task.id, null);
            }
        }
    }

    /** Free 15-minute pieces of a day (no meetings, no lunch), sorted: peak hours first, then by time. */
    static List<long[]> freeQuarters(ApiServer.Parts p, User u, Person x, LocalDate d) {
        List<long[]> busy = new ArrayList<>();
        for (CalEvent e : p.brain().eventsOn(u, d)) busy.add(new long[] { e.start, e.end });
        long ls = WorkTime.at(d, u.lunchStart);
        busy.add(new long[] { ls, ls + u.lunchMin * WorkTime.MIN });
        List<long[]> out = new ArrayList<>();
        for (long[] s : WorkTime.free(WorkTime.at(d, u.workStart + 15), WorkTime.at(d, u.workEnd - 10), busy))
            for (long t = s[0]; t + 15 * WorkTime.MIN <= s[1]; t += 15 * WorkTime.MIN) out.add(new long[] { t, t + 15 * WorkTime.MIN });
        out.sort((a, b) -> {
            boolean pa = inPeak(x, a[0]), pb = inPeak(x, b[0]);
            if (pa != pb) return pa ? -1 : 1;
            return Long.compare(a[0], b[0]);
        });
        return out;
    }

    static boolean inPeak(Person x, long t) { int m = WorkTime.minuteOf(t); return m >= x.peakFrom() && m < x.peakTo(); }

    /** Takes neighbouring quarters (in time order) from the front of the list, up to `minutes`. */
    static long[] takeChunk(List<long[]> quarters, int minutes) {
        if (quarters.isEmpty()) return null;
        long[] first = quarters.remove(0);
        long start = first[0], end = first[1];
        while (end - start < minutes * WorkTime.MIN) {
            long[] next = null;
            for (long[] q : quarters) if (q[0] == end) { next = q; break; }
            if (next == null) break;
            quarters.remove(next);
            end = next[1];
        }
        long want = start + Math.max(10, minutes) * WorkTime.MIN;
        return new long[] { start, Math.min(end, want) };
    }

    // ------------------------------------------------------------------ meeting notes

    static LocalDate lastWeekday(LocalDate today, DayOfWeek dow) {
        LocalDate d = today.minusDays(1);
        while (d.getDayOfWeek() != dow) d = d.minusDays(1);
        return d;
    }

    static void notes(ApiServer.Parts p, Map<String, User> u, LocalDate today) {
        LocalDate mon = lastWeekday(today.plusDays(1), DayOfWeek.MONDAY);
        if (!mon.isBefore(today)) mon = mon.minusWeeks(1);
        Clock.fix(Instant.ofEpochMilli(WorkTime.at(mon, 12 * 60 + 35)));
        var n1 = p.notes().save(u.get("arjun"), Json.obj("title", "Sprint planning", "at", WorkTime.at(mon, 690), "text", String.join("\n",
                "Sprint goal: ship the new onboarding flow and fix the top five bugs.",
                "We decided to move the dark mode work to the next sprint.",
                "Neha will finish the onboarding mockups by Wednesday.",
                "Ravi to fix the image upload crash by Friday.",
                "Action: @meera build the onboarding screens once the mockups are ready.",
                "Sana will write test cases for onboarding by Thursday.",
                "Budget for user testing was approved – Rs 15,000 for ten sessions.",
                "I will update the roadmap slide tomorrow.",
                "Who owns the release notes this time?",
                "We need to renew the Figma licence before the end of the month.",
                "Risk: the payment gateway sandbox is down, which may delay checkout testing.")));
        p.notes().makeTask(u.get("arjun"), n1.id, indexOf(n1, "roadmap"), null);
        p.notes().makeTask(u.get("arjun"), n1.id, indexOf(n1, "Figma"), null);

        LocalDate wed = lastWeekday(today, DayOfWeek.WEDNESDAY);
        Clock.fix(Instant.ofEpochMilli(WorkTime.at(wed, 16 * 60 + 5)));
        var n2 = p.notes().save(u.get("neha"), Json.obj("title", "Design review – checkout", "at", WorkTime.at(wed, 900), "text", String.join("\n",
                "- Reviewed the new checkout screens with Arjun, Ravi and Meera.",
                "- Everyone liked the one-page layout; the coupon field is hard to find.",
                "- Agreed to show the delivery date before payment.",
                "- Meera: the address form breaks on small phones.",
                "- I'll move the coupon field above the total by Monday.",
                "- Ravi will check if the delivery date API is fast enough.",
                "- Can Meera share the small-phone screenshots today?",
                "- Do we need a guest checkout? Ask the client.")));
        p.notes().makeTask(u.get("neha"), n2.id, indexOf(n2, "coupon"), null);

        LocalDate fri = lastWeekday(today, DayOfWeek.FRIDAY);
        Clock.fix(Instant.ofEpochMilli(WorkTime.at(fri, 12 * 60 + 10)));
        p.notes().save(u.get("farhan"), Json.obj("title", "Diwali campaign wrap-up", "at", WorkTime.at(fri, 690), "text", String.join("\n",
                "The Diwali offer campaign reached 48,000 people and brought 620 sign-ups.",
                "Reels did best: three times more shares than image posts.",
                "We decided to keep one reel a week in December.",
                "Tenzin will write the customer story blog by next Tuesday.",
                "Ishita to prepare the December posting calendar by Friday.",
                "I will send the final numbers to Arjun tomorrow.",
                "Should we try LinkedIn ads next quarter?")));
    }

    static int indexOf(com.cecil.dainandini.model.MeetingNote n, String word) {
        for (int i = 0; i < n.actions.size(); i++) if (String.valueOf(n.actions.get(i).get("text")).contains(word)) return i;
        return 0;
    }

    // ------------------------------------------------------------------ open work (quick capture)

    static long realNow;

    /** Typed now (so "tomorrow" means tomorrow), but dated back to when it was first noted. */
    static void capture(ApiServer.Parts p, User u, long at, String text) {
        Clock.fix(Instant.ofEpochMilli(realNow));
        Task t = p.tasks().create(u, Json.obj("text", text));
        t.createdAt = Math.min(at, realNow);
        t.updatedAt = t.createdAt;
    }

    static void openWork(ApiServer.Parts p, Map<String, User> u, LocalDate today, long now) {
        realNow = now;
        User neha = u.get("neha");
        long d2 = WorkTime.at(today.minusDays(2), 610), d4 = WorkTime.at(today.minusDays(4), 615), d1 = WorkTime.at(today.minusDays(1), 640);
        capture(p, neha, d2, "Finish onboarding flow mockups by tomorrow 4pm ~3h #design !!");
        capture(p, neha, d1, "Reply to client feedback on the dashboard today 3pm ~30m");
        capture(p, neha, d1, "Send icon pack to @ravi by today 5pm ~20m #design");
        capture(p, neha, d2, "Review checkout prototype with Meera on Thursday ~1h #design");
        capture(p, neha, d2, "Usability test plan for search next week ~2h #research");
        capture(p, neha, d4, "Update design system changelog every friday ~30m #design");
        capture(p, neha, d1, "Pay the team lunch bill tomorrow ~10m");
        capture(p, neha, WorkTime.at(today.minusDays(20), 700), "Prepare portfolio case study someday ~2h");
        capture(p, neha, WorkTime.at(today.minusDays(18), 700), "Clean up the old Figma files #design");
        Clock.fix(Instant.ofEpochMilli(d4));
        p.tasks().create(neha, Json.obj("title", "Export dark-mode screens for the profile page", "estimateMin", 45, "tags", List.of("design"),
                "energy", "DEEP", "importance", "HIGH", "dueDate", today.minusDays(1).toString()));
        // Neha already spent 40 minutes on the mockups (typed in by hand)
        Clock.fix(Instant.ofEpochMilli(realNow));
        for (Task t : p.store().openTasksOf(neha.id)) if (t.title.startsWith("Finish onboarding")) p.tasks().logTime(neha, t.id, 40);

        User ravi = u.get("ravi"), meera = u.get("meera"), sana = u.get("sana"), arjun = u.get("arjun");
        capture(p, ravi, d2, "Build invoices API by Friday ~4h #backend");
        capture(p, ravi, d1, "Code review for Meera today ~30m #review");
        capture(p, ravi, d1, "Check delivery date API speed by tomorrow ~45m #backend");
        capture(p, meera, d2, "Build onboarding screens by next Wednesday ~6h #frontend");
        capture(p, meera, d1, "Share small-phone screenshots with Neha today ~15m");
        capture(p, meera, d1, "Write docs for the search filters ~1h #docs");
        capture(p, sana, d2, "Write test cases for onboarding by Thursday ~2h #testing");
        capture(p, sana, d1, "Test checkout on Android tomorrow ~1h #testing");
        capture(p, arjun, d1, "Reply to Kestrel Foods about the release date today 2pm ~20m #email");
        capture(p, arjun, d2, "Prepare sprint review slides by Friday ~1h #planning");
        capture(p, arjun, d1, "Fix crash on image upload @ravi by Friday ~2h #bug !!");    // a lead hands work to Ravi
        capture(p, arjun, d1, "Review CVs for the frontend role by tomorrow ~45m #hiring");

        User tenzin = u.get("tenzin"), ishita = u.get("ishita"), farhan = u.get("farhan");
        capture(p, tenzin, d2, "Write blog post: customer story by next Tuesday ~3h #content");
        capture(p, tenzin, d1, "Edit the December newsletter by Friday ~1h #content");
        capture(p, ishita, d1, "Prepare December posting calendar by Friday ~1.5h #social");
        capture(p, ishita, d1, "Make reel: hiring drive tomorrow ~90m #video");
        capture(p, farhan, d1, "Send final Diwali numbers to Arjun tomorrow ~20m #report");
        capture(p, farhan, d2, "Plan LinkedIn ads test by end of month ~2h #planning");
        Clock.fix(Instant.ofEpochMilli(realNow));
    }
}
