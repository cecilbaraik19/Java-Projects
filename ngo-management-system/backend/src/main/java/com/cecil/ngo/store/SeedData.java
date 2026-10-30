package com.cecil.ngo.store;

import com.cecil.ngo.model.Activity;
import com.cecil.ngo.model.Beneficiary;
import com.cecil.ngo.model.Donation;
import com.cecil.ngo.model.Donor;
import com.cecil.ngo.model.Enums.ActivityKind;
import com.cecil.ngo.model.Enums.ActivityStatus;
import com.cecil.ngo.model.Enums.AgeGroup;
import com.cecil.ngo.model.Enums.Cause;
import com.cecil.ngo.model.Enums.DonorType;
import com.cecil.ngo.model.Enums.ExpenseCategory;
import com.cecil.ngo.model.Enums.Gender;
import com.cecil.ngo.model.Enums.PayMode;
import com.cecil.ngo.model.Enums.ProgramStatus;
import com.cecil.ngo.model.Enums.Review;
import com.cecil.ngo.model.Enums.Role;
import com.cecil.ngo.model.Expense;
import com.cecil.ngo.model.HoursLog;
import com.cecil.ngo.model.Program;
import com.cecil.ngo.model.User;
import com.cecil.ngo.model.Volunteer;
import com.cecil.ngo.util.Money;
import com.cecil.ngo.util.Passwords;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Demo data for the first run: a made-up NGO in Jharkhand with 6 programs, about 60 activities,
 * 16 volunteers, donors, donations, expenses and beneficiaries. All dates are counted from today,
 * so the demo always looks current. All people and companies are made up.
 * A fixed random seed means every fresh start gives the same data.
 */
public final class SeedData {
    private SeedData() {}

