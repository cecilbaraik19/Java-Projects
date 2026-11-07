# Sambandh – Customer Relationship Management

**Manage customers.** Sambandh keeps everything about every customer in one place: who they are, every call,
e-mail, meeting and WhatsApp chat (the **call log**), what they bought (**orders**), their complaints
(**support tickets** with deadlines), and what to do next (**follow-ups**). On top of that it works out, with plain
rules anyone can explain:

- a **health score** (0–100) for every customer, with the reason for each point – green, amber or red lamp;
- an **RFM segment** for every buyer (Recency, Frequency, Monetary): *Champions, Loyal, At risk, Lost …*;
- the **next best action** (“Solve ticket T-9019 – past its 72-hour deadline”, “Offer a win-back deal”), and it never
  suggests phoning someone on the Do-Not-Call list;
- **possible duplicates** (“Kujur Ravi” = “Ravi Kujur”, same phone typed differently) and a safe **merge**.

It also has **CSV import** (with a preview) and **CSV export** for Excel, **consent** rules (Do-Not-Call and
e-mail consent are enforced), **privacy tools** for India's *Digital Personal Data Protection Act, 2023* (download a
customer's data, erase it), three **roles** (sales agent, manager, admin) and an **activity log**.

Plain **Java 17** backend (no external libraries – the web server, JSON reader, CSV reader, password hashing, RFM,
health score and fuzzy name matching are all written with the JDK only) and a plain **HTML / CSS / JavaScript**
frontend, the same setup as the other projects in this folder. **Light mode and dark mode** are both built in.

The look is a new **“telephone switchboard”** theme – a CRM is about *connections*, and so was the old telephone
exchange: the menu is a **walnut cabinet with brass jack sockets** (the open page has a red plug in it), panels are
cream **enamel plates with brass screws** and engraved label strips, numbers turn on **mechanical counters**,
customer health is a **signal lamp** and a **half-moon meter with a needle**, the call log is a **patch cord** with each
conversation plugged into it, and duplicates are shown as two lines joined by a cord. Dark mode is the **Night
shift** (switch at the top right): the cabinet goes black and the lamps glow.

*Sambandh* (सम्बन्ध) means “relationship” – or “connection”.

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database server, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/com/cecil/sambandh/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/sambandh-crm.jar` |

Then open **http://localhost:8080** in your browser. (Set `PORT` to use another port.)

The first start creates the demo data in the `data` folder: the made-up company **Suryodaya Solar, Ranchi** (rooftop
solar, inverters, batteries, maintenance) with 63 customers, about 230 conversations, about 135 orders, 20+ support tickets and
follow-ups – all dated relative to *today*, so the demo always looks fresh. **All people, companies and numbers are
made up.** To start fresh, stop the app and delete the `data` folder.

### Demo logins

| Username | Password | Role | Can do |
|---|---|---|---|
| arjun | agent123 | Sales agent | Only **his own** customers: add, edit, log contacts, follow-ups, orders, tickets, export |
| kavya | agent123 | Sales agent | Same, for her customers |
| sameer | agent123 | Sales agent | Same, for his customers |
| ritu | manager123 | Manager | **Everyone's** customers, re-assign owners, **duplicates & merge**, **CSV import**, activity log, team board |
| admin | admin123 | Admin | Everything + **staff accounts** + **erase personal data** |

### Try it

1. Sign in as **ritu**. The **Switchboard** shows the counters, the lifecycle lines (Lead → Prospect → Customer →
   Loyal → Churned), money per month, the paying customers who are **slipping away**, health lamps, RFM segments and
   how each staff member is doing.
2. Click a red-lamp customer under **Slipping away**. Read the **health meter** – each of the 5 parts says why it got
   its points – and the **next best action**.
3. Press **Log contact**, choose *Call*, *We contacted them*, *Well*, write a line and save. The contact part of the
   health goes to 30/30 and the needle moves.
4. Open a customer with the red **DND** flag and try to log an **outgoing call** – it is refused (Do-Not-Call).
   Outgoing e-mails are refused too unless *Agreed to e-mails* is ticked.
5. **New customer** – type a name that already exists (try one from the list with a typo, or the same phone with a
   different format, e.g. `0` in front). A warning appears while you type; the same phone or e-mail is blocked.
6. **Duplicates** – three pairs were planted in the demo (same e-mail in capitals, same phone with the name words
   swapped, a typo in the name). **Swap which to keep**, then **Merge** – all records move to one customer.
7. **Import CSV** → **Use example** → **Preview**: three rows are ready, one is skipped with the reasons. **Import**.
8. Add an **order** to a Lead – it becomes a Customer by itself. Four purchases in a year make a customer **Loyal**.
9. Sign in as **arjun** – he sees only his customers (another agent's customer page says “not found”).
10. Sign in as **admin** → a customer → **Details & privacy** → **Download their data** (JSON), then **Erase personal
    data** (type the ID to confirm). Orders stay (amounts only) for the accounts.
11. Press **Day shift / Night shift** at the top right.

## How it works

```
 Browser (HTML/CSS/JS)                      Java backend (JDK only)                         data/ (JSON files)
 ─────────────────────                      ───────────────────────                         ──────────────────
 #/customers, #/c/C-1001 ...   fetch JSON    ApiServer  → security headers, sign-in,         users.json
 one page, views drawn from   ───────────►   CSRF check, roles, then the route               customers.json
 JSON, all text escaped                      CustomerService  add / edit / search / export   interactions.json
                              ◄───────────   ActivityService  calls, follow-ups, orders,     followups.json
                                             tickets + consent rules                         orders.json
                                             Insights   RFM, health score, next action       tickets.json
                                             Duplicates fuzzy matching + merge               audit.json
                                             ImportService  CSV preview + import             counters.json
                                             DashboardService  switchboard numbers
                                             DataStore  keeps it all in memory, saves JSON
