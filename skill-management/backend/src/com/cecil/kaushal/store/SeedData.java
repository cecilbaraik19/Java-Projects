package com.cecil.kaushal.store;

import com.cecil.kaushal.core.Levels;
import com.cecil.kaushal.model.Category;
import com.cecil.kaushal.model.Claim;
import com.cecil.kaushal.model.ClaimStatus;
import com.cecil.kaushal.model.Goal;
import com.cecil.kaushal.model.Note;
import com.cecil.kaushal.model.Project;
import com.cecil.kaushal.model.Role;
import com.cecil.kaushal.model.Skill;
import com.cecil.kaushal.model.User;
import com.cecil.kaushal.service.Access;
import com.cecil.kaushal.service.Problem;
import com.cecil.kaushal.util.Clock;
import com.cecil.kaushal.util.Json;
import com.cecil.kaushal.util.Passwords;
import com.cecil.kaushal.web.ApiServer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The demo company "Tarang Technologies" (made up): 24 people in Ranchi, Bengaluru and Pune, 41 skills, 16 job roles,
 * 5 projects and about a year of history.
 *
 * The history is NOT typed in as finished data. It is PLAYED THROUGH THE REAL SERVICES with the clock moved back:
 * people claim skills, their manager or an assessor checks them a few hours or days later (some at a lower level with
 * a reason, some sent back with a question), people earn certificates, endorse each other, make learning plans,
 * finish goals and get staffed on projects; on the first of every month the snapshot for the trend chart is taken.
 * So every number in the reports got there by the normal rules. Everything is dated relative to "now".
 */
public final class SeedData {
    private SeedData() { }

    static final long H = 3_600_000L, D = 24 * H;

    // ---------------------------------------------------------------- catalogue: category -> skills (symbol, name, synonyms, description)
    static final Object[][] CATS = {
            { "Languages", "lang", 24, new String[][] {
                    { "Jv", "Java", "java se,core java", "Object-oriented language for back-end services and Android." },
                    { "Py", "Python", "py,python3", "Scripting, automation, data work and machine learning." },
                    { "Js", "JavaScript", "js,ecmascript", "The language of the web browser (and Node.js)." },
                    { "Ts", "TypeScript", "ts", "JavaScript with types; catches mistakes before running." },
                    { "Go", "Go", "golang", "Small fast language for cloud tools and services." },
                    { "Sq", "SQL", "structured query language", "Asking questions of relational databases." },
                    { "Kt", "Kotlin", "", "Modern JVM language, the default for Android." } } },
            { "Web & Mobile", "web", 18, new String[][] {
                    { "Re", "React", "react.js,reactjs", "Component-based user interfaces in the browser." },
                    { "Sb", "Spring Boot", "spring,springboot", "Java framework for REST services." },
                    { "Nd", "Node.js", "node,nodejs", "JavaScript on the server." },
                    { "Hc", "HTML & CSS", "html,css,html5", "Page structure and styling." },
                    { "An", "Android", "android development", "Native Android apps." },
                    { "Ap", "REST API design", "api design,rest,rest api", "Designing clear, versioned HTTP APIs." } } },
            { "Cloud & DevOps", "cloud", 12, new String[][] {
                    { "Aw", "AWS", "amazon web services", "Amazon's cloud: EC2, S3, RDS, IAM, Lambda ..." },
                    { "Az", "Azure", "microsoft azure", "Microsoft's cloud." },
                    { "Dk", "Docker", "containers", "Packaging apps as containers." },
                    { "K8s", "Kubernetes", "k8s,kube", "Running containers at scale." },
                    { "Tf", "Terraform", "iac,infrastructure as code", "Cloud set-up written as code." },
                    { "Ci", "CI/CD", "ci,cd,github actions,jenkins", "Automatic build, test and deploy pipelines." },
                    { "Lx", "Linux", "unix,bash,shell", "Servers, shell and system administration." } } },
            { "Data & AI", "data", 15, new String[][] {
                    { "Pg", "PostgreSQL", "postgres,psql", "Open-source relational database." },
                    { "Dm", "Data modelling", "data modeling,er diagrams", "Designing tables, keys and relationships." },
                    { "Pb", "Power BI", "powerbi", "Dashboards and reports for business users." },
                    { "Ml", "Machine learning", "ml,ai", "Models that learn from data." },
                    { "Kf", "Apache Kafka", "kafka,event streaming", "Event streaming between services." } } },
            { "Security", "sec", 12, new String[][] {
                    { "As", "Application security", "appsec,owasp,secure coding", "Finding and fixing weaknesses in software (OWASP Top 10)." },
                    { "Ns", "Network security", "netsec,firewalls", "Firewalls, segmentation, VPNs, IDS." },
                    { "Cs", "Cloud security", "cloudsec", "Identity, keys and guard-rails in the cloud." },
                    { "Ir", "Incident response", "ir,soc", "Handling security incidents: detect, contain, recover." } } },
            { "Design", "design", 24, new String[][] {
                    { "Ux", "UX research", "user research,ux", "Interviews, usability tests, finding real needs." },
                    { "Ui", "UI design", "visual design,ui", "Layouts, type, colour and components." },
                    { "Fg", "Figma", "", "The design and prototyping tool." },
                    { "Ax", "Accessibility", "a11y,wcag", "Making products usable for everyone (WCAG)." } } },
            { "Ways of working", "method", 36, new String[][] {
                    { "Sc", "Agile & Scrum", "agile,scrum,kanban", "Working in short iterations with a team." },
                    { "Qa", "Testing & QA", "testing,qa,unit testing", "Unit, integration and exploratory testing." },
                    { "Sd", "System design", "architecture,software architecture", "Designing services, data flow and trade-offs." },
                    { "Tw", "Technical writing", "documentation,docs", "Clear docs, runbooks and specs." } } },
            { "People skills", "soft", 60, new String[][] {
                    { "Cm", "Communication", "presenting", "Explaining clearly, listening, writing well." },
                    { "Mn", "Mentoring", "coaching", "Helping others grow." },
                    { "Sh", "Stakeholder management", "stakeholders,client management", "Keeping clients and partners aligned." },
                    { "Ld", "Leadership", "people management", "Leading a team: direction, feedback, decisions." } } },
    };

