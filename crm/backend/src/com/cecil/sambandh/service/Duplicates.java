package com.cecil.sambandh.service;

import com.cecil.sambandh.model.Customer;
import com.cecil.sambandh.model.Role;
import com.cecil.sambandh.model.Stage;
import com.cecil.sambandh.model.User;
import com.cecil.sambandh.store.DataStore;
import com.cecil.sambandh.util.Clock;
import com.cecil.sambandh.util.Json;
import com.cecil.sambandh.util.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the same customer saved twice ("Rahul Kumar, 98351 23456" and "Kumar Rahul, +91-9835123456") and merges them.
 *
 * How two customers are compared (score 0-100):
 *   same e-mail (ignoring capitals)                       -> 100
 *   same phone number (after cleaning the format)         ->  95
 *   names alike (Jaro-Winkler 0.90 or more), score = similarity x 70,
 *       + 20 if the company is the same, + 15 if the city is the same   (at most 99)
 * Pairs scoring 75 or more are shown as "possible duplicates".
 *
 * Comparing every customer with every other would be slow for big lists (n x n), so we use "blocking":
 * we only compare customers that share an e-mail, a phone, or the first 3 letters of a name word.
 */
public class Duplicates {
    public static final int SHOW_FROM = 75;
    public static final double NAME_MIN = 0.90;

    private final DataStore store;
    private final CustomerService customers;

    public Duplicates(DataStore store, CustomerService customers) {
        this.store = store;
        this.customers = customers;
    }

    public record Match(int score, List<String> reasons) { }

    /** How alike two customers (or a new form and a customer) are. */
    public static Match compare(String nameA, String companyA, String emailA, String phoneA, String cityA,
                                String nameB, String companyB, String emailB, String phoneB, String cityB) {
        List<String> reasons = new ArrayList<>();
        int score = 0;
        if (emailA != null && emailA.equals(emailB)) { score = 100; reasons.add("Same e-mail"); }
        if (phoneA != null && phoneA.equals(phoneB)) { score = Math.max(score, 95); reasons.add("Same phone number"); }
        double sim = Text.nameSimilarity(nameA, nameB);
        if (sim >= NAME_MIN) {
            int s = (int) Math.round(sim * 70);
            reasons.add(sim >= 0.999 ? "Same name" : "Very similar name (" + Math.round(sim * 100) + "%)");
            String ca = Text.companyKey(companyA), cb = Text.companyKey(companyB);
            if (!ca.isEmpty() && ca.equals(cb)) { s += 20; reasons.add("Same company"); }
            String ta = Text.nameKey(cityA), tb = Text.nameKey(cityB);
            if (!ta.isEmpty() && ta.equals(tb)) { s += 15; reasons.add("Same city"); }
            score = Math.max(score, Math.min(99, s));
        }
        return new Match(score, reasons);
    }

    static Match compare(Customer a, Customer b) {
        return compare(a.name, a.company, a.email, a.phone, a.city, b.name, b.company, b.email, b.phone, b.city);
    }

    /** Blocking keys: e-mail, phone, and the first 3 letters of each name word. */
    static Set<String> keys(String name, String email, String phone) {
        Set<String> k = new HashSet<>();
        if (email != null) k.add("e:" + email);
        if (phone != null) k.add("p:" + phone);
        for (String w : Text.nameKey(name).split(" ")) if (w.length() >= 2) k.add("n:" + w.substring(0, Math.min(3, w.length())));
        return k;
    }