```

### RFM (Recency, Frequency, Monetary)

For every customer who has bought something: **R** = days since the last purchase, **F** = purchases in the last
365 days, **M** = money in the last 365 days (refunds left out). Each is turned into a score **1–5** by ranking the
customers and cutting them into five equal groups (“quintiles”; ties share a score). Then R and the average of F and
M pick a segment:

| | FM 1–2 | FM 3 | FM 4–5 |
|---|---|---|---|
| **R 4–5** | Promising | Loyal | Champions |
| **R 3** | Needs attention | Loyal | Loyal |
| **R 2** | Hibernating | At risk | Can't lose them |
| **R 1** | Lost | At risk | Can't lose them |

### Health score (0–100)

| Part | Points | Rule |
|---|---|---|
| Contact | 30 | last real contact: ≤14 days 30, ≤30 22, ≤60 12, ≤90 5, older 0 (internal notes do not count) |
| Buying | 25 | RFM R score × 5 (a lead or prospect without purchases gets a neutral 12) |
| Mood | 15 | last 5 conversations: went well = 1, okay = ½, badly = 0 → share × 15 |
| Support | 20 | 20 − 6 per open ticket − 8 more per ticket past its deadline |
| Follow-ups | 10 | 10 − 5 per overdue follow-up |

**70+ healthy** (green), **40–69 watch** (amber), **below 40 at risk** (red). A Customer or Loyal customer below 40 is
listed under **Slipping away**.

### Duplicate finder

| Match | Score |
|---|---|
| same e-mail (capitals ignored) | 100 – saving is blocked |
| same phone after cleaning (`98351 23456` = `+91-9835123456` = `09835123456`) | 95 – saving is blocked |
| names alike: words paired up one-to-one with **Jaro-Winkler** similarity, average ≥ 0.90 | similarity × 70, +20 same company, +15 same city |

Pairs scoring 75+ are shown. To stay fast with thousands of customers it uses **blocking**: only customers that share
an e-mail, a phone, or the first 3 letters of a name word are compared. **Merge** moves every record, fills empty
fields, combines tags, keeps the stronger stage, and takes the **safer consent** (e-mails only if both agreed,
Do-Not-Call if either asked). The merged record is hidden, not deleted.

### Automatic stage moves

A **Lead** with a good conversation becomes a **Prospect**. A lead / prospect / churned customer with a paid order
becomes a **Customer**; 4 paid orders in a year make them **Loyal**. Every automatic move is written in the activity log.

### Support deadlines (SLA)

High priority 24 hours, medium 72 hours, low 5 days from opening. Tickets still open after that are **past the
deadline**: a blinking red lamp, first in the list, and they lower the health score.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` (Mac / Linux). **52 tests**, clock fixed at 7 Nov 2026: JSON (escaping,
bad input, deep nesting), CSV (quotes, new lines, formula injection), money in lakh/crore, PBKDF2 passwords, phone
cleaning, e-mail check, Jaro-Winkler and word-order name matching; sign-in, same message for unknown user / wrong
password, lock after 5 tries, 12-hour session end, staff accounts, password reset signs out; customer input checks,
blocked exact duplicates without leaking other agents' data, agents see only their own, owner changes and bulk
re-assign, search by name / phone digits / tag, CSV export; Lead → Prospect, Do-Not-Call and e-mail consent rules,
follow-ups, orders → Customer → Loyal and refunds, ticket deadlines; RFM quintiles and the full segment table, health
parts add up, next action respects DND, dashboard totals; duplicates found, live check, merge; CSV preview and
import; privacy download and erase; saving and reloading the JSON files; and the real web server (CSP, path tricks,
sign-in needed, CSRF, cookie flags, roles, download, sign-in rate limit).

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8080 | web port |
| `DATA_DIR` | `data` folder of the project | where everything is saved |

