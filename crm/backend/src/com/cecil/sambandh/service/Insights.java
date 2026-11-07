package com.cecil.sambandh.service;

import com.cecil.sambandh.model.Customer;
import com.cecil.sambandh.model.FollowUp;
import com.cecil.sambandh.model.Interaction;
import com.cecil.sambandh.model.Order;
import com.cecil.sambandh.model.Stage;
import com.cecil.sambandh.model.Ticket;
import com.cecil.sambandh.store.DataStore;
import com.cecil.sambandh.util.Clock;
import com.cecil.sambandh.util.Json;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "intelligence" of the CRM, all explained in plain rules (no black box):
 *
 *  1. RFM analysis - scores every buying customer 1 to 5 on
 *       R = Recency   (how many days since the last purchase - fewer is better)
 *       F = Frequency (how many purchases in the last 365 days)
 *       M = Monetary  (how much money in the last 365 days)
 *     Scores are "quintiles": customers are ranked and split into 5 equal groups.
 *     R and the average of F and M put the customer in a segment: Champions, Loyal, At risk, Lost ...
 *
 *  2. Health score 0-100 from five parts (contact, buying, mood, support, follow-ups), each with a reason.
 *     70+ healthy (green lamp), 40-69 watch (amber), below 40 at risk (red).
 *
 *  3. Next best action - up to 3 suggestions built from the same facts, and they respect consent
 *     (never "call" someone on Do-Not-Call).
 *
 * A Snapshot groups all records by customer once, so a page with many customers stays fast.
 */
public class Insights {
    public static final int HEALTHY = 70;
    public static final int WATCH = 40;

    private final DataStore store;

    public Insights(DataStore store) { this.store = store; }

    // ------------------------------------------------------------------ snapshot

    /** All records grouped by customer id, plus the RFM scores. Build it while holding the store lock. */
    public static final class Snapshot {
        public final long now;
        public final LocalDate today;
        public final Map<String, List<Interaction>> interactions = new HashMap<>();
        public final Map<String, List<Order>> orders = new HashMap<>();
        public final Map<String, List<Ticket>> tickets = new HashMap<>();
        public final Map<String, List<FollowUp>> followUps = new HashMap<>();
        public final Map<String, Rfm> rfm = new HashMap<>();

        Snapshot(long now, LocalDate today) { this.now = now; this.today = today; }

        public List<Interaction> interactionsOf(String id) { return interactions.getOrDefault(id, List.of()); }
        public List<Order> ordersOf(String id) { return orders.getOrDefault(id, List.of()); }
        public List<Ticket> ticketsOf(String id) { return tickets.getOrDefault(id, List.of()); }
        public List<FollowUp> followUpsOf(String id) { return followUps.getOrDefault(id, List.of()); }

        /** Lifetime value: all money from orders that were not refunded. */
        public long lifetimeValue(String id) {
            long sum = 0;
            for (Order o : ordersOf(id)) if (o.counts()) sum += o.amountPaise;
            return sum;
        }

        /** Last real contact (calls, e-mails, meetings, chats - internal notes do not count). 0 = never. */
        public long lastContact(String id) {
            long last = 0;
            for (Interaction i : interactionsOf(id)) if (!"NOTE".equals(i.type)) last = Math.max(last, i.at);
            return last;
        }
    }

    public record Rfm(int recencyDays, int frequency, long monetaryPaise, int r, int f, int m, int fm, String segment) {
        Map<String, Object> toJson() {
            return Json.obj("recencyDays", recencyDays, "frequency", frequency, "monetaryPaise", monetaryPaise,
                    "r", r, "f", f, "m", m, "fm", fm, "segment", segment, "segmentNote", segmentNote(segment));
        }
    }

    public Snapshot snapshot() {
        synchronized (store) {
            Snapshot s = new Snapshot(Clock.millis(), Clock.today());
            for (Interaction i : store.interactions.values()) s.interactions.computeIfAbsent(i.customerId, k -> new ArrayList<>()).add(i);
            for (Order o : store.orders.values()) s.orders.computeIfAbsent(o.customerId, k -> new ArrayList<>()).add(o);
            for (Ticket t : store.tickets.values()) s.tickets.computeIfAbsent(t.customerId, k -> new ArrayList<>()).add(t);
            for (FollowUp f : store.followUps.values()) s.followUps.computeIfAbsent(f.customerId, k -> new ArrayList<>()).add(f);
            computeRfm(s, store.customers.values());
            return s;
        }
    }

