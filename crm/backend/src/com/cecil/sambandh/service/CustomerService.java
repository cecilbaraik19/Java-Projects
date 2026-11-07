package com.cecil.sambandh.service;

import com.cecil.sambandh.model.Customer;
import com.cecil.sambandh.model.Role;
import com.cecil.sambandh.model.Stage;
import com.cecil.sambandh.model.User;
import com.cecil.sambandh.store.DataStore;
import com.cecil.sambandh.util.Clock;
import com.cecil.sambandh.util.Csv;
import com.cecil.sambandh.util.Json;
import com.cecil.sambandh.util.Money;
import com.cecil.sambandh.util.Text;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Customers: who may see them, adding, editing, searching, re-assigning, exporting.
 *
 * The access rule is in ONE place (canSee): a sales agent sees only the customers they own; managers and admins
 * see everyone. A customer you may not see is answered with "not found", so its existence is not given away.
 */
public class CustomerService {
    public static final List<String> SOURCES = List.of("Website", "Referral", "Walk-in", "Cold call", "Event",
            "Social media", "Advertisement", "Partner", "Other");
    public static final List<String> TYPES = List.of("INDIVIDUAL", "BUSINESS");
    public static final int PAGE_SIZE = 25;
    public static final int MAX_TAGS = 8;

    private final DataStore store;
    private final Insights insights;
    private Duplicates duplicates;

    public CustomerService(DataStore store, Insights insights) {
        this.store = store;
        this.insights = insights;
    }

    public void setDuplicates(Duplicates d) { this.duplicates = d; }

    // ------------------------------------------------------------------ access

    public static boolean canSee(User u, Customer c) {
        if (c == null || !c.visible()) return false;
        return u.role.atLeast(Role.MANAGER) || u.id.equals(c.ownerId);
    }

    /** The customer if this user may see it, else "not found". Call while holding the store lock. */
    public Customer find(User u, String id) {
        Customer c = id == null ? null : store.customers.get(id);
        if (!canSee(u, c)) throw Problem.notFound("Customer");
        return c;
    }

    /** Like find, but also refuses customers whose personal data was erased (they cannot be changed any more). */
    Customer findWritable(User u, String id) {
        Customer c = find(u, id);
        if (c.erased) throw Problem.conflict("This customer's personal data was erased on request. Nothing more can be added.");
        return c;
    }

    // ------------------------------------------------------------------ create / edit

    /** The cleaned, checked fields of a customer form. */
    record Fields(String name, String company, String email, String phone, String city, String type, String source,
                  Stage stage, List<String> tags, String notes, boolean consentEmail, boolean doNotCall, String ownerId) { }

    Fields readFields(User actor, Map<String, Object> b, Customer existing) {
        List<String> errors = new ArrayList<>();
        String name = Text.clean(Json.str(b, "name"));
        if (name == null) errors.add("Name is needed.");
        else if (name.length() > 80) errors.add("Name is too long (80 characters max).");
        String company = Text.clean(Json.str(b, "company"));
        if (company != null && company.length() > 80) errors.add("Company is too long (80 characters max).");
        String emailRaw = Text.clean(Json.str(b, "email"));
        String email = null;
        if (emailRaw != null) {
            if (!Text.validEmail(emailRaw)) errors.add("E-mail does not look right (example: name@gmail.com).");
            else email = Text.emailKey(emailRaw);
        }
        String phoneRaw = Text.clean(Json.str(b, "phone"));
        String phone = null;
        if (phoneRaw != null) {
            phone = Text.normalizePhone(phoneRaw);
            if (phone == null) errors.add("Phone should be a 10-digit Indian number (example: 98351 23456).");
        }
        if (emailRaw == null && phoneRaw == null) errors.add("Give at least a phone number or an e-mail, so we can reach them.");
        String city = Text.clean(Json.str(b, "city"));
        if (city != null && city.length() > 40) errors.add("City is too long.");
        String type = Json.str(b, "type", "INDIVIDUAL").trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) errors.add("Type must be Individual or Business.");
        if ("BUSINESS".equals(type) && company == null) errors.add("A business customer needs a company name.");
        String source = Text.clean(Json.str(b, "source"));
        if (source != null && !SOURCES.contains(source)) errors.add("Unknown source.");
        Stage stage = Stage.parse(Json.str(b, "stage"));
        if (stage == null) {
            if (Json.str(b, "stage") != null && !Json.str(b, "stage").isBlank()) errors.add("Unknown stage.");
            stage = existing != null ? existing.stage : Stage.LEAD;
        }
        List<String> tags = cleanTags(b.get("tags"), errors);
        String notes = Text.clean(Json.str(b, "notes"));
        if (notes != null && notes.length() > 1000) errors.add("Notes are too long (1000 characters max).");
        boolean consentEmail = Json.bool(b, "consentEmail", existing != null && existing.consentEmail);
        boolean dnc = Json.bool(b, "doNotCall", existing != null && existing.doNotCall);
        if (consentEmail && email == null) consentEmail = false;   // nothing to agree to without an e-mail