    /** Live check while someone fills in the "new customer" form. Only customers this user may see are shown. */
    public List<Map<String, Object>> check(User actor, Map<String, Object> b) {
        String name = Text.clean(Json.str(b, "name"));
        String email = Text.emailKey(Text.clean(Json.str(b, "email")));
        String phone = Text.normalizePhone(Json.str(b, "phone"));
        String company = Text.clean(Json.str(b, "company"));
        String city = Text.clean(Json.str(b, "city"));
        String excludeId = Json.str(b, "excludeId");
        List<Map<String, Object>> out = new ArrayList<>();
        if (name == null && email == null && phone == null) return out;
        int hiddenStrong = 0;
        synchronized (store) {
            for (Customer c : store.customers.values()) {
                if (!c.visible() || c.erased || c.id.equals(excludeId)) continue;
                Match m = compare(name, company, email, phone, city, c.name, c.company, c.email, c.phone, c.city);
                if (m.score() < SHOW_FROM) continue;
                if (!CustomerService.canSee(actor, c)) { if (m.score() >= 95) hiddenStrong++; continue; }
                out.add(Json.obj("id", c.id, "name", c.name, "company", c.company, "city", c.city,
                        "ownerName", store.userName(c.ownerId), "score", m.score(), "reasons", m.reasons()));
            }
            if (hiddenStrong > 0) out.add(Json.obj("id", null, "name", "A customer of another agent", "score", 95,
                    "reasons", List.of("Same e-mail or phone – ask your manager")));
        }
        out.sort(Comparator.comparingInt((Map<String, Object> m) -> -((Number) m.get("score")).intValue()));
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    /** All possible duplicate pairs among the customers this user may see, best first. */
    public List<Map<String, Object>> findAll(User actor) {
        synchronized (store) {
            List<Customer> list = new ArrayList<>();
            for (Customer c : store.customers.values()) if (CustomerService.canSee(actor, c) && !c.erased) list.add(c);
            Map<String, List<Integer>> blocks = new HashMap<>();
            for (int i = 0; i < list.size(); i++) {
                Customer c = list.get(i);
                for (String k : keys(c.name, c.email, c.phone)) blocks.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
            }
            Set<Long> seen = new HashSet<>();
            List<Map<String, Object>> pairs = new ArrayList<>();
            for (List<Integer> block : blocks.values()) {
                for (int x = 0; x < block.size(); x++) {
                    for (int y = x + 1; y < block.size(); y++) {
                        int i = Math.min(block.get(x), block.get(y)), j = Math.max(block.get(x), block.get(y));
                        if (!seen.add((long) i * 1_000_000L + j)) continue;
                        Match m = compare(list.get(i), list.get(j));
                        if (m.score() < SHOW_FROM) continue;
                        Customer a = list.get(i), b = list.get(j);
                        // suggest keeping the older record (it usually has more history)
                        if (b.createdAt < a.createdAt) { Customer t = a; a = b; b = t; }
                        pairs.add(Json.obj("score", m.score(), "reasons", m.reasons(), "keep", brief(a), "other", brief(b)));
                    }
                }
            }
            pairs.sort(Comparator.comparingInt((Map<String, Object> m) -> -((Number) m.get("score")).intValue()));
            return pairs;
        }
    }

    private Map<String, Object> brief(Customer c) {
        return Json.obj("id", c.id, "name", c.name, "company", c.company, "email", c.email, "phone", c.phone,
                "city", c.city, "stageLabel", c.stage.label, "ownerName", store.userName(c.ownerId), "createdAt", c.createdAt,
                "records", countRecords(c.id));
    }

    private long countRecords(String id) {
        return store.interactions.values().stream().filter(i -> id.equals(i.customerId)).count()
                + store.orders.values().stream().filter(o -> id.equals(o.customerId)).count()
                + store.tickets.values().stream().filter(t -> id.equals(t.customerId)).count()
                + store.followUps.values().stream().filter(f -> id.equals(f.customerId)).count();
    }

    /**
     * Merges "other" into "keep":
     *  - every conversation, follow-up, order and ticket moves to "keep";
     *  - empty fields of "keep" are filled from "other"; a different e-mail / phone is written into the notes;
     *  - tags are combined; the stronger stage wins (Loyal > Customer > Prospect > Lead);
     *  - consent takes the SAFER choice: e-mails only if both agreed, Do-Not-Call if either asked for it;
     *  - "other" is hidden (marked merged) but not deleted, so the history still shows what happened.
     */
    public Customer merge(User actor, String keepId, String otherId) {
        if (!actor.role.atLeast(Role.MANAGER)) throw Problem.forbidden("Only a manager can merge customers.");
        if (keepId == null || keepId.equals(otherId)) throw Problem.bad("Choose two different customers.");
        synchronized (store) {
            Customer keep = customers.findWritable(actor, keepId);
            Customer other = customers.findWritable(actor, otherId);
            List<String> extra = new ArrayList<>();
            if (keep.company == null) keep.company = other.company;
            if (keep.city == null) keep.city = other.city;
            if (keep.source == null) keep.source = other.source;
            if (keep.email == null) keep.email = other.email;
            else if (other.email != null && !other.email.equals(keep.email)) extra.add("other e-mail " + other.email);
            if (keep.phone == null) keep.phone = other.phone;
            else if (other.phone != null && !other.phone.equals(keep.phone)) extra.add("other phone " + other.phone);
            if ("INDIVIDUAL".equals(keep.type) && "BUSINESS".equals(other.type) && keep.company != null) keep.type = "BUSINESS";
            Set<String> tags = new LinkedHashSet<>(keep.tags);
            tags.addAll(other.tags);
            keep.tags = new ArrayList<>(tags).subList(0, Math.min(tags.size(), CustomerService.MAX_TAGS));
            keep.tags = new ArrayList<>(keep.tags);
            keep.stage = stronger(keep.stage, other.stage);
            keep.consentEmail = keep.consentEmail && other.consentEmail && keep.email != null;
            keep.doNotCall = keep.doNotCall || other.doNotCall;
            keep.createdAt = Math.min(keep.createdAt, other.createdAt);
            StringBuilder notes = new StringBuilder(keep.notes == null ? "" : keep.notes);
            String add = "Merged from " + other.id + (extra.isEmpty() ? "" : " (" + String.join(", ", extra) + ")")
                    + (other.notes == null ? "" : ": " + other.notes);
            if (notes.length() > 0) notes.append("\n");
            notes.append(add);
            keep.notes = notes.length() > 1000 ? notes.substring(0, 1000) : notes.toString();
            keep.updatedAt = Clock.millis();

            int moved = 0;
            for (var i : store.interactions.values()) if (other.id.equals(i.customerId)) { i.customerId = keep.id; moved++; }
            for (var f : store.followUps.values()) if (other.id.equals(f.customerId)) { f.customerId = keep.id; moved++; }
            for (var o : store.orders.values()) if (other.id.equals(o.customerId)) { o.customerId = keep.id; moved++; }
            for (var t : store.tickets.values()) if (other.id.equals(t.customerId)) { t.customerId = keep.id; moved++; }
            other.mergedInto = keep.id;
            other.updatedAt = Clock.millis();
            store.log(actor.id, "customer.merge", keep.id, other.id + " (" + other.name + ") merged in, " + moved + " records moved");
            store.saveAll();
            return keep;
        }
    }

    static Stage stronger(Stage a, Stage b) {
        List<Stage> order = List.of(Stage.CHURNED, Stage.LEAD, Stage.PROSPECT, Stage.CUSTOMER, Stage.LOYAL);
        return order.indexOf(a) >= order.indexOf(b) ? a : b;
    }
}
