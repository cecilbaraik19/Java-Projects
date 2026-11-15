package com.cecil.sangam.store;

import com.cecil.sangam.core.WorkHours;
import com.cecil.sangam.model.Channel;
import com.cecil.sangam.model.Doc;
import com.cecil.sangam.model.Handoff;
import com.cecil.sangam.model.Message;
import com.cecil.sangam.model.Priority;
import com.cecil.sangam.model.Project;
import com.cecil.sangam.model.Role;
import com.cecil.sangam.model.Task;
import com.cecil.sangam.model.TaskStatus;
import com.cecil.sangam.model.User;
import com.cecil.sangam.service.ChatService;
import com.cecil.sangam.service.Problem;
import com.cecil.sangam.util.Clock;
import com.cecil.sangam.util.Json;
import com.cecil.sangam.util.Passwords;
import com.cecil.sangam.web.ApiServer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * The demo workspace: "Lumen Labs", a made-up travel-software company with 12 people in 11 cities, from Sydney to
 * San Francisco. ALL PEOPLE ARE MADE UP.
 *
 * The last two weeks are not typed in. They are PLAYED THROUGH THE REAL SERVICES with the clock moved back:
 * people post stand-ups when their own day starts, start and finish tasks (the WIP, blocker and review rules
 * refuse some moves – just like in real life), ask each other questions with @mentions and answer when THEIR
 * day begins, hand work over at the end of the day, edit documents (one edit is merged automatically), and book
 * calls with the fair-time finder (which builds up the "pain" points). So every number in the reports got there by
 * the normal rules. Everything is dated relative to the moment you first start the app.
 */
public final class SeedData {
    private SeedData() { }

    private record Person(String username, String name, String title, String city, String zone, String start, String end, Role role) { }

    private static final Person[] PEOPLE = {
            new Person("admin", "Priya Lakra", "Head of operations", "Ranchi", "Asia/Kolkata", "09:00", "18:00", Role.ADMIN),
            new Person("asha", "Asha Oraon", "Engineering lead", "Ranchi", "Asia/Kolkata", "10:00", "19:00", Role.MEMBER),
            new Person("rohit", "Rohit Nair", "Backend developer", "Bengaluru", "Asia/Kolkata", "09:30", "18:30", Role.MEMBER),
            new Person("mei", "Mei Tan", "Mobile developer", "Singapore", "Asia/Singapore", "09:00", "18:00", Role.MEMBER),
            new Person("jack", "Jack Morgan", "QA engineer", "Sydney", "Australia/Sydney", "08:30", "17:00", Role.MEMBER),
            new Person("lena", "Lena Vogel", "Product designer", "Berlin", "Europe/Berlin", "09:00", "17:30", Role.MEMBER),
            new Person("ines", "Inês Costa", "Data engineer", "Lisbon", "Europe/Lisbon", "09:00", "17:00", Role.MEMBER),
            new Person("kwame", "Kwame Mensah", "DevOps engineer", "Accra", "Africa/Accra", "08:00", "16:30", Role.MEMBER),
            new Person("amina", "Amina Wanjiru", "Frontend developer", "Nairobi", "Africa/Nairobi", "08:30", "17:30", Role.MEMBER),
            new Person("diego", "Diego Ramos", "Product manager", "São Paulo", "America/Sao_Paulo", "09:00", "18:00", Role.MEMBER),
            new Person("sarah", "Sarah Mitchell", "Full-stack developer", "Toronto", "America/Toronto", "09:00", "17:00", Role.MEMBER),
            new Person("client", "Hana Ito", "Marketing lead, Kestrel Travel (client)", "San Francisco", "America/Los_Angeles", "09:00", "17:00", Role.GUEST),
    };

    private record TaskDef(String title, int estimate, Priority priority, String who, int[] blockers, int dueInDays) { }

    private static TaskDef t(String title, int est, Priority p, String who, int dueInDays, int... blockers) {
        return new TaskDef(title, est, p, who, blockers, dueInDays);
    }