    // ------------------------------------------------------------------ RFM

    private static void computeRfm(Snapshot s, Collection<Customer> customers) {
        List<String> ids = new ArrayList<>();
        List<int[]> rf = new ArrayList<>();     // [recencyDays, frequency]
        List<Long> money = new ArrayList<>();
        LocalDate yearAgo = s.today.minusDays(365);
        for (Customer c : customers) {
            if (!c.visible()) continue;
            LocalDate last = null;
            int freq = 0;
            long sum = 0;
            for (Order o : s.ordersOf(c.id)) {
                if (!o.counts()) continue;
                if (last == null || o.date.isAfter(last)) last = o.date;
                if (o.date.isAfter(yearAgo)) { freq++; sum += o.amountPaise; }
            }
            if (last == null) continue;   // never bought: no RFM
            ids.add(c.id);
            rf.add(new int[]{(int) ChronoUnit.DAYS.between(last, s.today), freq});
            money.add(sum);
        }
        int n = ids.size();
        for (int i = 0; i < n; i++) {
            int days = rf.get(i)[0], freq = rf.get(i)[1];
            long sum = money.get(i);
            int better = 0, fewerF = 0, lessM = 0;
            for (int j = 0; j < n; j++) {
                if (rf.get(j)[0] > days) better++;          // others bought longer ago than this customer
                if (rf.get(j)[1] < freq) fewerF++;
                if (money.get(j) < sum) lessM++;
            }
            int r = quintile(better, n), f = quintile(fewerF, n), m = quintile(lessM, n);
            int fm = (int) Math.round((f + m) / 2.0);
            s.rfm.put(ids.get(i), new Rfm(days, freq, sum, r, f, m, fm, segment(r, fm)));
        }
    }

    /** Puts a customer in group 1..5 from how many others they beat. Ties share the same score. */
    public static int quintile(int beaten, int n) {
        if (n <= 1) return 3;
        return Math.min(5, 1 + (int) Math.floor(5.0 * beaten / n));
    }

    /** The segment table (R across, average of F and M down). Every one of the 25 boxes has a name. */
    public static String segment(int r, int fm) {
        if (r >= 4 && fm >= 4) return "Champions";
        if (r >= 3 && fm >= 3) return "Loyal";
        if (r >= 4) return "Promising";
        if (r == 3) return "Needs attention";
        if (fm >= 4) return "Can't lose them";
        if (fm == 3) return "At risk";
        if (r == 2) return "Hibernating";
        return "Lost";
    }

    static String segmentNote(String segment) {
        return switch (segment) {
            case "Champions" -> "Bought recently, buy often and spend the most.";
            case "Loyal" -> "Buy regularly and spend well.";
            case "Promising" -> "Bought recently but not much yet.";
            case "Needs attention" -> "Average on everything - could slip away.";
            case "Can't lose them" -> "Used to buy a lot, but not for a long time.";
            case "At risk" -> "Spent well before, now going quiet.";
            case "Hibernating" -> "Small and long ago.";
            default -> "Lowest on everything - probably gone.";
        };
    }

    public static final List<String> SEGMENTS = List.of("Champions", "Loyal", "Promising", "Needs attention",
            "Can't lose them", "At risk", "Hibernating", "Lost");

    // ------------------------------------------------------------------ health score

    public record Part(String key, String label, int points, int max, String note) {
        Map<String, Object> toJson() { return Json.obj("key", key, "label", label, "points", points, "max", max, "note", note); }
    }

    public record Health(int score, String band, List<Part> parts) {
        public Map<String, Object> toJson() {
            List<Object> p = new ArrayList<>();
            for (Part x : parts) p.add(x.toJson());
            return Json.obj("score", score, "band", band, "bandLabel", bandLabel(band), "parts", p);
        }
    }

    public static String band(int score) {
        return score >= HEALTHY ? "healthy" : score >= WATCH ? "watch" : "risk";
    }

    public static String bandLabel(String band) {
        return switch (band) { case "healthy" -> "Healthy"; case "watch" -> "Watch"; default -> "At risk"; };
    }

