package com.cecil.sambandh.store;

import com.cecil.sambandh.model.Customer;
import com.cecil.sambandh.model.FollowUp;
import com.cecil.sambandh.model.Interaction;
import com.cecil.sambandh.model.Order;
import com.cecil.sambandh.model.Role;
import com.cecil.sambandh.model.Stage;
import com.cecil.sambandh.model.Ticket;
import com.cecil.sambandh.model.User;
import com.cecil.sambandh.util.Clock;
import com.cecil.sambandh.util.Passwords;
import com.cecil.sambandh.util.Text;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Demo data for the made-up company "Suryodaya Solar, Ranchi" (rooftop solar, inverters, batteries, maintenance).
 * About 60 customers in different situations (champions, slipping away, new leads ...), their conversations,
 * orders, follow-ups and support tickets - all dated relative to today, so the demo always looks fresh.
 * A fixed random seed means the same data every time. All people, companies and numbers are made up.
 */
public final class SeedData {
    private SeedData() { }

    private static final String[] FIRST = {"Aarav", "Ananya", "Rohit", "Sneha", "Vikash", "Pooja", "Amit", "Nisha", "Sunil",
            "Kiran", "Deepak", "Ritika", "Manoj", "Shalini", "Abhishek", "Puja", "Rajesh", "Swati", "Nitin", "Komal",
            "Prakash", "Jyoti", "Sanjay", "Anjali", "Ravi", "Megha", "Alok", "Priti", "Gaurav", "Rani", "Imran", "Farah",
            "Joseph", "Mary", "Birsa", "Salomi", "Hemant", "Sarita", "Ajay", "Kumari"};
    private static final String[] LAST = {"Kumar", "Singh", "Mahto", "Oraon", "Munda", "Tirkey", "Lakra", "Kujur", "Soren",
            "Hansda", "Murmu", "Sahu", "Gupta", "Prasad", "Verma", "Sinha", "Jha", "Mishra", "Pandey", "Ekka", "Toppo",
            "Minz", "Khalkho", "Ansari", "Hussain", "Chatterjee", "Banerjee", "Agarwal", "Choudhary", "Bhagat"};
    private static final String[] CITIES = {"Ranchi", "Ranchi", "Ranchi", "Jamshedpur", "Jamshedpur", "Dhanbad", "Bokaro",
            "Hazaribagh", "Deoghar", "Giridih", "Ramgarh", "Dumka", "Chaibasa", "Khunti"};
    private static final String[] BUSINESSES = {"Hotel Green Valley", "Shanti Nursing Home", "Sai Cold Storage",
            "Kanke Rice Mill", "Jharkhand Public School", "Birsa Poultry Farm", "Patliputra Medical Store", "Annapurna Sweets",
            "Navjivan Hospital", "Subarnarekha Fabrics", "Ranchi Book Depot", "Hill View Resort", "Tata Nagar Auto Parts",
            "Morhabadi Gym", "Dhanbad Steel Traders", "Bokaro Printing Press"};
    private static final String[][] ITEMS = {
            {"Rooftop solar 3 kW", "165000", "195000"}, {"Rooftop solar 5 kW", "260000", "310000"},
            {"Rooftop solar 10 kW (commercial)", "480000", "560000"}, {"Inverter 1.5 kVA", "9000", "14000"},
            {"Lithium battery 150 Ah", "18000", "26000"}, {"Annual maintenance (AMC)", "4500", "9000"},
            {"Solar water heater 200 L", "21000", "28000"}, {"Panel cleaning visit", "800", "1500"},
            {"Net-meter installation", "6000", "9000"}, {"LED street light set", "12000", "30000"}};

