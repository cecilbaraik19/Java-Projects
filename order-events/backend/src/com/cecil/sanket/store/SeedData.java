package com.cecil.sanket.store;

import com.cecil.sanket.Platform;
import com.cecil.sanket.model.Customer;
import com.cecil.sanket.model.DeadLetter;
import com.cecil.sanket.model.Order;
import com.cecil.sanket.model.OrderView;
import com.cecil.sanket.model.Product;
import com.cecil.sanket.model.Role;
import com.cecil.sanket.model.User;
import com.cecil.sanket.service.Problem;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.util.Passwords;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * The demo world: "Jharkhand Haat", a made-up online shop in Ranchi, its staff, 40 customers and 18 products.
 * With history = true it also plays about 3½ days of shopping THROUGH THE REAL SYSTEM (orders, outbox, broker,
 * every consumer) with the clock moved back in time, so the event log, the charts and the read model are real.
 *
 * Planted for the demo:
 *  - yesterday 14:05-14:25 the payment gateway was down. Those payments were dead-lettered; the admin redrove
 *    most of them 25 minutes later, but 3 are still OPEN (their orders are "stuck" at stock held);
 *  - 2 hours ago a gift note with "{{" poisoned the notifier: 1 dead letter still OPEN;
 *  - two evenings ago a flash sale sold out the Dokra brass horse, so later orders were rejected (out of stock);
 *  - about 10% of orders come from the old mobile app (OrderPlaced version 1).
 * All people, products and numbers are made up.
 */
public final class SeedData {
    private SeedData() { }

    static final String[][] CUSTOMERS = {
            {"Neha Kumari", "Ranchi"}, {"Arjun Singh", "Jamshedpur"}, {"Ritika Ekka", "Ranchi"}, {"Aman Mahto", "Dhanbad"},
            {"Sneha Bhagat", "Ranchi"}, {"Vikash Munda", "Bokaro"}, {"Pooja Toppo", "Hazaribagh"}, {"Rahul Sahu", "Ranchi"},
            {"Anjali Kujur", "Jamshedpur"}, {"Saurav Das", "Kolkata"}, {"Priyanka Jha", "Patna"}, {"Deepak Hansda", "Deoghar"},
            {"Kiran Lakra", "Ranchi"}, {"Sanjay Yadav", "Gaya"}, {"Nisha Minz", "Bokaro"}, {"Rohan Mishra", "Patna"},
            {"Ayesha Khan", "Ranchi"}, {"Manish Gope", "Jamshedpur"}, {"Shreya Sinha", "Dhanbad"}, {"Abhishek Tudu", "Deoghar"},
            {"Puja Kachhap", "Ranchi"}, {"Nitin Mohanty", "Bhubaneswar"}, {"Swati Ghosh", "Kolkata"}, {"Ravi Kerketta", "Hazaribagh"},
            {"Komal Soren", "Jamshedpur"}, {"Imran Ansari", "Ranchi"}, {"Divya Pandey", "Patna"}, {"Sunita Barla", "Ranchi"},
            {"Gaurav Tirkey", "Bokaro"}, {"Megha Rao", "Bhubaneswar"}, {"Ajay Oraon", "Ranchi"}, {"Tanya Bose", "Kolkata"},
            {"Pankaj Mandal", "Dhanbad"}, {"Rekha Kisku", "Deoghar"}, {"Sahil Kumar", "Gaya"}, {"Jyoti Xalxo", "Ranchi"},
            {"Harsh Agarwal", "Jamshedpur"}, {"Mamta Horo", "Hazaribagh"}, {"Kunal Dutta", "Kolkata"}, {"Seema Lohra", "Ranchi"}};

    static final Object[][] PRODUCTS = {
            {"EAR-BUDS", "Wireless earbuds", "Electronics", 1499, 60, 8, "earbuds"},
            {"PWR-BANK", "Power bank 10000 mAh", "Electronics", 1199, 55, 8, "battery"},
            {"USB-C-CHG", "USB-C fast charger", "Electronics", 649, 70, 10, "plug"},
            {"PHONE-CVR", "Phone cover, Sohrai art print", "Electronics", 299, 80, 10, "phone"},
            {"LAPTOP-SLV", "Laptop sleeve 15 inch", "Electronics", 699, 30, 5, "laptop"},
            {"LED-PACK", "LED bulb pack of 4", "Home", 399, 60, 8, "bulb"},
            {"DESK-FAN", "Desk fan", "Home", 1899, 18, 4, "fan"},
            {"STEEL-BTL", "Steel water bottle 1 L", "Home", 549, 65, 10, "bottle"},
            {"JUTE-BAG", "Jute shopping bag", "Home", 199, 90, 12, "bag"},
            {"SAL-PLATES", "Sal leaf plates, pack of 50", "Home", 249, 40, 8, "leaf"},
            {"BAMBOO-LAMP", "Bamboo table lamp", "Crafts", 1250, 25, 5, "lamp"},
            {"DK-HORSE", "Dokra brass horse", "Crafts", 2450, 14, 3, "horse"},
            {"SOH-MUG", "Sohrai painted mug", "Crafts", 349, 50, 8, "mug"},
            {"TUSSAR-STOLE", "Tussar silk stole", "Clothing", 1890, 30, 5, "stole"},
            {"COTTON-KURTA", "Cotton kurta", "Clothing", 799, 45, 8, "kurta"},
            {"BACKPACK", "College backpack", "Bags", 1299, 28, 5, "backpack"},
            {"NOTE-SET", "Notebook set of 5", "Stationery", 260, 70, 10, "book"},
            {"MAHUA-HONEY", "Forest honey 500 g", "Food", 420, 35, 6, "jar"}};