    /** skill symbol -> its prerequisites "Sym:level". */
    static final String[][] PREREQS = {
            { "K8s", "Dk:2", "Lx:2" }, { "Sb", "Jv:2" }, { "Re", "Js:2" }, { "Ts", "Js:2" }, { "Nd", "Js:2" }, { "Tf", "Lx:1" },
            { "Ml", "Py:2" }, { "Kf", "Lx:1" }, { "Cs", "Aw:2" }, { "Sd", "Ap:2" }, { "Ld", "Mn:2" }, { "Pb", "Sq:2" },
            { "Pg", "Sq:2" }, { "An", "Kt:2" }, { "Ci", "Lx:1" }, { "Ui", "Fg:2" }, { "Ax", "Hc:2" },
    };

    /** key, name, ladder, step, description, requirements "Sym level [m] weight". */
    static final String[][] ROLES = {
            { "B1", "Backend Engineer", "Backend", "1", "Builds and runs services in Java and Spring Boot.", "Jv 3 m 3, Sb 2 m 3, Sq 2 m 2, Ap 2 2, Pg 2 1, Dk 1 1, Qa 2 1, Sc 2 1" },
            { "B2", "Senior Backend Engineer", "Backend", "2", "Owns services end to end and reviews others' designs.", "Jv 4 m 3, Sb 4 m 3, Sq 3 m 2, Ap 3 m 2, Pg 3 2, Dk 2 2, K8s 2 1, Kf 2 1, Sd 3 2, Qa 3 1, Mn 2 1" },
            { "B3", "Lead Backend Engineer", "Backend", "3", "Sets the technical direction for a group of services.", "Jv 4 m 2, Sb 4 m 2, Sd 4 m 3, Ap 4 2, K8s 3 1, Kf 3 1, Mn 3 m 2, Sh 3 2, Cm 4 2" },
            { "F1", "Frontend Engineer", "Frontend", "1", "Builds accessible web interfaces in React.", "Js 3 m 3, Re 3 m 3, Hc 3 m 2, Ts 2 2, Ax 1 1, Qa 2 1, Fg 1 1" },
            { "F2", "Senior Frontend Engineer", "Frontend", "2", "Owns the front-end architecture and quality.", "Js 4 m 3, Re 4 m 3, Ts 3 m 2, Hc 4 2, Ax 3 2, Ap 3 1, Sd 3 1, Mn 2 1, Ci 2 1" },
            { "P1", "DevOps Engineer", "Platform", "1", "Keeps builds, deploys and servers running.", "Lx 3 m 3, Dk 3 m 3, Aw 2 m 2, Ci 3 m 2, Tf 2 2, K8s 2 2, Py 2 1" },
            { "P2", "Senior Platform Engineer", "Platform", "2", "Designs the platform other teams build on.", "Lx 4 m 2, K8s 4 m 3, Tf 3 m 2, Aw 3 m 2, Ci 4 2, Cs 2 1, Go 2 1, Mn 2 1" },
            { "D1", "Data Analyst", "Data", "1", "Turns data into reports and answers.", "Sq 3 m 3, Pb 3 m 3, Dm 2 2, Py 2 2, Cm 3 1" },
            { "D2", "Data Engineer", "Data", "2", "Builds data pipelines and the warehouse.", "Py 4 m 3, Sq 4 m 3, Kf 3 m 2, Dm 3 m 2, Pg 3 2, Aw 2 1, Ml 2 1" },
            { "S1", "Security Analyst", "Security", "1", "Watches, tests and responds to security events.", "Ns 3 m 3, Ir 2 m 2, Lx 2 m 2, As 2 2, Py 2 1, Tw 2 1" },
            { "S2", "Senior Security Engineer", "Security", "2", "Secures applications and cloud by design.", "As 4 m 3, Cs 3 m 3, Ir 3 m 2, Ns 3 2, Aw 2 2, Mn 2 1, Tw 3 1" },
            { "U1", "Product Designer", "Design", "1", "Designs screens and flows from research.", "Ui 3 m 3, Fg 3 m 3, Ux 2 m 2, Ax 2 2, Cm 3 1, Hc 1 1" },
            { "U2", "Senior Product Designer", "Design", "2", "Leads research and the design system.", "Ux 4 m 3, Ui 4 m 3, Fg 4 2, Ax 3 m 2, Sh 3 2, Mn 2 1" },
            { "Q1", "QA Engineer", "Quality", "1", "Plans and automates testing.", "Qa 3 m 3, Jv 2 2, Sq 2 2, Ci 2 2, Ap 2 1, Sc 2 1" },
            { "M1", "Engineering Manager", "Management", "1", "Leads a team of engineers and their growth.", "Ld 3 m 3, Mn 3 m 3, Sh 3 m 2, Cm 4 m 2, Sc 3 2, Sd 3 2" },
            { "H1", "HR Business Partner", "People", "1", "Partners with teams on hiring, growth and skills.", "Cm 4 m 3, Sh 3 m 2, Tw 3 2, Pb 2 1, Mn 2 1" },
    };

