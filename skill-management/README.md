# Kaushal – Employee Skill Management Platform

**Track employee skills.** Kaushal (कौशल, "skill") is the skill platform of **Tarang Technologies**, a made-up software
company with **24 people** in Ranchi, Bengaluru and Pune. It answers four everyday questions:

> *What can our people really do? · Who can do X right now? · What does each person need to learn next? · Who should work on the new project?*

| Part | What it does |
|---|---|
| **Periodic table of skills** | The catalogue (41 skills in 8 groups) is drawn as a periodic table: every skill is an **element** with a number and a symbol (`Jv` Java, `K8s` Kubernetes, `Ux` UX research). Colour the table by group, by **my levels**, by **supply** (people at level 3+) or by **demand** (gaps and open project slots). |
| **5-level scale** | 1 Aware · 2 Working · 3 Practitioner · 4 Advanced · 5 Expert – shown as filled "electron shells" ●●●○○. |
| **Claim → verify (four eyes)** | A person claims a level (level 4–5 need an example). It counts only after **someone else** checks it: their manager (or anyone above), HR, or an **assessor** – a colleague at level 4+ in that skill, who can confirm only up to their own level. The checker can verify, choose a different level (with a reason) or **send it back** with a question. A new higher claim waits; the old verified level still counts meanwhile. |
| **Skill decay (half-life)** | A skill you stop using fades: after every *half-life* without use (Cloud 12 months … People skills 60 months) it counts **one level lower**. Fading skills get a striped tile and a **t½** mark; "I used it this month" brings the full level back. |
| **Endorsements** | Colleagues vouch for a skill. Not yourself, once per skill, 15 a week. An endorsement from an expert weighs more (1 + their level ÷ 2). |
| **Certificates** | AWS, CKA, CISSP … with expiry dates. Reminders 60 and 30 days before, and on the day it expires (the manager is told too). |
| **Job roles and readiness** | 16 roles on 8 career ladders. **Readiness %** = weighted share of the role's skills you have; **ready** = every must-have and 80 %+. |
| **Learning plans** | "Make a learning plan" turns the gaps for a role into goals **in a safe order** – prerequisites first (Kubernetes needs Linux and Docker), one every 6 weeks. Finishing a goal makes a claim; when it is verified, the goal closes by itself. |
| **Staffing with the Hungarian algorithm** | Projects have slots ("Backend developer, 50 %, Java 3 must, Kafka 2"). **Suggest a team** fills all open slots at once with the best total fit, using free time and must-haves. It also shows what picking one slot at a time (greedy) would give – in the demo, greedy fills 4 of 5 slots, Kaushal fills 5. |
| **Skill search language** | `java >= 4 and (aws or kubernetes:2) and not "machine learning"` – a small parser with brackets, and/or/not, level names, other names (`k8s`, `js`, `postgres`) and "did you mean Kubernetes?". |
| **Team matrix and bus factor** | People × skills heat map. **Bus-factor risk** = a skill the team needs with fewer than 2 people at level 3+. Export as CSV (Excel). |
| **Reports** | Ready-in-role, readiness median, time to check a claim (median and 85th percentile), shortages, risks, certificates, goals, and a **trend chart** from monthly snapshots. |
| **HR desk** | People (no loops in the reporting line), skill catalogue (unique names/symbols/other names, prerequisites without loops), job roles, **CSV import with a dry run**, activity log. |

The backend is plain **Java 17** with no external libraries (web server, JSON, CSV, password hashing, parser and the
algorithms all use the JDK only). The frontend is plain **HTML / CSS / JavaScript**, the same setup as the other projects
in this folder. **Light and dark mode** are built in.

The look is a new **"periodic table & lab paper"** theme:

- the page is **graph paper**; skills are **element tiles** coloured by group like the real periodic table;
- the level is shown as **electron shells** ●●●○○; levels lost to decay are striped; a waiting claim has a **dashed** border;
- the menu is a dark **lab bench** where every page has its own little element symbol (`Bn` Bench, `Pt` Periodic table, `Vf` Verify …);
- fonts are Bricolage Grotesque (headings), Atkinson Hyperlegible (text, very easy to read) and Martian Mono (numbers).