    private static final String[] TALK_GOOD = {"Happy with the generation report, asked about adding a battery.",
            "Wants a quote for the second building.", "Thanked us for the quick service visit.",
            "Interested in the AMC renewal, will pay this week.", "Liked the demo of the monitoring app.",
            "Referred a neighbour who also wants solar.", "Confirmed the installation date."};
    private static final String[] TALK_MEH = {"Asked for the subsidy paperwork again.", "Will discuss with family and call back.",
            "Wanted to know the payback period.", "Price check – comparing with another vendor.",
            "Sent the brochure and the price list.", "Asked about EMI options."};
    private static final String[] TALK_BAD = {"Upset that the technician came late twice.",
            "Complained the bill was higher than the quote.", "Inverter keeps beeping at night – very annoyed.",
            "Says generation dropped after the monsoon, not happy.", "Asked to stop calling during office hours."};
    private static final String[] TICKETS = {"Inverter showing error E-04", "Generation lower than promised",
            "Net-meter not yet approved by JBVNL", "Panel cracked after hailstorm", "Battery not charging fully",
            "Monitoring app shows no data", "Water leak near panel mounting", "Invoice correction needed"};
    private static final String[] FOLLOWUPS = {"Send revised quote", "Call back about subsidy papers", "Book site survey",
            "Collect pending payment", "Remind about AMC renewal", "Share installation photos", "Confirm technician visit",
            "Check if the inverter problem is solved"};

    public static void load(DataStore s) {
        Random rnd = new Random(2026);
        long now = Clock.millis();
        LocalDate today = Clock.today();

        User admin = user(s, "admin", "Neha Sinha", Role.ADMIN, "admin123", now);
        User manager = user(s, "ritu", "Ritu Mahato", Role.MANAGER, "manager123", now);
        User arjun = user(s, "arjun", "Arjun Tirkey", Role.AGENT, "agent123", now);
        User kavya = user(s, "kavya", "Kavya Prasad", Role.AGENT, "agent123", now);
        User sameer = user(s, "sameer", "Sameer Ansari", Role.AGENT, "agent123", now);
        User[] agents = {arjun, kavya, sameer, arjun, kavya, sameer, manager};

        // profile, how many
        String[][] plan = {{"champion", "6"}, {"loyal", "8"}, {"regular", "10"}, {"slipping", "8"}, {"new", "5"},
                {"prospect", "8"}, {"lead", "10"}, {"churned", "5"}};
        List<String> usedNames = new ArrayList<>();
        int biz = 0, n = 0;
        for (String[] p : plan) {
            int count = Integer.parseInt(p[1]);
            for (int k = 0; k < count; k++, n++) {
                String profile = p[0];
                Customer c = new Customer();
                c.id = s.nextId("C", 1001);
                String name;
                do { name = FIRST[rnd.nextInt(FIRST.length)] + " " + LAST[rnd.nextInt(LAST.length)]; } while (usedNames.contains(name));
                usedNames.add(name);
                c.name = name;
                boolean business = (n % 3 == 1) && biz < BUSINESSES.length;
                if (business) { c.company = BUSINESSES[biz++]; c.type = "BUSINESS"; }
                c.city = CITIES[rnd.nextInt(CITIES.length)];
                String mailUser = name.toLowerCase().replace(' ', '.');
                if (rnd.nextInt(10) < 8) c.email = mailUser + (business ? "@" + c.company.toLowerCase().replaceAll("[^a-z]", "") + ".example" : "@example.in");
                c.phone = "+91" + (6 + rnd.nextInt(4)) + String.format("%09d", rnd.nextInt(1_000_000_000));
                c.source = List.of("Website", "Referral", "Walk-in", "Cold call", "Event", "Social media", "Advertisement").get(rnd.nextInt(7));
                c.ownerId = agents[n % agents.length].id;
                c.consentEmail = c.email != null && rnd.nextInt(10) < 7;
                c.doNotCall = rnd.nextInt(12) == 0;
                int ageDays = switch (profile) {
                    case "champion", "loyal" -> 420 + rnd.nextInt(300);
                    case "regular", "slipping", "churned" -> 300 + rnd.nextInt(300);
                    case "new" -> 20 + rnd.nextInt(40);
                    case "prospect" -> 15 + rnd.nextInt(60);
                    default -> rnd.nextInt(25);
                };
                c.createdAt = at(today.minusDays(ageDays), 10, rnd);
                c.updatedAt = c.createdAt;
                c.stage = switch (profile) {
                    case "champion", "loyal" -> Stage.LOYAL;
                    case "regular", "slipping", "new" -> Stage.CUSTOMER;
                    case "prospect" -> Stage.PROSPECT;
                    case "churned" -> Stage.CHURNED;
                    default -> Stage.LEAD;
                };
                if (business) c.tags.add("commercial");
                if (profile.equals("champion")) c.tags.add("vip");
                if (profile.equals("lead") && rnd.nextBoolean()) c.tags.add("pm-surya-ghar");
                if (c.city.equals("Ranchi") && rnd.nextInt(3) == 0) c.tags.add("ranchi-east");
                s.customers.put(c.id, c);
                fill(s, c, profile, rnd, today, now);
            }
        }

        // A few duplicates on purpose (as if typed twice, or imported from an old spreadsheet)
        Customer a = byIndex(s, 2), b = byIndex(s, 9), d = byIndex(s, 15);
        dup(s, a, swapWords(a.name), a.phone == null ? null : "0" + a.phone.substring(3), a.city, null, rnd, today);
        dup(s, b, b.name.replaceFirst("(\\w)(\\w)$", "$1$1$2"), null, b.city, b.email == null ? null : b.email.toUpperCase(), rnd, today);
        dup(s, d, d.name.substring(0, d.name.length() - 1), null, d.city, null, rnd, today);

        s.log(admin.id, "seed", "demo data", s.customers.size() + " customers for Suryodaya Solar (all made up)");
    }