    /**
     * username, name, title, dept, location, role, manager, job role, target role, skills.
     * Skills: "Jv4" verified at 4 · "Jv2>3" verified at 2, later at 3 · "Hc4~3" claimed 4, checked at 3 ·
     * "Re3?" claimed, still waiting · "Sd3!" sent back with a question · "@26" last used 26 months ago.
     */
    static final String[][] PEOPLE = {
            { "anil", "Anil Tirkey", "Managing Director & CTO", "Leadership", "Ranchi", "MANAGER", "", "M1", "", "Jv4@40 Sd5 Ap4@14 Ld5 Mn4 Sh5 Cm4 Sc4 Aw3@20 Go2@60" },
            { "meera", "Meera Kujur", "Head of People (HR)", "People", "Ranchi", "ADMIN", "anil", "H1", "", "Cm4 Sh4 Tw3 Pb3 Mn3 Ld3 Sc2 Sq2" },
            { "ravi", "Ravi Munda", "Engineering Manager, Backend & Platform", "Engineering", "Ranchi", "MANAGER", "anil", "M1", "", "Jv5@10 Sb4@12 Sd4 Ap4 Sq4 Pg3 Kf3 Ld3 Mn4 Sh3 Cm4 Sc4 Dk3 K8s2@20 Lx3" },
            { "priya", "Priya Nair", "Engineering Manager, Frontend & Design", "Engineering", "Bengaluru", "MANAGER", "anil", "M1", "", "Js5@8 Re4@8 Ts4 Hc4 Ax3 Ux3 Ld4 Mn4 Sh4 Cm5 Sc4 Ap2 Sd3" },
            { "sunil", "Sunil Ekka", "Security Lead", "Security", "Pune", "MANAGER", "anil", "S2", "M1", "As5 Cs4 Ns4 Ir4 Aw3 Lx4 Py3 Tw3 Mn3 Ld3 Sh3 Cm3" },
            { "nisha", "Nisha Toppo", "People Partner", "People", "Ranchi", "EMPLOYEE", "meera", "H1", "", "Cm3 Sh2 Tw3 Sq2 Pb2 Mn1 Ux1?" },
            { "vikram", "Vikram Sahu", "Senior Backend Engineer", "Engineering", "Ranchi", "EMPLOYEE", "ravi", "B2", "B3", "Jv5 Sb4 Sq4 Pg4 Ap4 Sd3>4? Kf3 Lx2 Dk3 K8s2 Qa3 Mn3 Cm3 Go3@28 Sc3" },
            { "asha", "Asha Oraon", "Backend Engineer", "Engineering", "Ranchi", "EMPLOYEE", "ravi", "B1", "B2", "Jv2>3 Sb2>3 Sq3 Pg2 Ap2>3 Dk2 Qa2 Sc3 Cm3 Py2@22 Kf1 Hc2@30" },
            { "neha", "Neha Gupta", "Backend Engineer", "Engineering", "Ranchi", "EMPLOYEE", "ravi", "B1", "", "Jv3 Sb3 Sq2 Ap2 Pg2 Dk1 Qa3 Sc2 Py3 Cm2 Kt2@19 Sd3!" },
            { "sanjay", "Sanjay Kumar", "Backend Engineer", "Engineering", "Pune", "EMPLOYEE", "ravi", "B1", "", "Jv3 Sb2 Sq3 Pg3 Ap2 Dk2 Kf2 Lx2 Go2 Sc2 Cm2 Qa2" },
            { "rohit", "Rohit Lakra", "DevOps Engineer", "Engineering", "Ranchi", "EMPLOYEE", "ravi", "P1", "P2", "Lx3 Dk3 Aw3 Ci3 Tf2 K8s2>3 Py2 Go1 Sc2 Cm2 Az2@26 Tw2?" },
            { "farhan", "Farhan Ali", "Senior Platform Engineer", "Engineering", "Bengaluru", "EMPLOYEE", "ravi", "P2", "", "Lx5 Dk4 K8s4 Tf4 Aw4 Az3@15 Ci4 Cs2 Go3 Py3 Mn3 Sd3 Ap2 Kf2" },
            { "kavya", "Kavya Reddy", "Data Engineer", "Engineering", "Bengaluru", "EMPLOYEE", "ravi", "D2", "", "Py4 Sq4 Kf3 Dm3 Pg3 Aw2 Ml2>3? Pb2 Dk2 Lx2 Sc2 Cm3" },
            { "imran", "Imran Khan", "Data Analyst", "Engineering", "Pune", "EMPLOYEE", "ravi", "D1", "D2", "Sq3 Pb4~3 Dm2 Py2 Cm3 Ml1 Pg2 Sh2" },
            { "deepa", "Deepa Minz", "QA Engineer", "Engineering", "Ranchi", "EMPLOYEE", "ravi", "Q1", "", "Qa4 Jv2 Sq2 Lx1 Ci2 Ap2 Sc3 Py2 Tw2 Js2 Hc2 Ax2" },
            { "arjun", "Arjun Mehta", "Senior Frontend Engineer", "Engineering", "Bengaluru", "EMPLOYEE", "priya", "F2", "", "Js5 Re4 Ts4 Hc4 Ax3 Ap3 Sd3 Mn2 Lx1 Ci2 Nd3 Fg2 Sc3 Cm3" },
            { "sneha", "Sneha Kerketta", "Frontend Engineer", "Engineering", "Ranchi", "EMPLOYEE", "priya", "F1", "F2", "Js3 Re4~3 Hc3 Ts1>2 Ax1 Qa2 Fg2 Sc2 Cm3 Nd2" },
            { "pooja", "Pooja Soren", "Frontend Engineer", "Engineering", "Bengaluru", "EMPLOYEE", "priya", "F1", "", "Js3 Re3 Hc4~3 Ts2 Ax3 Fg2 Ui2 Qa2 Cm2 Kt2@24 An2@24" },
            { "karan", "Karan Malhotra", "Frontend Engineer", "Engineering", "Pune", "EMPLOYEE", "priya", "F1", "", "Js3 Hc3 Ts2 Nd3 Qa1 Sc2 Re3?" },
            { "tanvi", "Tanvi Joshi", "Product Designer", "Design", "Bengaluru", "EMPLOYEE", "priya", "U1", "U2", "Fg4 Ui3 Ux3 Hc2 Ax2 Cm3 Sh2 Tw2" },
            { "rahul", "Rahul Bhagat", "Senior Product Designer", "Design", "Ranchi", "EMPLOYEE", "priya", "U2", "", "Ux4 Fg4 Ui4 Hc2 Ax3 Sh3 Mn3 Cm4 Tw3" },
            { "manoj", "Manoj Hembrom", "Senior Security Engineer", "Security", "Pune", "EMPLOYEE", "sunil", "S2", "", "As4 Aw2 Cs3 Ir3 Ns3 Lx3 Py3 Mn2 Tw3 Dk2" },
            { "ayesha", "Ayesha Siddiqui", "Security Analyst", "Security", "Pune", "EMPLOYEE", "sunil", "S1", "S2", "Ns3 Ir2 Lx3 As2>3 Py2 Tw2 Aw1 Cm3" },
            { "ritika", "Ritika Das", "Security Analyst", "Security", "Ranchi", "EMPLOYEE", "sunil", "S1", "", "Ir2 Lx2 As1 Py2 Tw3 Cm2 Ns3?" },
    };