    private static final TaskDef[] ORB = {
            t("Set up crash reporting for the mobile app", 6, Priority.NORMAL, "kwame", 5),
            t("Design the new trip timeline screen", 8, Priority.HIGH, "lena", 4),
            t("Build the trip timeline API endpoint", 10, Priority.HIGH, "rohit", 8, 1),
            t("Build the trip timeline screen in Flutter", 12, Priority.HIGH, "mei", 12, 1, 2),
            t("Offline cache for saved trips", 10, Priority.NORMAL, "mei", 17, 3),
            t("Accessibility pass on the booking flow", 6, Priority.NORMAL, "amina", 9),
            t("Push notification opt-in screen", 5, Priority.NORMAL, "lena", 7),
            t("Push notification service", 8, Priority.NORMAL, "rohit", 13, 6),
            t("Regression test plan for release 3.2", 6, Priority.HIGH, "jack", 15, 3),
            t("Release 3.2 to the app stores", 3, Priority.URGENT, "kwame", 20, 8, 4),
            t("Fix: login fails on slow 3G", 4, Priority.URGENT, "rohit", 2),
            t("Dark-mode colours for trip cards", 3, Priority.LOW, "lena", 0),
            t("Write release notes for 3.2", 2, Priority.NORMAL, "diego", 21, 9),
            t("Reduce app start time below 2 seconds", 8, Priority.NORMAL, "mei", 10),
            t("Analytics events for the timeline", 4, Priority.LOW, "amina", 16, 3),
            t("API versioning plan", 4, Priority.NORMAL, "asha", 6),
            t("Code review checklist for the team", 3, Priority.LOW, "asha", 11),
    };
    private static final TaskDef[] ATL = {
            t("Move nightly jobs to the new scheduler", 10, Priority.HIGH, "ines", 7),
            t("Bookings table: partition by month", 6, Priority.NORMAL, "ines", 5),
            t("Data quality checks for the supplier feed", 8, Priority.HIGH, "rohit", 6),
            t("Terraform module for the warehouse", 8, Priority.NORMAL, "kwame", 6),
            t("Cost dashboard for cloud spend", 6, Priority.NORMAL, "sarah", 12, 3),
            t("Backfill 2025 booking events", 12, Priority.NORMAL, "ines", 14, 1),
            t("Privacy: delete user data on request", 10, Priority.URGENT, "rohit", 10, 0),
            t("Document the data model", 4, Priority.LOW, "diego", 0),
            t("Alert when the supplier feed is late", 4, Priority.HIGH, "kwame", 9, 2),
            t("Move the reports to the new warehouse", 10, Priority.NORMAL, "sarah", 20, 5, 3),
            t("Review warehouse access roles", 3, Priority.NORMAL, "asha", 8),
    };
    private static final TaskDef[] WEB = {
            t("Agree the sitemap with Kestrel Travel", 3, Priority.HIGH, "diego", 2),
            t("Homepage design", 8, Priority.HIGH, "lena", 6, 0),
            t("Destination page template design", 8, Priority.NORMAL, "lena", 9, 0),
            t("Build the homepage", 10, Priority.HIGH, "amina", 13, 1),
            t("Build the destination template", 10, Priority.NORMAL, "sarah", 15, 2),
            t("Move content from the old site", 8, Priority.NORMAL, "diego", 16, 2),
            t("SEO redirects for old URLs", 4, Priority.NORMAL, "sarah", 18, 5),
            t("Cross-browser test", 5, Priority.NORMAL, "jack", 19, 3, 4),
            t("Launch checklist", 2, Priority.NORMAL, "diego", 22, 7, 6),
            t("Cookie banner and privacy page", 3, Priority.NORMAL, "amina", 8),
    };

    /** Work that arrives in the second week (the backlog grows, like in real projects). */
    private static final TaskDef[] ORB2 = {
            t("Timeline: show train and bus legs", 8, Priority.NORMAL, "mei", 10),
            t("Timeline API: add rail supplier data", 6, Priority.NORMAL, "rohit", 9),
            t("Empty state illustration for the timeline", 3, Priority.LOW, "lena", 8),
            t("Load test the timeline API", 4, Priority.HIGH, "jack", 7),
            t("Feature flag for the timeline rollout", 3, Priority.HIGH, "kwame", 6),
            t("In-app survey after the first trip", 4, Priority.LOW, "amina", 12),
    };
    private static final TaskDef[] ATL2 = {
            t("Nightly job: retry with back-off", 4, Priority.NORMAL, "ines", 6),
            t("Supplier feed: schema version check", 5, Priority.HIGH, "rohit", 5),
            t("Warehouse: weekly cost report e-mail", 3, Priority.LOW, "sarah", 9),
            t("Disaster recovery test for the warehouse", 6, Priority.NORMAL, "kwame", 11),
    };
    private static final TaskDef[] WEB2 = {
            t("Seasonal hero image setting", 4, Priority.NORMAL, "amina", 8),
            t("Japanese translation of the homepage", 5, Priority.NORMAL, "sarah", 10),
            t("Blog page template", 6, Priority.NORMAL, "lena", 9),
            t("Analytics and cookie consent check", 2, Priority.NORMAL, "jack", 12),
    };