    private static User user(DataStore s, String username, String name, Role role, String pw, long now) {
        User u = new User();
        u.id = s.nextId("U", 1);
        u.username = username;
        u.name = name;
        u.role = role;
        u.passwordHash = Passwords.hash(pw);
        u.createdAt = now - 400L * 86_400_000L;
        s.users.put(u.id, u);
        return u;
    }

    private static Customer byIndex(DataStore s, int i) { return new ArrayList<>(s.customers.values()).get(i); }

    private static String swapWords(String name) {
        String[] w = name.split(" ");
        return w.length == 2 ? w[1] + " " + w[0] : name;
    }

    private static void dup(DataStore s, Customer orig, String name, String phoneTyped, String city, String email,
                            Random rnd, LocalDate today) {
        Customer c = new Customer();
        c.id = s.nextId("C", 1001);
        c.name = name;
        c.company = orig.company;
        c.type = orig.type;
        c.city = city;
        c.phone = phoneTyped == null ? null : Text.normalizePhone(phoneTyped);
        c.email = email == null ? null : Text.emailKey(email);
        if (c.phone == null && c.email == null) c.phone = "+9170" + String.format("%08d", rnd.nextInt(100_000_000));
        c.source = "Other";
        c.stage = Stage.LEAD;
        c.ownerId = orig.ownerId;
        c.notes = "Added from the old Excel sheet.";
        c.createdAt = at(today.minusDays(5 + rnd.nextInt(20)), 12, rnd);
        c.updatedAt = c.createdAt;
        s.customers.put(c.id, c);
        Interaction i = talk(s, c, today.minusDays(3 + rnd.nextInt(5)), "CALL", "OUT", "Called about a solar quote – said they already talked to us.", "NEUTRAL", rnd);
        i.byUserId = orig.ownerId;
    }