    public Health health(Customer c, Snapshot s) {
        List<Part> parts = new ArrayList<>();

        // 1. Contact - 30 points
        long last = s.lastContact(c.id);
        int contact;
        String contactNote;
        if (last == 0) { contact = 0; contactNote = "Never contacted"; }
        else {
            long days = Math.max(0, (s.now - last) / 86_400_000L);
            contact = days <= 14 ? 30 : days <= 30 ? 22 : days <= 60 ? 12 : days <= 90 ? 5 : 0;
            contactNote = days == 0 ? "Contacted today" : "Last contact " + days + " day" + (days == 1 ? "" : "s") + " ago";
        }
        parts.add(new Part("contact", "Contact", contact, 30, contactNote));

        // 2. Buying - 25 points (R score x 5)
        Rfm rfm = s.rfm.get(c.id);
        int buying;
        String buyNote;
        if (rfm != null) {
            buying = rfm.r() * 5;
            buyNote = "Last purchase " + rfm.recencyDays() + " days ago (R score " + rfm.r() + " of 5)";
        } else if (c.stage == Stage.LEAD || c.stage == Stage.PROSPECT) {
            buying = 12;
            buyNote = "No purchase yet - normal for a " + c.stage.label.toLowerCase();
        } else {
            buying = 0;
            buyNote = "No purchases recorded";
        }
        parts.add(new Part("buying", "Buying", buying, 25, buyNote));

        // 3. Mood - 15 points from the last 5 conversations
        List<Interaction> talks = new ArrayList<>();
        for (Interaction i : s.interactionsOf(c.id)) if (!"NOTE".equals(i.type)) talks.add(i);
        talks.sort(Comparator.comparingLong((Interaction i) -> i.at).reversed());
        int mood;
        String moodNote;
        if (talks.isEmpty()) { mood = 8; moodNote = "No conversations yet"; }
        else {
            List<Interaction> recent = talks.subList(0, Math.min(5, talks.size()));
            double sum = 0;
            int pos = 0, neg = 0;
            for (Interaction i : recent) {
                if ("POSITIVE".equals(i.outcome)) { sum += 1; pos++; }
                else if ("NEGATIVE".equals(i.outcome)) neg++;
                else sum += 0.5;
            }
            mood = (int) Math.round(15 * sum / recent.size());
            moodNote = "Last " + recent.size() + ": " + pos + " went well, " + neg + " went badly";
        }
        parts.add(new Part("mood", "Mood", mood, 15, moodNote));

        // 4. Support - 20 points, minus 6 per open ticket and 8 more per ticket past its deadline
        int open = 0, breached = 0;
        for (Ticket t : s.ticketsOf(c.id)) {
            if (t.open()) open++;
            if (t.breached(s.now)) breached++;
        }
        int support = Math.max(0, 20 - 6 * open - 8 * breached);
        String supNote = open == 0 ? "No open tickets"
                : open + " open ticket" + (open == 1 ? "" : "s") + (breached > 0 ? ", " + breached + " past the deadline" : "");
        parts.add(new Part("support", "Support", support, 20, supNote));

        // 5. Follow-ups - 10 points, minus 5 per overdue follow-up
        int overdue = 0;
        for (FollowUp f : s.followUpsOf(c.id)) if (f.overdue(s.today)) overdue++;
        int fol = Math.max(0, 10 - 5 * overdue);
        parts.add(new Part("followups", "Follow-ups", fol, 10, overdue == 0 ? "Nothing overdue" : overdue + " overdue"));

        int score = 0;
        for (Part p : parts) score += p.points();
        return new Health(score, band(score), parts);
    }

    /** Customers we already earn from who are slipping away. */
    public static boolean atRisk(Customer c, Health h) {
        return (c.stage == Stage.CUSTOMER || c.stage == Stage.LOYAL) && !c.erased && h.score() < WATCH;
    }

    // ------------------------------------------------------------------ next best action