        String ownerId = Json.str(b, "ownerId");
        if (ownerId == null || ownerId.isBlank()) ownerId = existing != null ? existing.ownerId : actor.id;
        if (existing == null && !actor.role.atLeast(Role.MANAGER)) ownerId = actor.id;   // agents own what they add
        if (existing != null && !ownerId.equals(existing.ownerId) && !actor.role.atLeast(Role.MANAGER))
            errors.add("Only a manager can give a customer to someone else.");
        User owner = store.users.get(ownerId);
        if (owner == null || !owner.active) errors.add("Owner must be an active staff member.");

        if (!errors.isEmpty()) throw Problem.bad(String.join(" ", errors)).with(Json.obj("errors", errors));
        return new Fields(name, company, email, phone, city, type, source, stage, tags, notes, consentEmail, dnc, ownerId);
    }

    static List<String> cleanTags(Object raw, List<String> errors) {
        List<String> in = new ArrayList<>();
        if (raw instanceof List<?> l) for (Object o : l) { if (o != null) in.add(String.valueOf(o)); }
        else if (raw instanceof String s) for (String p : s.split("[,;|]")) in.add(p);
        Set<String> out = new LinkedHashSet<>();
        for (String t : in) {
            String x = t.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
            if (x.isEmpty()) continue;
            if (!x.matches("[a-z0-9][a-z0-9-]{0,19}")) { errors.add("Tag \"" + t.trim() + "\": use up to 20 letters, digits or dashes."); continue; }
            out.add(x);
        }
        if (out.size() > MAX_TAGS) errors.add("At most " + MAX_TAGS + " tags.");
        return new ArrayList<>(out);
    }

    public Customer create(User actor, Map<String, Object> body) {
        synchronized (store) {
            Fields f = readFields(actor, body, null);
            blockExactDuplicates(actor, f, null);
            Customer c = newCustomer(f);
            store.customers.put(c.id, c);
            store.log(actor.id, "customer.create", c.id, c.name + " (" + c.stage.label + ", owner " + store.userName(c.ownerId) + ")");
            store.save("customers", "audit");
            return c;
        }
    }

    /** Builds and numbers a new customer (no saving - the caller saves). Used by create and by CSV import. */
    Customer newCustomer(Fields f) {
        Customer c = new Customer();
        c.id = store.nextId("C", 1001);
        apply(c, f);
        c.createdAt = Clock.millis();
        c.updatedAt = c.createdAt;
        return c;
    }

    private static void apply(Customer c, Fields f) {
        c.name = f.name(); c.company = f.company(); c.email = f.email(); c.phone = f.phone(); c.city = f.city();
        c.type = f.type(); c.source = f.source(); c.stage = f.stage(); c.tags = new ArrayList<>(f.tags());
        c.notes = f.notes(); c.consentEmail = f.consentEmail(); c.doNotCall = f.doNotCall(); c.ownerId = f.ownerId();
    }

    /** The same e-mail or phone number may not be saved twice. Near-matches only warn (see Duplicates). */
    private void blockExactDuplicates(User actor, Fields f, String selfId) {
        for (Customer o : store.customers.values()) {
            if (!o.visible() || o.erased || o.id.equals(selfId)) continue;
            String what = null;
            if (f.email() != null && f.email().equals(o.email)) what = "e-mail";
            else if (f.phone() != null && f.phone().equals(o.phone)) what = "phone number";
            if (what == null) continue;
            if (canSee(actor, o)) {
                throw Problem.conflict("This " + what + " already belongs to " + o.id + " – " + o.name + ". Open that customer instead.")
                        .with(Json.obj("existingId", o.id));
            }
            throw Problem.conflict("This " + what + " already belongs to a customer owned by " + store.userName(o.ownerId)
                    + ". Ask your manager to share or re-assign it.");
        }
    }

    public Customer update(User actor, String id, Map<String, Object> body) {
        synchronized (store) {
            Customer c = findWritable(actor, id);
            Fields f = readFields(actor, body, c);
            blockExactDuplicates(actor, f, c.id);
            List<String> changes = new ArrayList<>();
            if (!f.name().equals(c.name)) changes.add("name");
            if (f.stage() != c.stage) changes.add("stage " + c.stage.label + " → " + f.stage().label);
            if (!f.ownerId().equals(c.ownerId)) changes.add("owner " + store.userName(c.ownerId) + " → " + store.userName(f.ownerId()));
            if (!eq(f.email(), c.email)) changes.add("e-mail");
            if (!eq(f.phone(), c.phone)) changes.add("phone");
            if (f.consentEmail() != c.consentEmail) changes.add(f.consentEmail() ? "agreed to e-mails" : "withdrew e-mail consent");
            if (f.doNotCall() != c.doNotCall) changes.add(f.doNotCall() ? "added to Do-Not-Call" : "removed from Do-Not-Call");
            if (!eq(f.company(), c.company) || !eq(f.city(), c.city) || !eq(f.notes(), c.notes) || !f.tags().equals(c.tags)
                    || !eq(f.source(), c.source) || !f.type().equals(c.type)) changes.add("details");
            if (changes.isEmpty()) return c;
            apply(c, f);
            c.updatedAt = Clock.millis();
            store.log(actor.id, "customer.update", c.id, String.join(", ", changes));
            store.save("customers", "audit");
            return c;
        }
    }

    private static boolean eq(Object a, Object b) { return a == null ? b == null : a.equals(b); }

    /** Manager gives many customers to one owner at once. */
    public int bulkAssign(User actor, List<String> ids, String ownerId) {
        if (!actor.role.atLeast(Role.MANAGER)) throw Problem.forbidden("Only a manager can re-assign customers.");
        if (ids.isEmpty()) throw Problem.bad("Select at least one customer.");
        if (ids.size() > 500) throw Problem.bad("At most 500 at a time.");
        synchronized (store) {
            User owner = store.users.get(ownerId);
            if (owner == null || !owner.active) throw Problem.bad("Choose an active staff member.");
            int moved = 0;
            for (String id : ids) {
                Customer c = find(actor, id);
                if (c.erased || owner.id.equals(c.ownerId)) continue;
                c.ownerId = owner.id;
                c.updatedAt = Clock.millis();
                moved++;
            }
            store.log(actor.id, "customer.assign", moved + " customers", "to " + owner.name);
            store.save("customers", "audit");
            return moved;
        }
    }

    // ------------------------------------------------------------------ list / search

    public Map<String, Object> list(User actor, Map<String, String> q) {
        Insights.Snapshot s = insights.snapshot();
        List<Map<String, Object>> rows = new ArrayList<>();
        synchronized (store) {
            for (Customer c : filtered(actor, q, s)) rows.add(row(c, s));
        }
        sortRows(rows, q.getOrDefault("sort", "recent"));
        int total = rows.size();
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = 1;
        try { page = Integer.parseInt(q.getOrDefault("page", "1")); } catch (NumberFormatException ignored) { }
        page = Math.max(1, Math.min(page, pages));
        List<Map<String, Object>> items = rows.subList((page - 1) * PAGE_SIZE, Math.min(total, page * PAGE_SIZE));
        return Json.obj("items", new ArrayList<>(items), "total", total, "page", page, "pages", pages,
                "allIds", "1".equals(q.get("ids")) ? rows.stream().map(r -> r.get("id")).toList() : null);
    }

    /** All customers this user may see that pass the search and filters. Call while holding the store lock. */
    List<Customer> filtered(User actor, Map<String, String> q, Insights.Snapshot s) {
        String text = Text.clean(q.get("q"));
        String needle = text == null ? null : text.toLowerCase(Locale.ROOT);
        String digits = text == null ? "" : text.replaceAll("[^0-9]", "");
        Stage stage = Stage.parse(q.get("stage"));
        String owner = q.get("owner");
        if ("me".equals(owner)) owner = actor.id;
        String healthBand = q.get("health");
        String tag = Text.clean(q.get("tag"));
        String segment = Text.clean(q.get("segment"));
        String consent = q.get("consent");
        List<Customer> out = new ArrayList<>();
        for (Customer c : store.customers.values()) {
            if (!canSee(actor, c)) continue;
            if (stage != null && c.stage != stage) continue;
            if (owner != null && !owner.isBlank() && !owner.equals(c.ownerId)) continue;
            if (tag != null && !c.tags.contains(tag.toLowerCase(Locale.ROOT))) continue;
            if ("dnc".equals(consent) && !c.doNotCall) continue;
            if ("email".equals(consent) && !c.consentEmail) continue;
            if (segment != null) {
                Insights.Rfm r = s.rfm.get(c.id);
                if (r == null || !r.segment().equals(segment)) continue;
            }
            if (healthBand != null && !healthBand.isBlank()) {
                Insights.Health h = insights.health(c, s);
                if ("atrisk".equals(healthBand)) { if (!Insights.atRisk(c, h)) continue; }
                else if (!h.band().equals(healthBand)) continue;
            }
            if (needle != null && !matches(c, needle, digits)) continue;
            out.add(c);
        }
        return out;
    }

    private static boolean matches(Customer c, String needle, String digits) {
        if (c.id.toLowerCase(Locale.ROOT).equals(needle)) return true;
        for (String f : new String[]{c.name, c.company, c.email, c.city}) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        if (digits.length() >= 4 && c.phone != null && c.phone.contains(digits)) return true;
        for (String t : c.tags) if (t.equals(needle) || t.equals(needle.replace(' ', '-'))) return true;
        return false;
    }

    /** One line of the customer list. */
    Map<String, Object> row(Customer c, Insights.Snapshot s) {
        Insights.Health h = insights.health(c, s);
        Insights.Rfm r = s.rfm.get(c.id);
        long open = s.ticketsOf(c.id).stream().filter(t -> t.open()).count();
        return Json.obj("id", c.id, "name", c.name, "company", c.company, "city", c.city, "email", c.email,
                "phone", c.phone, "stage", c.stage, "stageLabel", c.stage.label, "ownerId", c.ownerId,
                "ownerName", store.userName(c.ownerId), "tags", c.tags, "health", h.score(), "band", h.band(),
                "atRisk", Insights.atRisk(c, h), "ltvPaise", s.lifetimeValue(c.id), "lastContact", s.lastContact(c.id),
                "segment", r == null ? null : r.segment(), "openTickets", open, "doNotCall", c.doNotCall,
                "consentEmail", c.consentEmail, "erased", c.erased, "createdAt", c.createdAt);
    }

    static void sortRows(List<Map<String, Object>> rows, String sort) {
        Comparator<Map<String, Object>> byName = Comparator.comparing(r -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT));
        Comparator<Map<String, Object>> cmp = switch (sort) {
            case "name" -> byName;
            case "health" -> Comparator.<Map<String, Object>>comparingLong(r -> ((Number) r.get("health")).longValue()).thenComparing(byName);
            case "health-desc" -> Comparator.<Map<String, Object>>comparingLong(r -> -((Number) r.get("health")).longValue()).thenComparing(byName);
            case "value" -> Comparator.<Map<String, Object>>comparingLong(r -> -((Number) r.get("ltvPaise")).longValue()).thenComparing(byName);
            case "new" -> Comparator.<Map<String, Object>>comparingLong(r -> -((Number) r.get("createdAt")).longValue()).thenComparing(byName);
            default -> Comparator.<Map<String, Object>>comparingLong(r -> -((Number) r.get("lastContact")).longValue()).thenComparing(byName);
        };
        rows.sort(cmp);
    }

    // ------------------------------------------------------------------ one customer, in full

    public Map<String, Object> detail(User actor, String id) {
        Insights.Snapshot s = insights.snapshot();
        synchronized (store) {
            Customer c = find(actor, id);
            Insights.Health h = insights.health(c, s);
            Map<String, Object> m = c.toJson();
            m.put("stageLabel", c.stage.label);
            m.put("ownerName", store.userName(c.ownerId));
            m.put("health", h.toJson());
            m.put("atRisk", Insights.atRisk(c, h));
            m.put("rfm", insights.rfmJson(s, c.id));
            m.put("ltvPaise", s.lifetimeValue(c.id));
            m.put("lastContact", s.lastContact(c.id));
            m.put("nextActions", insights.nextActions(c, s, h));

            List<Object> timeline = new ArrayList<>();
            s.interactionsOf(c.id).stream().sorted(Comparator.comparingLong((com.cecil.sambandh.model.Interaction i) -> i.at).reversed())
                    .forEach(i -> { Map<String, Object> x = i.toJson(); x.put("byName", store.userName(i.byUserId)); timeline.add(x); });
            m.put("timeline", timeline);

            List<Object> fus = new ArrayList<>();
            s.followUpsOf(c.id).stream()
                    .sorted(Comparator.comparing((com.cecil.sambandh.model.FollowUp f) -> f.done).thenComparing(f -> f.due))
                    .forEach(f -> { Map<String, Object> x = f.toJson(); x.put("assigneeName", store.userName(f.assigneeId));
                        x.put("overdue", f.overdue(s.today)); fus.add(x); });
            m.put("followUps", fus);

            List<Object> ords = new ArrayList<>();
            s.ordersOf(c.id).stream().sorted(Comparator.comparing((com.cecil.sambandh.model.Order o) -> o.date).reversed())
                    .forEach(o -> ords.add(o.toJson()));
            m.put("orders", ords);

            List<Object> tks = new ArrayList<>();
            s.ticketsOf(c.id).stream().sorted(Comparator.comparingLong((com.cecil.sambandh.model.Ticket t) -> t.createdAt).reversed())
                    .forEach(t -> { Map<String, Object> x = t.toJson(); x.put("deadline", t.deadline());
                        x.put("breached", t.breached(s.now)); tks.add(x); });
            m.put("tickets", tks);

            if (c.mergedInto == null) {
                List<Object> merged = new ArrayList<>();
                for (Customer o : store.customers.values()) if (c.id.equals(o.mergedInto)) merged.add(o.id);
                m.put("mergedFrom", merged);
            }
            m.put("canManage", actor.role.atLeast(Role.MANAGER));
            m.put("canErase", actor.role == Role.ADMIN && !c.erased);
            return m;
        }
    }

    // ------------------------------------------------------------------ export

    public static final List<String> CSV_HEADER = List.of("id", "name", "company", "email", "phone", "city", "type",
            "source", "stage", "owner", "tags", "consent_email", "do_not_call", "health", "rfm_segment",
            "lifetime_value_rupees", "last_contact", "created");

    /** CSV of every customer this user may see that passes the filters (Excel opens it). */
    public String exportCsv(User actor, Map<String, String> q) {
        Insights.Snapshot s = insights.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append(Csv.line(CSV_HEADER));
        int n = 0;
        synchronized (store) {
            for (Customer c : filtered(actor, q, s)) {
                Insights.Health h = insights.health(c, s);
                Insights.Rfm r = s.rfm.get(c.id);
                long last = s.lastContact(c.id);
                User owner = store.users.get(c.ownerId);
                sb.append(Csv.line(java.util.Arrays.asList(c.id, c.name, c.company, c.email, c.phone, c.city, c.type,
                        c.source, c.stage.label, owner == null ? "" : owner.username, String.join(";", c.tags),
                        c.consentEmail ? "yes" : "no", c.doNotCall ? "yes" : "no", h.score(),
                        r == null ? "" : r.segment(), String.format(Locale.ROOT, "%.2f", s.lifetimeValue(c.id) / 100.0),
                        last == 0 ? "" : LocalDate.ofInstant(Instant.ofEpochMilli(last), Clock.ZONE).toString(),
                        LocalDate.ofInstant(Instant.ofEpochMilli(c.createdAt), Clock.ZONE).toString())));
                n++;
            }
            store.log(actor.id, "customer.export", n + " customers", "CSV download");
            store.save("audit");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ privacy (DPDP Act 2023)

    /** Everything we hold about one person, as one JSON file (their "right to access" their data). */
    public Map<String, Object> privacyExport(User actor, String id) {
        Map<String, Object> d = detail(actor, id);
        synchronized (store) {
            store.log(actor.id, "privacy.export", id, "personal data file downloaded");
            store.save("audit");
        }
        Map<String, Object> out = Json.obj(
                "about", "All data Sambandh CRM holds about this customer, prepared on " + Clock.today()
                        + " (Digital Personal Data Protection Act, 2023 - right to access).",
                "customer", Json.obj("id", d.get("id"), "name", d.get("name"), "company", d.get("company"),
                        "email", d.get("email"), "phone", d.get("phone"), "city", d.get("city"), "type", d.get("type"),
                        "source", d.get("source"), "stage", d.get("stageLabel"), "tags", d.get("tags"), "notes", d.get("notes"),
                        "agreedToEmails", d.get("consentEmail"), "doNotCall", d.get("doNotCall"), "accountManager", d.get("ownerName")),
                "conversations", d.get("timeline"),
                "followUps", d.get("followUps"),
                "orders", d.get("orders"),
                "supportTickets", d.get("tickets"));
        return out;
    }

    /**
     * "Right to erasure": removes the person's personal data but keeps the money records (orders) that the
     * business must keep for accounts and tax. Cannot be undone. Admin only, and the ID must be typed to confirm.
     */
    public Customer erase(User actor, String id, String confirmId) {
        if (actor.role != Role.ADMIN) throw Problem.forbidden("Only an admin can erase personal data.");
        synchronized (store) {
            Customer c = find(actor, id);
            if (c.erased) throw Problem.conflict("Already erased.");
            if (confirmId == null || !confirmId.trim().equalsIgnoreCase(c.id))
                throw Problem.bad("Type the customer ID (" + c.id + ") to confirm.");
            c.name = "Erased customer " + c.id;
            c.company = null; c.email = null; c.phone = null; c.city = null; c.notes = null;
            c.tags = new ArrayList<>();
            c.consentEmail = false;
            c.doNotCall = true;     // never contact again
            c.stage = Stage.CHURNED;
            c.erased = true;
            c.updatedAt = Clock.millis();
            store.interactions.values().stream().filter(i -> c.id.equals(i.customerId)).forEach(i -> i.summary = "[erased]");
            store.followUps.values().stream().filter(f -> c.id.equals(f.customerId)).forEach(f -> { f.title = "[erased]"; f.done = true; });
            store.tickets.values().stream().filter(t -> c.id.equals(t.customerId)).forEach(t -> {
                t.subject = "[erased]";
                if (t.open()) { t.status = "RESOLVED"; t.resolvedAt = Clock.millis(); }
            });
            store.log(actor.id, "privacy.erase", c.id, "personal data erased; orders kept for accounts");
            store.saveAll();
            return c;
        }
    }

    public static String money(long paise) { return Money.format(paise); }
}
