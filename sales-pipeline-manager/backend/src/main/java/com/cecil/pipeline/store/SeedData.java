package com.cecil.pipeline.store;

import com.cecil.pipeline.model.Account;
import com.cecil.pipeline.model.Activity;
import com.cecil.pipeline.model.Contact;
import com.cecil.pipeline.model.Deal;
import com.cecil.pipeline.model.Enums.ActivityType;
import com.cecil.pipeline.model.Enums.LeadSource;
import com.cecil.pipeline.model.Enums.Priority;
import com.cecil.pipeline.model.Enums.Role;
import com.cecil.pipeline.model.Enums.Stage;
import com.cecil.pipeline.model.User;
import com.cecil.pipeline.util.Passwords;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Demo data loaded on the first run, so the app has something to show.
 * All companies and people are made up. Dates are relative to today, so the demo always looks current.
 * Delete the data folder to get fresh demo data.
 */
public final class SeedData {

    private SeedData() { }

    private static final String[][] USERS = {
        // username, name, email, role, password, monthly target
        { "admin", "Admin", "admin@pipeline.local", "ADMIN", "admin123", "0" },
        { "neha", "Neha Kapoor", "neha@pipeline.local", "MANAGER", "manager123", "2500000" },
        { "arjun", "Arjun Mehta", "arjun@pipeline.local", "SALES_REP", "sales123", "2000000" },
        { "priya", "Priya Nair", "priya@pipeline.local", "SALES_REP", "sales123", "2000000" },
        { "rahul", "Rahul Verma", "rahul@pipeline.local", "SALES_REP", "sales123", "1500000" },
    };

    private static final String[][] ACCOUNTS = {
        // name, industry, city, size, website
        { "Brightleaf Retail", "Retail", "Mumbai", "201-1000", "brightleaf-retail.example" },
        { "Kestrel Logistics", "Logistics", "Pune", "51-200", "kestrel-logistics.example" },
        { "Nilgiri Health Clinics", "Healthcare", "Bengaluru", "201-1000", "nilgirihealth.example" },
        { "Orbit Fintech", "Finance", "Gurugram", "51-200", "orbitfintech.example" },
        { "Sahyadri Steel Works", "Manufacturing", "Nashik", "1000+", "sahyadristeel.example" },
        { "Lotus Learning Academy", "Education", "Ranchi", "11-50", "lotuslearning.example" },
        { "Coral Bay Hotels", "Hospitality", "Goa", "201-1000", "coralbayhotels.example" },
        { "Pinecrest Realty", "Real estate", "Hyderabad", "51-200", "pinecrestrealty.example" },
        { "Vayu Motors", "Manufacturing", "Chennai", "1000+", "vayumotors.example" },
        { "Greenfield Organics", "Retail", "Jaipur", "11-50", "greenfieldorganics.example" },
        { "Quanta Software Labs", "Technology", "Bengaluru", "201-1000", "quantalabs.example" },
        { "Riverstone Hospitals", "Healthcare", "Kolkata", "1000+", "riverstonehospitals.example" },
        { "Tarang Media House", "Other", "Delhi", "51-200", "tarangmedia.example" },
        { "Jharkhand Polytechnic Trust", "Education", "Jamshedpur", "201-1000", "jptrust.example" },
    };

    private static final String[] FIRST = { "Amit", "Sneha", "Vikram", "Ananya", "Rohit", "Kavya", "Suresh", "Meera",
        "Karan", "Divya", "Manish", "Pooja", "Nikhil", "Isha", "Sanjay", "Ritu", "Deepak", "Tanvi", "Harsh", "Aditi" };
    private static final String[] LAST = { "Sharma", "Iyer", "Singh", "Reddy", "Das", "Patel", "Gupta", "Menon",
        "Joshi", "Bose", "Kulkarni", "Rao", "Chatterjee", "Mishra", "Pillai", "Saxena" };
    private static final String[] TITLES = { "CEO", "CTO", "Head of IT", "Procurement Manager", "Operations Director",
        "Finance Controller", "IT Manager", "Purchase Officer", "COO", "Principal" };
    private static final boolean[] DECIDER = { true, true, true, false, true, true, false, false, true, true };