    static final String[] ADJUST_NOTES = {
            "Strong work - for level %d I'd like to see you review others' work too. Let's aim for it next quarter.",
            "Good progress. Level %d fits what I saw on the last release; claim again after the next big task.",
    };
    static final String RETURN_NOTE = "Which design did you lead? Please add an example (a doc or a pull request) and claim again.";
    static final String[] WORK = { "the Kestrel payments API", "the analytics hub", "the old intranet", "the e-Mandi pilot",
            "client support tickets", "the hiring portal", "the internal SSO", "the release pipeline" };

    record Item(String sym, int first, int later, int verifyAs, char mark, int monthsAgo) { }

    static Item item(String code) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Z][a-z0-9]{1,2})(\\d)(?:>(\\d))?(?:~(\\d))?([?!])?(?:@(\\d+))?").matcher(code);
        if (!m.matches()) throw new IllegalArgumentException("Bad seed skill " + code);
        int first = Integer.parseInt(m.group(2));
        int later = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        int as = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
        char mark = m.group(5) == null ? ' ' : m.group(5).charAt(0);
        int ago = m.group(6) == null ? -1 : Integer.parseInt(m.group(6));
        return new Item(m.group(1), first, later, as, mark, ago);
    }

    // ---------------------------------------------------------------- the play-through

    public static void load(ApiServer.Parts p) {
        DataStore s = p.store();
        Instant realFixed = Clock.fixed();
        long now = Clock.millis();
        long t0 = now - 395 * D;
        Random rnd = new Random(2026);
        s.batch = true;
        try {
            Clock.fix(Instant.ofEpochMilli(t0));
            // HR first (needed to create everything else)
            User meera = new User();
            meera.id = s.nextId("U", 1);
            meera.username = "meera";
            meera.name = "Meera Kujur";
            meera.role = Role.ADMIN;
            meera.passwordHash = Passwords.hash("admin123");
            meera.joined = "2019-06-03";
            s.users.put(meera.id, meera);

            Map<String, String> sym = new HashMap<>();   // symbol -> skill id
            int order = 0;
            for (Object[] c : CATS) {
                Category cat = p.catalog().addCategory(meera, Json.obj("name", c[0], "group", c[1], "halfLife", c[2]));
                cat.order = ++order;
                for (String[] k : (String[][]) c[3]) {
                    List<Object> syn = new ArrayList<>();
                    for (String x : k[2].split(",")) if (!x.isBlank()) syn.add(x.trim());
                    Skill sk = p.catalog().saveSkill(meera, null, Json.obj("symbol", k[0], "name", k[1], "categoryId", cat.id,
                            "description", k[3], "synonyms", syn));
                    sym.put(k[0], sk.id);
                }
            }
            for (String[] pr : PREREQS) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 1; i < pr.length; i++) m.put(sym.get(pr[i].split(":")[0]), Long.parseLong(pr[i].split(":")[1]));
                p.catalog().saveSkill(meera, sym.get(pr[0]), Json.obj("prereqs", m));
            }
            Map<String, String> role = new HashMap<>();
            for (String[] r : ROLES) {
                List<Object> reqs = new ArrayList<>();
                for (String q : r[5].split(",\\s*")) {
                    String[] t = q.trim().split(" ");
                    boolean must = t.length == 4;
                    reqs.add(Json.obj("skillId", sym.get(t[0]), "level", Long.parseLong(t[1]), "must", must,
                            "weight", Long.parseLong(t[must ? 3 : 2])));
                }
                role.put(r[0], p.growth().saveRole(meera, null, Json.obj("name", r[1], "ladder", r[2], "step", Long.parseLong(r[3]),
                        "description", r[4], "reqs", reqs)).id);
            }

            // people (managers first: the list is in that order)
            Map<String, User> by = new HashMap<>();
            by.put("meera", meera);
            String[] joined = { "2016-04-11", "2019-06-03", "2017-08-21", "2018-01-15", "2018-09-03", "2023-02-06", "2019-11-18",
                    "2023-07-03", "2022-01-10", "2023-09-25", "2021-05-17", "2020-03-02", "2021-10-04", "2024-01-08", "2020-12-14",
                    "2019-07-29", "2024-06-17", "2022-08-01", "2024-02-12", "2023-03-20", "2020-06-08", "2021-02-22", "2024-04-01", "2024-08-19" };
            int ji = 0;
            for (String[] u : PEOPLE) {
                String pw = u[5].equals("ADMIN") ? "admin123" : u[5].equals("MANAGER") ? "manager123" : "member123";
                Map<String, Object> b = Json.obj("username", u[0], "name", u[1], "title", u[2], "dept", u[3], "location", u[4],
                        "role", u[5], "managerId", u[6].isEmpty() ? null : by.get(u[6]).id, "jobRoleId", role.get(u[7]), "password", pw);
                User x = u[0].equals("meera") ? p.admin().saveUser(meera, meera.id, b) : p.admin().saveUser(meera, null, b);
                x.targetRoleId = u[8].isEmpty() ? null : role.get(u[8]);
                x.joined = joined[ji++];
                by.put(u[0], x);
            }

            // ---- every event of the year, in time order
            List<Ev> evs = new ArrayList<>();
            Map<String, Integer> lastUsedAgo = new HashMap<>();     // claim key -> months ago (applied at the end)
            for (String[] u : PEOPLE) {
                User x = by.get(u[0]);
                int core = 0;
                for (String code : u[9].split(" ")) {
                    Item it = item(code);
                    String sid = sym.get(it.sym());
                    if (sid == null) throw new IllegalStateException("Unknown symbol " + it.sym());
                    lastUsedAgo.put(x.id + "|" + sid, it.monthsAgo() >= 0 ? it.monthsAgo() : rnd.nextInt(4));
                    long t1 = it.mark() == '?' && it.later() == 0 ? now - (1 + rnd.nextInt(11)) * D - rnd.nextInt(8) * H
                            : it.mark() == '!' ? now - 20 * D
                            : t0 + (5 + (core++ < 4 ? rnd.nextInt(60) : rnd.nextInt(355))) * D + rnd.nextInt(10) * H;
                    evs.add(new Ev(t1, () -> claim(p, x, sid, it.first(), rnd)));
                    if (it.mark() == '!' && it.later() == 0) {
                        evs.add(new Ev(t1 + 2 * D + 3 * H, () -> p.profiles().sendBack(verifier(p, x, sid, it.first(), rnd), s.claim(x.id, sid).id, RETURN_NOTE)));
                        continue;
                    }
                    if (it.mark() == '?' && it.later() == 0) continue;
                    long v1 = t1 + 3 * H + (long) (rnd.nextDouble() * rnd.nextDouble() * 6 * D);
                    int vLevel = it.later() == 0 && it.verifyAs() > 0 ? it.verifyAs() : it.first();
                    evs.add(new Ev(v1, () -> verify(p, x, sid, vLevel, rnd)));
                    if (it.later() > 0) {
                        long t2 = Math.min(now - 6 * D, v1 + (60 + rnd.nextInt(180)) * D);
                        if (it.mark() == '?') t2 = now - (2 + rnd.nextInt(8)) * D;
                        evs.add(new Ev(t2, () -> claim(p, x, sid, it.later(), rnd)));
                        if (it.mark() != '?') {
                            int v2Level = it.verifyAs() > 0 ? it.verifyAs() : it.later();
                            evs.add(new Ev(t2 + 5 * H + (long) (rnd.nextDouble() * 3 * D), () -> verify(p, x, sid, v2Level, rnd)));
                        }
                    }
                }
            }
            certs(p, by, sym, now, evs);
            goals(p, by, sym, role, now, evs);
            projects(p, by, sym, now, evs);
            endorsements(p, by, now, rnd, evs);

            evs.sort(Comparator.comparingLong(Ev::at));
            YearMonth month = YearMonth.from(LocalDate.ofInstant(Instant.ofEpochMilli(t0), Clock.ZONE)).plusMonths(1);
            for (Ev e : evs) {
                while (true) {
                    long ms = month.atDay(1).atStartOfDay(Clock.ZONE).toInstant().toEpochMilli();
                    if (ms > e.at() || ms > now) break;
                    Clock.fix(Instant.ofEpochMilli(ms + 2 * H));
                    p.profiles().certReminders();
                    p.admin().snapshot(month.atDay(1));
                    month = month.plusMonths(1);
                }
                Clock.fix(Instant.ofEpochMilli(e.at()));
                e.run().run();
            }

            // last-used months as the people keep them up to date (they are told when a skill starts to fade)
            Clock.fix(Instant.ofEpochMilli(now));
            YearMonth thisMonth = YearMonth.from(Clock.today());
            for (Claim c : s.claims.values()) {
                Integer ago = lastUsedAgo.get(c.userId + "|" + c.skillId);
                if (ago == null) continue;
                YearMonth lu = thisMonth.minusMonths(ago);
                c.lastUsed = lu.toString();
            }
            p.profiles().certReminders();
            p.admin().monthly();
            for (Note n : s.notes.values()) if (n.at < now - 5 * D) n.read = true;
            for (User u : s.users.values()) u.lastSeen = now - rnd.nextInt(72) * H;
        } finally {
            Clock.fix(realFixed);
            s.saveAll();
        }
    }

    record Ev(long at, Runnable run) { }

    static void claim(ApiServer.Parts p, User x, String sid, int level, Random rnd) {
        Skill sk = p.store().skills.get(sid);
        String ev = "Used " + sk.name + " on " + WORK[rnd.nextInt(WORK.length)] + (level >= 4 ? "; reviewed others' work and wrote the team guide." : ".");
        p.profiles().claim(x, Json.obj("skillId", sid, "level", level, "evidence", ev));
    }

    /** Usually the manager; sometimes an assessor (someone at level 4+ who may confirm up to their own level). */
    static User verifier(ApiServer.Parts p, User x, String sid, int level, Random rnd) {
        DataStore s = p.store();
        Claim c = s.claim(x.id, sid);
        if (rnd.nextInt(100) < 28) {
            List<User> assessors = new ArrayList<>();
            for (User u : s.users.values()) {
                Access.Right r = Access.verify(s, u, c);
                if (r != null && r.why().startsWith("assessor") && r.maxLevel() >= level) assessors.add(u);
            }
            if (!assessors.isEmpty()) return assessors.get(rnd.nextInt(assessors.size()));
        }
        User m = x.managerId == null ? null : s.users.get(x.managerId);
        if (m != null) return m;
        for (User u : s.users.values()) if (u.admin() && !u.id.equals(x.id)) return u;
        throw new IllegalStateException("Nobody can verify " + x.name);
    }

    static void verify(ApiServer.Parts p, User x, String sid, int level, Random rnd) {
        Claim c = p.store().claim(x.id, sid);
        if (c == null || c.status != ClaimStatus.CLAIMED) return;
        User v = verifier(p, x, sid, level, rnd);
        String note = level == c.claimed ? null : String.format(ADJUST_NOTES[rnd.nextInt(ADJUST_NOTES.length)], level);
        p.profiles().verify(v, c.id, level, note);
    }

    // ---------------------------------------------------------------- certificates

    static void certs(ApiServer.Parts p, Map<String, User> by, Map<String, String> sym, long now, List<Ev> evs) {
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now), Clock.ZONE);
        Object[][] list = {
                { "farhan", "AWS Certified Solutions Architect - Associate", "Amazon Web Services", "Aw", -1080, 20 },
                { "farhan", "Certified Kubernetes Administrator (CKA)", "The Linux Foundation", "K8s", -420, 670 },
                { "rohit", "AWS Certified Cloud Practitioner", "Amazon Web Services", "Aw", -790, 300 },
                { "rohit", "HashiCorp Certified: Terraform Associate", "HashiCorp", "Tf", -755, -25 },
                { "kavya", "Databricks Certified Data Engineer Associate", "Databricks", "Py", -300, 430 },
                { "imran", "Microsoft Certified: Power BI Data Analyst Associate", "Microsoft", "Pb", -330, 40 },
                { "sunil", "CISSP", "ISC2", "As", -500, 960 },
                { "manoj", "OSCP", "OffSec", "As", -610, null },
                { "ayesha", "CompTIA Security+", "CompTIA", "Ns", -340, 755 },
                { "ritika", "Cisco CCNA", "Cisco", "Ns", -970, 125 },
                { "vikram", "Oracle Certified Professional: Java SE 17 Developer", "Oracle", "Jv", -560, null },
                { "ravi", "Certified ScrumMaster (CSM)", "Scrum Alliance", "Sc", -680, 50 },
                { "priya", "Certified ScrumMaster (CSM)", "Scrum Alliance", "Sc", -350, 380 },
                { "tanvi", "Google UX Design Certificate", "Google", "Ux", -400, null },
                { "deepa", "ISTQB Certified Tester Foundation Level", "ISTQB", "Qa", -880, null },
                { "anil", "AWS Certified Solutions Architect - Professional", "Amazon Web Services", "Aw", -1250, -150 },
                { "arjun", "Meta Front-End Developer Certificate", "Meta", "Re", -260, null },
        };
        for (Object[] c : list) {
            User u = by.get((String) c[0]);
            LocalDate issued = today.plusDays((Integer) c[4]);
            String expires = c[5] == null ? null : today.plusDays((Integer) c[5]).toString();
            long at = Math.max(now - 390 * D, issued.atStartOfDay(Clock.ZONE).toInstant().toEpochMilli() + 2 * D);
            evs.add(new Ev(at, () -> p.profiles().saveCert(u, null, Json.obj("name", c[1], "issuer", c[2], "skillId", sym.get((String) c[3]),
                    "issued", issued.toString(), "expires", expires))));
        }
    }

    // ---------------------------------------------------------------- learning goals

    static void goals(ApiServer.Parts p, Map<String, User> by, Map<String, String> sym, Map<String, String> role, long now, List<Ev> evs) {
        DataStore s = p.store();
        User asha = by.get("asha"), ravi = by.get("ravi"), priya = by.get("priya"), sunil = by.get("sunil");
        // Asha makes a plan for Senior Backend Engineer (Linux comes first: Kubernetes needs it)
        evs.add(new Ev(now - 76 * D, () -> {
            List<Goal> plan = p.growth().planForRole(asha, asha.id, role.get("B2"));
            for (Goal g : plan) {
                Skill k = s.skills.get(g.skillId);
                int done = switch (k.symbol) { case "Lx" -> 3; case "Kf" -> 4; case "Jv" -> 2; case "Pg" -> 1; default -> 0; };
                for (int i = 0; i < done; i++) p.growth().step(asha, g.id, i, true);
            }
        }));
        evs.add(new Ev(now - 9 * D, () -> {                     // finished Kafka -> a claim waits for Ravi
            for (Goal g : s.goals.values()) if (g.userId.equals(asha.id) && g.skillId.equals(sym.get("Kf"))) p.growth().finish(asha, g.id);
        }));
        evs.add(new Ev(now - 33 * D, () -> {
            Goal g = p.growth().addGoal(ravi, Json.obj("userId", by.get("neha").id, "skillId", sym.get("Sb"), "target", 4L,
                    "due", today(now).plusDays(40).toString(), "reason", "Ready for the payments API rewrite"));
            p.growth().step(by.get("neha"), g.id, 0, true);
        }));
        evs.add(new Ev(now - 96 * D, () -> p.growth().addGoal(ravi, Json.obj("userId", by.get("rohit").id, "skillId", sym.get("Tf"),
                "target", 3L, "due", today(now).minusDays(5).toString(), "reason", "Gap for Senior Platform Engineer"))));
        evs.add(new Ev(now - 50 * D, () -> {
            Goal g = p.growth().addGoal(by.get("imran"), Json.obj("skillId", sym.get("Py"), "target", 3L,
                    "due", today(now).plusDays(60).toString(), "reason", "Gap for Data Engineer"));
            p.growth().step(by.get("imran"), g.id, 0, true);
            p.growth().step(by.get("imran"), g.id, 1, true);
        }));
        evs.add(new Ev(now - 40 * D, () -> p.growth().addGoal(sunil, Json.obj("userId", by.get("ayesha").id, "skillId", sym.get("Cs"),
                "target", 3L, "due", today(now).plusDays(110).toString(), "reason", "Gap for Senior Security Engineer"))));
        evs.add(new Ev(now - 64 * D, () -> {
            Goal g = p.growth().addGoal(by.get("tanvi"), Json.obj("skillId", sym.get("Ux"), "target", 4L,
                    "due", today(now).plusDays(75).toString(), "reason", "Gap for Senior Product Designer"));
            p.growth().step(by.get("tanvi"), g.id, 0, true);
        }));
        evs.add(new Ev(now - 140 * D, () -> {
            Goal g = p.growth().addGoal(by.get("karan"), Json.obj("skillId", sym.get("Ap"), "target", 2L,
                    "due", today(now).plusDays(10).toString(), "reason", "Wants to help on APIs"));
            p.growth().drop(by.get("karan"), g.id);
        }));
        evs.add(new Ev(now - 25 * D, () -> p.growth().addGoal(priya, Json.obj("userId", by.get("sneha").id, "skillId", sym.get("Ax"),
                "target", 3L, "due", today(now).plusDays(90).toString(), "reason", "Gap for Senior Frontend Engineer"))));
    }

    static LocalDate today(long now) { return LocalDate.ofInstant(Instant.ofEpochMilli(now), Clock.ZONE); }

    // ---------------------------------------------------------------- projects

    static void projects(ApiServer.Parts p, Map<String, User> by, Map<String, String> sym, long now, List<Ev> evs) {
        LocalDate t = today(now);
        // name, client, owner, status, start, end, created (days ago), slots: title|pct|reqs|assignee
        Object[][] list = {
                { "Old intranet rebuild", "Internal", "priya", "DONE", -300, -120, 320, new String[] {
                        "Frontend developer|100|Js 3 m 3, Re 3 m 3, Hc 3 2|pooja", "Frontend developer|50|Js 3 m 3, Hc 3 m 2|karan" } },
                { "Kestrel payments API", "Kestrel Travel (made up)", "ravi", "ACTIVE", -60, 120, 70, new String[] {
                        "Tech lead|50|Jv 4 m 3, Sb 4 m 3, Sd 3 m 2|vikram", "Backend developer|100|Jv 3 m 3, Sb 3 m 3, Sq 3 2, Kf 2 1|asha",
                        "Backend developer|100|Jv 3 m 3, Sb 2 m 2, Pg 3 2|sanjay", "DevOps|50|K8s 3 m 3, Ci 3 m 2, Aw 2 1|rohit",
                        "QA|50|Qa 3 m 3, Ap 2 1|deepa" } },
                { "Tarang analytics hub", "Internal", "ravi", "ACTIVE", -30, 90, 40, new String[] {
                        "Data engineer|100|Py 4 m 3, Kf 3 m 2, Sq 4 2|kavya", "Dashboards|50|Pb 3 m 3, Sq 3 m 2|imran",
                        "Platform|30|Tf 3 m 3, Aw 3 2|farhan" } },
                { "e-Mandi farmers' market app", "Jharkhand Agri Board (made up)", "priya", "PLANNED", 20, 200, 6, new String[] {
                        "Frontend lead|60|Re 4 m 3, Ts 3 m 2, Ax 3 2, Ci 2 1|", "Frontend developer|100|Re 3 m 3, Js 3 m 3, Hc 3 2|",
                        "Product designer|50|Ui 3 m 3, Ux 3 m 3, Fg 3 1|", "Backend API (Node.js)|50|Nd 3 m 3, Ap 3 m 3, Pg 2 1|",
                        "Accessibility review|20|Ax 3 m 1|" } },
                { "Zero-trust network rollout", "Internal", "sunil", "PLANNED", 10, 150, 3, new String[] {
                        "Security architect|50|Cs 3 m 3, Ns 3 m 2, As 3 2|", "Cloud engineer|50|Aw 3 m 3, Tf 3 m 3, Cs 2 1|",
                        "Network analyst|100|Ns 3 m 3, Ir 2 m 2|ayesha" } },
        };
        for (Object[] pr : list) {
            User owner = by.get((String) pr[2]);
            long created = now - ((Integer) pr[6]) * D;
            evs.add(new Ev(created, () -> {
                Project prj = p.staffing().saveProject(owner, null, Json.obj("name", pr[0], "client", pr[1], "status", "DONE".equals(pr[3]) ? "ACTIVE" : pr[3],
                        "start", t.plusDays((Integer) pr[4]).toString(), "end", t.plusDays((Integer) pr[5]).toString()));
                for (String sl : (String[]) pr[7]) {
                    String[] f = sl.split("\\|", -1);
                    List<Object> reqs = new ArrayList<>();
                    for (String q : f[2].split(",\\s*")) {
                        String[] x = q.trim().split(" ");
                        boolean must = x.length == 4;
                        reqs.add(Json.obj("skillId", sym.get(x[0]), "level", Long.parseLong(x[1]), "must", must, "weight", Long.parseLong(x[must ? 3 : 2])));
                    }
                    prj = p.staffing().saveSlot(owner, prj.id, null, Json.obj("title", f[0], "pct", Long.parseLong(f[1]), "reqs", reqs));
                    if (!f[3].isEmpty()) {
                        Project.Slot slot = prj.slots.get(prj.slots.size() - 1);
                        try {
                            p.staffing().assign(owner, prj.id, slot.id, by.get(f[3]).id, false);
                        } catch (Problem notReadyYet) {
                            p.staffing().assign(owner, prj.id, slot.id, by.get(f[3]).id, true);   // a stretch role
                        }
                    }
                }
                if ("DONE".equals(pr[3])) {
                    Project done = prj;
                    p.staffing().saveProject(owner, done.id, Json.obj("status", "DONE"));
                }
            }));
        }
    }

    // ---------------------------------------------------------------- endorsements

    static void endorsements(ApiServer.Parts p, Map<String, User> by, long now, Random rnd, List<Ev> evs) {
        DataStore s = p.store();
        String[] names = by.keySet().stream().sorted().toArray(String[]::new);
        String[] comments = { "Helped me fix a hard production bug.", "Explains it better than the docs.", "Great reviews.",
                "", "", "Saved the release.", "Go-to person for this.", "" };
        for (int i = 0; i < 95; i++) {
            long at = now - (2 + rnd.nextInt(300)) * D - rnd.nextInt(20) * H;
            String from = names[rnd.nextInt(names.length)], to = names[rnd.nextInt(names.length)];
            int pick = rnd.nextInt(1000), cm = rnd.nextInt(comments.length);
            evs.add(new Ev(at, () -> {
                User f = by.get(from), t = by.get(to);
                if (f == t) return;
                // same team, or the same department, mostly
                boolean near = (t.managerId != null && t.managerId.equals(f.managerId)) || t.id.equals(f.managerId)
                        || f.id.equals(t.managerId) || (f.dept != null && f.dept.equals(t.dept));
                if (!near) return;
                List<Claim> cs = new ArrayList<>();
                for (Claim c : s.claimsOf(t.id)) if (c.verified >= 2) cs.add(c);
                if (cs.isEmpty()) return;
                cs.sort(Comparator.comparingInt(c -> -c.verified));
                Claim c = cs.get(pick % Math.min(4, cs.size()));
                try {
                    p.profiles().endorse(f, t.id, c.skillId, comments[cm]);
                } catch (Problem already) { /* endorsed before - skip */ }
            }));
        }
    }

    /** Used by a test: the level names in the seed are valid. */
    static boolean selfCheck() {
        for (String[] u : PEOPLE) for (String code : u[9].split(" ")) if (Levels.name(item(code).first()).equals("?")) return false;
        return true;
    }
}
