# Sales Pipeline Manager

Track potential sales from the first contact until the deal is won or lost.

Plain **Java 17** backend (no external libraries) and a plain **HTML / CSS / JavaScript** frontend,
the same setup as the other projects in this folder.

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/main/java/com/cecil/pipeline/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/sales-pipeline.jar` |

Then open **http://localhost:8080** in your browser.

### Demo logins

| Username | Password | Role |
|---|---|---|
| admin | admin123 | Admin (manages the team) |
| neha | manager123 | Manager (can edit every deal) |
| arjun, priya, rahul | sales123 | Sales rep (edits own deals) |

On the first run the app fills itself with demo data (14 companies, 48 deals, activities).
All names are made up. To start fresh, stop the app and delete the `data` folder.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` (Mac / Linux). 21 tests: JSON, passwords, login lock-out,
deal rules, permissions, health score, forecast maths, saving to disk, and the HTTP API end to end.

## What it does

**Pipeline stages:** Lead (10%) → Qualified (25%) → Proposal sent (50%) → Negotiation (75%) → Won / Lost.
The % is the default chance of winning. You can change it per deal.

| Page | What you can do |
|---|---|
| Dashboard | Open pipeline, weighted forecast, won this month vs target, win rate, average deal size, days to win. Lists of deals that need attention, deals closing soon, healthiest deals, your tasks and recent wins. Switch between "My numbers" and "Team". |
| Pipeline board | Kanban board. Drag deals between stages, or onto Won / Lost. Each card has a "Move to" menu too (works on phones and with the keyboard). |
| Deals | Table with search, filters (status, stage, owner, health, close date), sorting, and **Export to CSV** (opens in Excel). |
| Deal page | Stage buttons, all deal facts, **deal health score** with reasons, activities, notes and a full history timeline. |
| Activities | Calls, emails, meetings, demos, tasks. Tabs for Overdue / Today / Upcoming / Done. Tick to finish. |
| Accounts | Companies with their contacts (mark decision makers), open and won value. |
| Forecast & reports | 6-month revenue forecast, conversion funnel, won per month, days spent in each stage, team leaderboard vs target, lead sources, reasons deals are lost, sales velocity. |
| Team (admin) | Add people, change role and monthly target, reset passwords, turn accounts off. |

### Key ideas, in simple words

- **Weighted value** = deal value × chance of winning. A ₹10 lakh deal at 50% counts as ₹5 lakh in the forecast.
- **Deal health score (0-100)** is made of simple points: how far the stage is, recent calls/meetings,
  how recently someone worked on it, talking to a decision maker, a sensible close date, a planned next step,
  minus points for being stuck in one stage over 30 days or a close date that has passed.
  70+ is Hot, 45-69 is Warm, below 45 is Cold. The deal page shows every point and why.
- **Going cold**: a deal nobody worked on for 14 days shows up in "Needs attention".
- **Win rate** = won ÷ (won + lost).
- **Sales velocity** = (open deals × win rate × average won value) ÷ average days to win.
  It says how much money the pipeline brings in per day (shown per month).
- **Lost deals need a reason**, so the "Why deals are lost" report is useful.

### Who can do what

| | Sales rep | Manager | Admin |
|---|---|---|---|
| See all deals, accounts, reports | ✔ | ✔ | ✔ |
| Edit / move / delete own deals | ✔ | ✔ | ✔ |
| Edit anyone's deal, give a deal to someone else | | ✔ | ✔ |
| Delete companies | | ✔ | ✔ |
| Manage the team | | | ✔ |

## Folder layout

```
sales-pipeline-manager/
├── backend/                         Java backend (also serves the website)
│   ├── pom.xml
│   ├── run.bat / run.sh             build + start
│   ├── run-tests.bat / run-tests.sh
│   └── src/
│       ├── main/java/com/cecil/pipeline/
│       │   ├── App.java             starts everything
│       │   ├── model/               User, Account, Contact, Deal, Activity, Enums
│       │   ├── store/               DataStore (JSON files), SeedData (demo data)
│       │   ├── service/             Auth, Account, Deal, Activity, Analytics
│       │   ├── insights/            DealScorer (health score)
│       │   ├── web/                 ApiServer (routes), Http (helpers)
│       │   └── util/                Json, Passwords, Csv
│       └── test/java/com/cecil/pipeline/AllTests.java
├── frontend/                        index.html, app.css, app.js, favicon.svg
├── data/                            created on first run (users, accounts, contacts, deals, activities .json)
├── pom.xml                          root Maven file for IntelliJ
├── run.bat / run.sh                 shortcuts to backend/run.*
└── run-tests.bat / run-tests.sh
```

## Settings (optional)

Set these as environment variables before starting:

- `PORT` – port number (default `8080`)
- `HOST` – `127.0.0.1` by default (only your computer). Use `0.0.0.0` to open it to your local network.
- `DATA_DIR` – where to keep the data files (default `data/` in the project folder)

## Security

- Passwords are stored as PBKDF2-SHA256 hashes with a random salt, never as plain text.
- 5 wrong passwords lock that username for 5 minutes.
- Sessions use random 256-bit tokens that expire after 12 hours of no use.
- Every API call checks the role (rep / manager / admin) on the server, not just in the browser.
- All user text is escaped before it is shown, and a strict Content-Security-Policy blocks inline scripts.
- Cross-site POST requests are rejected; static files cannot be read outside the `frontend` folder.
- CSV export blocks spreadsheet formula injection (`=`, `+`, `-`, `@`).
- Data files are written to a temp file first and then moved, so a crash never leaves a half-written file.

## API (for reference)

All API calls except login need the header `Authorization: Bearer <token>`.

| Method | Path | What |
|---|---|---|
| POST | /api/auth/login | `{username, password}` → `{token, user}` |
| POST | /api/auth/logout, /api/auth/password | sign out, change password |
| GET | /api/meta | stages, sources, activity types and other lists |
| GET/POST/PUT | /api/users, /api/users/{username} | team (admin) |
| GET/POST/PUT/DELETE | /api/accounts, /api/accounts/{id} | companies |
| GET/POST/PUT/DELETE | /api/contacts, /api/contacts/{id} | people |
| GET/POST | /api/deals | list (filters: status, stage, owner, accountId, q, priority, closing, health, sort) / create |
| GET | /api/deals/export.csv | CSV download (same filters) |
| GET/PUT/DELETE | /api/deals/{id} | one deal with activities, health score and timeline |
| POST | /api/deals/{id}/stage | `{stage, lostReason}` |
| POST/DELETE | /api/deals/{id}/notes, /api/deals/{id}/notes/{noteId} | notes |
| GET/POST/PUT/DELETE | /api/activities, /api/activities/{id} | activities (filters: owner, dealId, status) |
| POST | /api/activities/{id}/complete | `{done, outcome}` |
| GET | /api/dashboard?owner=me\|team\|{username} | dashboard numbers |
| GET | /api/reports?owner=&months=6 | forecast, funnel, leaderboard and more |
