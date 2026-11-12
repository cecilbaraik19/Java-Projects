package com.cecil.sikho.store;

import com.cecil.sikho.ledger.TimeBank;
import com.cecil.sikho.model.Circle;
import com.cecil.sikho.model.Exchange;
import com.cecil.sikho.model.Listing;
import com.cecil.sikho.model.Locality;
import com.cecil.sikho.model.Report;
import com.cecil.sikho.model.Role;
import com.cecil.sikho.model.Skill;
import com.cecil.sikho.model.User;
import com.cecil.sikho.service.CircleService;
import com.cecil.sikho.service.ExchangeService;
import com.cecil.sikho.service.ModerationService;
import com.cecil.sikho.service.Problem;
import com.cecil.sikho.util.Clock;
import com.cecil.sikho.util.Json;
import com.cecil.sikho.util.Passwords;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Builds the demo community on the first start: the made-up "Sikho-Sikhao Ranchi" group with 12 localities,
 * 55 skills, 46 members and about 12 weeks of history (exchanges, reviews, circles, a dispute and two reports).
 *
 * The history is not typed in: it is played through the real services with the clock moved back in time, event
 * by event in time order. So every credit in the ledger got there through the normal rules and the hash chain
 * is real. Everything is dated relative to "now", so the demo always looks fresh. All people are made up.
 */
public final class SeedData {
    private static final long H = 3_600_000L, D = 24 * H;

    private final DataStore store;
    private final TimeBank bank;
    private final ExchangeService exchanges;
    private final CircleService circles;
    private final ModerationService moderation;
    private final Random rnd = new Random(2026);
    private final long now;
    private final LocalDate today;
    private final PriorityQueue<Event> queue = new PriorityQueue<>((a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.order, b.order));
    private long order;
    private final Map<String, User> byName = new LinkedHashMap<>();
    private int skipped;

    private record Event(long at, long order, Runnable action) { }

    /** One planned exchange: the learner and the id are filled in when the request step runs. */
    private static final class Plan { String id; User learner; }

    /**
     * Credits some demo people must still have for the story in the README (their later requests and circle seats).
     * Random history never lets them spend below this.
     */
    private static final Map<String, Integer> RESERVE = Map.ofEntries(Map.entry("ananya", 3), Map.entry("neha", 3),
            Map.entry("kabir", 3), Map.entry("rohit", 3), Map.entry("pooja", 3), Map.entry("sneha", 3), Map.entry("arjun", 3),
            Map.entry("lata", 3), Map.entry("sunita", 2), Map.entry("imran", 2), Map.entry("priya", 2), Map.entry("manoj", 2),
            Map.entry("kavita", 1), Map.entry("ravi", 1), Map.entry("aman", 2), Map.entry("fatima.khatoon", 1),
            Map.entry("deepak.lakra", 1), Map.entry("meera", 3), Map.entry("admin", 3));

    private SeedData(DataStore store, TimeBank bank, ExchangeService exchanges, CircleService circles, ModerationService moderation) {
        this.store = store;
        this.bank = bank;
        this.exchanges = exchanges;
        this.circles = circles;
        this.moderation = moderation;
        this.now = Clock.millis();
        this.today = Clock.today();
    }

    public static void load(DataStore store, TimeBank bank, ExchangeService exchanges, CircleService circles,
                            ModerationService moderation) {
        Instant before = Clock.fixed();
        store.batch = true;
        try {
            new SeedData(store, bank, exchanges, circles, moderation).build();
        } finally {
            Clock.fix(before);
            store.saveAll();
        }
    }

    // ------------------------------------------------------------------ time helpers

    /** A moment: days from today (negative = in the past) at hour:minute, India time. */
    private long day(int days, int hour, int minute) {
        return today.plusDays(days).atTime(hour, minute).atZone(Clock.ZONE).toInstant().toEpochMilli();
    }

    private void at(long when, Runnable r) { queue.add(new Event(when, order++, r)); }

    // ------------------------------------------------------------------ the build

    private void build() {
        localities();
        skills();
        members();
        listings();
        history();
        demoStory();
        demoCircles();
        moderationStory();
        while (!queue.isEmpty()) {
            Event e = queue.poll();
            Clock.fix(Instant.ofEpochMilli(e.at));
            try {
                e.action.run();
            } catch (Problem p) {
                if (System.getenv("SEED_DEBUG") != null) System.err.println("skip @" + Instant.ofEpochMilli(e.at) + ": " + p.getMessage());
                skipped++;   // e.g. a learner without enough credits at that moment: that planned exchange just does not happen
            }
        }
        Clock.fix(Instant.ofEpochMilli(now));
        store.notes.values().forEach(n -> { if (n.at < now - 3 * D) n.read = true; });
        store.log(null, "demo.build", "community", store.users.size() + " members, " + store.exchanges.size()
                + " exchanges, " + store.ledger.size() + " ledger lines (" + skipped + " planned steps skipped)");
    }

    private void localities() {
        Object[][] l = {
                { "lalpur", "Lalpur", 23.3702, 85.3311 }, { "morabadi", "Morabadi", 23.3958, 85.3335 },
                { "bariatu", "Bariatu", 23.3866, 85.3487 }, { "kokar", "Kokar", 23.3772, 85.3571 },
                { "harmu", "Harmu", 23.3627, 85.3049 }, { "doranda", "Doranda", 23.3389, 85.3197 },
                { "hinoo", "Hinoo", 23.3452, 85.3029 }, { "kanke", "Kanke", 23.4281, 85.3213 },
                { "ratu-road", "Ratu Road", 23.3911, 85.3087 }, { "argora", "Argora", 23.3549, 85.2923 },
                { "ashok-nagar", "Ashok Nagar", 23.3561, 85.3113 }, { "namkum", "Namkum", 23.3478, 85.3658 } };
        for (Object[] x : l) store.localities.put((String) x[0], new Locality((String) x[0], (String) x[1], (Double) x[2], (Double) x[3]));
    }

