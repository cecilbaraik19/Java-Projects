package com.cecil.pipeline.service;

import com.cecil.pipeline.model.Account;
import com.cecil.pipeline.model.Contact;
import com.cecil.pipeline.model.Deal;
import com.cecil.pipeline.model.User;
import com.cecil.pipeline.store.DataStore;
import com.cecil.pipeline.util.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Companies (accounts) and the people at them (contacts). The whole sales team can see and edit these. */
public class AccountService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^[0-9+()\\-\\s]{6,20}$");

    private final DataStore store;

    public AccountService(DataStore store) {
        this.store = store;
    }

    // ------------------------------------------------------------------ accounts

    public List<Map<String, Object>> listAccounts(String q) {
        synchronized (store) {
            String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
            List<Map<String, Object>> out = new ArrayList<>();
            store.accounts.stream()
                .filter(a -> needle.isEmpty() || (a.name + " " + a.city + " " + a.industry).toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)))
                .forEach(a -> out.add(accountSummary(a)));
            return out;
        }
    }

    private Map<String, Object> accountSummary(Account a) {
        Map<String, Object> m = a.toMap();
        long open = 0, openValue = 0, wonValue = 0;
        int contacts = 0;
        for (Deal d : store.deals) {
            if (!a.id.equals(d.accountId)) continue;
            if (d.isOpen()) { open++; openValue += d.value; }
            else if (d.stage == com.cecil.pipeline.model.Enums.Stage.WON) wonValue += d.value;
        }
        for (Contact c : store.contacts) if (a.id.equals(c.accountId)) contacts++;
        m.put("openDeals", open);
        m.put("openValue", openValue);
        m.put("wonValue", wonValue);
        m.put("contactCount", contacts);
        User o = store.user(a.owner);
        m.put("ownerName", o == null ? null : o.displayName);
        return m;
    }

    public Map<String, Object> getAccount(String id) {
        synchronized (store) {
            Account a = store.account(id);
            if (a == null) throw ServiceException.notFound("Account");
            Map<String, Object> m = accountSummary(a);
            List<Object> cs = new ArrayList<>();
            for (Contact c : store.contacts) if (id.equals(c.accountId)) cs.add(c.toMap());
            m.put("contacts", cs);
            return m;
        }
    }

    public Account createAccount(User actor, Map<String, Object> body) {
        synchronized (store) {
            Account a = new Account();
            a.id = store.nextId("A");
            a.createdAt = Instant.now().toString();
            a.owner = actor.username;
            applyAccount(a, body, true);
            a.updatedAt = a.createdAt;
            store.accounts.add(a);
            store.save();
            return a;
        }
    }

    public Account updateAccount(User actor, String id, Map<String, Object> body) {
        synchronized (store) {
            Account a = store.account(id);
            if (a == null) throw ServiceException.notFound("Account");
            applyAccount(a, body, false);
            a.updatedAt = Instant.now().toString();
            store.save();
            return a;
        }
    }

    public void deleteAccount(User actor, String id) {
        if (!actor.canEditAll()) throw ServiceException.forbidden("Only a manager or admin can delete accounts");
        synchronized (store) {
            Account a = store.account(id);
            if (a == null) throw ServiceException.notFound("Account");
            boolean hasDeals = store.deals.stream().anyMatch(d -> id.equals(d.accountId));
            if (hasDeals) throw ServiceException.conflict("This account still has deals. Delete or move them first.");
            store.contacts.removeIf(c -> id.equals(c.accountId));
            store.accounts.remove(a);
            store.save();
        }
    }

    private void applyAccount(Account a, Map<String, Object> body, boolean creating) {
        if (creating || body.containsKey("name")) {
            String name = Json.str(body, "name", "").trim();
            if (name.isEmpty()) throw ServiceException.bad("Company name is required");
            if (name.length() > 100) throw ServiceException.bad("Company name is too long");
            for (Account other : store.accounts) {
                if (other != a && other.name.equalsIgnoreCase(name)) throw ServiceException.conflict("An account with this name already exists");
            }
            a.name = name;
        }
        if (body.containsKey("industry")) a.industry = limit(Json.str(body, "industry", "Other"), 40, "Industry");
        if (a.industry == null || a.industry.isBlank()) a.industry = "Other";
        if (body.containsKey("website")) {
            String w = Json.str(body, "website", "").trim();
            if (!w.isEmpty() && !w.matches("^(https?://)?[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/\\S*)?$")) throw ServiceException.bad("Website does not look right");
            a.website = w;
        }
        if (body.containsKey("phone")) a.phone = phone(Json.str(body, "phone", ""));
        if (body.containsKey("city")) a.city = limit(Json.str(body, "city", ""), 60, "City");
        if (body.containsKey("size")) a.size = limit(Json.str(body, "size", ""), 20, "Size");
        if (body.containsKey("notes")) a.notes = limit(Json.str(body, "notes", ""), 2000, "Notes");
        if (body.containsKey("owner")) {
            String o = Json.str(body, "owner");
            if (o != null && !o.isBlank()) {
                if (store.user(o) == null) throw ServiceException.bad("Unknown account owner");
                a.owner = store.user(o).username;
            }
        }
    }

    // ------------------------------------------------------------------ contacts

    public List<Map<String, Object>> listContacts(String accountId, String q) {
        synchronized (store) {
            String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Contact c : store.contacts) {
                if (accountId != null && !accountId.isBlank() && !accountId.equals(c.accountId)) continue;
                Account a = store.account(c.accountId);
                String hay = (c.name + " " + c.email + " " + c.title + " " + (a == null ? "" : a.name)).toLowerCase(Locale.ROOT);
                if (!needle.isEmpty() && !hay.contains(needle)) continue;
                Map<String, Object> m = c.toMap();
                m.put("accountName", a == null ? null : a.name);
                out.add(m);
            }
            out.sort(Comparator.comparing(m -> String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));
            return out;
        }
    }

    public Contact createContact(User actor, Map<String, Object> body) {
        synchronized (store) {
            Contact c = new Contact();
            c.id = store.nextId("C");
            c.createdAt = Instant.now().toString();
            applyContact(c, body, true);
            c.updatedAt = c.createdAt;
            store.contacts.add(c);
            store.save();
            return c;
        }
    }

    public Contact updateContact(User actor, String id, Map<String, Object> body) {
        synchronized (store) {
            Contact c = store.contact(id);
            if (c == null) throw ServiceException.notFound("Contact");
            applyContact(c, body, false);
            c.updatedAt = Instant.now().toString();
            store.save();
            return c;
        }
    }

    public void deleteContact(User actor, String id) {
        synchronized (store) {
            Contact c = store.contact(id);
            if (c == null) throw ServiceException.notFound("Contact");
            for (Deal d : store.deals) if (id.equals(d.contactId)) d.contactId = null;
            store.contacts.remove(c);
            store.save();
        }
    }

    private void applyContact(Contact c, Map<String, Object> body, boolean creating) {
        if (creating || body.containsKey("accountId")) {
            String acc = Json.str(body, "accountId");
            if (acc == null || store.account(acc) == null) throw ServiceException.bad("Choose the company this person works at");
            c.accountId = acc;
        }
        if (creating || body.containsKey("name")) {
            String n = Json.str(body, "name", "").trim();
            if (n.isEmpty()) throw ServiceException.bad("Contact name is required");
            c.name = limit(n, 80, "Name");
        }
        if (body.containsKey("title")) c.title = limit(Json.str(body, "title", ""), 80, "Job title");
        if (body.containsKey("email")) {
            String e = Json.str(body, "email", "").trim();
            if (!e.isEmpty() && !EMAIL.matcher(e).matches()) throw ServiceException.bad("Email address does not look right");
            c.email = e;
        }
        if (body.containsKey("phone")) c.phone = phone(Json.str(body, "phone", ""));
        if (body.containsKey("decisionMaker")) c.decisionMaker = Json.bool(body, "decisionMaker", false);
    }

    // ------------------------------------------------------------------ small helpers

    static String limit(String s, int max, String field) {
        String v = s == null ? "" : s.trim();
        if (v.length() > max) throw ServiceException.bad(field + " is too long (max " + max + " characters)");
        return v;
    }

    private static String phone(String p) {
        String v = p == null ? "" : p.trim();
        if (!v.isEmpty() && !PHONE.matcher(v).matches()) throw ServiceException.bad("Phone number does not look right");
        return v;
    }
}
