package com.cecil.shop.seed;

import com.cecil.shop.common.Json;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Demo data for the first run. (Demo only: each service takes ONLY its own part from here and
 * saves it in its own database. In a real company each team would load its own data.)
 *
 * Shop: "Bandar Bazaar" - a made-up online store. All people, brands and phone numbers are made up.
 */
public final class SeedData {
    private SeedData() {}

    public static final String STORE_NAME = "Bandar Bazaar";
    /** Raise this when the demo data changes. Services with older data start fresh (version 2 = real product photos). */
    public static final long VERSION = 2;

    /** id, sku, name, category, brand, price Rs, mrp Rs, icon, stock, rating x10, description */
    private static final Object[][] PRODUCTS = {
        {1, "ELC-HP-01", "Nimbus Wireless Headphones", "Electronics", "Sonora", 2499, 3999, "headphones", 34, 44, "Over-ear Bluetooth headphones with 40 hours of battery and soft memory-foam cushions."},
        {2, "ELC-PH-02", "Pulse 5G Smartphone (128 GB)", "Electronics", "Volt", 17999, 21999, "phone", 18, 43, "6.6 inch display, 50 MP camera and a 5000 mAh battery that lasts all day."},
        {3, "ELC-WT-03", "Stride Fitness Watch", "Electronics", "Volt", 3299, 4999, "watch", 26, 41, "Heart-rate, steps and sleep tracking, with 10 days of battery."},
        {4, "ELC-LP-04", "Featherbook 14 Laptop", "Electronics", "Arcline", 48990, 56990, "laptop", 7, 45, "14 inch laptop, 16 GB RAM, 512 GB SSD. Light enough to carry to college every day."},
        {5, "ELC-KB-05", "Clack Mechanical Keyboard", "Electronics", "Arcline", 3499, 4499, "keyboard", 22, 46, "Tactile switches, white backlight and a detachable USB-C cable."},
        {6, "ELC-SP-06", "Boom Mini Speaker", "Electronics", "Sonora", 1799, 2499, "speaker", 40, 42, "Pocket speaker with deep bass. Splash-proof, 12 hours of music."},
        {7, "ELC-CM-07", "Snapshot Action Camera", "Electronics", "Lenzo", 8999, 11999, "camera", 4, 40, "4K video, waterproof case included. Made for treks and bike rides."},
        {8, "FSH-TS-08", "Monsoon Cotton T-Shirt", "Fashion", "Kapda Co.", 499, 899, "tshirt", 120, 41, "Soft 100% cotton round-neck tee. Pre-washed, so it will not shrink."},
        {9, "FSH-SH-09", "Trail Runner Shoes", "Fashion", "Stride", 2899, 3999, "shoe", 35, 43, "Light running shoes with a grippy sole for roads and trails."},
        {10, "FSH-BP-10", "Campus Backpack 28L", "Fashion", "Packwell", 1299, 1999, "backpack", 48, 45, "Laptop sleeve, bottle pocket and a rain cover tucked in the base."},
        {11, "FSH-SG-11", "Horizon Sunglasses", "Fashion", "Lenzo", 999, 1799, "sunglasses", 60, 39, "UV400 polarised lenses with a light, bendable frame."},
        {12, "FSH-CP-12", "Desert Cap", "Fashion", "Kapda Co.", 399, 599, "cap", 75, 40, "Cotton cap with an adjustable strap. One size fits most."},
        {13, "FSH-SH-13", "Indigo Cotton Shirt", "Fashion", "Kapda Co.", 899, 1499, "tshirt", 3, 44, "Soft indigo cotton shirt with a regular fit. Easy to iron."},
        {14, "HOM-MG-14", "Chai Time Mug Set (2)", "Home & Kitchen", "Mitti", 449, 699, "mug", 90, 46, "Two stoneware mugs, 300 ml each. Microwave and dishwasher safe."},
        {15, "HOM-LP-15", "Study Desk Lamp", "Home & Kitchen", "Ujala", 1149, 1699, "lamp", 30, 42, "LED lamp with 3 colour modes and a bendable neck. USB powered."},
        {16, "HOM-PL-16", "Money Plant in Ceramic Pot", "Home & Kitchen", "Hara Bhara", 349, 499, "plant", 55, 43, "Easy indoor plant that grows in low light. Pot included."},
        {17, "HOM-BT-17", "Steel Water Bottle 1L", "Home & Kitchen", "Mitti", 599, 899, "bottle", 85, 45, "Double-wall steel keeps water cold for 24 hours and hot for 12."},
        {18, "HOM-CW-18", "Steel Cookware Set (3 pots)", "Home & Kitchen", "Rasoi", 1699, 2299, "pot", 20, 44, "Three stainless steel pots with lids. Works on gas and induction."},
        {19, "HOM-SP-19", "Spice Trio Pack (Haldi, Mirch, Dhania)", "Home & Kitchen", "Rasoi", 549, 799, "jar", 2, 47, "Turmeric, red chilli and coriander powder, 200 g each. Freshly ground."},
        {20, "BOK-BK-20", "Clean Code Habits", "Books", "Lighthouse Press", 399, 550, "book", 40, 46, "Short, practical lessons on writing code that other people can read."},
        {21, "BOK-BK-21", "Microservices in Plain Words", "Books", "Lighthouse Press", 549, 699, "book", 28, 48, "Services, events, gateways and sagas explained with everyday examples."},
        {22, "BOK-BK-22", "The Networking Notebook", "Books", "Packet Books", 449, 599, "book", 33, 44, "Subnetting, routing and switching, with practice questions."},
        {23, "BOK-BK-23", "Stories from the Ghats", "Books", "Western Leaf", 299, 399, "book", 50, 42, "Twelve short stories set in small hill towns."},
        {24, "SPT-YM-24", "Grip Yoga Mat 6mm", "Sports & Fitness", "Flex", 799, 1299, "yoga", 44, 43, "Non-slip mat with a carry strap."},
        {25, "SPT-DB-25", "Hex Dumbbells 2 x 5kg", "Sports & Fitness", "Flex", 1899, 2499, "dumbbell", 15, 44, "Rubber-coated dumbbells that will not roll away."},
        {26, "SPT-BL-26", "Street Football Size 5", "Sports & Fitness", "Goalpost", 649, 899, "ball", 38, 41, "Machine-stitched ball for hard ground."},
        {27, "SPT-BD-27", "Badminton Racquet Set", "Sports & Fitness", "Goalpost", 2199, 2999, "bat", 12, 42, "Two light racquets with a shuttlecock tube and a carry cover."},
        {28, "BTY-PF-28", "Sandal & Rain Perfume 50ml", "Beauty", "Attar House", 899, 1299, "perfume", 25, 40, "Warm sandalwood with a fresh rain note. Lasts all day."},
        {29, "BTY-SC-29", "Aloe Face Gel 100g", "Beauty", "Hara Bhara", 249, 349, "jar", 70, 43, "Cooling aloe gel for everyday use."},
        {30, "BTY-SC-30", "Coconut Hair Oil 200ml", "Beauty", "Hara Bhara", 199, 260, "bottle", 95, 45, "Cold-pressed coconut oil. Nothing added."},
    };