    private void skills() {
        Object[][] s = {
                { "Music", "Guitar", "gitar acoustic chords strumming" }, { "Music", "Harmonium", "bhajan sargam" },
                { "Music", "Tabla", "taal rhythm percussion" }, { "Music", "Hindustani singing", "vocal raag sargam gaana" },
                { "Music", "Flute", "bansuri" }, { "Music", "Keyboard", "piano casio" },
                { "Languages", "Spoken English", "speaking conversation fluency angrezi" }, { "Languages", "Hindi writing", "devanagari grammar" },
                { "Languages", "Bengali", "bangla" }, { "Languages", "Nagpuri", "sadri jharkhand" },
                { "Languages", "Sanskrit basics", "shloka" }, { "Languages", "French basics", "francais" },
                { "Cooking", "Jharkhandi cooking", "dhuska litti chokha rugra recipe" }, { "Cooking", "Baking", "cake bread oven" },
                { "Cooking", "South Indian cooking", "dosa idli sambar" }, { "Cooking", "Healthy tiffin", "lunchbox nutrition" },
                { "Cooking", "Bengali sweets", "rasgulla sandesh mishti" },
                { "Tech & Computers", "Computer basics", "typing email internet windows" },
                { "Tech & Computers", "Excel", "spreadsheet formulas charts" },
                { "Tech & Computers", "Smartphone help", "android whatsapp apps" },
                { "Tech & Computers", "Python programming", "coding beginner" },
                { "Tech & Computers", "Web design", "html css website" },
                { "Tech & Computers", "Photo editing", "canva lightroom" },
                { "Tech & Computers", "Cyber safety", "online fraud otp scam password" },
                { "Arts & Crafts", "Sohrai painting", "tribal wall art khovar" }, { "Arts & Crafts", "Sketching", "pencil drawing" },
                { "Arts & Crafts", "Sewing", "tailoring stitching blouse" }, { "Arts & Crafts", "Knitting", "wool sweater crochet" },
                { "Arts & Crafts", "Mehendi", "henna design" }, { "Arts & Crafts", "Bamboo craft", "basket weaving" },
                { "Arts & Crafts", "Photography", "camera composition mobile photo" },
                { "Fitness & Yoga", "Yoga", "asana pranayama stretching" }, { "Fitness & Yoga", "Cycling", "bicycle riding" },
                { "Fitness & Yoga", "Football coaching", "soccer" }, { "Fitness & Yoga", "Badminton", "shuttle" },
                { "Fitness & Yoga", "Self-defence", "karate safety" }, { "Fitness & Yoga", "Zumba", "dance fitness" },
                { "Home & Repair", "Basic electrical repair", "wiring switch fan bijli" },
                { "Home & Repair", "Plumbing basics", "tap leak pipe" }, { "Home & Repair", "Bicycle repair", "puncture chain" },
                { "Home & Repair", "Furniture polishing", "wood" },
                { "Study & Careers", "School maths", "algebra geometry ganit" }, { "Study & Careers", "Physics", "science class 11 12" },
                { "Study & Careers", "Accounts & Tally", "bookkeeping gst" }, { "Study & Careers", "Interview practice", "job interview mock" },
                { "Study & Careers", "Resume writing", "cv" }, { "Study & Careers", "Chess", "openings strategy" },
                { "Garden & Nature", "Kitchen gardening", "vegetables pots terrace bagwani" },
                { "Garden & Nature", "Composting", "compost waste" }, { "Garden & Nature", "Bonsai", "plants" },
                { "Garden & Nature", "Bird watching", "birds nature" },
                { "Life Skills", "First aid", "cpr emergency" }, { "Life Skills", "Money basics", "budget savings" },
                { "Life Skills", "Public speaking", "stage confidence" }, { "Life Skills", "Driving theory", "road rules licence" } };
        int n = 101;
        for (Object[] x : s) {
            String id = "S-" + n++;
            store.skills.put(id, new Skill(id, (String) x[1], (String) x[0], List.of(((String) x[2]).split(" "))));
        }
        store.meta.put("next.S", 201L);
    }

    private Skill skill(String name) {
        for (Skill s : store.skills.values()) if (s.name.equals(name)) return s;
        throw new IllegalStateException("No skill " + name);
    }

    // ------------------------------------------------------------------ members

    private static final String[] SLOTS = { "MON-EVE", "TUE-EVE", "WED-EVE", "THU-EVE", "FRI-EVE", "SAT-MORN", "SAT-AFT",
            "SAT-EVE", "SUN-MORN", "SUN-AFT", "SUN-EVE", "MON-MORN", "WED-MORN", "FRI-AFT", "TUE-AFT" };
    private static final String[] BIOS = {
            "Retired bank clerk. I like teaching slowly, with lots of practice.",
            "College student at Ranchi University. Happy to share what I know.",
            "Homemaker and proud cook. My kitchen is always open for learners.",
            "Software tester by day, musician in the evening.",
            "School teacher. I believe everyone can learn anything with patience.",
            "I run a small tailoring shop and love passing the craft on.",
            "Nurse at a city hospital. First aid saves lives — let me show you how.",
            "Engineering student. I fix things and teach people to fix things.",
            "Artist from a Sohrai painting family in Hazaribagh.",
            "Gardener at heart. Ask me about anything that grows.",
            "Yoga every morning for 15 years. Beginners welcome.",
            "New to the city and keen to meet neighbours." };
    private static final String[] LANGS = { "Hindi, English", "Hindi, English, Nagpuri", "Hindi, Bengali, English", "Hindi, Kurukh, English",
            "Hindi, Urdu", "Hindi, English, Santhali", "Hindi, Mundari" };