    /** Question / answer pairs. {to} {from} {task} are filled in. */
    private static final String[][] TALK = {
            { "@{to} quick one on {task}: is the API contract final? I would like to start today.", "@{from} yes, final for v1. I added the error codes to the doc." },
            { "@{to} can you look at my pull request for {task} when your day starts? No rush.", "@{from} done – two small comments, otherwise looks good 👍" },
            { "@{to} I am stuck on {task}: the staging database is empty again. Any idea?", "@{from} the nightly copy failed – I re-ran it, should be fine now." },
            { "@{to} for {task}, do we support iOS 15 or only 16+?", "@{from} 16+ only, we agreed that last month. I will write it in the doc." },
            { "@{to} could you check the designs for {task}? Left you a note in the file.", "@{from} looked at them, the spacing on small phones needs 8 px more. Rest is great." },
            { "@{to} is {task} still on track for the due date?", "@{from} yes, one more day. I will move it to review tomorrow." },
            { "@{to} small question: who owns the test data for {task}?", "@{from} I do – I will refresh it before your morning." },
            { "@{to} heads-up: {task} changes the login token format. Please check the mobile side.", "@{from} thanks, tested it – the app handles the new format." },
            { "@{to} I recorded a 3-minute video walking through {task} instead of a call. Link in the doc.", "@{from} watched it, very clear. Saved us a meeting 🙏" },
            { "@{to} can you pair with me on {task} in the overlap hour tomorrow?", "@{from} sure, 30 minutes at the start of my day works." },
    };

    private static final String[] YESTERDAY = { "Finished the first version of %s.", "Reviewed two pull requests and fixed review comments on %s.",
            "Worked on %s – most of the edge cases are done.", "Paired on %s and wrote tests.", "Investigated a bug, then continued with %s." };
    private static final String[] TODAY = { "Continue with %s.", "Finish %s and move it to review.", "Start %s.", "Tests and clean-up for %s.",
            "Answer review comments, then %s." };

    /** Things to do at a given time. Actions can add new actions (answers come later). */
    private record Action(long at, int order, Runnable run) { }