Dark mode is **Spectrum**: the tiles glow in their group colour like emission lines (switch it at the bottom of the menu).

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/com/cecil/kaushal/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/kaushal.jar` |

Then open **http://localhost:8080**. (Set `PORT` to use another port.)

The first start takes a few seconds. It builds the demo company in the `data` folder: 24 people, 41 skills, 16 roles,
about **260 skills on profiles, 40 endorsements, 17 certificates, 17 learning goals and 5 projects**. The year of history
is not typed in: it is **played through the real services** with the clock moved back (people claim skills, managers and
assessors check them a few hours or days later – some at a lower level, one sent back – certificates expire, people
endorse each other, make learning plans, finish goals and get staffed; on the first of every month a snapshot is taken).
So every number in the reports got there by the normal rules. Everything is dated relative to *now*. **All people are
made up.** To start fresh, stop the app and delete the `data` folder.

### Demo logins

| Username | Password | Who | Good for |
|---|---|---|---|
| asha | member123 | Asha Oraon, backend engineer, Ranchi | Fading skills, a Kafka claim waiting, 65 % ready for Senior, a learning plan |
| vikram | member123 | Vikram Sahu, senior backend engineer | An **assessor** (Java 5) – can check colleagues' Java claims |
| ravi | manager123 | Ravi Munda, engineering manager | Claims to check, team matrix, Terraform bus-factor risk, Kestrel project |
| priya | manager123 | Priya Nair, engineering manager | The **e-Mandi** project: "Suggest a team" (Hungarian vs greedy) |
| meera | admin123 | Meera Kujur, head of People (HR) | HR desk, company reports, CSV import, activity log |

Every other person has the password `member123` (managers `manager123`).

### Try it

1. Sign in as **asha**. The **bench** shows your verified skills, the ones fading (**t½**) – press *I used it this month* –,
   your readiness for *Senior Backend Engineer* and your learning goals.
2. **My skills** → *+ Add a skill*: pick Kubernetes, level 2. The tile has a dashed border: it waits for a check.
3. **Growth**: see the gaps for every role. *Make a learning plan* adds goals with **Linux before Kubernetes**.
4. **Find people**: try `java >= 4 and (aws or kubernetes:2)`, then a typo like `kubernets` (Kaushal asks "did you mean").
5. Sign in as **ravi** → **Verify**: check Asha's Kafka claim – verify at 2, or choose 1 (you must give a reason), or send it back.
6. **Team matrix** (ravi): levels of the whole team; Terraform has only one person at level 3+ (bus-factor risk). *Export CSV*.
7. Sign in as **priya** → **Projects → e-Mandi** → *Suggest a team*. Only Arjun can do the Node.js API, so the
   algorithm gives the frontend lead to Priya and the API to Arjun – all 5 slots filled. Slot-by-slot picking would give Arjun
   the lead and leave the API empty.
8. Sign in as **meera** → **Reports** (trend chart, shortages, certificates) and **HR desk** → *Import CSV* → *Check (dry run)*.
9. Switch to **Spectrum** (dark mode) at the bottom of the menu and open the **Periodic table**.

## How it works

```
 website (HTML/CSS/JS) ──/api JSON──► ApiServer ─► AuthService · CatalogService · ProfileService · GrowthService
                                         │         StaffingService · AdminService · ViewService (read-only pages)
                                         │
                core: Levels (scale + decay) · Readiness (gap %) · SkillGraph (prerequisites DAG)
                      Hungarian (best team) · SkillQuery (search language) · Stats (median, percentile)
                                         ▼
                         DataStore (memory + JSON files in data/)
```

### Life of a claim

```
 (none) ──claim──► CLAIMED ──verify (same or other level + reason)──► VERIFIED ──claim higher──► CLAIMED ...
                      │                                                   (old verified level still counts)
                      └──send back (with a question)──► RETURNED ──claim again──► CLAIMED
 Who may check: manager or anyone above · HR · assessor (level 4+ in the skill, up to their own level) · never yourself
```

### The formulas

| What | How |
|---|---|
| Level that counts today | `max(1, verified − ⌊months since last used ÷ half-life⌋)` – e.g. Java 4, half-life 24, not used for 30 months → 3 |
| Readiness for a role | `100 × Σ weight × min(have ÷ need, 1) ÷ Σ weight`; ready = all must-haves and ≥ 80 % |
| Endorsement weight | `1 + endorser's level ÷ 2` (1.0 … 3.5) |
| Bus factor | for each must-have skill of the group's roles and project slots: people at level 3+; fewer than 2 = risk |
| Staffing cost | `100 − fit` per (slot, person); not allowed (no time / missing must-have) = 1 000 000; "leave empty" = 1 000 |