    private void members() {
        String hash = Passwords.hash("member123");   // demo only: the same password for every made-up member
        // username, name, locality, maxKm, role, days ago joined
        Object[][] people = {
                { "admin", "Rekha Devi", "lalpur", 6, Role.MODERATOR, 140 },
                { "ananya", "Ananya Singh", "lalpur", 6, Role.MEMBER, 95 },
                { "ravi", "Ravi Kumar", "bariatu", 6, Role.MEMBER, 110 },
                { "imran", "Imran Ansari", "morabadi", 6, Role.MEMBER, 88 },
                { "priya", "Priya Tirkey", "kokar", 6, Role.MEMBER, 101 },
                { "meera", "Meera Oraon", "kanke", 5, Role.MEMBER, 2 },
                { "kabir", "Kabir Sahu", "lalpur", 5, Role.MEMBER, 64 },
                { "neha", "Neha Ekka", "ashok-nagar", 6, Role.MEMBER, 72 },
                { "arjun", "Arjun Mahto", "harmu", 6, Role.MEMBER, 80 },
                { "kavita", "Kavita Kujur", "lalpur", 5, Role.MEMBER, 120 },
                { "sunita", "Sunita Munda", "morabadi", 5, Role.MEMBER, 115 },
                { "manoj", "Manoj Bhagat", "doranda", 7, Role.MEMBER, 90 },
                { "rohit", "Rohit Sinha", "hinoo", 7, Role.MEMBER, 70 },
                { "pooja", "Pooja Verma", "ratu-road", 6, Role.MEMBER, 60 },
                { "vikram", "Vikram Choudhary", "harmu", 8, Role.MEMBER, 40 },
                { "sneha", "Sneha Gupta", "argora", 6, Role.MEMBER, 66 },
                { "aman", "Aman Tiwari", "argora", 6, Role.MEMBER, 58 },
                { "gaurav", "Gaurav Mishra", "doranda", 8, Role.MEMBER, 50 },
                { "lata", "Lata Hembrom", "morabadi", 6, Role.MEMBER, 105 },
                { "anjali", "Anjali Minz", "kanke", 7, Role.MEMBER, 98 } };
        for (Object[] p : people) addUser((String) p[0], (String) p[1], (String) p[2], (Integer) p[3], (Role) p[4], (Integer) p[5],
                p[0].equals("admin") ? Passwords.hash("admin123") : hash);
        String[] more = { "Fatima Khatoon", "Deepak Lakra", "Sandeep Toppo", "Rahul Prasad", "Shalini Xalxo", "Nisha Kerketta",
                "Aditya Jha", "Jyoti Kachhap", "Suresh Mahli", "Ritu Agarwal", "Akash Barla", "Divya Pandey", "Prakash Soren",
                "Farhan Qureshi", "Bina Tigga", "Harish Nag", "Swati Rai", "Mohan Bediya", "Tara Baskey", "Nitin Kumar",
                "Sushma Kandulna", "Amit Dungdung", "Pallavi Sharma", "Joseph Lakra", "Mary Tete", "Ashok Mahato" };
        List<String> locs = new ArrayList<>(store.localities.keySet());
        for (String name : more) {
            String username = name.toLowerCase().replaceAll("[^a-z]", ".").replaceAll("\\.+", ".");
            addUser(username, name, locs.get(rnd.nextInt(locs.size())), 4 + rnd.nextInt(5), Role.MEMBER, 30 + rnd.nextInt(100), hash);
        }
        // fixed details for the demo people, so the walk-through in the README always works
        User a = byName.get("ananya");
        a.availability = new ArrayList<>(List.of("TUE-EVE", "THU-EVE", "SAT-MORN", "SAT-EVE", "SUN-MORN", "SUN-EVE"));
        a.bio = "Software tester by day, guitarist in the evening. I teach with songs you already know.";
        a.languages = "Hindi, English";
        a.phone = "9876501234";
        User r = byName.get("ravi");
        r.availability = new ArrayList<>(List.of("TUE-EVE", "SAT-MORN", "SAT-EVE", "SUN-MORN", "WED-EVE"));
        r.bio = "Accountant. Excel is my superpower and I want to finally learn the guitar.";
        r.phone = "9123456780";
        User m = byName.get("meera");
        m.bio = "Sohrai artist, just moved to Kanke. I want to learn computers for my art business.";
        m.languages = "Hindi, Kurukh, English";
        User adm = byName.get("admin");
        adm.bio = "Community moderator and retired school principal. Write to me if anything feels wrong.";
    }

    private void addUser(String username, String name, String locality, int maxKm, Role role, int daysAgo, String hash) {
        User u = new User();
        u.id = store.nextId("U", 101);
        u.username = username;
        u.name = name;
        u.role = role;
        u.passwordHash = hash;
        u.localityId = locality;
        Locality loc = store.localities.get(locality);
        double angle = rnd.nextDouble() * 2 * Math.PI, km = 0.15 + rnd.nextDouble() * 0.6;
        u.lat = loc.lat + km / 111.0 * Math.cos(angle);
        u.lng = loc.lng + km / (111.0 * Math.cos(Math.toRadians(loc.lat))) * Math.sin(angle);
        u.maxKm = maxKm;
        u.bio = BIOS[rnd.nextInt(BIOS.length)];
        u.languages = LANGS[rnd.nextInt(LANGS.length)];
        u.phone = "9" + (100000000L + (long) (rnd.nextDouble() * 899999999L));
        List<String> slots = new ArrayList<>();
        int want = 4 + rnd.nextInt(4);
        while (slots.size() < want) {
            String s = SLOTS[rnd.nextInt(SLOTS.length)];
            if (!slots.contains(s)) slots.add(s);
        }
        u.availability = slots;
        u.createdAt = day(-daysAgo, 9 + rnd.nextInt(10), rnd.nextInt(60));
        store.users.put(u.id, u);
        byName.put(username, u);
        at(u.createdAt, () -> bank.post(TimeBank.BANK, u.id, TimeBank.WELCOME, "WELCOME", null, "Welcome to Sikho-Sikhao"));
    }

    // ------------------------------------------------------------------ cards