    public static void load(Platform p, boolean history) {
        DataStore store = p.store;
        synchronized (store) {
            user(store, "admin", "Sunil Tirkey", Role.ADMIN, "admin123", null);
            user(store, "priya", "Priya Lakra", Role.STAFF, "staff123", null);
            user(store, "manoj", "Manoj Oraon", Role.STAFF, "staff123", null);
            for (int i = 0; i < CUSTOMERS.length; i++) {
                Customer c = new Customer();
                c.id = "C-" + (101 + i);
                c.name = CUSTOMERS[i][0];
                c.city = CUSTOMERS[i][1];
                c.pincode = String.valueOf(800000 + Math.abs(c.city.hashCode()) % 60000);
                c.phone = "98" + String.format("%08d", Math.abs((c.name + "#ph").hashCode()) % 100_000_000);
                store.customers.put(c.id, c);
            }
            user(store, "neha", "Neha Kumari", Role.CUSTOMER, "customer123", "C-101");
            user(store, "arjun", "Arjun Singh", Role.CUSTOMER, "customer123", "C-102");
            for (Object[] r : PRODUCTS) {
                Product pr = new Product();
                pr.sku = (String) r[0];
                pr.name = (String) r[1];
                pr.category = (String) r[2];
                pr.price = ((Integer) r[3]).longValue();
                pr.stock = (Integer) r[4];
                pr.reorderLevel = (Integer) r[5];
                pr.icon = (String) r[6];
                store.products.put(pr.sku, pr);
            }
            store.meta.put("sim.ordersPerMin", 6L);
            store.meta.put("sim.declinePct", 5L);
            store.meta.put("sim.oldAppPct", 10L);
            store.meta.put("sim.autoShip", true);
            store.log(null, "setup", "demo", "demo shop created (all people and numbers are made up)");
        }
        if (history) history(p);
        p.dispatcher.saveState();
        store.saveAll();
        p.broker.flush();
    }

    private static void user(DataStore s, String username, String name, Role role, String password, String customerId) {
        User u = new User();
        u.id = s.nextId("U", 1);
        u.username = username;
        u.name = name;
        u.role = role;
        u.passwordHash = Passwords.hash(password);
        u.customerId = customerId;
        u.createdAt = Clock.millis();
        s.users.put(u.id, u);
    }

    // ------------------------------------------------------------------ playing the past

    // kind: 0 place, 1 cancel, 2 dispatch, 3 deliver, 4 restock, 5 outage on, 6 outage off, 7 redrive, 8 poison, 9 flash on, 10 flash off
    private record Action(long at, int kind, String orderId) { }