### The algorithms

* **Prerequisites form a DAG** (directed graph without loops). A new arrow is refused if a **depth-first search** from
  the new prerequisite can reach the skill. **Kahn's algorithm** gives a safe order, and the learning path is a
  **DFS post-order** over the gaps plus their missing prerequisites.
* **Hungarian algorithm** (Kuhn–Munkres, O(n²·m)): picks one person per slot with the lowest total cost. A test compares
  it with brute force on 400 random cases.
* **Recursive-descent parser** for the search language (`or` → `and` → `not` → `atom`), with the longest-match rule for
  skill names of several words, and **Levenshtein distance** for "did you mean".
* **Percentiles** with linear interpolation for the report.

| Rule | |
|---|---|
| Claims | one per person per skill, at most 60; level 4–5 need an example (15+ characters); "last used" not in the future |
| Checking | never your own; a different level needs a reason; sending back needs a question |
| Endorsements | not yourself, once per skill, 15 per 7 days |
| Goals | one open goal per skill; target above the current level; due within 2 years |
| Staffing | free time = 100 % − slots in planned/active projects; must-haves needed (or mark as a *stretch* role) |
| Catalogue | names, symbols and other names unique; a skill a role still needs cannot be retired |

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` runs **28 tests** without JUnit. The clock is frozen at Monday 16 Nov 2026.
They cover JSON, passwords, CSV (with the Excel formula guard), decay, readiness, the prerequisite graph and learning
path, the Hungarian algorithm against brute force, the search language and its error messages, the whole claim/verify
flow (four eyes, assessors, reasons, sending back), endorsements and the weekly limit, certificates and reminders,
learning plans, staffing and suggestions, the catalogue rules, the reporting line, CSV import, privacy, bus factor,
snapshots, account lock, **16 threads at once**, the web server (CSRF, cookies, JSON only, no path tricks) and the whole
demo company (no rule is broken anywhere).

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8080 | Web port |
| `DATA_DIR` | `data` in this folder | Where the JSON data is kept |

## Security and privacy

* Passwords: PBKDF2-HMAC-SHA256, 120,000 rounds, random salt. 5 wrong passwords lock the account for 5 minutes;
  10 sign-in tries per minute per computer; the same message for unknown user and wrong password.
* Sessions: random token in an **HttpOnly, SameSite=Strict** cookie; only its SHA-256 is kept; 12 hours idle.
* CSRF: every change needs the `X-Kaushal` header, a same-site Origin and JSON. CSP, X-Frame-Options and nosniff headers.
  Pages are built without `innerHTML` (no XSS). CSV export guards against formula injection.
* **Privacy**: everybody sees verified skills, endorsements and certificates (an internal talent directory). Claims still
  waiting, checker notes, evidence, goals and the career page are only for the person, their managers and HR.
* Every important action goes to the activity log.

## Folders

```
skill-management/
├─ run.bat / run.sh, run-tests.bat / run-tests.sh   start the app / the tests
├─ pom.xml                                           Maven parent (IntelliJ)
├─ backend/
│  ├─ pom.xml
│  ├─ src/com/cecil/kaushal/
│  │  ├─ App.java          starts everything + the background job (reminders, monthly snapshot)
│  │  ├─ model/            User, Category, Skill, Claim, Endorsement, Cert, JobRole, Req, Goal, Project, Note, Snapshot ...
│  │  ├─ core/             Levels, Readiness, SkillGraph, Hungarian, SkillQuery, Stats
│  │  ├─ service/          Auth, Catalog, Profile, Growth, Staffing, Admin, View, Access, Problem
│  │  ├─ store/            DataStore (memory + JSON files), SeedData (demo company)
│  │  ├─ web/              ApiServer (routes, CSRF, rate limit), Http
│  │  └─ util/             Json, Csv, Passwords, Clock, Text
│  └─ test/com/cecil/kaushal/AllTests.java
├─ frontend/               index.html, app.css (periodic-table theme), app.js, theme.js, favicon.svg
└─ data/                   created on first start (not in git)
```

## Limits (honest)

* The people, company and history are **made up**.
* Levels are a judgement by people; Kaushal makes the judgement visible and checked, it does not test anybody.
* Notifications stay inside the app (no e-mail).
* Fine for one company of a few hundred people; a big company would use a database instead of JSON files.
