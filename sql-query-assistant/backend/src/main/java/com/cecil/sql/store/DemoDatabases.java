package com.cecil.sql.store;

import com.cecil.sql.db.*;
import com.cecil.sql.db.TableSchema.ForeignKey;

import java.time.LocalDate;
import java.util.*;

import static com.cecil.sql.db.ColumnType.*;

/**
 * Builds the two made-up demo databases. A fixed random seed means the data is the same on every computer,
 * and dates are counted back from "today", so the demo always looks current. All names are invented.
 *
 *   koel_college  – Koel Valley Institute of Technology: departments, faculty, students, courses,
 *                   enrollments (marks), fee_payments
 *   ghar_store    – Ghar Store, an online home-goods shop: customers, products, orders, order_items
 */
public final class DemoDatabases {
    private DemoDatabases() {}

    public static final String COLLEGE = "koel_college";
    public static final String SHOP = "ghar_store";

    private static Column col(String name, ColumnType type, String label, String... syn) { return Column.of(name, type, label, syn); }
    private static Map<String, List<String>> words(String... valueThenWords) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String s : valueThenWords) {
            String[] p = s.split("=");
            m.put(p[0], List.of(p[1].split("\\|")));
        }
        return m;
    }
    private static TableSchema table(String name, String alias, String singular, String plural, String desc,
                                     List<String> syn, List<Column> cols, String pk, List<ForeignKey> fks,
                                     List<String> display, String nameCol) {
        return new TableSchema(name, alias, singular, plural, desc, syn, cols, pk, fks, display, nameCol);
    }

    // ================================================================== college
    static final String[][] DEPTS = {
            // id, name, code, building, established
            {"1", "Computer Science", "CSE", "Aryabhata Block", "2004"},
            {"2", "Information Technology", "IT", "Aryabhata Block", "2008"},
            {"3", "Electronics and Communication", "ECE", "Raman Block", "2004"},
            {"4", "Mechanical Engineering", "ME", "Visvesvaraya Block", "2004"},
            {"5", "Civil Engineering", "CE", "Visvesvaraya Block", "2006"},
            {"6", "Electrical Engineering", "EE", "Raman Block", "2010"},
            {"7", "Business Administration", "BBA", "Tagore Block", "2012"},
    };
    static final String[] DEPT_WORDS = {
            "Computer Science=cse|computer science|computer|cs department|comp sci",
            "Information Technology=information technology|it department|i.t.",
            "Electronics and Communication=ece|electronics|electronics and communication|communication",
            "Mechanical Engineering=mechanical|mech|me department",
            "Civil Engineering=civil",
            "Electrical Engineering=electrical|eee|ee department",
            "Business Administration=bba|business|management|business administration",
    };
    static final String[][] COURSES = {
            // code, title, dept, credits, semester
            {"CS201", "Data Structures", "1", "4", "3"}, {"CS202", "Database Management Systems", "1", "4", "4"},
            {"CS203", "Operating Systems", "1", "4", "4"}, {"CS301", "Computer Networks", "1", "3", "5"},
            {"CS302", "Java Programming", "1", "3", "3"}, {"CS401", "Machine Learning", "1", "3", "7"},
            {"CS402", "Cyber Security", "1", "3", "7"}, {"IT201", "Web Technologies", "2", "3", "3"},
            {"IT202", "Software Engineering", "2", "3", "4"}, {"IT301", "Cloud Computing", "2", "3", "5"},
            {"IT302", "Python Programming", "2", "3", "1"}, {"EC201", "Digital Electronics", "3", "4", "3"},
            {"EC202", "Signals and Systems", "3", "4", "4"}, {"EC301", "Microprocessors", "3", "3", "5"},
            {"ME201", "Thermodynamics", "4", "4", "3"}, {"ME202", "Fluid Mechanics", "4", "4", "4"},
            {"ME301", "Machine Design", "4", "3", "5"}, {"CE201", "Surveying", "5", "3", "3"},
            {"CE202", "Structural Analysis", "5", "4", "4"}, {"EE201", "Electrical Machines", "6", "4", "3"},
            {"EE202", "Power Systems", "6", "4", "5"}, {"BA101", "Principles of Management", "7", "3", "1"},
            {"BA201", "Marketing Management", "7", "3", "3"}, {"BA202", "Financial Accounting", "7", "3", "2"},
            {"GE101", "Engineering Mathematics", "1", "4", "1"}, {"GE102", "Communication Skills", "7", "2", "1"},
    };
    static final String[] FIRST_F = {"Priya", "Ananya", "Sneha", "Pooja", "Riya", "Kavya", "Neha", "Aisha", "Ishita", "Tanvi",
            "Shreya", "Nikita", "Simran", "Meera", "Divya", "Anjali", "Sakshi", "Muskan", "Ritu", "Payal", "Sonali", "Jyoti"};
    static final String[] FIRST_M = {"Rahul", "Aman", "Rohit", "Arjun", "Vikram", "Saurabh", "Aditya", "Karan", "Nikhil", "Abhishek",
            "Manish", "Deepak", "Sahil", "Harsh", "Kunal", "Ravi", "Ankit", "Suraj", "Aryan", "Imran", "Prakash", "Sunil"};
    static final String[] LAST = {"Sharma", "Kumar", "Singh", "Verma", "Gupta", "Oraon", "Munda", "Mahato", "Tirkey", "Ekka",
            "Prasad", "Mishra", "Sinha", "Das", "Hembrom", "Soren", "Toppo", "Lakra", "Ansari", "Jha", "Pandey", "Minz"};
    static final String[] CITIES = {"Ranchi", "Ranchi", "Ranchi", "Jamshedpur", "Jamshedpur", "Dhanbad", "Bokaro", "Hazaribagh",
            "Deoghar", "Giridih", "Patna", "Kolkata", "Gumla", "Ramgarh"};

    public static Database college(LocalDate today) {
        Random rnd = new Random(2026);
        Database db = new Database(COLLEGE, "Koel Valley Institute of Technology",
                "A made-up engineering college in Ranchi: departments, teachers, students, courses, marks and fees.", null);

        // --- departments
        TableSchema dept = table("departments", "d", "department", "departments", "Each academic department of the college.",
                List.of("dept", "branch", "stream", "school"),
                List.of(col("dept_id", INT, "Department ID"),
                        col("name", TEXT, "Department", "department name").withValueWords(words(DEPT_WORDS)),
                        col("code", TEXT, "Code", "short name"),
                        col("building", TEXT, "Building", "block"),
                        col("established", INT, "Year started", "established", "founded", "started")),
                "dept_id", List.of(), List.of("dept_id", "name", "code", "building"), "name");
        List<Object[]> drows = new ArrayList<>();
        for (String[] d : DEPTS) drows.add(new Object[]{Long.parseLong(d[0]), d[1], d[2], d[3], Long.parseLong(d[4])});
        db.add(new Table(dept, drows));

        // --- faculty
        TableSchema fac = table("faculty", "f", "teacher", "teachers", "Teaching staff. Salary and email are private.",
                List.of("faculty", "faculty member", "professor", "lecturer", "staff", "teaching staff", "instructor"),
                List.of(col("faculty_id", INT, "Faculty ID"),
                        col("name", TEXT, "Teacher", "teacher name"),
                        col("gender", TEXT, "Gender").withValueWords(words("Female=female|woman|women|lady|ladies",
                                "Male=male|man|men|gents")),
                        col("dept_id", INT, "Department ID"),
                        col("designation", TEXT, "Designation", "post", "rank", "position", "title").withValueWords(words(
                                "Professor=professor|full professor", "Associate Professor=associate professor|associate",
                                "Assistant Professor=assistant professor|assistant")),
                        col("salary", DECIMAL, "Monthly salary", "salary", "pay", "earn", "earns", "income").privateColumn().withUnit("rupees"),
                        col("joined", DATE, "Joining date", "joined", "joining", "hired", "join date"),
                        col("email", TEXT, "Email", "mail", "email id").privateColumn()),
                "faculty_id", List.of(new ForeignKey("dept_id", "departments", "dept_id")),
                List.of("faculty_id", "name", "designation", "dept_id"), "name");
        List<Object[]> frows = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        String[] titles = {"Professor", "Associate Professor", "Assistant Professor", "Assistant Professor"};
        int fid = 101;
        for (int d = 1; d <= DEPTS.length; d++) {
            int count = d <= 2 ? 6 : 4;
            for (int k = 0; k < count; k++) {
                boolean female = rnd.nextInt(100) < 45;
                String nm = uniqueName(rnd, female, usedNames);
                String title = k == 0 ? "Professor" : titles[1 + rnd.nextInt(3)];
                double base = title.equals("Professor") ? 168000 : title.startsWith("Associate") ? 124000 : 78000;
                double salary = Math.round((base + rnd.nextInt(26000)) / 500.0) * 500.0;
                LocalDate joined = today.minusDays(400 + rnd.nextInt(6200));
                String email = nm.toLowerCase().replace(" ", ".") + "@kvit.edu.in";
                frows.add(new Object[]{(long) fid++, "Dr. " + nm, female ? "Female" : "Male", (long) d, title, salary, joined.toString(), email});
            }
        }
        db.add(new Table(fac, frows));

        // --- students
        TableSchema stu = table("students", "s", "student", "students", "Everyone studying at the college. Email and phone are private.",
                List.of("pupil", "learner", "candidate", "scholar", "classmate"),
                List.of(col("student_id", INT, "Student ID", "roll", "roll number", "roll no"),
                        col("name", TEXT, "Student", "student name"),
                        col("gender", TEXT, "Gender", "sex").withValueWords(words("Female=female|girl|girls|woman|women|lady|ladies",
                                "Male=male|boy|boys|man|men")),
                        col("city", TEXT, "City", "hometown", "home town", "town", "place", "location", "live", "lives", "come from"),
                        col("dept_id", INT, "Department ID"),
                        col("semester", INT, "Semester", "sem", "term"),
                        col("admission_year", INT, "Admission year", "admitted", "admission", "joined", "batch", "enrolled in"),
                        col("cgpa", DECIMAL, "CGPA", "gpa", "grade point", "grade point average", "pointer", "score", "performance"),
                        col("hosteller", TEXT, "Hosteller", "hostel", "stays in hostel").withValueWords(words(
                                "Yes=hosteller|hostellers|in hostel|in the hostel|hostel students|live in hostel|stay in hostel|boarders",
                                "No=day scholar|day scholars|day-scholar|not in hostel")),
                        col("email", TEXT, "Email", "mail", "email id").privateColumn(),
                        col("phone", TEXT, "Phone", "mobile", "contact").privateColumn()),
                "student_id", List.of(new ForeignKey("dept_id", "departments", "dept_id")),
                List.of("student_id", "name", "city", "semester", "cgpa"), "name");
        List<Object[]> srows = new ArrayList<>();
        int sid = 1001;
        int currentYear = today.getYear();
        boolean oddTerm = today.getMonthValue() >= 7;   // July–December runs odd semesters
        for (int d = 1; d <= DEPTS.length; d++) {
            int count = d == 1 ? 52 : d == 2 ? 40 : d == 7 ? 28 : 30;
            for (int k = 0; k < count; k++) {
                boolean female = rnd.nextInt(100) < (d == 7 ? 55 : d >= 4 && d <= 6 ? 22 : 42);
                String nm = uniqueName(rnd, female, usedNames);
                int yearOfStudy = 1 + rnd.nextInt(4);
                int semester = yearOfStudy * 2 - (oddTerm ? 1 : 0);
                int admission = oddTerm ? currentYear - yearOfStudy + 1 : currentYear - yearOfStudy;
                double cgpa = Math.round(Math.min(9.9, Math.max(5.0, 7.2 + rnd.nextGaussian() * 1.05)) * 100.0) / 100.0;
                String city = CITIES[rnd.nextInt(CITIES.length)];
                boolean hostel = !city.equals("Ranchi") ? rnd.nextInt(100) < 75 : rnd.nextInt(100) < 15;
                String email = nm.toLowerCase().replace(" ", ".") + sid % 100 + "@student.kvit.edu.in";
                String phone = "9" + (100000000L + rnd.nextInt(899999999));
                srows.add(new Object[]{(long) sid++, nm, female ? "Female" : "Male", city, (long) d, (long) semester,
                        (long) admission, cgpa, hostel ? "Yes" : "No", email, phone});
            }
        }
        db.add(new Table(stu, srows));

        // --- courses
        TableSchema crs = table("courses", "c", "course", "courses", "Subjects taught, with who teaches them.",
                List.of("subject", "subjects", "paper", "papers", "class", "classes", "module"),
                List.of(col("course_id", INT, "Course ID"),
                        col("code", TEXT, "Course code", "course code", "subject code"),
                        col("title", TEXT, "Course", "course name", "subject name", "title").withValueWords(words(
                                "Database Management Systems=dbms|database|databases", "Data Structures=ds|dsa|data structure",
                                "Operating Systems=os|operating system", "Computer Networks=cn|networking|networks",
                                "Java Programming=java", "Machine Learning=ml", "Python Programming=python",
                                "Cyber Security=cybersecurity|security", "Engineering Mathematics=maths|math|mathematics")),
                        col("dept_id", INT, "Department ID"),
                        col("credits", INT, "Credits", "credit", "credit hours"),
                        col("faculty_id", INT, "Teacher ID"),
                        col("semester", INT, "Semester", "sem", "term")),
                "course_id", List.of(new ForeignKey("dept_id", "departments", "dept_id"), new ForeignKey("faculty_id", "faculty", "faculty_id")),
                List.of("code", "title", "credits", "semester"), "title");
        List<Object[]> crows = new ArrayList<>();
        Map<Long, List<Long>> facByDept = new HashMap<>();
        for (Object[] f : frows) facByDept.computeIfAbsent((Long) f[3], x -> new ArrayList<>()).add((Long) f[0]);
        long cid = 1;
        for (String[] c : COURSES) {
            long d = Long.parseLong(c[2]);
            List<Long> fs = facByDept.get(d);
            crows.add(new Object[]{cid++, c[0], c[1], d, Long.parseLong(c[3]), fs.get(rnd.nextInt(fs.size())), Long.parseLong(c[4])});
        }
        db.add(new Table(crs, crows));

        // --- enrollments (marks)
        TableSchema enr = table("enrollments", "e", "enrollment", "enrollments", "Which student takes which course, with marks and attendance.",
                List.of("enrolment", "registration", "registrations", "marks list", "result", "results", "exam"),
                List.of(col("enrollment_id", INT, "Enrollment ID"),
                        col("student_id", INT, "Student ID"),
                        col("course_id", INT, "Course ID"),
                        col("marks", INT, "Marks", "mark", "score", "scored", "scores", "got", "exam marks"),
                        col("grade", TEXT, "Grade", "letter grade").withValueWords(words("F=fail|failed|failing|fails")),
                        col("attendance", INT, "Attendance %", "attendance", "present", "attendance percentage").withUnit("percent")),
                "enrollment_id", List.of(new ForeignKey("student_id", "students", "student_id"), new ForeignKey("course_id", "courses", "course_id")),
                List.of("enrollment_id", "student_id", "course_id", "marks", "grade", "attendance"), null);
        List<Object[]> erows = new ArrayList<>();
        long eid = 1;
        for (Object[] s : srows) {
            long d = (Long) s[4];
            double cg = (Double) s[7];
            List<Object[]> options = new ArrayList<>();
            for (Object[] c : crows) if ((Long) c[3] == d || ((String) c[1]).startsWith("GE")) options.add(c);
            Collections.shuffle(options, rnd);
            int take = Math.min(options.size(), 3 + rnd.nextInt(2));
            for (int k = 0; k < take; k++) {
                int marks = (int) Math.round(Math.min(99, Math.max(18, cg * 9.2 + rnd.nextGaussian() * 9 - 2)));
                int att = (int) Math.min(100, Math.max(48, 80 + rnd.nextGaussian() * 11));
                erows.add(new Object[]{eid++, s[0], options.get(k)[0], (long) marks, grade(marks), (long) att});
            }
        }
        db.add(new Table(enr, erows));

        // --- fee payments
        TableSchema fee = table("fee_payments", "p", "payment", "payments", "Fees paid by students.",
                List.of("fee", "fees", "fee payment", "fee payments", "transaction", "transactions", "dues", "receipt"),
                List.of(col("payment_id", INT, "Payment ID", "receipt number"),
                        col("student_id", INT, "Student ID"),
                        col("purpose", TEXT, "Purpose", "type", "kind").withValueWords(words(
                                "Tuition=tuition|semester fee|course fee", "Hostel=hostel fee|hostel fees|hostel",
                                "Exam=exam fee|exam fees|examination")),
                        col("amount", DECIMAL, "Amount", "fee amount", "money", "rupees", "collection", "collected", "revenue").withUnit("rupees"),
                        col("paid_on", DATE, "Paid on", "date", "payment date", "paid on", "when"),
                        col("mode", TEXT, "Mode", "payment mode", "method", "via", "using", "through").withValueWords(words(
                                "UPI=upi|gpay|phonepe|paytm", "Net Banking=net banking|netbanking|online banking", "Card=card|debit card|credit card",
                                "Cash=cash")),
                        col("status", TEXT, "Status", "state").withValueWords(words("Pending=pending|unpaid|due|not paid",
                                "Failed=failed|unsuccessful|declined", "Paid=paid|successful|completed|cleared"))),
                "payment_id", List.of(new ForeignKey("student_id", "students", "student_id")),
                List.of("payment_id", "student_id", "purpose", "amount", "paid_on", "mode", "status"), null);
        List<Object[]> prows = new ArrayList<>();
        long pid = 5001;
        String[] modes = {"UPI", "UPI", "UPI", "Net Banking", "Card", "Cash"};
        for (Object[] s : srows) {
            long d = (Long) s[4];
            double tuition = d == 7 ? 38000 : d <= 2 ? 62000 : 55000;
            LocalDate when = today.minusDays(5 + rnd.nextInt(320));
            prows.add(payment(pid++, s, "Tuition", tuition, when, modes[rnd.nextInt(modes.length)], rnd));
            if ("Yes".equals(s[8])) prows.add(payment(pid++, s, "Hostel", 32000, today.minusDays(5 + rnd.nextInt(320)), modes[rnd.nextInt(modes.length)], rnd));
            if (rnd.nextInt(100) < 55) prows.add(payment(pid++, s, "Exam", 2500, today.minusDays(5 + rnd.nextInt(200)), modes[rnd.nextInt(modes.length)], rnd));
        }
        prows.sort(Comparator.comparing(r -> (String) r[4]));
        long renumber = 5001;
        for (Object[] r : prows) r[0] = renumber++;
        db.add(new Table(fee, prows));
        return db;
    }

    private static Object[] payment(long id, Object[] s, String purpose, double amount, LocalDate when, String mode, Random rnd) {
        int roll = rnd.nextInt(100);
        String status = roll < 86 ? "Paid" : roll < 96 ? "Pending" : "Failed";
        return new Object[]{id, s[0], purpose, amount, when.toString(), mode, status};
    }

    static String grade(int m) {
        return m >= 90 ? "O" : m >= 80 ? "A+" : m >= 70 ? "A" : m >= 60 ? "B+" : m >= 50 ? "B" : m >= 40 ? "C" : "F";
    }

    private static String uniqueName(Random rnd, boolean female, Set<String> used) {
        for (int tries = 0; tries < 200; tries++) {
            String f = female ? FIRST_F[rnd.nextInt(FIRST_F.length)] : FIRST_M[rnd.nextInt(FIRST_M.length)];
            String n = f + " " + LAST[rnd.nextInt(LAST.length)];
            if (used.add(n)) return n;
        }
        String n = (female ? "Anita" : "Anil") + " " + LAST[rnd.nextInt(LAST.length)] + " " + used.size();
        used.add(n);
        return n;
    }

    // ================================================================== shop
    static final String[][] PRODUCTS = {
            // name, category, price
            {"Steel Pressure Cooker 5L", "Kitchen", "2199"}, {"Non-stick Tawa", "Kitchen", "899"}, {"Copper Water Bottle", "Kitchen", "749"},
            {"Mixer Grinder 750W", "Kitchen", "3499"}, {"Spice Box (Masala Dabba)", "Kitchen", "649"}, {"Glass Storage Jars (Set of 6)", "Kitchen", "999"},
            {"Cast Iron Kadai", "Kitchen", "1499"}, {"Electric Kettle 1.5L", "Kitchen", "1299"},
            {"Sheesham Wood Coffee Table", "Furniture", "8999"}, {"Cane Lounge Chair", "Furniture", "6499"}, {"Folding Study Table", "Furniture", "2799"},
            {"Bookshelf 5-Tier", "Furniture", "4599"}, {"Shoe Rack", "Furniture", "1899"}, {"Bean Bag XXL", "Furniture", "2299"},
            {"Brass Diya Set", "Decor", "599"}, {"Madhubani Wall Painting", "Decor", "2499"}, {"Terracotta Vase", "Decor", "799"},
            {"Dokra Elephant Figure", "Decor", "1799"}, {"Jute Rug 4x6", "Decor", "1999"}, {"Wall Clock Wooden", "Decor", "1199"},
            {"LED Bulb 9W (Pack of 4)", "Lighting", "449"}, {"Floor Lamp", "Lighting", "3299"}, {"Fairy String Lights", "Lighting", "399"},
            {"Bedside Table Lamp", "Lighting", "1399"}, {"Solar Garden Light", "Lighting", "899"},
            {"Cotton Bedsheet Double", "Bedding", "1299"}, {"Jaipuri Razai (Quilt)", "Bedding", "2699"}, {"Memory Foam Pillow", "Bedding", "1099"},
            {"Mosquito Net Double", "Bedding", "699"}, {"Blanket Fleece", "Bedding", "1599"},
            {"Microfibre Mop", "Cleaning", "899"}, {"Broom (Phool Jhadu)", "Cleaning", "199"}, {"Dustbin Pedal 12L", "Cleaning", "849"},
            {"Cleaning Cloth (Pack of 10)", "Cleaning", "299"}, {"Laundry Basket", "Cleaning", "749"},
    };
    static final String[][] PLACES = {
            {"Ranchi", "Jharkhand"}, {"Ranchi", "Jharkhand"}, {"Jamshedpur", "Jharkhand"}, {"Dhanbad", "Jharkhand"},
            {"Patna", "Bihar"}, {"Gaya", "Bihar"}, {"Kolkata", "West Bengal"}, {"Kolkata", "West Bengal"}, {"Durgapur", "West Bengal"},
            {"Bhubaneswar", "Odisha"}, {"Cuttack", "Odisha"}, {"Lucknow", "Uttar Pradesh"}, {"Varanasi", "Uttar Pradesh"},
            {"Delhi", "Delhi"}, {"Delhi", "Delhi"}, {"Bengaluru", "Karnataka"}, {"Pune", "Maharashtra"}, {"Mumbai", "Maharashtra"},
    };

    public static Database shop(LocalDate today) {
        Random rnd = new Random(7);
        Database db = new Database(SHOP, "Ghar Store (online shop)",
                "A made-up online shop for home goods: customers, products, orders and the items in each order.", null);

        TableSchema cust = table("customers", "cu", "customer", "customers", "People who have an account. Email is private.",
                List.of("buyer", "buyers", "client", "clients", "shopper", "shoppers", "user", "users"),
                List.of(col("customer_id", INT, "Customer ID"),
                        col("name", TEXT, "Customer", "customer name"),
                        col("city", TEXT, "City", "town", "location", "live", "lives"),
                        col("state", TEXT, "State", "region", "province"),
                        col("joined", DATE, "Joined on", "joined", "signed up", "registered", "sign up", "join date"),
                        col("segment", TEXT, "Segment", "type", "customer type", "kind").withValueWords(words(
                                "Wholesale=wholesale|wholesaler|wholesalers|bulk|business", "Retail=retail|regular|normal")),
                        col("email", TEXT, "Email", "mail").privateColumn()),
                "customer_id", List.of(), List.of("customer_id", "name", "city", "state", "segment"), "name");
        List<Object[]> curows = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 1; i <= 80; i++) {
            boolean female = rnd.nextBoolean();
            String nm = uniqueName(rnd, female, used);
            String[] pl = PLACES[rnd.nextInt(PLACES.length)];
            curows.add(new Object[]{(long) i, nm, pl[0], pl[1], today.minusDays(30 + rnd.nextInt(900)).toString(),
                    rnd.nextInt(100) < 18 ? "Wholesale" : "Retail", nm.toLowerCase().replace(" ", "") + i + "@mail.example"});
        }
        db.add(new Table(cust, curows));

        TableSchema prod = table("products", "pr", "product", "products", "Everything the shop sells.",
                List.of("item", "items", "goods", "thing", "things", "stock item", "article"),
                List.of(col("product_id", INT, "Product ID"),
                        col("name", TEXT, "Product", "product name", "item name").withValueWords(words(
                                "Steel Pressure Cooker 5L=pressure cooker|cooker", "Mixer Grinder 750W=mixer|grinder|mixer grinder",
                                "Electric Kettle 1.5L=kettle", "Bean Bag XXL=bean bag", "Floor Lamp=floor lamp")),
                        col("category", TEXT, "Category", "type", "kind", "section", "department"),
                        col("price", DECIMAL, "Price", "cost", "mrp", "rate", "costs", "costing", "priced", "expensive", "cheap").withUnit("rupees"),
                        col("stock", INT, "Stock", "in stock", "quantity left", "units left", "inventory", "available")),
                "product_id", List.of(), List.of("product_id", "name", "category", "price", "stock"), "name");
        List<Object[]> prrows = new ArrayList<>();
        long prid = 1;
        for (String[] p : PRODUCTS) prrows.add(new Object[]{prid++, p[0], p[1], Double.parseDouble(p[2]), (long) (rnd.nextInt(10) == 0 ? 0 : rnd.nextInt(140))});
        db.add(new Table(prod, prrows));

        TableSchema ord = table("orders", "o", "order", "orders", "Each order placed on the shop.",
                List.of("purchase", "purchases", "sale", "sales", "booking", "bookings"),
                List.of(col("order_id", INT, "Order ID", "order number"),
                        col("customer_id", INT, "Customer ID"),
                        col("order_date", DATE, "Order date", "date", "ordered on", "placed", "ordered", "bought", "when"),
                        col("status", TEXT, "Status", "state").withValueWords(words("Delivered=delivered|completed",
                                "Cancelled=cancelled|canceled", "Returned=returned|return|returns", "Shipped=shipped|on the way|in transit",
                                "Processing=processing|pending|not shipped")),
                        col("payment_mode", TEXT, "Payment mode", "paid by", "payment", "paid with", "via", "using", "through").withValueWords(words(
                                "COD=cod|cash on delivery|cash", "UPI=upi|gpay|phonepe|paytm", "Card=card|debit card|credit card",
                                "Net Banking=net banking|netbanking"))),
                "order_id", List.of(new ForeignKey("customer_id", "customers", "customer_id")),
                List.of("order_id", "customer_id", "order_date", "status", "payment_mode"), null);
        TableSchema item = table("order_items", "oi", "order item", "order items", "The products inside each order. amount = quantity x unit price.",
                List.of("line item", "line items", "item sold", "items sold", "basket", "sold", "sell", "sells", "selling"),
                List.of(col("item_id", INT, "Item ID"),
                        col("order_id", INT, "Order ID"),
                        col("product_id", INT, "Product ID"),
                        col("quantity", INT, "Quantity", "qty", "units", "pieces", "units sold"),
                        col("unit_price", DECIMAL, "Unit price", "selling price", "sold at").withUnit("rupees"),
                        col("amount", DECIMAL, "Amount", "line total", "revenue", "sales", "sales value", "order value", "value",
                                "spent", "spend", "spending", "earning", "earnings", "turnover", "money", "income", "worth").withUnit("rupees")),
                "item_id", List.of(new ForeignKey("order_id", "orders", "order_id"), new ForeignKey("product_id", "products", "product_id")),
                List.of("item_id", "order_id", "product_id", "quantity", "unit_price", "amount"), null);
        List<Object[]> orows = new ArrayList<>(), irows = new ArrayList<>();
        String[] pay = {"UPI", "UPI", "UPI", "COD", "COD", "Card", "Net Banking"};
        long iid = 1;
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 320; i++) dates.add(today.minusDays(rnd.nextInt(420)));
        Collections.sort(dates);
        for (int i = 0; i < dates.size(); i++) {
            long oid = 10001 + i;
            LocalDate d = dates.get(i);
            long age = today.toEpochDay() - d.toEpochDay();
            int roll = rnd.nextInt(100);
            String status = age < 3 ? "Processing" : age < 8 ? (roll < 70 ? "Shipped" : "Processing")
                    : roll < 82 ? "Delivered" : roll < 92 ? "Cancelled" : "Returned";
            orows.add(new Object[]{oid, (long) (1 + rnd.nextInt(80)), d.toString(), status, pay[rnd.nextInt(pay.length)]});
            int lines = 1 + rnd.nextInt(3);
            Set<Integer> picked = new HashSet<>();
            for (int k = 0; k < lines; k++) {
                int p = rnd.nextInt(prrows.size());
                if (!picked.add(p)) continue;
                double price = (Double) prrows.get(p)[3];
                if (rnd.nextInt(100) < 20) price = Math.round(price * 0.9);
                long qty = 1 + (rnd.nextInt(100) < 80 ? rnd.nextInt(2) : rnd.nextInt(6));
                irows.add(new Object[]{iid++, oid, prrows.get(p)[0], qty, price, price * qty});
            }
        }
        db.add(new Table(ord, orows));
        db.add(new Table(item, irows));
        return db;
    }
}