    private static void history(Platform p) {
        DataStore store = p.store;
        Instant previous = Clock.fixedOrNull();
        long now = Clock.millis();
        long end = now - 12 * 60_000L;
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now), Clock.ZONE);
        long start = today.minusDays(3).atStartOfDay(Clock.ZONE).toInstant().toEpochMilli();
        long yesterday = today.minusDays(1).atStartOfDay(Clock.ZONE).toInstant().toEpochMilli();
        Random rnd = new Random(20261111L);
        store.batch = true;
        p.broker.batch = true;
        p.dispatcher.hopDelayMs = 40;
        PriorityQueue<Action> queue = new PriorityQueue<>((a, b) -> Long.compare(a.at, b.at));
        double[] curve = {0.15, 0.1, 0.1, 0.1, 0.1, 0.15, 0.3, 0.6, 0.6, 0.8, 1.0, 1.0, 1.0, 1.0, 0.9, 0.9, 0.9, 0.9, 1.4, 1.5, 1.5, 1.4, 1.0, 0.5};
        for (long h = start; h < end; h += 3_600_000L) {
            int hour = (int) ((h - start) / 3_600_000L % 24);
            double expected = 7 * curve[hour];
            int n = (int) expected + (rnd.nextDouble() < expected - (int) expected ? 1 : 0);
            for (int i = 0; i < n; i++) {
                long at = h + rnd.nextInt(3_600_000);
                if (at < end) queue.add(new Action(at, 0, null));
            }
        }
        for (long d = start + 6 * 3_600_000L; d < end; d += 86_400_000L) queue.add(new Action(d, 4, null));
        long outage = yesterday + (14 * 60 + 5) * 60_000L;
        if (outage < end) {
            queue.add(new Action(outage, 5, null));
            queue.add(new Action(outage + 20 * 60_000L, 6, null));
            queue.add(new Action(outage + 45 * 60_000L, 7, null));
            // a few extra orders while the gateway was down
            for (int i = 0; i < 6; i++) queue.add(new Action(outage + 60_000L + rnd.nextInt(18 * 60_000), 0, null));
        }
        queue.add(new Action(now - 2 * 3_600_000L, 8, null));
        // a flash sale two evenings ago: the Dokra horse sells out and later orders are rejected
        long flash = today.minusDays(2).atStartOfDay(Clock.ZONE).toInstant().toEpochMilli() + 19 * 3_600_000L;
        if (flash + 40 * 60_000L < end) {
            queue.add(new Action(flash, 9, null));
            queue.add(new Action(flash + 30 * 60_000L, 10, null));
            for (int i = 0; i < 45; i++) queue.add(new Action(flash + 30_000L + rnd.nextInt(29 * 60_000), 0, null));
        }

        User admin = store.userByName("admin");
        Clock.fix(Instant.ofEpochMilli(start));
        while (!queue.isEmpty()) {
            Action a = queue.poll();
            if (a.at > Clock.millis()) Clock.fix(Instant.ofEpochMilli(a.at));
            try {
                switch (a.kind) {
                    case 0 -> {
                        Order o = p.sim.randomOrder();
                        settle(p, rnd);
                        OrderView v = store.views.get(o.id);
                        if (v != null && "CONFIRMED".equals(v.status)) {
                            if (rnd.nextDouble() < 0.04) queue.add(new Action(Clock.millis() + 120_000 + rnd.nextInt(30 * 60_000), 1, o.id));
                            long dispatchAt = Clock.millis() + 3_600_000L + rnd.nextInt(13 * 3_600_000);
                            if (dispatchAt < end - 20 * 60_000L) queue.add(new Action(dispatchAt, 2, o.id));
                        }
                    }
                    case 1 -> { p.orders.cancel(null, a.orderId); settle(p, rnd); }
                    case 2 -> {
                        p.shipping.dispatch(null, a.orderId);
                        settle(p, rnd);
                        long deliverAt = Clock.millis() + 18 * 3_600_000L + rnd.nextInt(40 * 3_600_000);
                        if (deliverAt < end) queue.add(new Action(deliverAt, 3, a.orderId));
                    }
                    case 3 -> { p.shipping.deliver(null, a.orderId); settle(p, rnd); }
                    case 4 -> {
                        for (Product pr : new ArrayList<>(store.products.values()))
                            if (pr.sku.equals("DK-HORSE") ? pr.available() < 12 : pr.available() < 30)
                                p.inventory.restock(null, pr.sku, (pr.sku.equals("DK-HORSE") ? 16 : 60) - pr.available());
                        settle(p, rnd);
                    }
                    case 5 -> p.payment.gatewayDown = true;
                    case 6 -> p.payment.gatewayDown = false;
                    case 7 -> {
                        List<DeadLetter> open = new ArrayList<>();
                        for (DeadLetter d : store.deadLetters.values()) if ("OPEN".equals(d.state) && d.group.equals("payment")) open.add(d);
                        for (int i = 0; i < open.size() - 3; i++) p.dispatcher.redrive(admin, open.get(i).id);
                        settle(p, rnd);
                    }
                    case 9 -> p.sim.historyFlash = true;
                    case 10 -> p.sim.historyFlash = false;
                    case 8 -> {
                        Customer c = store.customers.get("C-117");
                        p.orders.placeFor(c.id, List.<String[]>of(new String[]{"SOH-MUG", "2"}), "UPI", "WEB", "Happy {{name} birthday, Ammi!", null);
                        settle(p, rnd);
                    }
                    default -> { }
                }
            } catch (Problem ignored) {
                // e.g. a cancel that came too late - just like real life
            }
        }
        Clock.fix(previous);
        p.dispatcher.hopDelayMs = 0;
        p.sim.historyFlash = false;
        store.batch = false;
        p.broker.batch = false;
    }

    /** Lets the system process everything, moving the frozen clock 15-140 ms per step so each hop takes a little time. */
    private static void settle(Platform p, Random rnd) {
        for (int i = 0; i < 400; i++) {
            int r = p.relay.tick();
            int n = p.dispatcher.tick(Clock.millis(), 1);
            if (r == 0 && n == 0) {
                if (p.dispatcher.anyRetryWaiting()) Clock.advanceMillis(1000);
                else if (p.dispatcher.totalLag() > 0) Clock.advanceMillis(10 + rnd.nextInt(60));
                else break;
            } else {
                Clock.advanceMillis(15 + rnd.nextInt(125));
            }
        }
    }
}