    private static final Map<String, String[]> OFFER_TEXT = new HashMap<>();
    static {
        OFFER_TEXT.put("Guitar", new String[] { "Guitar from zero to your first song", "Chords, strumming and simple Bollywood songs. Bring your own guitar if you have one; I have a spare." });
        OFFER_TEXT.put("Excel", new String[] { "Excel for work and home budgets", "Formulas, tables, charts and a monthly budget sheet you can keep using." });
        OFFER_TEXT.put("Spoken English", new String[] { "Speak English with confidence", "Daily conversation, interviews and phone calls. We practise, I correct gently." });
        OFFER_TEXT.put("Photography", new String[] { "Better photos with your phone", "Light, framing and simple editing. We walk around the lake and practise." });
        OFFER_TEXT.put("Sohrai painting", new String[] { "Sohrai painting with natural colours", "The traditional wall art of Jharkhand: patterns, animals and earth colours on paper." });
        OFFER_TEXT.put("Jharkhandi cooking", new String[] { "Dhuska, litti chokha and rugra curry", "Cook along in my kitchen. You go home with the food and the recipe." });
        OFFER_TEXT.put("Yoga", new String[] { "Gentle morning yoga", "Breathing and simple asanas for back and knees. Good for all ages." });
        OFFER_TEXT.put("Computer basics", new String[] { "Computer basics for beginners", "Typing, e-mail, saving files and using the internet safely." });
        OFFER_TEXT.put("Cyber safety", new String[] { "Stay safe from online fraud", "OTP scams, fake calls, strong passwords and safe UPI use, explained simply." });
        OFFER_TEXT.put("Basic electrical repair", new String[] { "Fix a fan, switch or plug yourself", "Safety first: switch off, test, then repair. Simple tools only." });
        OFFER_TEXT.put("Python programming", new String[] { "Python for absolute beginners", "Variables, loops and a small project in four sessions." });
        OFFER_TEXT.put("Healthy tiffin", new String[] { "Quick healthy tiffin ideas", "Five tasty lunchbox recipes that take 15 minutes in the morning." });
        OFFER_TEXT.put("Sketching", new String[] { "Pencil sketching basics", "Shapes, shading and drawing from life. Paper and pencils are on me." });
    }

    private static final String[] LEVEL_WORDS = { "BEGINNER", "INTERMEDIATE", "EXPERT" };

    private Listing card(User u, Listing.Type type, String skillName, String level, String mode, String place, int daysAfterJoin) {
        Skill s = skill(skillName);
        Listing l = new Listing();
        l.id = store.nextId("L", 1001);
        l.userId = u.id;
        l.type = type;
        l.skillId = s.id;
        String[] t = OFFER_TEXT.get(skillName);
        if (type == Listing.Type.OFFER) {
            l.title = t != null ? t[0] : "I can teach " + s.name.toLowerCase();
            l.details = t != null ? t[1] : "Happy to teach " + s.name.toLowerCase() + " to beginners at a relaxed pace.";
        } else {
            l.title = "I want to learn " + s.name.toLowerCase();
            l.details = WANT_TEXT[rnd.nextInt(WANT_TEXT.length)];
        }
        l.level = Listing.parse(Listing.Level.class, level, Listing.Level.BEGINNER);
        l.mode = Listing.parse(Listing.Mode.class, mode, Listing.Mode.IN_PERSON);
        l.place = Listing.parse(Listing.Place.class, place, Listing.Place.PUBLIC);
        l.createdAt = Math.min(now - H, u.createdAt + daysAfterJoin * D);
        store.listings.put(l.id, l);
        return l;
    }

    private static final String[] WANT_TEXT = { "Complete beginner. Weekends suit me best.", "I know a little and want to get better.",
            "Need this for my job. Evenings are best.", "Always wanted to try this!", "For my children and me, we can learn together." };

    // what people offer and want: wants lean to tech, English and maths; offers lean to cooking, crafts and Hindi
    private static final String[] OFFER_POOL = { "Jharkhandi cooking", "Baking", "Sewing", "Knitting", "Mehendi", "Sketching", "Hindi writing",
            "Yoga", "Harmonium", "Tabla", "Kitchen gardening", "Composting", "Bicycle repair", "Plumbing basics", "Chess", "Badminton",
            "Football coaching", "Bengali", "Nagpuri", "First aid", "Healthy tiffin", "South Indian cooking", "Money basics", "School maths",
            "Bamboo craft", "Bird watching", "Flute", "Accounts & Tally", "Zumba", "Cycling", "Bengali sweets", "Physics", "Driving theory",
            "Spoken English", "Smartphone help", "Furniture polishing", "Public speaking", "Photo editing" };
    private static final String[] WANT_POOL = { "Spoken English", "Spoken English", "Computer basics", "Computer basics", "Python programming",
            "Python programming", "Excel", "School maths", "Yoga", "Guitar", "Guitar", "Interview practice", "Resume writing", "Web design",
            "Smartphone help", "Cyber safety", "Self-defence", "Photography", "Baking", "French basics", "Keyboard", "First aid",
            "Public speaking", "Bonsai", "Hindustani singing", "Sanskrit basics", "Kitchen gardening", "Physics" };