    /** Conversations, orders, tickets and follow-ups that fit the customer's profile. */
    private static void fill(DataStore s, Customer c, String profile, Random rnd, LocalDate today, long now) {
        int orders = 0, talks = 0, lastOrderDays = 0, lastTalkDays = 0, spread = 0, bad = 0;
        switch (profile) {
            case "champion" -> { orders = 5 + rnd.nextInt(3); lastOrderDays = 5 + rnd.nextInt(25); spread = 300; talks = 6 + rnd.nextInt(4); lastTalkDays = 1 + rnd.nextInt(10); }
            case "loyal" -> { orders = 4 + rnd.nextInt(2); lastOrderDays = 20 + rnd.nextInt(60); spread = 330; talks = 5 + rnd.nextInt(3); lastTalkDays = 3 + rnd.nextInt(25); }
            case "regular" -> { orders = 2 + rnd.nextInt(2); lastOrderDays = 40 + rnd.nextInt(120); spread = 250; talks = 3 + rnd.nextInt(3); lastTalkDays = 5 + rnd.nextInt(40); bad = rnd.nextInt(4) == 0 ? 1 : 0; }
            case "slipping" -> { orders = 2 + rnd.nextInt(3); lastOrderDays = 200 + rnd.nextInt(120); spread = 200; talks = 3 + rnd.nextInt(3); lastTalkDays = 70 + rnd.nextInt(80); bad = 1 + rnd.nextInt(2); }
            case "new" -> { orders = 1; lastOrderDays = 3 + rnd.nextInt(20); talks = 2 + rnd.nextInt(2); lastTalkDays = 1 + rnd.nextInt(10); }
            case "prospect" -> { talks = 2 + rnd.nextInt(3); lastTalkDays = 1 + rnd.nextInt(20); }
            case "lead" -> { talks = rnd.nextInt(3) == 0 ? 0 : 1; lastTalkDays = 1 + rnd.nextInt(10); }
            case "churned" -> { orders = 1 + rnd.nextInt(2); lastOrderDays = 400 + rnd.nextInt(200); spread = 100; talks = 2 + rnd.nextInt(2); lastTalkDays = 180 + rnd.nextInt(150); bad = 1; }
            default -> { }
        }
        // orders: the last one at lastOrderDays ago, earlier ones spread before it
        for (int i = 0; i < orders; i++) {
            int daysAgo = lastOrderDays + (i == 0 ? 0 : rnd.nextInt(Math.max(1, spread)) + 7 * i);
            String[] item = pickItem(c, i, rnd);
            long lo = Long.parseLong(item[1]), hi = Long.parseLong(item[2]);
            Order o = new Order();
            o.id = s.nextId("O", 7001);
            o.customerId = c.id;
            o.item = item[0];
            o.amountPaise = (lo + (long) (rnd.nextDouble() * (hi - lo))) / 100 * 100 * 100;
            o.date = today.minusDays(daysAgo);
            o.status = (i == 0 && profile.equals("new") && rnd.nextBoolean()) ? "PENDING" : "PAID";
            if (i == orders - 1 && orders > 2 && rnd.nextInt(6) == 0) o.status = "REFUNDED";
            o.byUserId = c.ownerId;
            s.orders.put(o.id, o);
        }
        // conversations: the newest at lastTalkDays ago
        int span = Math.max(30, (int) ((Clock.millis() - c.createdAt) / 86_400_000L) - lastTalkDays);
        for (int i = 0; i < talks; i++) {
            int daysAgo = lastTalkDays + (i == 0 ? 0 : rnd.nextInt(span));
            String type = List.of("CALL", "CALL", "CALL", "EMAIL", "MEETING", "WHATSAPP").get(rnd.nextInt(6));
            if (c.email == null && type.equals("EMAIL")) type = "CALL";
            String dir = rnd.nextInt(3) == 0 ? "IN" : "OUT";
            if (c.doNotCall && type.equals("CALL")) dir = "IN";
            if (type.equals("EMAIL") && !c.consentEmail) dir = "IN";
            String outcome;
            String text;
            if (i < bad) { outcome = "NEGATIVE"; text = TALK_BAD[rnd.nextInt(TALK_BAD.length)]; }
            else if (profile.equals("champion") || profile.equals("loyal") || profile.equals("new") || rnd.nextInt(3) == 0) {
                outcome = "POSITIVE"; text = TALK_GOOD[rnd.nextInt(TALK_GOOD.length)];
            } else { outcome = "NEUTRAL"; text = TALK_MEH[rnd.nextInt(TALK_MEH.length)]; }
            talk(s, c, today.minusDays(daysAgo), type, dir, text, outcome, rnd);
        }
        if (rnd.nextInt(4) == 0) {
            Interaction note = talk(s, c, today.minusDays(lastTalkDays + 1), "NOTE", null,
                    "Roof faces south, about 40 sq m free. Prefers calls after 6 pm.", "NEUTRAL", rnd);
            note.minutes = 0;
        }
        // tickets
        boolean ticket = switch (profile) {
            case "slipping" -> true;
            case "regular", "loyal", "new" -> rnd.nextInt(3) == 0;
            case "champion" -> rnd.nextInt(4) == 0;
            default -> false;
        };
        if (ticket) {
            Ticket t = new Ticket();
            t.id = s.nextId("T", 9001);
            t.customerId = c.id;
            t.subject = TICKETS[rnd.nextInt(TICKETS.length)];
            t.priority = List.of("HIGH", "MEDIUM", "MEDIUM", "LOW").get(rnd.nextInt(4));
            t.byUserId = c.ownerId;
            if (profile.equals("slipping")) {
                t.createdAt = now - (4 + rnd.nextInt(10)) * 86_400_000L;   // open and late
                t.status = rnd.nextBoolean() ? "OPEN" : "IN_PROGRESS";
            } else if (rnd.nextBoolean()) {
                t.createdAt = now - (2 + rnd.nextInt(30)) * 3_600_000L;    // fresh, within deadline
                t.status = "OPEN";
            } else {
                t.createdAt = now - (10 + rnd.nextInt(120)) * 86_400_000L;
                t.status = "RESOLVED";
                t.resolvedAt = t.createdAt + (5 + rnd.nextInt(60)) * 3_600_000L;
            }
            s.tickets.put(t.id, t);
        }
        // follow-ups
        int fus = switch (profile) {
            case "prospect", "slipping" -> 1 + rnd.nextInt(2);
            case "lead", "new", "regular" -> rnd.nextInt(2);
            case "champion", "loyal" -> rnd.nextInt(3) == 0 ? 1 : 0;
            default -> 0;
        };
        for (int i = 0; i < fus; i++) {
            FollowUp f = new FollowUp();
            f.id = s.nextId("F", 3001);
            f.customerId = c.id;
            f.title = FOLLOWUPS[rnd.nextInt(FOLLOWUPS.length)];
            int r = rnd.nextInt(10);
            if (profile.equals("slipping") && i == 0) f.due = today.minusDays(2 + rnd.nextInt(8));
            else if (r < 3) f.due = today;
            else if (r < 5) f.due = today.minusDays(1 + rnd.nextInt(4));
            else f.due = today.plusDays(1 + rnd.nextInt(10));
            if (r == 9) { f.done = true; f.doneAt = now - rnd.nextInt(5) * 86_400_000L; f.due = today.minusDays(rnd.nextInt(5)); }
            f.priority = List.of("HIGH", "MEDIUM", "MEDIUM", "LOW").get(rnd.nextInt(4));
            f.assigneeId = c.ownerId;
            f.createdBy = c.ownerId;
            f.createdAt = now - (3 + rnd.nextInt(10)) * 86_400_000L;
            s.followUps.put(f.id, f);
        }
    }