    public static void fill(DataStore s) {
        Random rnd = new Random(2026);
        LocalDate today = s.today();

        // ---------------------------------------------------------- logins
        user(s, "admin", "Sunita Oraon", Role.ADMIN, "admin123", null);
        user(s, "kavita", "Kavita Munda", Role.MANAGER, "manager123", null);
        user(s, "deepak", "Deepak Toppo", Role.MANAGER, "manager123", null);
        user(s, "rahul", "Rahul Sinha", Role.FINANCE, "finance123", null);

        // ---------------------------------------------------------- programs
        Program edu = program(s, "Shiksha Evening Learning Centres", Cause.EDUCATION, "Ormanjhi & Kanke blocks, Ranchi",
                "Free evening classes for children of classes 3 to 8: reading, maths and homework help, five days a week in community halls.",
                today.minusDays(210), today.plusDays(155), 650_000, 400, ProgramStatus.ACTIVE, "kavita");
        Program health = program(s, "Swasthya Village Health Camps", Cause.HEALTH, "Khunti district",
                "Monthly health camps with a doctor and nurses: check-ups, anaemia tests, medicines and referrals for serious cases.",
                today.minusDays(240), today.plusDays(125), 900_000, 2500, ProgramStatus.ACTIVE, "deepak");
        Program water = program(s, "Jal Suraksha - Clean Water", Cause.WATER, "Gumla district",
                "Repairing broken handpumps, testing water and training village water committees to keep pumps working.",
                today.minusDays(180), today.plusDays(60), 420_000, 1800, ProgramStatus.ACTIVE, "deepak");
        Program sakhi = program(s, "Sakhi Self-Help Groups", Cause.WOMEN, "Bero block, Ranchi",
                "Helping women form savings groups and learn tailoring, pickle making and bookkeeping to earn a steady income.",
                today.minusDays(150), today.plusDays(215), 380_000, 240, ProgramStatus.ACTIVE, "kavita");
        Program trees = program(s, "Hariyali Tree Plantation", Cause.ENVIRONMENT, "Ranchi city and Namkum",
                "Planting and caring for native trees with schools and resident groups during the monsoon.",
                today.minusDays(330), today.minusDays(150), 150_000, 1000, ProgramStatus.COMPLETED, "kavita");
        Program winter = program(s, "Winter Blanket Distribution", Cause.RELIEF, "Latehar district",
                "Blankets and warm clothes for elderly people and families living in kutcha houses before the cold wave.",
                today.plusDays(18), today.plusDays(80), 200_000, 1500, ProgramStatus.PLANNED, "deepak");

        // ---------------------------------------------------------- volunteers
        String[][] vols = {
                {"Aman Kujur", "9431012345", "aman.kujur@mail.example", "Ranchi", "Teaching;Kurukh", "WEEKENDS"},
                {"Priya Kumari", "9835021876", "priya.k@mail.example", "Ranchi", "Teaching;Data entry", "ANY"},
                {"Rohit Mahto", "7004112233", "rohit.mahto@mail.example", "Khunti", "First aid;Driving", "ANY"},
                {"Neha Ekka", "9939445566", "neha.ekka@mail.example", "Ranchi", "Counselling;Hindi", "WEEKDAYS"},
                {"Sanjay Lakra", "8789001122", "", "Gumla", "Plumbing;Driving", "ANY"},
                {"Anjali Tirkey", "9122334455", "anjali.t@mail.example", "Ranchi", "Photography;Social media", "WEEKENDS"},
                {"Vikash Oraon", "9006778899", "vikash.o@mail.example", "Gumla", "Plumbing;Kurukh", "ANY"},
                {"Pooja Singh", "7631445522", "pooja.singh@mail.example", "Ranchi", "Accounts;Data entry", "WEEKDAYS"},
                {"Md. Faizan", "8102556677", "faizan@mail.example", "Ranchi", "Teaching;Urdu", "ANY"},
                {"Sneha Minz", "9546223344", "sneha.minz@mail.example", "Khunti", "First aid;Counselling", "ANY"},
                {"Ravi Bhagat", "9204667788", "", "Bero", "Tailoring;Hindi", "WEEKDAYS"},
                {"Meena Devi", "9471889900", "meena.devi@mail.example", "Bero", "Tailoring;Bookkeeping", "ANY"},
                {"Arjun Soren", "8340112299", "arjun.soren@mail.example", "Latehar", "Driving;Santhali", "ANY"},
                {"Kriti Agarwal", "9334556611", "kriti.a@mail.example", "Ranchi", "Social media;Teaching", "WEEKENDS"},
                {"Bablu Hembrom", "7979334411", "", "Khunti", "First aid;Mundari", "ANY"},
                {"Shalini Xaxa", "9608221144", "shalini.x@mail.example", "Ranchi", "Counselling;Photography", "WEEKENDS"},
        };
        List<Volunteer> vl = new ArrayList<>();
        int vi = 0;
        for (String[] r : vols) {
            Volunteer v = new Volunteer();
            v.id = s.newId("V", 3);
            v.name = r[0]; v.phone = r[1]; v.email = r[2]; v.city = r[3];
            v.skills = new ArrayList<>(List.of(r[4].split(";")));
            v.availability = r[5];
            v.joinedOn = today.minusDays(420 - vi * 5L).toString();
            v.active = vi != 15; // the last one has stopped volunteering
            s.volunteers.add(v);
            vl.add(v);
            vi++;
        }
        user(s, "aman", "Aman Kujur", Role.VOLUNTEER, "volunteer123", vl.get(0).id);
        user(s, "priya", "Priya Kumari", Role.VOLUNTEER, "volunteer123", vl.get(1).id);

        // which volunteers usually help which program
        Map<String, int[]> crew = new HashMap<>();
        crew.put(edu.id, new int[]{0, 1, 3, 8, 13});
        crew.put(health.id, new int[]{2, 3, 9, 14, 5});
        crew.put(water.id, new int[]{4, 6, 2, 12});
        crew.put(sakhi.id, new int[]{10, 11, 7, 3});
        crew.put(trees.id, new int[]{0, 5, 13, 15, 8});
        crew.put(winter.id, new int[]{12, 2, 4, 14});

        // ---------------------------------------------------------- activities
        Map<String, Set<String>> busy = new HashMap<>(); // date -> volunteer ids already on something that day
        // past (done) activities
        activitySeries(s, rnd, busy, crew, vl, edu, today.minusDays(200), 16, 13, ActivityKind.CLASS,
                new String[]{"Reading circle", "Maths games day", "Homework help weekend", "Spoken English session", "Science fun day", "Parents meeting"},
                new String[]{"Ormanjhi community hall", "Kanke panchayat bhawan", "Chutu village school"}, 25, 60, "kavita");
        activitySeries(s, rnd, busy, crew, vl, health, today.minusDays(230), 9, 25, ActivityKind.CAMP,
                new String[]{"General health camp", "Anaemia testing camp", "Eye check-up camp", "Mother & child camp"},
                new String[]{"Murhu health sub-centre", "Torpa block office ground", "Arki village school"}, 120, 260, "deepak");
        activitySeries(s, rnd, busy, crew, vl, water, today.minusDays(170), 8, 20, ActivityKind.DRIVE,
                new String[]{"Handpump repair drive", "Water testing round", "Water committee training"},
                new String[]{"Sisai block", "Bharno village", "Ghaghra market area"}, 80, 220, "deepak");
        activitySeries(s, rnd, busy, crew, vl, sakhi, today.minusDays(140), 7, 19, ActivityKind.TRAINING,
                new String[]{"Tailoring training", "SHG bookkeeping class", "Pickle making workshop", "Bank linkage meeting"},
                new String[]{"Bero SHG centre", "Itki community hall"}, 15, 40, "kavita");
        activitySeries(s, rnd, busy, crew, vl, trees, today.minusDays(320), 6, 28, ActivityKind.DRIVE,
                new String[]{"School plantation drive", "Sapling care round", "Colony plantation day"},
                new String[]{"Namkum", "Morabadi ground", "Hatia colony"}, 60, 200, "kavita");

        // one past activity that nobody closed yet (shows up as "to do" on the dashboard)
        Activity open = activity(s, edu, "Library corner set-up", ActivityKind.CLASS, today.minusDays(2), "16:00", "Chutu village school", 3, "kavita");
        assign(open, busy, vl, new int[]{1, 8}, 2);

        // upcoming activities
        upcoming(s, busy, vl, edu, "Reading circle", ActivityKind.CLASS, today.plusDays(2), "16:30", "Ormanjhi community hall", 4, new int[]{1, 8}, "kavita");
        upcoming(s, busy, vl, health, "General health camp", ActivityKind.CAMP, today.plusDays(4), "09:30", "Murhu health sub-centre", 6, new int[]{2, 9, 14}, "deepak");
        upcoming(s, busy, vl, water, "Handpump repair drive", ActivityKind.DRIVE, today.plusDays(5), "08:00", "Sisai block", 4, new int[]{4, 6}, "deepak");
        upcoming(s, busy, vl, sakhi, "Tailoring training", ActivityKind.TRAINING, today.plusDays(6), "11:00", "Bero SHG centre", 3, new int[]{10, 11}, "kavita");
        upcoming(s, busy, vl, edu, "Science fun day", ActivityKind.CLASS, today.plusDays(9), "15:00", "Kanke panchayat bhawan", 5, new int[]{0, 13}, "kavita");
        upcoming(s, busy, vl, health, "Anaemia testing camp", ActivityKind.CAMP, today.plusDays(12), "09:00", "Torpa block office ground", 5, new int[]{3}, "deepak");
        upcoming(s, busy, vl, sakhi, "Bank linkage meeting", ActivityKind.MEETING, today.plusDays(15), "12:00", "Itki community hall", 2, new int[]{7}, "kavita");
        upcoming(s, busy, vl, water, "Water committee training", ActivityKind.TRAINING, today.plusDays(19), "10:30", "Bharno village", 3, new int[]{}, "deepak");
        upcoming(s, busy, vl, winter, "Beneficiary survey", ActivityKind.SURVEY, today.plusDays(24), "09:00", "Manika block villages", 6, new int[]{12}, "deepak");
        upcoming(s, busy, vl, winter, "Blanket distribution - phase 1", ActivityKind.DISTRIBUTION, today.plusDays(40), "10:00", "Latehar town hall", 10, new int[]{}, "deepak");
        upcoming(s, busy, vl, edu, "Parents meeting", ActivityKind.MEETING, today.plusDays(26), "17:00", "Chutu village school", 2, new int[]{}, "kavita");

        // ---------------------------------------------------------- volunteer hours
        // Everyone on a done activity logs hours. Most are approved; Aman's latest ones are left for the demo.
        List<Activity> done = new ArrayList<>();
        for (Activity a : s.activities) if (a.status == ActivityStatus.DONE) done.add(a);
        done.sort(Comparator.comparing(a -> a.date));
        Activity amanLast = null, amanSecondLast = null;
        for (Activity a : done) if (a.volunteerIds.contains(vl.get(0).id)) { amanSecondLast = amanLast; amanLast = a; }
        for (Activity a : done) {
            for (String vid : a.volunteerIds) {
                if (a == amanLast && vid.equals(vl.get(0).id)) continue; // not logged yet - shows in Aman's "log hours" list
                HoursLog h = new HoursLog();
                h.id = s.newId("H", 4);
                h.volunteerId = vid;
                h.activityId = a.id;
                h.date = a.date;
                h.hours = 2 + rnd.nextInt(9) / 2.0;
                h.note = "";
                boolean recent = !LocalDate.parse(a.date).isBefore(today.minusDays(12));
                boolean pending = (a == amanSecondLast && vid.equals(vl.get(0).id)) || (recent && vid.equals(vl.get(1).id));
                h.status = pending ? Review.PENDING : Review.APPROVED;
                h.loggedBy = pending ? usernameOf(s, vid) : a.createdBy;
                h.reviewedBy = pending ? null : a.createdBy;
                h.loggedAt = a.date + "T19:00:00";
                s.hours.add(h);
            }
        }

        // ---------------------------------------------------------- donors and donations
        Donor[] donors = {
                donor(s, "Ranchi Steelworks Ltd (CSR)", DonorType.CORPORATE, "csr@ranchisteel.example", "", "Ranchi"),
                donor(s, "Damodar Power Co. CSR Cell", DonorType.CORPORATE, "csr@damodarpower.example", "", "Bokaro"),
                donor(s, "Sahyog Charitable Trust", DonorType.FOUNDATION, "grants@sahyogtrust.example", "", "Kolkata"),
                donor(s, "Jharkhand Rural Health Mission (grant)", DonorType.GOVERNMENT, "", "", "Ranchi"),
                donor(s, "Anita Sharma", DonorType.INDIVIDUAL, "anita.sharma@mail.example", "9431776655", "Ranchi"),
                donor(s, "Prakash Jha", DonorType.INDIVIDUAL, "prakash.jha@mail.example", "9835667788", "Jamshedpur"),
                donor(s, "Dr. Rekha Murmu", DonorType.INDIVIDUAL, "rekha.murmu@mail.example", "9973112244", "Ranchi"),
                donor(s, "Sunil & Meera Gupta", DonorType.INDIVIDUAL, "guptas@mail.example", "", "Dhanbad"),
                donor(s, "Imran Qureshi", DonorType.INDIVIDUAL, "imran.q@mail.example", "8051223344", "Ranchi"),
                donor(s, "Alumni Circle, St. Xavier's", DonorType.FOUNDATION, "alumni@sxc.example", "", "Ranchi"),
                donor(s, "Kiran Lal", DonorType.INDIVIDUAL, "", "9102334455", "Hazaribagh"),
                donor(s, "Vasudha Foundation", DonorType.FOUNDATION, "hello@vasudha.example", "", "Delhi"),
        };
        // {donor index, days ago, amount, program (null = general), mode}
        Object[][] gifts = {
                {0, 300, 400000L, edu, PayMode.BANK}, {2, 290, 250000L, health, PayMode.BANK}, {4, 285, 5000L, null, PayMode.UPI},
                {11, 270, 120000L, trees, PayMode.BANK}, {5, 255, 11000L, edu, PayMode.UPI}, {3, 240, 450000L, health, PayMode.BANK},
                {6, 230, 25000L, health, PayMode.CHEQUE}, {7, 210, 15000L, null, PayMode.UPI}, {1, 200, 300000L, water, PayMode.BANK},
                {8, 190, 2000L, null, PayMode.CASH}, {9, 175, 60000L, edu, PayMode.BANK}, {4, 160, 5000L, null, PayMode.UPI},
                {10, 150, 1500L, null, PayMode.CASH}, {0, 140, 150000L, sakhi, PayMode.BANK}, {5, 120, 11000L, null, PayMode.UPI},
                {2, 110, 100000L, water, PayMode.BANK}, {6, 100, 21000L, sakhi, PayMode.UPI}, {7, 90, 10000L, edu, PayMode.UPI},
                {4, 75, 5000L, null, PayMode.UPI}, {11, 70, 80000L, sakhi, PayMode.BANK}, {8, 60, 5100L, health, PayMode.UPI},
                {3, 55, 200000L, health, PayMode.BANK}, {9, 45, 40000L, null, PayMode.CHEQUE}, {4, 40, 5000L, null, PayMode.UPI},
                {1, 35, 120000L, winter, PayMode.BANK}, {5, 25, 21000L, winter, PayMode.UPI}, {10, 20, 2000L, null, PayMode.CASH},
                {6, 14, 15000L, edu, PayMode.CARD}, {7, 10, 25000L, winter, PayMode.UPI}, {4, 6, 5000L, null, PayMode.UPI},
                {8, 3, 3100L, null, PayMode.UPI}, {0, 2, 150000L, edu, PayMode.BANK},
        };
        List<Donation> dl = new ArrayList<>();
        int ref = 410_000;
        for (Object[] g : gifts) {
            Donation d = new Donation();
            d.donorId = donors[(Integer) g[0]].id;
            d.date = today.minusDays((Integer) g[1]).toString();
            d.amount = (Long) g[2];
            d.programId = g[3] == null ? null : ((Program) g[3]).id;
            d.mode = (PayMode) g[4];
            d.reference = d.mode == PayMode.CASH ? "" : (d.mode == PayMode.CHEQUE ? "CHQ " : d.mode == PayMode.BANK ? "NEFT" : "UPI") + (ref += 7919);
            d.note = "";
            d.recordedBy = "rahul";
            d.recordedAt = d.date + "T12:00:00";
            dl.add(d);
        }
        dl.sort(Comparator.comparing(d -> d.date));
        Map<String, Integer> seq = new HashMap<>();
        for (Donation d : dl) {
            d.id = s.newId("G", 4);
            String fy = Money.financialYear(LocalDate.parse(d.date));
            int n = seq.merge(fy, 1, Integer::sum);
            d.receiptNo = "RCPT/" + fy + "/" + String.format("%04d", n);
            s.donations.add(d);
        }

        // ---------------------------------------------------------- expenses
        // {program, category, approved total to spread over time, vendor, what for}
        Object[][] spend = {
                {edu, ExpenseCategory.MATERIALS, 118000L, "Gyan Book Depot", "Notebooks, readers and stationery"},
                {edu, ExpenseCategory.STAFF, 162000L, "Centre teachers", "Monthly honorarium for 6 teachers"},
                {edu, ExpenseCategory.FOOD, 41000L, "Local snack vendor", "Evening snacks for children"},
                {health, ExpenseCategory.MEDICAL, 288000L, "Jan Aushadhi Kendra, Khunti", "Medicines and test kits"},
                {health, ExpenseCategory.STAFF, 135000L, "Visiting doctors and nurses", "Camp honorarium"},
                {health, ExpenseCategory.VENUE, 52000L, "Shree Tent House", "Tents, chairs and tables"},
                {health, ExpenseCategory.TRAVEL, 46000L, "Hired vehicles", "Medical team travel"},
                {water, ExpenseCategory.MATERIALS, 274000L, "Gumla Hardware", "Handpump spare parts and pipes"},
                {water, ExpenseCategory.STAFF, 92000L, "Mechanic team", "Repair team wages"},
                {water, ExpenseCategory.TRAVEL, 34000L, "Hired vehicles", "Field travel"},
                {sakhi, ExpenseCategory.MATERIALS, 64000L, "Usha sewing centre", "Sewing machines and cloth"},
                {sakhi, ExpenseCategory.STAFF, 38000L, "Trainers", "Trainer fees"},
                {sakhi, ExpenseCategory.PRINTING, 6500L, "Print Point", "Ledger books and posters"},
                {trees, ExpenseCategory.MATERIALS, 92000L, "Van Nursery, Namkum", "Saplings and tree guards"},
                {trees, ExpenseCategory.TRAVEL, 17000L, "Tractor hire", "Water tanker and transport"},
                {trees, ExpenseCategory.FOOD, 14500L, "Local caterer", "Refreshments for volunteers"},
        };
        String[] submitters = {"kavita", "deepak"};
        for (Object[] r : spend) {
            Program p = (Program) r[0];
            long left = (Long) r[2];
            int parts = 3 + rnd.nextInt(3);
            LocalDate start = LocalDate.parse(p.startDate).plusDays(5);
            LocalDate end = p.endDate != null && LocalDate.parse(p.endDate).isBefore(today) ? LocalDate.parse(p.endDate) : today.minusDays(3);
            long span = Math.max(1, end.toEpochDay() - start.toEpochDay());
            for (int i = 0; i < parts; i++) {
                long amt = i == parts - 1 ? left : Math.round(left / (double) (parts - i) / 100.0) * 100;
                left -= amt;
                Expense e = new Expense();
                e.id = s.newId("E", 4);
                e.programId = p.id;
                e.category = (ExpenseCategory) r[1];
                e.amount = amt;
                e.date = start.plusDays(span * (i + 1) / (parts + 1)).toString();
                e.vendor = (String) r[3];
                e.description = (String) r[4] + " (" + (i + 1) + " of " + parts + ")";
                e.status = Review.APPROVED;
                e.submittedBy = p.lead != null ? p.lead : submitters[i % 2];
                e.reviewedBy = "rahul";
                e.reviewNote = "";
                e.submittedAt = e.date + "T10:00:00";
                s.expenses.add(e);
            }
        }
        // waiting for approval
        expense(s, edu, ExpenseCategory.MATERIALS, 8400, today.minusDays(3), "Gyan Book Depot", "Library corner: 60 story books", Review.PENDING, "kavita", null, "");
        expense(s, health, ExpenseCategory.MEDICAL, 36500, today.minusDays(4), "Jan Aushadhi Kendra, Khunti", "Iron and folic acid tablets for next camp", Review.PENDING, "deepak", null, "");
        expense(s, water, ExpenseCategory.MATERIALS, 28000, today.minusDays(2), "Gumla Hardware", "Pipes for 4 more handpumps (goes over budget)", Review.PENDING, "deepak", null, "");
        expense(s, sakhi, ExpenseCategory.TRAVEL, 3200, today.minusDays(1), "Auto rickshaw", "Trainer travel for this month", Review.PENDING, "rahul", null, "");
        expense(s, edu, ExpenseCategory.FOOD, 9500, today.minusDays(20), "Hotel Sitara", "Lunch for parents meeting", Review.REJECTED, "kavita", "rahul", "Too costly - use the local snack vendor");

        // ---------------------------------------------------------- beneficiaries
        String[] girls = {"Sita", "Rani", "Puja", "Sunita", "Asha", "Pinky", "Shanti", "Lalita", "Mamta", "Basanti", "Kiran", "Anita", "Rita", "Sangeeta", "Phulmani", "Jyoti"};
        String[] boys = {"Raju", "Sonu", "Birsa", "Ramesh", "Anil", "Manoj", "Sunil", "Deepak", "Bijay", "Raju", "Mahesh", "Prakash", "Sukhram", "Budhua", "Ajay", "Santosh"};
        String[] surnames = {"Oraon", "Munda", "Kumari", "Kumar", "Lohra", "Mahto", "Tirkey", "Kerketta", "Bara", "Toppo", "Barla", "Devi", "Soy", "Purty"};
        Object[][] plan = { // program, villages, count, typical age group
                {edu, new String[]{"Ormanjhi", "Kanke", "Chutu"}, 22, AgeGroup.CHILD},
                {health, new String[]{"Murhu", "Torpa", "Arki"}, 18, AgeGroup.ADULT},
                {water, new String[]{"Sisai", "Bharno", "Ghaghra"}, 8, AgeGroup.ADULT},
                {sakhi, new String[]{"Bero", "Itki"}, 14, AgeGroup.ADULT},
                {trees, new String[]{"Namkum", "Hatia"}, 4, AgeGroup.YOUTH},
        };
        Set<String> used = new HashSet<>();
        for (Object[] pl : plan) {
            Program p = (Program) pl[0];
            String[] villages = (String[]) pl[1];
            for (int i = 0; i < (Integer) pl[2]; i++) {
                Gender g = p == sakhi ? Gender.FEMALE : rnd.nextInt(100) < 55 ? Gender.FEMALE : Gender.MALE;
                String first = g == Gender.FEMALE ? girls[rnd.nextInt(girls.length)] : boys[rnd.nextInt(boys.length)];
                String last = surnames[rnd.nextInt(surnames.length)];
                if (g == Gender.MALE && (last.equals("Kumari") || last.equals("Devi"))) last = "Kumar";
                if (g == Gender.FEMALE && last.equals("Kumar")) last = "Kumari";
                String village = villages[rnd.nextInt(villages.length)];
                String key = first + last + village;
                if (!used.add(key)) continue;
                AgeGroup age = (AgeGroup) pl[3];
                if (age == AgeGroup.ADULT && rnd.nextInt(5) == 0) age = AgeGroup.SENIOR;
                if (age == AgeGroup.CHILD && rnd.nextInt(6) == 0) age = AgeGroup.YOUTH;
                Beneficiary b = new Beneficiary();
                b.id = s.newId("B", 4);
                b.name = first + " " + last;
                b.gender = g;
                b.ageGroup = age;
                b.village = village;
                b.programIds = new ArrayList<>(List.of(p.id));
                if (p == health && rnd.nextInt(4) == 0) b.programIds.add(water.id);
                b.enrolledOn = LocalDate.parse(p.startDate).plusDays(rnd.nextInt(60)).toString();
                b.notes = "";
                s.beneficiaries.add(b);
            }
        }

        // ---------------------------------------------------------- a little history for the activity feed
        s.events.add(new com.cecil.ngo.model.Event(today.minusDays(3) + "T10:12:00", "kavita", "submitted expense", "₹8,400 - Library corner: 60 story books", "program:" + edu.id));
        s.events.add(new com.cecil.ngo.model.Event(today.minusDays(2) + "T09:40:00", "deepak", "submitted expense", "₹28,000 - Pipes for 4 more handpumps", "program:" + water.id));
        s.events.add(new com.cecil.ngo.model.Event(today.minusDays(2) + "T15:05:00", "rahul", "recorded donation", "₹1,50,000 from Ranchi Steelworks Ltd (CSR)", "program:" + edu.id));
        s.events.add(new com.cecil.ngo.model.Event(today.minusDays(1) + "T18:20:00", "aman", "signed up", "Aman Kujur for Science fun day", "program:" + edu.id));
    }