    private void listings() {
        User a = byName.get("ananya"), r = byName.get("ravi"), im = byName.get("imran"), pr = byName.get("priya"), me = byName.get("meera");
        card(a, Listing.Type.OFFER, "Guitar", "EXPERT", "IN_PERSON", "MY_PLACE", 2);
        card(a, Listing.Type.OFFER, "Sketching", "INTERMEDIATE", "EITHER", "PUBLIC", 30);
        card(a, Listing.Type.WANT, "Spoken English", "INTERMEDIATE", "EITHER", "PUBLIC", 2);
        card(a, Listing.Type.WANT, "Excel", "BEGINNER", "IN_PERSON", "PUBLIC", 40);
        card(r, Listing.Type.OFFER, "Excel", "EXPERT", "IN_PERSON", "PUBLIC", 3);
        card(r, Listing.Type.OFFER, "Computer basics", "EXPERT", "IN_PERSON", "PUBLIC", 3);
        card(r, Listing.Type.OFFER, "Cyber safety", "INTERMEDIATE", "EITHER", "PUBLIC", 20);
        card(r, Listing.Type.WANT, "Guitar", "BEGINNER", "IN_PERSON", "YOUR_PLACE", 5);
        card(im, Listing.Type.OFFER, "Photography", "EXPERT", "IN_PERSON", "PUBLIC", 4);
        card(im, Listing.Type.WANT, "Guitar", "BEGINNER", "IN_PERSON", "YOUR_PLACE", 4);
        card(pr, Listing.Type.OFFER, "Spoken English", "EXPERT", "EITHER", "PUBLIC", 3);
        card(pr, Listing.Type.WANT, "Photography", "BEGINNER", "IN_PERSON", "PUBLIC", 3);
        card(me, Listing.Type.OFFER, "Sohrai painting", "EXPERT", "IN_PERSON", "MY_PLACE", 0);
        card(me, Listing.Type.WANT, "Computer basics", "BEGINNER", "IN_PERSON", "PUBLIC", 0);
        card(byName.get("kabir"), Listing.Type.WANT, "Guitar", "BEGINNER", "IN_PERSON", "YOUR_PLACE", 2);
        card(byName.get("kabir"), Listing.Type.OFFER, "Chess", "EXPERT", "EITHER", "PUBLIC", 2);
        card(byName.get("neha"), Listing.Type.WANT, "Guitar", "INTERMEDIATE", "IN_PERSON", "YOUR_PLACE", 2);
        card(byName.get("neha"), Listing.Type.OFFER, "Mehendi", "EXPERT", "IN_PERSON", "MY_PLACE", 2);
        card(byName.get("arjun"), Listing.Type.WANT, "Guitar", "BEGINNER", "IN_PERSON", "YOUR_PLACE", 3);
        card(byName.get("arjun"), Listing.Type.OFFER, "Football coaching", "INTERMEDIATE", "IN_PERSON", "PUBLIC", 3);
        card(byName.get("kavita"), Listing.Type.OFFER, "Healthy tiffin", "EXPERT", "IN_PERSON", "MY_PLACE", 1);
        card(byName.get("kavita"), Listing.Type.OFFER, "Jharkhandi cooking", "EXPERT", "IN_PERSON", "MY_PLACE", 1);
        card(byName.get("kavita"), Listing.Type.WANT, "Smartphone help", "BEGINNER", "IN_PERSON", "PUBLIC", 10);
        card(byName.get("sunita"), Listing.Type.OFFER, "Yoga", "EXPERT", "IN_PERSON", "PUBLIC", 1);
        card(byName.get("sunita"), Listing.Type.WANT, "Excel", "BEGINNER", "IN_PERSON", "PUBLIC", 8);
        card(byName.get("manoj"), Listing.Type.OFFER, "Basic electrical repair", "EXPERT", "IN_PERSON", "YOUR_PLACE", 2);
        card(byName.get("manoj"), Listing.Type.WANT, "Spoken English", "BEGINNER", "IN_PERSON", "PUBLIC", 2);
        card(byName.get("rohit"), Listing.Type.WANT, "Basic electrical repair", "BEGINNER", "IN_PERSON", "MY_PLACE", 2);
        card(byName.get("rohit"), Listing.Type.OFFER, "School maths", "EXPERT", "EITHER", "PUBLIC", 2);
        card(byName.get("pooja"), Listing.Type.WANT, "Python programming", "BEGINNER", "EITHER", "PUBLIC", 2);
        card(byName.get("pooja"), Listing.Type.OFFER, "Baking", "INTERMEDIATE", "IN_PERSON", "MY_PLACE", 2);
        card(byName.get("vikram"), Listing.Type.OFFER, "Python programming", "EXPERT", "EITHER", "PUBLIC", 1);
        card(byName.get("vikram"), Listing.Type.WANT, "Tabla", "BEGINNER", "IN_PERSON", "PUBLIC", 1);
        card(byName.get("sneha"), Listing.Type.OFFER, "Knitting", "EXPERT", "IN_PERSON", "MY_PLACE", 2);
        card(byName.get("sneha"), Listing.Type.WANT, "Self-defence", "BEGINNER", "IN_PERSON", "PUBLIC", 2);
        card(byName.get("aman"), Listing.Type.OFFER, "Self-defence", "INTERMEDIATE", "IN_PERSON", "PUBLIC", 2);
        card(byName.get("aman"), Listing.Type.WANT, "Baking", "BEGINNER", "IN_PERSON", "PUBLIC", 2);
        card(byName.get("gaurav"), Listing.Type.OFFER, "Interview practice", "INTERMEDIATE", "ONLINE", "PUBLIC", 1);
        card(byName.get("lata"), Listing.Type.OFFER, "Sohrai painting", "EXPERT", "IN_PERSON", "PUBLIC", 1);
        card(byName.get("lata"), Listing.Type.WANT, "Smartphone help", "BEGINNER", "IN_PERSON", "PUBLIC", 9);
        card(byName.get("anjali"), Listing.Type.OFFER, "Kitchen gardening", "EXPERT", "IN_PERSON", "MY_PLACE", 1);
        card(byName.get("anjali"), Listing.Type.WANT, "Web design", "BEGINNER", "ONLINE", "PUBLIC", 1);
        card(byName.get("admin"), Listing.Type.OFFER, "First aid", "EXPERT", "IN_PERSON", "PUBLIC", 1);
        card(byName.get("admin"), Listing.Type.OFFER, "Hindi writing", "EXPERT", "IN_PERSON", "PUBLIC", 1);
        for (User u : new ArrayList<>(byName.values())) {
            if (store.listings.values().stream().anyMatch(l -> l.userId.equals(u.id))) continue;
            int offers = 1 + rnd.nextInt(2), wants = 1 + rnd.nextInt(2);
            List<String> used = new ArrayList<>();
            for (int i = 0; i < offers; i++) {
                String s = pick(OFFER_POOL, used);
                card(u, Listing.Type.OFFER, s, LEVEL_WORDS[1 + rnd.nextInt(2)], rnd.nextInt(5) == 0 ? "EITHER" : "IN_PERSON",
                        rnd.nextBoolean() ? "PUBLIC" : "MY_PLACE", rnd.nextInt(10));
            }
            for (int i = 0; i < wants; i++) {
                String s = pick(WANT_POOL, used);
                card(u, Listing.Type.WANT, s, LEVEL_WORDS[rnd.nextInt(2)], rnd.nextInt(4) == 0 ? "EITHER" : "IN_PERSON", "PUBLIC", rnd.nextInt(30));
            }
        }
    }

    private String pick(String[] pool, List<String> used) {
        while (true) {
            String s = pool[rnd.nextInt(pool.length)];
            if (!used.contains(s)) { used.add(s); return s; }
        }
    }

    // ------------------------------------------------------------------ exchanges, played through the real services