    private static final String[] PRODUCTS = { "CRM rollout", "Cloud migration", "Annual support contract",
        "POS terminals", "Security audit", "Laptop refresh", "ERP integration", "Analytics dashboard",
        "Website redesign", "Staff training programme", "Managed network services", "Data backup solution",
        "HR software licences", "Mobile app build", "Warehouse scanners", "Firewall upgrade", "Email migration" };

    private static final String[] LOST = { "Price too high", "Chose a competitor", "No budget",
        "No decision / went silent", "Timing not right", "Missing features" };

    private static final String[][] DONE_ACTS = {
        { "CALL", "Discovery call", "Understood current setup and pain points" },
        { "EMAIL", "Sent company profile", "They asked for case studies" },
        { "MEETING", "Requirements meeting", "Met the IT team, got a list of must-haves" },
        { "DEMO", "Product demo", "Demo went well, a few questions on pricing" },
        { "EMAIL", "Sent proposal", "Proposal shared with finance" },
        { "CALL", "Follow-up on proposal", "Waiting for budget approval" },
        { "MEETING", "Pricing discussion", "Asked for a 10% discount on a 2-year deal" },
        { "CALL", "Checked in with champion", "Still positive, decision expected soon" },
    };

    private static final String[][] PLANNED = {
        { "CALL", "Follow-up call" },
        { "EMAIL", "Send revised quote" },
        { "MEETING", "Meet decision maker" },
        { "DEMO", "Second demo for the wider team" },
        { "FOLLOW_UP", "Chase signed contract" },
        { "TASK", "Prepare proposal" },
    };