    // ------------------------------------------------------------------ small builders

    private static void user(DataStore s, String username, String name, Role role, String pw, String volunteerId) {
        User u = new User();
        u.username = username;
        u.displayName = name;
        u.role = role;
        u.passwordHash = Passwords.hash(pw);
        u.volunteerId = volunteerId;
        u.createdAt = s.now();
        s.users.add(u);
    }

    private static String usernameOf(DataStore s, String volunteerId) {
        for (User u : s.users) if (volunteerId.equals(u.volunteerId)) return u.username;
        return "kavita";
    }

    private static Program program(DataStore s, String name, Cause cause, String loc, String desc, LocalDate start, LocalDate end,
                                   long budget, int target, ProgramStatus st, String lead) {
        Program p = new Program();
        p.id = s.newId("P", 2);
        p.name = name; p.cause = cause; p.location = loc; p.description = desc;
        p.startDate = start.toString(); p.endDate = end == null ? null : end.toString();
        p.budget = budget; p.targetBeneficiaries = target; p.status = st; p.lead = lead;
        p.createdAt = start.minusDays(10) + "T11:00:00";
        s.programs.add(p);
        return p;
    }

    private static Activity activity(DataStore s, Program p, String title, ActivityKind kind, LocalDate date, String time,
                                     String place, int need, String by) {
        Activity a = new Activity();
        a.id = s.newId("A", 3);
        a.programId = p.id; a.title = title; a.kind = kind; a.date = date.toString(); a.time = time; a.place = place;
        a.volunteersNeeded = need; a.createdBy = by; a.createdAt = date.minusDays(10) + "T10:00:00"; a.outcome = "";
        s.activities.add(a);
        return a;
    }