    private static final String[] HELLO = { "Namaste! I am a complete beginner. Could we start with the basics?",
            "Hi, I saw your card. I know a little already and want to practise more.",
            "Hello! Is the weekend fine for you? I can come to the library.",
            "Hi! I'd love to learn this. I'll be on time, promise :)",
            "Namaskar, my daughter will join me if that's okay." };
    private static final String[] REVIEW_5 = { "Very patient and clear. I learned more in one evening than in a month of videos.",
            "Lovely session, felt like learning from a friend.", "Explained everything step by step. Highly recommend!",
            "So well prepared. Even gave me notes to take home." };
    private static final String[] REVIEW_4 = { "Good session, a bit rushed at the end.", "Helpful and friendly. Will come again.",
            "Learned a lot. Started a little late but made up for it." };
    private static final String[] REVIEW_L = { "Eager learner, asked good questions.", "On time and ready to practise.",
            "Lovely to teach, came well prepared." };
    private static final String[][] TAGS = { { "Patient", "Clear" }, { "Friendly", "On time" }, { "Well prepared", "Knows a lot" },
            { "Fun", "Patient" }, { "Clear" } };

    /**
     * Plans one exchange: the learner asks on the teacher's card at requestAt for a session at sessionAt.
     * outcome: DONE (both confirm and review), DECLINE, CANCEL, OPEN (accepted, still to come), REQUESTED (no answer yet).
     */
    private Plan plan(User learner, Listing offer, int hours, long requestAt, long sessionAt, String outcome, int stars) {
        return plan(() -> learner, offer, hours, requestAt, sessionAt, outcome, stars);
    }

    private Plan plan(java.util.function.Supplier<User> who, Listing offer, int hours, long requestAt, long sessionAt, String outcome, int stars) {
        Plan p = new Plan();
        User teacher = store.users.get(offer.userId);
        at(requestAt, () -> {
            User learner = who.get();
            if (learner == null) return;
            Exchange e = exchanges.request(learner, offer.id, Json.obj("hours", hours, "at", sessionAt,
                    "message", HELLO[rnd.nextInt(HELLO.length)]));
            p.id = e.id;
            p.learner = learner;
        });
        if (outcome.equals("REQUESTED")) return p;
        long answer = requestAt + (2 + rnd.nextInt(18)) * H;
        if (outcome.equals("DECLINE")) {
            at(answer, () -> { if (p.id != null) exchanges.decline(teacher, p.id, "Sorry, I am travelling that week."); });
            return p;
        }
        at(answer, () -> { if (p.id != null) exchanges.accept(teacher, p.id); });
        if (rnd.nextBoolean()) at(answer + 2 * H, () -> { if (p.id != null) exchanges.message(p.learner, p.id, "Thank you! See you then."); });
        if (outcome.equals("CANCEL")) {
            at(answer + 12 * H, () -> { if (p.id != null) exchanges.cancel(p.learner, p.id, "Exams came up, so sorry!"); });
            return p;
        }
        if (outcome.equals("OPEN")) return p;
        at(sessionAt + hours * H + 20 * 60_000L, () -> { if (p.id != null) exchanges.done(teacher, p.id); });
        at(sessionAt + hours * H + 3 * H, () -> { if (p.id != null) exchanges.done(p.learner, p.id); });
        long rev = sessionAt + D + rnd.nextInt(10) * H;
        if (rev < now) {
            at(rev, () -> {
                if (p.id == null) return;
                String text = stars >= 5 ? REVIEW_5[rnd.nextInt(REVIEW_5.length)] : stars == 4 ? REVIEW_4[rnd.nextInt(REVIEW_4.length)] : "It was okay.";
                exchanges.review(p.learner, p.id, Json.obj("stars", stars, "text", text, "tags", List.of(TAGS[rnd.nextInt(TAGS.length)])));
            });
            if (rev + 5 * H < now && rnd.nextInt(4) != 0)
                at(rev + 5 * H, () -> { if (p.id != null) exchanges.review(teacher, p.id, Json.obj("stars", 4 + rnd.nextInt(2),
                        "text", REVIEW_L[rnd.nextInt(REVIEW_L.length)], "tags", List.of("Eager to learn"))); });
        }
        return p;
    }

    private Listing offerOf(User u, String skillName) {
        Skill s = skill(skillName);
        for (Listing l : store.listings.values()) if (l.userId.equals(u.id) && l.type == Listing.Type.OFFER && l.skillId.equals(s.id)) return l;
        throw new IllegalStateException(u.username + " has no offer for " + skillName);
    }

    /** About 12 weeks of everyday exchanges, growing a little each week. */
    private void history() {
        List<Listing> offers = new ArrayList<>();
        for (Listing l : store.listings.values()) if (l.type == Listing.Type.OFFER) offers.add(l);
        List<User> people = new ArrayList<>(byName.values());
        for (int week = 12; week >= 1; week--) {
            int count = 4 + (12 - week) / 2 + rnd.nextInt(3);
            for (int i = 0; i < count; i++) {
                Listing o = offers.get(rnd.nextInt(offers.size()));
                User teacher = store.users.get(o.userId);
                int dayOffset = -week * 7 + rnd.nextInt(7);
                long session = day(dayOffset, 9 + rnd.nextInt(11), rnd.nextBoolean() ? 0 : 30);
                long request = session - (2 + rnd.nextInt(5)) * D;
                if (request < teacher.createdAt + D || session + 2 * D > now) continue;
                int hours = 1 + (rnd.nextInt(3) == 0 ? 1 : 0);
                int r = rnd.nextInt(20);
                String outcome = r < 15 ? "DONE" : r < 17 ? "DECLINE" : "CANCEL";
                int stars = rnd.nextInt(10) < 7 ? 5 : rnd.nextInt(5) == 0 ? 3 : 4;
                // the learner is chosen when the request happens, among neighbours who can afford it then
                plan(() -> learnerFor(o, teacher, people, hours), o, hours, request, session, outcome, stars);
            }
        }
    }

