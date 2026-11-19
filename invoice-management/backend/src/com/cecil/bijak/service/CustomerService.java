package com.cecil.bijak.service;

import com.cecil.bijak.core.Gstin;
import com.cecil.bijak.core.Tax;
import com.cecil.bijak.model.Customer;
import com.cecil.bijak.model.Item;
import com.cecil.bijak.model.User;
import com.cecil.bijak.store.DataStore;
import com.cecil.bijak.util.Clock;
import com.cecil.bijak.util.Json;
import com.cecil.bijak.util.Money;
import com.cecil.bijak.util.Text;

import java.util.Map;

/** Customers (buyers) and items (what we sell). Sales may add and change both. */
public class CustomerService {
    private final DataStore store;

    public CustomerService(DataStore store) { this.store = store; }

    public Customer save(User u, String id, Map<String, Object> b) {
        Access.writes(u);
        synchronized (store) {
            Customer c;
            if (id == null) {
                c = new Customer();
                c.createdAt = Clock.millis();
                c.createdBy = u.id;
            } else {
                c = store.customers.get(id);
                if (c == null) throw Problem.notFound("Customer");
            }
            String name = Text.clean(Json.str(b, "name"), 120);
            if (Text.blank(name) || name.length() < 2) throw Problem.bad("Type the customer's name.");
            String gstin = Gstin.normalize(Json.str(b, "gstin", ""));
            String state;
            if (!gstin.isEmpty()) {
                String p = Gstin.problem(gstin);
                if (p != null) throw Problem.bad("GSTIN: " + p);
                for (Customer o : store.customers.values())
                    if (o != c && gstin.equals(o.gstin)) throw Problem.conflict("This GSTIN is already used by " + o.name + ".");
                state = Gstin.stateOf(gstin);   // the state always comes from the GSTIN
            } else {
                state = Json.str(b, "state", "");
                if (!Gstin.STATES.containsKey(state)) throw Problem.bad("Pick the customer's state (needed for GST when there is no GSTIN).");
            }
            String email = Text.clean(Json.str(b, "email", ""), 120);
            if (!email.isEmpty() && !email.matches("[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}")) throw Problem.bad("That e-mail address does not look right.");
            int terms = (int) Json.num(b, "termsDays", 30);
            if (terms < 0 || terms > 180) throw Problem.bad("Payment terms must be 0 to 180 days.");
            long limit;
            try { limit = Money.parse(Json.str(b, "creditLimit", "0")); } catch (IllegalArgumentException e) { throw Problem.bad("Credit limit: " + e.getMessage()); }
            if (limit < 0) throw Problem.bad("The credit limit cannot be below zero.");
            int tds = (int) Math.round(Json.dbl(b, "tdsPct", 0) * 100);
            if (tds < 0 || tds > 1000) throw Problem.bad("TDS must be 0 to 10 %.");

            c.name = name;
            c.gstin = gstin.isEmpty() ? null : gstin;
            c.state = state;
            c.contact = Text.clean(Json.str(b, "contact", ""), 80);
            c.email = email;
            c.phone = Text.clean(Json.str(b, "phone", ""), 30);
            c.billingAddress = Text.block(Json.str(b, "billingAddress", ""), 400);
            c.shippingAddress = Text.block(Json.str(b, "shippingAddress", ""), 400);
            c.notes = Text.block(Json.str(b, "notes", ""), 1000);
            c.termsDays = terms;
            c.creditLimit = limit;
            c.tdsBp = tds;
            c.noReminders = Json.bool(b, "noReminders", false);
            c.active = Json.bool(b, "active", true);
            if (id == null) {
                c.id = store.nextId("C", 101);
                store.customers.put(c.id, c);
            }
            store.log(u.id, id == null ? "customer.add" : "customer.edit", c.id, c.name);
            store.save("customers", "audit");
            return c;
        }
    }

    public Item saveItem(User u, String id, Map<String, Object> b) {
        Access.writes(u);
        synchronized (store) {
            Item i;
            if (id == null) i = new Item();
            else {
                i = store.items.get(id);
                if (i == null) throw Problem.notFound("Item");
            }
            String name = Text.clean(Json.str(b, "name"), 120);
            if (Text.blank(name)) throw Problem.bad("Type the item's name.");
            boolean service = Json.bool(b, "service", false);
            String hsn = Text.clean(Json.str(b, "hsn", ""), 8).replace(" ", "");
            // HSN codes for goods have 4, 6 or 8 digits; SAC codes for services have 6 digits and start with 99
            if (service ? !hsn.matches("99\\d{4}") : !hsn.matches("\\d{4}|\\d{6}|\\d{8}"))
                throw Problem.bad(service ? "A SAC code (for a service) has 6 digits and starts with 99, like 998729." : "An HSN code has 4, 6 or 8 digits, like 4819.");
            int gst = (int) Json.num(b, "gstBp", 1800);
            if (!Tax.RATES.contains(gst)) throw Problem.bad("GST rate must be 0, 5, 12, 18 or 28 %.");
            long rate;
            try { rate = Money.parse(Json.str(b, "rate", "0")); } catch (IllegalArgumentException e) { throw Problem.bad("Rate: " + e.getMessage()); }
            if (rate < 0) throw Problem.bad("The rate cannot be below zero.");
            i.name = name;
            i.hsn = hsn;
            i.service = service;
            i.unit = Text.clean(Json.str(b, "unit", "Nos"), 12);
            i.rate = rate;
            i.gstBp = gst;
            i.active = Json.bool(b, "active", true);
            if (id == null) {
                i.id = store.nextId("P", 201);
                store.items.put(i.id, i);
            }
            store.log(u.id, id == null ? "item.add" : "item.edit", i.id, i.name);
            store.save("items", "audit");
            return i;
        }
    }
}