    /** Adds volunteers, skipping anyone already busy that day, up to 'max'. */
    private static void assign(Activity a, Map<String, Set<String>> busy, List<Volunteer> vl, int[] who, int max) {
        Set<String> day = busy.computeIfAbsent(a.date, k -> new HashSet<>());
        for (int i : who) {
            if (a.volunteerIds.size() >= max) break;
            Volunteer v = vl.get(i);
            if (day.contains(v.id)) continue;
            if (LocalDate.parse(a.date).isBefore(LocalDate.parse(v.joinedOn))) continue;
            a.volunteerIds.add(v.id);
            day.add(v.id);
        }
    }

    private static void activitySeries(DataStore s, Random rnd, Map<String, Set<String>> busy, Map<String, int[]> crew, List<Volunteer> vl,
                                       Program p, LocalDate first, int count, int gapDays, ActivityKind kind, String[] titles,
                                       String[] places, int minReach, int maxReach, String by) {
        LocalDate d = first;
        LocalDate stop = p.endDate != null && LocalDate.parse(p.endDate).isBefore(s.today()) ? LocalDate.parse(p.endDate) : s.today().minusDays(4);
        for (int i = 0; i < count && !d.isAfter(stop); i++) {
            if (d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.plusDays(1);
            int need = 2 + rnd.nextInt(3);
            Activity a = activity(s, p, titles[i % titles.length], kind == ActivityKind.CLASS && i % 6 == 5 ? ActivityKind.MEETING : kind,
                    d, String.format("%02d:%02d", 9 + rnd.nextInt(8), rnd.nextBoolean() ? 0 : 30), places[i % places.length], need, by);
            int[] team = crew.get(p.id).clone();
            for (int k = team.length - 1; k > 0; k--) { int j = rnd.nextInt(k + 1); int t = team[k]; team[k] = team[j]; team[j] = t; }
            assign(a, busy, vl, team, need);
            if (rnd.nextInt(14) == 0) {
                a.status = ActivityStatus.CANCELLED;
                a.outcome = "Cancelled: heavy rain";
            } else {
                a.status = ActivityStatus.DONE;
                a.reached = minReach + rnd.nextInt(maxReach - minReach + 1);
                a.outcome = "Went well. " + a.reached + " people took part.";
            }
            d = d.plusDays(gapDays - 3 + rnd.nextInt(7));
        }
    }

    private static void upcoming(DataStore s, Map<String, Set<String>> busy, List<Volunteer> vl, Program p, String title, ActivityKind kind,
                                 LocalDate date, String time, String place, int need, int[] who, String by) {
        Activity a = activity(s, p, title, kind, date, time, place, need, by);
        assign(a, busy, vl, who, need);
    }

    private static Donor donor(DataStore s, String name, DonorType type, String email, String phone, String city) {
        Donor d = new Donor();
        d.id = s.newId("D", 3);
        d.name = name; d.type = type; d.email = email; d.phone = phone; d.city = city;
        d.createdAt = s.now();
        s.donors.add(d);
        return d;
    }

    private static void expense(DataStore s, Program p, ExpenseCategory c, long amt, LocalDate date, String vendor, String what,
                                Review st, String by, String reviewer, String note) {
        Expense e = new Expense();
        e.id = s.newId("E", 4);
        e.programId = p.id; e.category = c; e.amount = amt; e.date = date.toString(); e.vendor = vendor; e.description = what;
        e.status = st; e.submittedBy = by; e.reviewedBy = reviewer; e.reviewNote = note; e.submittedAt = date + "T10:00:00";
        s.expenses.add(e);
    }
}