    private static String[] pickItem(Customer c, int i, Random rnd) {
        if (i == 0 && "BUSINESS".equals(c.type) && rnd.nextBoolean()) return ITEMS[2];
        if (i == 0 || rnd.nextInt(5) == 0) return ITEMS[rnd.nextInt(2)];
        return ITEMS[3 + rnd.nextInt(ITEMS.length - 3)];
    }

    private static Interaction talk(DataStore s, Customer c, LocalDate day, String type, String dir, String text,
                                    String outcome, Random rnd) {
        Interaction i = new Interaction();
        i.id = s.nextId("I", 5001);
        i.customerId = c.id;
        i.type = type;
        i.direction = dir;
        i.summary = text;
        i.outcome = outcome;
        i.minutes = type.equals("MEETING") ? 30 + rnd.nextInt(60) : type.equals("NOTE") ? 0 : 2 + rnd.nextInt(15);
        long t = at(day, 9 + rnd.nextInt(9), rnd);
        i.at = Math.min(t, Clock.millis() - 60_000);
        i.byUserId = c.ownerId;
        s.interactions.put(i.id, i);
        return i;
    }

    private static long at(LocalDate day, int hour, Random rnd) {
        return day.atTime(LocalTime.of(hour, rnd.nextInt(60))).atZone(Clock.ZONE).toInstant().toEpochMilli();
    }
}