    /**
     * Real product photos from Unsplash (free to use under the Unsplash License).
     * download-photos.bat saves them into frontend/img/products/, so the shop shows them even offline.
     * If a photo file is missing, the shop shows the drawn icon instead.
     */
    public static final String[][] PHOTOS = {
        {"ELC-HP-01", "photo-1553774661-0651f14b2da4"}, {"ELC-PH-02", "photo-1758770478125-4850521fd941"},
        {"ELC-WT-03", "photo-1434494745656-1aea7daa8f6f"}, {"ELC-LP-04", "photo-1587560699334-cc4ff634909a"},
        {"ELC-KB-05", "photo-1618384887929-16ec33fab9ef"}, {"ELC-SP-06", "photo-1549400854-b4300f444934"},
        {"ELC-CM-07", "photo-1562878671-b3efe27953b9"}, {"FSH-TS-08", "photo-1581655353564-df123a1eb820"},
        {"FSH-SH-09", "photo-1560769629-975ec94e6a86"}, {"FSH-BP-10", "photo-1622560480605-d83c853bc5c3"},
        {"FSH-SG-11", "photo-1584036553516-bf83210aa16c"}, {"FSH-CP-12", "photo-1691256676359-20e5c6d4bc92"},
        {"FSH-SH-13", "photo-1740711152088-88a009e877bb"}, {"HOM-MG-14", "photo-1491720731493-223f97d92c21"},
        {"HOM-LP-15", "photo-1585597647877-6eaa01bf9a05"}, {"HOM-PL-16", "photo-1609061801093-1b7a8a574c69"},
        {"HOM-BT-17", "photo-1486381950565-e3ada9f1a370"}, {"HOM-CW-18", "photo-1580929753603-10519c6e480a"},
        {"HOM-SP-19", "photo-1623569559000-c3031217d717"}, {"BOK-BK-20", "photo-1497633762265-9d179a990aa6"},
        {"BOK-BK-21", "photo-1550399105-c4db5fb85c18"}, {"BOK-BK-22", "photo-1556566952-11eff3d06ed4"},
        {"BOK-BK-23", "photo-1543002588-bfa74002ed7e"}, {"SPT-YM-24", "photo-1646239646963-b0b9be56d6b5"},
        {"SPT-DB-25", "photo-1703668984128-b506579acdd2"}, {"SPT-BL-26", "photo-1520804588595-ac2328f38c4a"},
        {"SPT-BD-27", "photo-1521537634581-0dced2fee2ef"}, {"BTY-PF-28", "photo-1458538977777-0549b2370168"},
        {"BTY-SC-29", "photo-1509423350716-97f9360b4e09"}, {"BTY-SC-30", "photo-1560769680-ba2f3767c785"},
    };