    public List<Map<String, Object>> nextActions(Customer c, Snapshot s, Health h) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (c.erased) return out;
        for (Ticket t : s.ticketsOf(c.id)) {
            if (t.breached(s.now)) out.add(action("ticket", "Solve ticket " + t.id + " – “" + t.subject + "”",
                    "It is past its " + Ticket.slaHours(t.priority) + "-hour deadline.", t.id));
        }
        for (FollowUp f : s.followUpsOf(c.id)) {
            if (f.overdue(s.today)) out.add(action("followup", "Do the overdue follow-up: " + f.title,
                    "It was due on " + f.due + ".", f.id));
        }
        List<Interaction> talks = new ArrayList<>();
        for (Interaction i : s.interactionsOf(c.id)) if (!"NOTE".equals(i.type)) talks.add(i);
        talks.sort(Comparator.comparingLong((Interaction i) -> i.at).reversed());
        if (!talks.isEmpty() && "NEGATIVE".equals(talks.get(0).outcome)) {
            out.add(action("repair", "Repair the relationship", "The last conversation went badly – a short "
                    + channel(c, "call") + " from a senior person often helps.", null));
        }
        long last = s.lastContact(c.id);
        long days = last == 0 ? -1 : (s.now - last) / 86_400_000L;
        Rfm rfm = s.rfm.get(c.id);
        boolean paying = c.stage == Stage.CUSTOMER || c.stage == Stage.LOYAL;
        if (paying && (days < 0 || days > 30)) {
            out.add(action("checkin", "Check in with a " + channel(c, "call"),
                    days < 0 ? "We have never talked to them." : "No contact for " + days + " days.", null));
        }
        if (rfm != null) {
            switch (rfm.segment()) {
                case "Champions" -> out.add(action("referral", "Ask for a referral or a review",
                        "One of your best customers (RFM: Champions).", null));
                case "Can't lose them", "At risk" -> out.add(action("winback", "Offer a win-back deal",
                        "They used to spend well but have gone quiet (RFM: " + rfm.segment() + ").", null));
                default -> { }
            }
        }
        long positives = talks.stream().filter(i -> "POSITIVE".equals(i.outcome)).count();
        if (c.stage == Stage.LEAD) {
            if (talks.isEmpty()) out.add(action("first", "Make the first contact",
                    "New lead – reach out by " + channel(c, "phone") + " while the interest is fresh.", null));
            else if (positives > 0) out.add(action("promote", "Send a quote and move them to Prospect",
                    "They responded well.", null));
        } else if (c.stage == Stage.PROSPECT && positives >= 2) {
            out.add(action("close", "Close the deal – send the final quote", positives + " good conversations so far.", null));
        }
        if (out.isEmpty()) out.add(action("ok", "Nothing urgent", "Health " + h.score() + "/100 – keep in touch as usual.", null));
        return out.size() > 3 ? new ArrayList<>(out.subList(0, 3)) : out;
    }

    /** Picks a contact method the customer allows. */
    static String channel(Customer c, String preferred) {
        if (!c.doNotCall && c.phone != null) return preferred;
        if (c.consentEmail && c.email != null) return "e-mail (they are on Do-Not-Call)";
        if (c.phone != null || c.email != null) return "message only when they contact us (Do-Not-Call, no e-mail consent)";
        return "visit (no phone or e-mail saved)";
    }

    private static Map<String, Object> action(String kind, String title, String why, String ref) {
        return Json.obj("kind", kind, "title", title, "why", why, "ref", ref);
    }

    public Map<String, Object> rfmJson(Snapshot s, String customerId) {
        Rfm r = s.rfm.get(customerId);
        return r == null ? null : r.toJson();
    }

    // ------------------------------------------------------------------ automatic stage moves

    /**
     * After an order is saved: a lead / prospect / churned customer who buys becomes a Customer, and a customer with
     * 4 or more purchases in the last year becomes Loyal. Returns the new stage, or null if nothing changed.
     */
    public static Stage autoStage(Customer c, List<Order> ordersOfCustomer, LocalDate today) {
        long paidLastYear = ordersOfCustomer.stream().filter(o -> o.counts() && o.date.isAfter(today.minusDays(365))).count();
        boolean anyPaid = ordersOfCustomer.stream().anyMatch(Order::counts);
        if (!anyPaid) return null;
        if (c.stage == Stage.LEAD || c.stage == Stage.PROSPECT || c.stage == Stage.CHURNED) {
            return paidLastYear >= 4 ? Stage.LOYAL : Stage.CUSTOMER;
        }
        if (c.stage == Stage.CUSTOMER && paidLastYear >= 4) return Stage.LOYAL;
        return null;
    }
}