    public static void load(ApiServer.Parts p) {
        DataStore store = p.store();
        long realNow = Clock.millis();
        Instant frozen = Clock.fixed();
        Random rnd = new Random(2026);
        store.batch = true;
        long begin = realNow - 15 * WorkHours.DAY;
        Clock.fix(Instant.ofEpochMilli(begin));

        Map<String, User> u = new HashMap<>();
        for (Person x : PEOPLE) {
            User a = new User();
            a.id = store.nextId("U", 1);
            a.username = x.username;
            a.name = x.name;
            a.title = x.title;
            a.city = x.city;
            a.zone = x.zone;
            a.workStart = WorkHours.parseHm(x.start);
            a.workEnd = WorkHours.parseHm(x.end);
            a.role = x.role;
            a.passwordHash = Passwords.hash(x.role == Role.ADMIN ? "admin123" : x.role == Role.GUEST ? "guest1234" : "member123");
            a.createdAt = begin;
            store.users.put(a.id, a);
            u.put(x.username, a);
        }
        u.get("jack").workDays = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        User admin = u.get("admin");

        // channels for everybody
        Channel general = channel(store, "general", "Company news. Announcements only, please – chat goes to #random.", admin, begin);
        Channel random = channel(store, "random", "Photos, music, the weather in 10 cities ☀️🌧️", admin, begin);
        channel(store, "help-desk", "Laptop, VPN and account problems", admin, begin);

        // projects
        Project orb = project(p, admin, "ORB", "Orbit mobile app", "The Lumen travel app for iOS and Android – release 3.2 with the new trip timeline.", "red",
                u, "asha", "rohit", "mei", "jack", "lena", "kwame", "amina", "diego");
        Project atl = project(p, admin, "ATL", "Atlas data platform", "The bookings warehouse, pipelines and cost reports.", "blue",
                u, "asha", "ines", "rohit", "kwame", "sarah", "diego");
        Project web = project(p, admin, "WEB", "Kestrel website relaunch", "New website for our client Kestrel Travel. Hana (Kestrel) is a guest here.", "green",
                u, "diego", "lena", "amina", "sarah", "jack", "client");
        orb.wipDoing = 5;
        atl.wipDoing = 4;
        web.wipReview = 2;

        Map<String, List<Task>> tasks = new HashMap<>();
        tasks.put(orb.id, makeTasks(p, orb, ORB, u, u.get("asha")));
        tasks.put(atl.id, makeTasks(p, atl, ATL, u, u.get("asha")));
        tasks.put(web.id, makeTasks(p, web, WEB, u, u.get("diego")));

        // documents
        Map<String, Doc> docs = new HashMap<>();
        docs.put("release", p.docs().create(u.get("diego"), orb.id, Json.obj("title", "Release 3.2 plan", "body", String.join("\n",
                "Release 3.2 – trip timeline", "", "Goal: travellers see their whole trip on one screen.", "",
                "Scope", "- Timeline screen (iOS and Android)", "- Timeline API", "- Offline cache for saved trips", "",
                "Dates", "- Code freeze: in two weeks", "- Store release: the week after", "",
                "Owners", "- Product: Diego", "- Engineering: Asha", "- QA: Jack"))));
        docs.put("handoff", p.docs().create(u.get("asha"), orb.id, Json.obj("title", "How we hand over work (follow the sun)", "body", String.join("\n",
                "Follow-the-sun handoff", "",
                "At the end of your day, write a handoff in Sangam (project page → Handoff).", "Three lines are enough:",
                "1. Done today", "2. Next step (what the next person can pick up)", "3. Watch out for (risks, open questions)", "",
                "The baton goes to the person who is working now with the most hours left, or else to the next one to start.",
                "Press \"Got it\" when you pick it up, so the sender can sleep well."))));
        docs.put("model", p.docs().create(u.get("ines"), atl.id, Json.obj("title", "Warehouse data model", "body", String.join("\n",
                "Warehouse data model", "", "bookings – one row per booking (partitioned by month)", "travellers – one row per traveller",
                "suppliers – hotels, airlines, rail", "events – raw app events (kept 13 months)", "",
                "Rules", "- No personal data in events", "- Deletion requests must finish within 30 days"))));
        docs.put("sitemap", p.docs().create(u.get("diego"), web.id, Json.obj("title", "Sitemap draft (shared with Kestrel)", "body", String.join("\n",
                "Kestrel Travel – sitemap draft", "", "Home", "Destinations", "  Destination page (template)", "Deals", "About us",
                "Contact", "", "Open questions for Hana:", "- Do we keep the blog?", "- Which languages at launch?"))));

        PriorityQueue<Action> q = new PriorityQueue<>(Comparator.comparingLong(Action::at).thenComparingInt(Action::order));
        int[] order = { 0 };
        long end = realNow - 3 * WorkHours.MIN;
        java.util.function.BiConsumer<Long, Runnable> at = (time, r) -> { if (time < end) q.add(new Action(time, order[0]++, r)); };

        List<Project> projects = List.of(orb, atl, web);
        LocalDate firstDay = LocalDate.ofInstant(Instant.ofEpochMilli(begin), java.time.ZoneOffset.UTC).plusDays(1);
        LocalDate lastDay = LocalDate.ofInstant(Instant.ofEpochMilli(realNow), java.time.ZoneOffset.UTC).plusDays(1);
        int dayNo = 0;
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1), dayNo++) {
            final int dn = dayNo;
            for (User x : store.users.values()) {
                if (!x.workDays.contains(day.getDayOfWeek().getValue())) continue;
                long start = WorkHours.startOn(x, day), stop = WorkHours.endOn(x, day);
                // 1. stand-up soon after the day starts (most days)
                if (!x.guest() && !x.admin() && rnd.nextInt(100) < 86) {
                    long when = start + (10 + rnd.nextInt(80)) * WorkHours.MIN;
                    String a = pick(rnd, YESTERDAY), b = pick(rnd, TODAY);
                    boolean blocked = rnd.nextInt(100) < 12;
                    at.accept(when, () -> standup(p, x, a, b, blocked, rnd, projects, tasks));
                }
                // 2. work on the board an hour later
                if (!x.guest()) at.accept(start + (60 + rnd.nextInt(60)) * WorkHours.MIN, () -> work(p, x, projects, tasks, rnd));
                if (!x.guest()) at.accept(start + (300 + rnd.nextInt(90)) * WorkHours.MIN, () -> work(p, x, projects, tasks, rnd));
                // 3. questions to colleagues (answers come when THEIR day starts)
                int talks = x.guest() ? (rnd.nextInt(100) < 35 ? 1 : 0) : rnd.nextInt(3);
                for (int k = 0; k < talks; k++) {
                    long when = start + (30 + rnd.nextInt((int) ((stop - start) / WorkHours.MIN) - 60)) * WorkHours.MIN;
                    at.accept(when, () -> ask(p, x, projects, tasks, rnd, at));
                }
                // 4. handoffs at the end of the day
                String handProject = switch (x.username) { case "asha" -> orb.id; case "ines" -> atl.id; case "diego" -> web.id; case "mei" -> orb.id; default -> null; };
                if (handProject != null && rnd.nextInt(100) < 80)
                    at.accept(stop - (10 + rnd.nextInt(20)) * WorkHours.MIN, () -> handoff(p, x, handProject, rnd, at));
            }
            // company chatter
            if (dn % 5 == 1) at.accept(WorkHours.startOn(admin, day) + 2 * WorkHours.HOUR, () -> say(p, admin, general.id,
                    pick(rnd, new String[] { "Reminder: please set your working hours in Settings – Sangam uses them for quiet hours and call times.",
                            "Office note: the Ranchi office is closed on Friday for the festival. Ranchi folks, please write handoffs on Thursday.",
                            "New joiners next month! Please add \"how we work\" tips to the handoff guide in Orbit docs.",
                            "Our policy: no calls before 08:00 or after 20:00 local time. Use threads and recordings for the rest." })));
            if (dn % 2 == 0) {
                User x = pickUser(rnd, store);
                if (!x.guest()) at.accept(WorkHours.startOn(x, day) + 4 * WorkHours.HOUR, () -> say(p, x, random.id,
                        pick(rnd, new String[] { "Sunny and 31 °C here. Who wants to swap weather? ☀️", "Monsoon is over, finally a dry walk to the café.",
                                "Snow in the forecast. Working from the sofa with tea today ❄️", "Lunch break photo: best pão de queijo in town.",
                                "Anyone else listening to lo-fi while coding? Share your playlist!", "Fun fact: right now it is a different day in Sydney and San Francisco." })));
            }
            // documents change over time
            if (dn == 2) at.accept(WorkHours.startOn(u.get("asha"), day) + 3 * WorkHours.HOUR, () -> append(p, u.get("asha"), docs.get("release"), "Risks", "- The timeline API depends on the new supplier feed"));
            if (dn == 5) at.accept(WorkHours.startOn(u.get("lena"), day) + 2 * WorkHours.HOUR, () -> concurrentEdit(p, u.get("lena"), u.get("jack"), docs.get("release")));
            if (dn == 6) at.accept(WorkHours.startOn(u.get("diego"), day) + 2 * WorkHours.HOUR, () -> append(p, u.get("diego"), docs.get("sitemap"), "Answers from Hana", "- Blog: yes, keep it\n- Languages: English and Japanese"));
            if (dn == 8) at.accept(WorkHours.startOn(u.get("rohit"), day) + 3 * WorkHours.HOUR, () -> append(p, u.get("rohit"), docs.get("model"), "Deletion pipeline", "- Requests go to the delete_queue table; the nightly job removes rows in all tables"));
            if (dn == 10) at.accept(WorkHours.startOn(u.get("kwame"), day) + 2 * WorkHours.HOUR, () -> append(p, u.get("kwame"), docs.get("handoff"), "On call", "- The on-call person is shown in #help-desk topic; urgent messages only for outages"));
            if (dn == 7) at.accept(WorkHours.startOn(u.get("asha"), day) + 2 * WorkHours.HOUR, () -> tasks.get(orb.id).addAll(makeTasks(p, orb, ORB2, u, u.get("asha"))));
            if (dn == 8) at.accept(WorkHours.startOn(u.get("asha"), day) + 2 * WorkHours.HOUR, () -> tasks.get(atl.id).addAll(makeTasks(p, atl, ATL2, u, u.get("asha"))));
            if (dn == 9) at.accept(WorkHours.startOn(u.get("diego"), day) + 2 * WorkHours.HOUR, () -> tasks.get(web.id).addAll(makeTasks(p, web, WEB2, u, u.get("diego"))));
            // calls, booked with the fair-time finder a day or two ahead
            if (dn == 1 || dn == 6 || dn == 11) at.accept(WorkHours.startOn(u.get("diego"), day) + WorkHours.HOUR, () ->
                    book(p, u.get("diego"), "Orbit sprint planning", orb, List.of("asha", "rohit", "mei", "jack", "lena", "kwame", "amina"), 45, u, rnd));
            if (dn == 2 || dn == 9) at.accept(WorkHours.startOn(u.get("ines"), day) + WorkHours.HOUR, () ->
                    book(p, u.get("ines"), "Atlas weekly sync", atl, List.of("asha", "rohit", "kwame", "sarah"), 30, u, rnd));
            if (dn == 3 || dn == 10) at.accept(WorkHours.startOn(u.get("diego"), day) + 2 * WorkHours.HOUR, () ->
                    book(p, u.get("diego"), "Website review with Kestrel", web, List.of("lena", "amina", "sarah", "client"), 30, u, rnd));
            if (dn == 4 || dn == 12) at.accept(WorkHours.startOn(u.get("asha"), day) + WorkHours.HOUR, () ->
                    book(p, u.get("asha"), "Mobile API pairing", orb, List.of("rohit", "mei"), 30, u, rnd));
        }

        while (!q.isEmpty()) {
            Action a = q.poll();
            Clock.fix(Instant.ofEpochMilli(a.at()));
            try { a.run().run(); } catch (Problem ignored) { /* refused by a rule – that happens in real teams too */ }
        }
        Clock.fix(Instant.ofEpochMilli(end));
        // a few calls in the coming days, so the calendar is not empty
        book(p, u.get("diego"), "Orbit release go / no-go", orb, List.of("asha", "kwame", "jack", "mei"), 30, u, rnd);
        book(p, u.get("diego"), "Kestrel launch rehearsal", web, List.of("lena", "amina", "sarah", "jack", "client"), 45, u, rnd);

        // what each person has read: everything up to the last time they were working
        for (User x : store.users.values()) {
            List<long[]> w = WorkHours.windows(x, end - 4 * WorkHours.DAY, end);
            long seen = w.isEmpty() ? end - 4 * WorkHours.DAY : Math.min(w.get(w.size() - 1)[1], end - 40 * WorkHours.MIN);
            x.lastSeen = 0;
            for (Message m : store.messages.values())
                if (m.at <= seen && m.seq > x.readUpTo.getOrDefault(m.channelId, 0L)) x.readUpTo.put(m.channelId, m.seq);
            for (var n : store.notes.values()) if (n.userId.equals(x.id) && n.deliverAt <= seen) n.read = true;
        }
        Clock.fix(frozen);
        store.log(admin.id, "demo.ready", "Lumen Labs", store.tasks.size() + " tasks, " + store.messages.size() + " messages");
        store.saveAll();
    }

    // ------------------------------------------------------------------ building blocks

    private static Channel channel(DataStore store, String name, String topic, User by, long at) {
        Channel c = new Channel();
        c.id = store.nextId("C", 1);
        c.name = name;
        c.topic = topic;
        c.kind = Channel.Kind.PUBLIC;
        c.createdBy = by.id;
        c.createdAt = at;
        store.channels.put(c.id, c);
        return c;
    }

    private static Project project(ApiServer.Parts p, User admin, String key, String name, String desc, String color, Map<String, User> u, String... members) {
        List<String> ids = new ArrayList<>();
        for (String m : members) ids.add(u.get(m).id);
        return p.work().createProject(admin, Json.obj("key", key, "name", name, "description", desc, "color", color, "memberIds", ids));
    }

    private static List<Task> makeTasks(ApiServer.Parts p, Project pr, TaskDef[] defs, Map<String, User> u, User by) {
        List<Task> out = new ArrayList<>();
        for (TaskDef d : defs) {
            List<String> blockers = new ArrayList<>();
            for (int b : d.blockers()) blockers.add(out.get(b).id);
            Task t = p.work().create(by, pr.id, Json.obj("title", d.title(), "estimate", d.estimate(), "priority", d.priority().name(),
                    "assigneeId", u.get(d.who()).id, "due", Clock.today().plusDays(d.dueInDays() + 1).toString(), "blockedBy", blockers,
                    "description", "Part of " + pr.name + ". Ask in #" + pr.key.toLowerCase() + " if something is unclear."));
            out.add(t);
        }
        return out;
    }

    private static <T> T pick(Random r, T[] a) { return a[r.nextInt(a.length)]; }

    private static User pickUser(Random r, DataStore store) {
        List<User> l = new ArrayList<>(store.users.values());
        return l.get(r.nextInt(l.size()));
    }

    private static Task someTask(Random rnd, User x, List<Project> projects, Map<String, List<Task>> tasks) {
        List<Task> mine = new ArrayList<>();
        for (Project pr : projects) for (Task t : tasks.get(pr.id)) if (x.id.equals(t.assigneeId) && t.status != TaskStatus.DONE) mine.add(t);
        if (mine.isEmpty()) for (Project pr : projects) if (pr.has(x.id)) mine.addAll(tasks.get(pr.id));
        return mine.isEmpty() ? null : mine.get(rnd.nextInt(mine.size()));
    }

    private static void standup(ApiServer.Parts p, User x, String y, String t, boolean blocked, Random rnd, List<Project> projects, Map<String, List<Task>> tasks) {
        Task a = someTask(rnd, x, projects, tasks), b = someTask(rnd, x, projects, tasks);
        String an = a == null ? "the backlog" : a.key + " " + a.title.toLowerCase(), bn = b == null ? "reviews" : b.key;
        String bl = "none";
        if (blocked) {
            User other = null;
            for (Project pr : projects) if (pr.has(x.id)) for (String id : pr.memberIds) {
                User c = p.store().users.get(id);
                if (c != null && !c.id.equals(x.id) && !c.guest() && rnd.nextBoolean()) { other = c; break; }
            }
            bl = other == null ? "Waiting for staging access." : "Waiting for @" + other.username + " to answer my question about " + bn + ".";
        }
        p.rhythm().postStandup(x, Json.obj("yesterday", String.format(y, an), "today", String.format(t, bn), "blockers", bl));
    }

    /** One person's board work: review others' work, send finished work to review, start the next task. */
    private static void work(ApiServer.Parts p, User x, List<Project> projects, Map<String, List<Task>> tasks, Random rnd) {
        long now = Clock.millis();
        for (Project pr : projects) {
            if (!pr.has(x.id)) continue;
            for (Task t : tasks.get(pr.id)) {
                try {
                    if (t.status == TaskStatus.REVIEW && !x.id.equals(t.assigneeId) && now - t.updatedAt > 6 * WorkHours.HOUR && rnd.nextInt(100) < 35)
                        p.work().move(x, t.id, "DONE", null);
                    else if (t.status == TaskStatus.DOING && x.id.equals(t.assigneeId)
                            && now - t.startedAt > t.estimate * 7 * WorkHours.HOUR + rnd.nextInt(30) * WorkHours.HOUR)
                        p.work().move(x, t.id, "REVIEW", null);
                } catch (Problem ignored) { }
            }
            for (Task t : tasks.get(pr.id)) {
                if (t.status != TaskStatus.TODO || !x.id.equals(t.assigneeId) || rnd.nextInt(100) > 45) continue;
                try { p.work().move(x, t.id, "DOING", null); } catch (Problem ignored) { }
            }
        }
    }

    private static void ask(ApiServer.Parts p, User x, List<Project> projects, Map<String, List<Task>> tasks, Random rnd,
                            java.util.function.BiConsumer<Long, Runnable> at) {
        List<Project> mine = new ArrayList<>();
        for (Project pr : projects) if (pr.has(x.id)) mine.add(pr);
        if (mine.isEmpty()) return;
        Project pr = mine.get(rnd.nextInt(mine.size()));
        List<User> others = new ArrayList<>();
        for (String id : pr.memberIds) { User o = p.store().users.get(id); if (o != null && !o.id.equals(x.id) && (!o.guest() || x.guest() || rnd.nextInt(4) == 0)) others.add(o); }
        if (x.guest()) others.removeIf(User::guest);
        if (others.isEmpty()) return;
        User to = others.get(rnd.nextInt(others.size()));
        List<Task> pt = tasks.get(pr.id);
        Task task = pt.get(rnd.nextInt(pt.size()));
        String[] pair = TALK[rnd.nextInt(TALK.length)];
        if (x.guest()) pair = new String[] { "@{to} the marketing team loved the homepage draft! One question: can the hero image change by season? ({task})",
                "@{from} yes – we can add a seasonal image setting. I will note it on {task}." };
        String qText = fill(pair[0], x, to, task), aText = fill(pair[1], x, to, task);
        ChatService.Posted posted = p.chat().post(x, pr.channelId, qText, null, false, "text");
        long now = Clock.millis();
        long replyAt = WorkHours.nextStart(to, now) + (WorkHours.working(to, now) ? (5 + rnd.nextInt(50)) : (15 + rnd.nextInt(70))) * WorkHours.MIN;
        boolean thread = rnd.nextBoolean();
        if (rnd.nextInt(100) < 92)
            at.accept(replyAt, () -> p.chat().post(to, pr.channelId, aText, thread ? posted.message().id : null, false, "text"));
        if (rnd.nextInt(100) < 30) {
            String emoji = pick(rnd, ChatService.REACTIONS.toArray(new String[0]));
            at.accept(replyAt + 2 * WorkHours.MIN, () -> p.chat().react(to, posted.message().id, emoji));
        }
    }

    private static String fill(String s, User from, User to, Task t) {
        return s.replace("{to}", to.username).replace("{from}", from.username).replace("{task}", t.key);
    }

    private static void say(ApiServer.Parts p, User x, String channelId, String text) {
        p.chat().post(x, channelId, text, null, false, "text");
    }

    private static void handoff(ApiServer.Parts p, User x, String projectId, Random rnd, java.util.function.BiConsumer<Long, Runnable> at) {
        Project pr = p.store().projects.get(projectId);
        List<String> done = new ArrayList<>(), next = new ArrayList<>();
        for (Task t : p.store().tasks.values()) {
            if (!t.projectId.equals(pr.id)) continue;
            if (t.status == TaskStatus.DONE && Clock.millis() - t.doneAt < WorkHours.DAY) done.add(t.key);
            if (t.status == TaskStatus.REVIEW || (t.status == TaskStatus.TODO && t.blockedBy.isEmpty())) next.add(t.key);
        }
        String text = "Done today: " + (done.isEmpty() ? "reviews and small fixes" : String.join(", ", done)) + "\n"
                + "Next: " + (next.isEmpty() ? "keep going with Doing" : String.join(", ", next.subList(0, Math.min(3, next.size())))) + "\n"
                + "Watch out: " + pick(rnd, new String[] { "staging is slow today", "the supplier feed was late", "nothing special", "Hana may answer about the sitemap overnight" });
        Handoff h = p.rhythm().handoff(x, pr.id, Json.obj("text", text));
        User to = p.store().users.get(h.toId);
        if (rnd.nextInt(100) < 85) at.accept(h.deliverAt + (10 + rnd.nextInt(70)) * WorkHours.MIN, () -> p.rhythm().ack(to, h.id));
    }

    private static void append(ApiServer.Parts p, User x, Doc d, String heading, String lines) {
        String body = d.body + "\n\n" + heading + "\n" + lines;
        p.docs().save(x, d.id, Json.obj("baseVersion", d.version, "body", body, "note", "added " + heading.toLowerCase()));
    }

    /** Two people edit the same version: the second save is merged automatically (different lines). */
    private static void concurrentEdit(ApiServer.Parts p, User a, User b, Doc d) {
        int base = d.version;
        String text = d.body;
        p.docs().save(a, d.id, Json.obj("baseVersion", base, "body", text.replace("- Timeline screen (iOS and Android)", "- Timeline screen (iOS and Android), with dark mode"), "note", "dark mode in scope"));
        p.docs().save(b, d.id, Json.obj("baseVersion", base, "body", text.replace("- QA: Jack", "- QA: Jack (test plan in ORB-9)"), "note", "link to the test plan"));
    }

    private static void book(ApiServer.Parts p, User organizer, String title, Project pr, List<String> names, int minutes, Map<String, User> u, Random rnd) {
        List<String> ids = new ArrayList<>();
        for (String n : names) ids.add(u.get(n).id);
        Map<String, Object> s = p.meetings().suggest(organizer, Json.obj("attendeeIds", ids, "minutes", minutes, "days", 3,
                "from", Clock.millis() + WorkHours.DAY / 2));
        List<?> options = (List<?>) s.get("options");
        if (options.isEmpty()) return;
        Map<?, ?> best = (Map<?, ?>) options.get(0);
        p.meetings().schedule(organizer, Json.obj("title", title, "attendeeIds", ids, "minutes", minutes, "start", best.get("start"),
                "projectId", pr.id, "agenda", "1. What changed since last time\n2. Blockers\n3. Decisions (written in the doc afterwards)"));
    }
}