    /** Prefers someone who posted a "want" card for that skill and lives close enough; else any neighbour nearby. */
    private User learnerFor(Listing offer, User teacher, List<User> people, int hours) {
        List<User> wanting = new ArrayList<>(), near = new ArrayList<>();
        long t = Clock.millis();
        for (User u : people) {
            if (u.id.equals(teacher.id) || u.createdAt > t - D || u.suspended) continue;
            if (bank.balance(u.id) - hours < RESERVE.getOrDefault(u.username, 0)) continue;
            double km = com.cecil.sikho.match.Geo.km(u.lat, u.lng, teacher.lat, teacher.lng);
            if (km > 7) continue;
            near.add(u);
            for (Listing l : store.listings.values())
                if (l.userId.equals(u.id) && l.type == Listing.Type.WANT && l.skillId.equals(offer.skillId)) wanting.add(u);
        }
        if (!wanting.isEmpty() && rnd.nextInt(3) != 0) return wanting.get(rnd.nextInt(wanting.size()));
        return near.isEmpty() ? null : near.get(rnd.nextInt(near.size()));
    }

    /** The exchanges the README walk-through talks about. */
    private void demoStory() {
        User a = byName.get("ananya");
        Listing guitar = offerOf(a, "Guitar");
        // Ananya's earlier guitar students, all happy: she becomes a "Trusted teacher"
        String[] students = { "neha", "kabir", "sunita", "rohit", "pooja", "sneha", "aman" };
        int[] hours = { 1, 1, 2, 1, 1, 1, 2 };
        int k = 0;
        for (String s : students) {
            long session = day(-60 + k * 8, 17, 30);
            plan(byName.get(s), guitar, hours[k], session - 3 * D, session, "DONE", k == 3 ? 4 : 5);
            k++;
        }
        // yesterday: taught Arjun; Arjun already pressed "done" - Ananya still has to
        Plan arjun = new Plan();
        long yday = day(-1, 17, 0);
        at(day(-6, 20, 0), () -> arjun.id = exchanges.request(byName.get("arjun"), guitar.id, Json.obj("hours", 2, "at", yday,
                "message", "Hi Ananya! Beginner here, I have my father's old guitar.")).id);
        at(day(-5, 9, 15), () -> exchanges.accept(a, arjun.id));
        at(day(-1, 19, 30), () -> exchanges.done(byName.get("arjun"), arjun.id));
        // tomorrow: teaching Neha (accepted)
        Plan neha = new Plan();
        at(day(-3, 21, 0), () -> neha.id = exchanges.request(byName.get("neha"), guitar.id, Json.obj("hours", 1, "at", day(1, 17, 30),
                "message", "Can we work on barre chords this time?")).id);
        at(day(-2, 8, 40), () -> exchanges.accept(a, neha.id));
        // waiting for Ananya: Kabir asks (Ananya can accept or decline)
        at(now - 5 * H, () -> exchanges.request(byName.get("kabir"), guitar.id, Json.obj("hours", 2, "at", day(2, 18, 0),
                "message", "Namaste didi! I want to play one song for my sister's wedding next month. Is that possible?")));
        // Ananya learns Spoken English from Priya in 3 days (credits already held)
        Plan english = new Plan();
        at(now - 2 * D, () -> english.id = exchanges.request(a, offerOf(byName.get("priya"), "Spoken English").id,
                Json.obj("hours", 2, "at", day(3, 18, 0), "place", "Online (video call)",
                        "message", "Hi Priya, I have client calls in English and get nervous. Can we practise conversations?")).id);
        at(now - D - 3 * H, () -> exchanges.accept(byName.get("priya"), english.id));
        at(now - D - 2 * H, () -> exchanges.message(byName.get("priya"), english.id, "Of course! Make a list of 5 situations from your work and we'll role-play them."));
        // finished 4 days ago: Ananya learned healthy tiffin from Kavita and has not reviewed yet
        Plan tiffin = new Plan();
        long tiffinAt = day(-4, 10, 0);
        at(tiffinAt - 4 * D, () -> tiffin.id = exchanges.request(a, offerOf(byName.get("kavita"), "Healthy tiffin").id,
                Json.obj("hours", 1, "at", tiffinAt, "message", "I always skip lunch at work. Help!")).id);
        at(tiffinAt - 3 * D, () -> exchanges.accept(byName.get("kavita"), tiffin.id));
        at(tiffinAt + 2 * H, () -> exchanges.done(byName.get("kavita"), tiffin.id));
        at(tiffinAt + 4 * H, () -> exchanges.done(a, tiffin.id));
        at(tiffinAt + 9 * H, () -> exchanges.review(byName.get("kavita"), tiffin.id, Json.obj("stars", 5, "text", "Ananya took notes like a pro!", "tags", List.of("Eager to learn"))));
        // Ravi taught Excel to Sunita last week; Ravi taught computer basics earlier
        plan(byName.get("sunita"), offerOf(byName.get("ravi"), "Excel"), 2, day(-12, 10, 0), day(-8, 11, 0), "DONE", 5);
        plan(byName.get("kavita"), offerOf(byName.get("ravi"), "Computer basics"), 1, day(-30, 10, 0), day(-26, 16, 0), "DONE", 5);
    }

    private void demoCircles() {
        User sunita = byName.get("sunita"), kavita = byName.get("kavita"), ravi = byName.get("ravi"), lata = byName.get("lata"), anjali = byName.get("anjali");
        circle(sunita, "Yoga", "Sunday yoga at Morabadi ground", "Bring a mat or a bedsheet. All ages.", "Morabadi ground (near the stage)", "morabadi", day(-20, 7, 0), 1, 12,
                List.of("kavita", "lata", "imran", "ravi", "fatima.khatoon", "joseph.lakra"), true);
        circle(kavita, "Jharkhandi cooking", "Litti chokha cook-along", "We cook together and eat together.", "Lalpur community hall kitchen", "lalpur", day(-34, 11, 0), 2, 8,
                List.of("ananya", "priya", "aman", "mary.tete", "ritu.agarwal", "nisha.kerketta"), true);
        circle(ravi, "Smartphone help", "Smartphone help desk for elders", "Bring your phone and your questions.", "Bariatu public library", "bariatu", day(-12, 16, 0), 2, 10,
                List.of("kavita", "lata", "sunita", "manoj", "swati.rai"), true);
        circle(lata, "Sohrai painting", "Sohrai wall painting for beginners", "Learn the patterns and earth colours of Sohrai. Paper and colours provided.",
                "Morabadi community hall", "morabadi", day(5, 10, 0), 2, 10, List.of("priya", "neha", "imran", "sneha", "kavita"), false);
        circle(ravi, "Cyber safety", "Phone & UPI safety for seniors", "OTP scams, fake KYC calls and safe payments. Children welcome to bring parents.",
                "Bariatu public library", "bariatu", day(3, 16, 0), 2, 12, List.of("lata", "kavita", "sunita", "manoj"), false);
        circle(anjali, "Kitchen gardening", "Kitchen garden in pots", "Tomatoes, chillies and coriander on a balcony. Take a seedling home!",
                "Kanke block office lawn", "kanke", day(9, 9, 0), 2, 8, List.of("deepak.lakra", "meera"), false);
    }