    public static String imagePath(String sku) { return "/static/img/products/" + sku.toLowerCase() + ".jpg"; }

    public static List<Map<String, Object>> products(long now) {
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (Object[] p : PRODUCTS) {
            int rating = (int) p[9];
            out.add(Json.obj("id", (long) (int) p[0], "sku", p[1], "name", p[2], "category", p[3], "brand", p[4],
                    "price", (long) (int) p[5] * 100, "mrp", (long) (int) p[6] * 100, "icon", p[7],
                    "rating", rating / 10.0, "reviews", (long) (40 + ((int) p[0] * 37) % 400),
                    "description", p[10], "image", imagePath((String) p[1]), "active", true, "createdAt", now - (long) (60 - n++) * 86_400_000L));
        }
        return out;
    }

    /** sku -> starting stock */
    public static List<Map<String, Object>> stock() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] p : PRODUCTS) {
            int onHand = (int) p[8];
            out.add(Json.obj("id", (long) (int) p[0], "sku", p[1], "productId", (long) (int) p[0], "name", p[2],
                    "onHand", (long) onHand, "reserved", 0L, "lowAt", (long) Math.max(3, Math.min(10, onHand / 5))));
        }
        return out;
    }

    /** id, username, name, email, role, password, wallet Rs */
    private static final Object[][] USERS = {
        {1, "admin", "Store Admin", "admin@bandarbazaar.test", "ADMIN", "admin123", 0},
        {2, "riya", "Riya Sharma", "riya@example.test", "CUSTOMER", "shop123", 75000},
        {3, "arjun", "Arjun Mehta", "arjun@example.test", "CUSTOMER", "shop123", 1200},
        {4, "meera", "Meera Kujur", "meera@example.test", "CUSTOMER", "shop123", 40000},
        {5, "kabir", "Kabir Oraon", "kabir@example.test", "CUSTOMER", "shop123", 25000},
    };

    public static List<Object[]> users() { return List.of(USERS); }

    public static List<Map<String, Object>> wallets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] u : USERS) {
            out.add(Json.obj("id", (long) (int) u[0], "userId", (long) (int) u[0], "name", u[2], "balance", (long) (int) u[6] * 100));
        }
        return out;
    }

    private static final String[][] ADDRESSES = {
        {"Riya Sharma", "98350 11223", "14 Lake View Road, Morabadi", "Ranchi", "834008"},
        {"Meera Kujur", "70040 55667", "B-2 Green Park, Bistupur", "Jamshedpur", "831001"},
        {"Kabir Oraon", "91020 88990", "House 7, Hirapur", "Dhanbad", "826001"},
    };

    /**
     * Past orders, so the admin pages are not empty. Dates are counted back from today.
     * Each order also needs a payment (payment service) - see payments().
     */
    public static List<Map<String, Object>> orders(long now) {
        List<Map<String, Object>> products = products(now);
        long[] userIds = {2, 4, 5};
        String[] userNames = {"Riya Sharma", "Meera Kujur", "Kabir Oraon"};
        List<Map<String, Object>> out = new ArrayList<>();
        long seed = 7;
        int year = LocalDate.now(ZoneId.systemDefault()).getYear();
        for (int i = 0; i < 26; i++) {
            seed = (seed * 1103515245 + 12345) & 0x7fffffffL;
            int u = (int) (seed % 3);
            int daysAgo = 21 - (i * 21 / 26);
            long at = now - daysAgo * 86_400_000L - (seed % 36_000) * 1000L;
            List<Object> items = new ArrayList<>();
            int lines = 1 + (int) (seed % 3);
            long subtotal = 0;
            for (int l = 0; l < lines; l++) {
                seed = (seed * 1103515245 + 12345) & 0x7fffffffL;
                Map<String, Object> p = products.get((int) (seed % products.size()));
                if (Json.lng(p, "price") > 2_000_000) continue;
                boolean dup = false;
                for (Object o : items) if (Json.lng(Json.map(o), "productId") == Json.lng(p, "id")) dup = true;
                if (dup) continue;
                long qty = 1 + (seed % 2);
                long line = Json.lng(p, "price") * qty;
                subtotal += line;
                items.add(Json.obj("productId", p.get("id"), "sku", p.get("sku"), "name", p.get("name"), "icon", p.get("icon"), "image", p.get("image"),
                        "price", p.get("price"), "qty", qty, "lineTotal", line));
            }
            if (items.isEmpty()) continue;
            long shipping = subtotal >= 99_900 ? 0 : 4_900;
            String status;
            if (i == 9 || i == 17) status = "CANCELLED";
            else if (daysAgo >= 6) status = "DELIVERED";
            else if (daysAgo >= 3) status = "SHIPPED";
            else if (daysAgo >= 1) status = "PACKED";
            else status = "CONFIRMED";
            String[] a = ADDRESSES[u];
            long id = out.size() + 1;
            List<Object> history = new ArrayList<>();
            history.add(Json.obj("status", "CONFIRMED", "at", at + 900, "by", "order-service"));
            if (!status.equals("CONFIRMED") && !status.equals("CANCELLED")) history.add(Json.obj("status", "PACKED", "at", at + 6 * 3_600_000L, "by", "Store Admin"));
            if (status.equals("SHIPPED") || status.equals("DELIVERED")) history.add(Json.obj("status", "SHIPPED", "at", at + 26 * 3_600_000L, "by", "Store Admin"));
            if (status.equals("DELIVERED")) history.add(Json.obj("status", "DELIVERED", "at", at + 4 * 86_400_000L, "by", "Store Admin"));
            if (status.equals("CANCELLED")) history.add(Json.obj("status", "CANCELLED", "at", at + 2 * 3_600_000L, "by", userNames[u]));
            List<Object> saga = new ArrayList<>();
            saga.add(step("Reserve stock", "inventory-service", "OK", "All items held", 18, at));
            saga.add(step("Take payment", "payment-service", "OK", "Paid from wallet", 22, at + 30));
            saga.add(step("Confirm stock", "inventory-service", "OK", "Stock taken out of the warehouse", 9, at + 60));
            if (status.equals("CANCELLED")) {
                saga.add(step("Refund payment", "payment-service", "COMPENSATED", "Cancelled by customer - money returned", 15, at + 2 * 3_600_000L));
                saga.add(step("Put stock back", "inventory-service", "COMPENSATED", "Items returned to stock", 11, at + 2 * 3_600_000L + 40));
            }
            out.add(Json.obj("id", id, "orderNo", String.format("ORD-%d-%05d", year, id), "userId", userIds[u], "userName", userNames[u],
                    "items", items, "subtotal", subtotal, "shipping", shipping, "total", subtotal + shipping,
                    "address", Json.obj("name", a[0], "phone", a[1], "line", a[2], "city", a[3], "pin", a[4]),
                    "status", status, "saga", saga, "history", history, "createdAt", at, "updatedAt", at,
                    "idempotencyKey", "seed-" + id, "cancelReason", status.equals("CANCELLED") ? "Changed my mind" : ""));
        }
        return out;
    }

    private static Map<String, Object> step(String step, String service, String status, String note, long ms, long at) {
        return Json.obj("step", step, "service", service, "status", status, "note", note, "ms", ms, "at", at);
    }

    /** One payment per seeded order (refunded when the order was cancelled). */
    public static List<Map<String, Object>> payments(long now) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> o : orders(now)) {
            boolean cancelled = "CANCELLED".equals(Json.str(o, "status"));
            out.add(Json.obj("id", o.get("id"), "orderId", o.get("id"), "orderNo", o.get("orderNo"), "userId", o.get("userId"),
                    "amount", o.get("total"), "status", cancelled ? "REFUNDED" : "CAPTURED", "reason", "",
                    "at", o.get("createdAt"), "refundedAt", cancelled ? Json.lng(o, "createdAt") + 7_200_000L : 0L));
        }
        return out;
    }
}