## Security

- Passwords hashed with **PBKDF2-SHA256** (120,000 rounds, random salt) · 5 wrong passwords lock the account for
  5 minutes · same message for unknown user and wrong password · 10 sign-in tries a minute per computer.
- **HttpOnly, SameSite=Strict** session cookie, ends after 12 hours idle; only a SHA-256 of the session token is kept.
- **CSRF**: every change must be JSON, carry the `X-Sambandh` header and come from the same site.
- **Roles checked on the server**: agents only reach their own customers (others look “not found”); managers import,
  merge, re-assign and read the log; only admins manage staff and erase data.
- Strict **Content-Security-Policy** (no inline scripts or styles) · all text escaped before it is shown · CSV export
  defuses Excel formulas (`=`, `+`, `-`, `@`) · static files only by plain name (no `..`) · server listens only on
  127.0.0.1 · request size limit 1.2 MB.
- **Consent and privacy**: Do-Not-Call and e-mail consent enforced; data download and erasure (DPDP Act 2023);
  every sign-in, change, export, merge and erasure is in the activity log.

## Folders

```
crm/
├── run.bat / run.sh, run-tests.bat / run-tests.sh
├── frontend/                       index.html, app.css (switchboard theme), app.js, theme.js, favicon.svg
├── backend/src/com/cecil/sambandh/
│   ├── App.java                    starts everything
│   ├── model/                      Customer, Interaction, FollowUp, Order, Ticket, User, Role, Stage, AuditEntry
│   ├── service/                    AuthService, CustomerService, ActivityService, Insights (RFM + health + next action),
│   │                               Duplicates, ImportService, DashboardService, Problem
│   ├── store/                      DataStore (JSON files), SeedData (demo company)
│   ├── util/                       Json, Csv, Text (cleaning + Jaro-Winkler), Money, Passwords, Clock
│   └── web/                        ApiServer (routes), Http
├── backend/test/com/cecil/sambandh/AllTests.java
└── data/                           created on first run (users, customers, interactions, followups, orders,
                                    tickets, audit, counters – all .json)
```

The Java files are in `backend/src` instead of the usual `backend/src/main/java` – a shorter path; `backend/pom.xml`
tells Maven and IntelliJ where they are.

## Limits (honest)

- Data is kept in memory and saved as JSON files – fine for thousands of customers, not millions. A bigger system
  would use a database (MySQL / PostgreSQL).
- The app does not really send e-mails, SMS or make calls – staff do that and **log** it here.
- The health score and RFM are simple, explainable rules, not machine learning. They are good at pointing to who
  needs attention; they do not predict exactly who will leave.
- Fuzzy matching can miss very different spellings (“Mohd.” / “Mohammed”) and can warn about two different people
  with very similar names in the same city – that is why merging is always a person's decision.
- Fonts come from Google Fonts; without internet the page uses normal system fonts (still works).