    public static void fill(DataStore store) {
        Random rnd = new Random(2026);
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now();

        // ---- users
        for (String[] u : USERS) {
            User x = new User();
            x.username = u[0];
            x.displayName = u[1];
            x.email = u[2];
            x.role = Role.valueOf(u[3]);
            x.passwordHash = Passwords.hash(u[4]);
            x.monthlyTarget = Long.parseLong(u[5]);
            x.createdAt = now.minus(Duration.ofDays(200)).toString();
            store.users.add(x);
        }
        String[] sellers = { "arjun", "priya", "rahul", "arjun", "priya", "neha" };

        // ---- accounts and 1-3 contacts each
        List<Account> accs = new ArrayList<>();
        int person = 0;
        for (String[] a : ACCOUNTS) {
            Account acc = new Account();
            acc.id = store.nextId("A");
            acc.name = a[0];
            acc.industry = a[1];
            acc.city = a[2];
            acc.size = a[3];
            acc.website = a[4];
            acc.phone = "+91 " + (70000 + rnd.nextInt(29999)) + " " + (10000 + rnd.nextInt(89999));
            acc.owner = sellers[rnd.nextInt(3)];
            acc.notes = "";
            acc.createdAt = now.minus(Duration.ofDays(180 + rnd.nextInt(20))).toString();
            acc.updatedAt = acc.createdAt;
            store.accounts.add(acc);
            accs.add(acc);
            int n = 1 + rnd.nextInt(3);
            for (int i = 0; i < n; i++) {
                Contact c = new Contact();
                c.id = store.nextId("C");
                c.accountId = acc.id;
                String first = FIRST[person % FIRST.length];
                String last = LAST[(person * 7 + 3) % LAST.length];
                person++;
                c.name = first + " " + last;
                int t = rnd.nextInt(TITLES.length);
                c.title = TITLES[t];
                c.decisionMaker = DECIDER[t];
                String domain = a[4];
                c.email = first.toLowerCase() + "." + last.toLowerCase() + "@" + domain;
                c.phone = "+91 9" + (100000000 + rnd.nextInt(899999999));
                c.createdAt = acc.createdAt;
                c.updatedAt = acc.createdAt;
                store.contacts.add(c);
            }
        }

        // ---- deals: how many end up in each stage
        List<Stage> plan = new ArrayList<>();
        add(plan, Stage.LEAD, 7);
        add(plan, Stage.QUALIFIED, 7);
        add(plan, Stage.PROPOSAL, 7);
        add(plan, Stage.NEGOTIATION, 5);
        add(plan, Stage.WON, 14);
        add(plan, Stage.LOST, 8);

        int wonIndex = 0;
        for (int i = 0; i < plan.size(); i++) {
            Stage finalStage = plan.get(i);
            Account acc = accs.get((i * 5 + rnd.nextInt(3)) % accs.size());
            List<Contact> cs = store.contacts.stream().filter(c -> c.accountId.equals(acc.id)).toList();

            Deal d = new Deal();
            d.id = store.nextId("D");
            String product = PRODUCTS[(i * 3 + rnd.nextInt(4)) % PRODUCTS.length];
            d.title = product + " - " + acc.name.split(" ")[0];
            d.accountId = acc.id;
            d.contactId = cs.isEmpty() || rnd.nextInt(10) == 0 ? null : cs.get(rnd.nextInt(cs.size())).id;
            d.owner = i % 4 == 0 ? acc.owner : sellers[rnd.nextInt(sellers.length)];
            d.value = (long) (1 + rnd.nextInt(60)) * 25_000L + (rnd.nextInt(4) == 0 ? 1_000_000L : 0);
            d.source = LeadSource.values()[rnd.nextInt(LeadSource.values().length)];
            d.priority = rnd.nextInt(5) == 0 ? Priority.HIGH : rnd.nextInt(4) == 0 ? Priority.LOW : Priority.MEDIUM;
            d.description = "Interested in " + product.toLowerCase() + " for their " + acc.city + " office.";

            // timing
            Instant created, end;
            if (finalStage.isClosed()) {
                // spread wins over the last 6 months, a few in the current month
                int closedDaysAgo = finalStage == Stage.WON
                    ? (wonIndex++ < 4 ? rnd.nextInt(Math.max(1, today.getDayOfMonth())) : 5 + rnd.nextInt(170))
                    : 3 + rnd.nextInt(170);
                end = now.minus(Duration.ofDays(closedDaysAgo)).minus(Duration.ofHours(rnd.nextInt(8)));
                created = end.minus(Duration.ofDays(15 + rnd.nextInt(60)));
            } else {
                created = now.minus(Duration.ofDays(3 + rnd.nextInt(80))).minus(Duration.ofHours(rnd.nextInt(10)));
                end = now.minus(Duration.ofHours(1 + rnd.nextInt(48)));
                if (end.isBefore(created)) end = created.plus(Duration.ofHours(2));
            }
            d.createdAt = created.toString();

            // stage path: LEAD -> ... -> final
            List<Stage> path = new ArrayList<>();
            int top = finalStage.isOpen() ? finalStage.ordinal()
                : finalStage == Stage.WON ? Stage.NEGOTIATION.ordinal() : rnd.nextInt(4);
            int start = rnd.nextInt(3) == 0 ? Math.min(1, top) : 0; // some deals come in already qualified
            for (int s = start; s <= top; s++) path.add(Stage.values()[s]);
            if (finalStage.isClosed()) path.add(finalStage);
            long span = Duration.between(created, end).toMinutes();
            Stage prev = null;
            for (int s = 0; s < path.size(); s++) {
                Deal.StageChange sc = new Deal.StageChange();
                sc.from = prev;
                sc.to = path.get(s);
                long at = s == 0 ? 0 : (finalStage.isClosed() && s == path.size() - 1 ? span
                    : span * s / path.size() - rnd.nextInt((int) Math.max(1, span / (path.size() * 3))));
                sc.at = created.plus(Duration.ofMinutes(Math.max(0, at))).toString();
                sc.by = d.owner;
                d.history.add(sc);
                prev = sc.to;
            }
            d.stage = finalStage;
            if (finalStage.isClosed()) {
                d.closedAt = end.toString();
                d.expectedClose = end.atZone(zone).toLocalDate();
                if (finalStage == Stage.LOST) d.lostReason = LOST[rnd.nextInt(LOST.length)];
            } else {
                d.expectedClose = today.plusDays(-8 + rnd.nextInt(110));
                if (rnd.nextInt(6) == 0) d.probability = Math.min(95, finalStage.probability + 10);
            }

            // finished activities
            Instant last = Instant.parse(d.history.get(d.history.size() - 1).at);
            int doneCount = 1 + rnd.nextInt(4);
            boolean goingCold = finalStage.isOpen() && rnd.nextInt(4) == 0;
            for (int k = 0; k < doneCount; k++) {
                String[] t = DONE_ACTS[Math.min(DONE_ACTS.length - 1, k * 2 + rnd.nextInt(2))];
                Activity a = new Activity();
                a.id = store.nextId("T");
                a.dealId = d.id;
                a.type = ActivityType.valueOf(t[0]);
                a.subject = t[1];
                a.outcome = t[2];
                a.notes = "";
                a.owner = d.owner;
                a.createdBy = d.owner;
                Instant limit = goingCold ? created.plus(Duration.ofMinutes(Math.max(60, span / 4))) : end;
                Instant doneAt = created.plus(Duration.ofMinutes((long) (Duration.between(created, limit).toMinutes() * ((k + 1.0) / (doneCount + 0.3)))));
                a.done = true;
                a.doneAt = doneAt.toString();
                a.dueDate = doneAt.atZone(zone).toLocalDate();
                a.createdAt = created.toString();
                store.activities.add(a);
                if (doneAt.isAfter(last)) last = doneAt;
            }
            if (goingCold) {
                // push the stage moves back too, so the deal really looks untouched
                last = created.plus(Duration.ofMinutes(Math.max(60, span / 4)));
                for (Deal.StageChange sc : d.history) {
                    if (Instant.parse(sc.at).isAfter(last)) sc.at = last.toString();
                }
            }
            d.lastActivityAt = last.toString();
            d.updatedAt = last.toString();

            // a planned next step for most open deals (some of them overdue)
            if (finalStage.isOpen() && rnd.nextInt(10) < 7) {
                String[] t = PLANNED[rnd.nextInt(PLANNED.length)];
                Activity a = new Activity();
                a.id = store.nextId("T");
                a.dealId = d.id;
                a.type = ActivityType.valueOf(t[0]);
                a.subject = t[1];
                a.notes = "";
                a.outcome = "";
                a.owner = d.owner;
                a.createdBy = d.owner;
                a.dueDate = today.plusDays(goingCold ? -(2 + rnd.nextInt(6)) : -2 + rnd.nextInt(12));
                a.createdAt = last.toString();
                store.activities.add(a);
            }

            if (rnd.nextInt(3) == 0) {
                Deal.Note n = new Deal.Note();
                n.id = store.nextId("N");
                n.text = finalStage == Stage.LOST ? "Customer said: " + d.lostReason.toLowerCase() + ". Revisit next quarter."
                    : "Champion is " + (d.contactId == null ? "not identified yet" : "supportive") + ". Budget cycle ends in March.";
                n.at = last.toString();
                n.by = d.owner;
                d.notes.add(n);
            }
            store.deals.add(d);
        }

        // a couple of personal to-dos without a deal
        for (String[] t : new String[][] { { "arjun", "Update call notes in the CRM" }, { "priya", "Plan next month's prospect list" } }) {
            Activity a = new Activity();
            a.id = store.nextId("T");
            a.type = ActivityType.TASK;
            a.subject = t[1];
            a.notes = "";
            a.outcome = "";
            a.owner = t[0];
            a.createdBy = t[0];
            a.dueDate = today.plusDays(1);
            a.createdAt = now.toString();
            store.activities.add(a);
        }
    }

    private static void add(List<Stage> list, Stage s, int n) {
        for (int i = 0; i < n; i++) list.add(s);
    }
}