    private void circle(User host, String skillName, String title, String details, String place, String locality, long at, int hours, int seats,
                        List<String> people, boolean done) {
        String[] id = new String[1];
        long created = at - 10 * D;
        at(Math.max(created, host.createdAt + D), () -> id[0] = circles.create(host, Json.obj("skillId", skill(skillName).id, "title", title,
                "details", details, "place", place, "localityId", locality, "at", at, "hours", hours, "seats", seats)).id);
        long t = Math.max(created, host.createdAt + D) + H;
        for (String p : people) {
            User u = byName.get(p);
            if (u == null) continue;
            long when = Math.max(t, u.createdAt + H);
            at(when, () -> { if (id[0] != null) circles.join(u, id[0]); });
            t += 7 * H;
        }
        if (done) at(at + hours * H + H, () -> { if (id[0] != null) circles.complete(host, id[0]); });
    }

    /** A dispute and two reports waiting for the moderator, plus one decided earlier. */
    private void moderationStory() {
        User admin = byName.get("admin");
        // dispute: Rohit says Manoj did not come; Manoj says nobody opened the gate
        User rohit = byName.get("rohit"), manoj = byName.get("manoj");
        Plan d = new Plan();
        long session = day(-2, 18, 0);
        at(day(-7, 12, 0), () -> d.id = exchanges.request(rohit, offerOf(manoj, "Basic electrical repair").id, Json.obj("hours", 2, "at", session,
                "message", "My ceiling fan hums and turns slowly. Can you teach me to fix it?")).id);
        at(day(-6, 9, 0), () -> exchanges.accept(manoj, d.id));
        at(day(-2, 20, 10), () -> exchanges.message(manoj, d.id, "I reached at 6 and rang the bell many times, nobody opened the gate."));
        at(day(-1, 8, 30), () -> exchanges.dispute(rohit, d.id, "Manoj ji did not come and did not pick up my calls. I waited at home from 5:45."));

        // money: Vikram asks Pooja for "fees" on UPI
        User pooja = byName.get("pooja"), vikram = byName.get("vikram");
        Plan m = new Plan();
        at(day(-3, 19, 0), () -> m.id = exchanges.request(pooja, offerOf(vikram, "Python programming").id, Json.obj("hours", 2, "at", day(4, 18, 0),
                "place", "Online (video call)", "message", "Hi! I want to learn Python for data work. Total beginner.")).id);
        at(day(-3, 22, 0), () -> exchanges.accept(vikram, m.id));
        at(day(-2, 10, 0), () -> exchanges.message(vikram, m.id, "Before class you must pay 500 rupees software fees by UPI to my number."));
        at(day(-2, 11, 30), () -> moderation.report(pooja, Json.obj("targetId", vikram.id, "exchangeId", m.id, "reason", "MONEY",
                "text", "He is asking me to pay ₹500 by UPI for 'software fees'. I thought lessons are only for time credits?")));

        // late cancellation: Aman cancelled Sneha's self-defence class 2 hours before
        User sneha = byName.get("sneha"), aman = byName.get("aman");
        Plan l = new Plan();
        long lateAt = day(-5, 17, 0);
        at(day(-9, 18, 0), () -> l.id = exchanges.request(sneha, offerOf(aman, "Self-defence").id, Json.obj("hours", 1, "at", lateAt,
                "message", "I walk home late from coaching. Want to feel safer.")).id);
        at(day(-9, 21, 0), () -> exchanges.accept(aman, l.id));
        at(lateAt - 2 * H, () -> exchanges.cancel(aman, l.id, "Something came up"));
        at(day(-4, 10, 0), () -> moderation.report(sneha, Json.obj("targetId", aman.id, "exchangeId", l.id, "reason", "NO_SHOW",
                "text", "Second time he cancelled at the last minute. I had already reached the park.")));

        // decided earlier: Gaurav spammed requests with links, was reported and paused
        User gaurav = byName.get("gaurav");
        at(day(-25, 15, 0), () -> moderation.report(byName.get("kabir"), Json.obj("targetId", gaurav.id, "reason", "SPAM",
                "text", "Sends everyone links to a paid 'job guarantee' course.")));
        at(day(-24, 10, 0), () -> {
            for (Report r : store.reports.values())
                if (r.targetId.equals(gaurav.id) && "OPEN".equals(r.status)) moderation.resolve(admin, r.id, "SUSPEND", "Advertising a paid course to many members.");
        });
    }

    /** For the tests: the made-up member with this username. */
    public static User member(DataStore store, String username) { return store.userByName(username); }

    /** Seeds without the history (tests that want an empty community with only places and skills). */
    public static void placesAndSkills(DataStore store) {
        SeedData s = new SeedData(store, null, null, null, null);
        s.localities();
        s.skills();
    }

    /** Makes a member directly (tests). */
    public static User quickMember(DataStore store, TimeBank bank, String username, String locality) {
        User u = new User();
        u.id = store.nextId("U", 101);
        u.username = username;
        u.name = Character.toUpperCase(username.charAt(0)) + username.substring(1) + " Test";
        u.passwordHash = "x";
        u.localityId = locality;
        Locality loc = store.localities.get(locality);
        u.lat = loc.lat;
        u.lng = loc.lng;
        u.availability = new ArrayList<>(List.of("SAT-MORN", "SUN-EVE"));
        u.createdAt = Clock.millis();
        store.users.put(u.id, u);
        if (bank != null) bank.post(TimeBank.BANK, u.id, TimeBank.WELCOME, "WELCOME", null, "Welcome");
        return u;
    }
}
