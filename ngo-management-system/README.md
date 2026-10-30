# NGO Management System

Manage the day-to-day work of an NGO in one place: **programs** (with budgets and targets), **field activities**
(camps, classes, drives), **volunteers** who sign up and log their hours, **donors and donations** with printable
receipts, **expenses** that need approval, **beneficiaries**, and a printable **impact report**.

Plain **Java 17** backend (no external libraries) and a plain **HTML / CSS / JavaScript** frontend,
the same setup as the other projects in this folder. **Light mode and dark mode** are both built in.
The look is a new **"marigold & aubergine"** theme: a deep purple banner with a marigold garland (toran) along its edge,
chip-shaped tabs instead of a sidebar, warm cream pages, one colour per cause, and activities shown as "tickets" with a date stub.

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/main/java/com/cecil/ngo/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/ngo-management-system.jar` |

Then open **http://localhost:8080** in your browser.

### Demo logins

| Username | Password | Role | What they can do |
|---|---|---|---|
| admin | admin123 | Director / Admin | Everything, including logins, NGO details and approving over-budget spending |
| kavita, deepak | manager123 | Program Manager | Programs, activities, volunteers, hours, beneficiaries; submits expenses |
| rahul | finance123 | Finance Officer | Donors, donations, receipts; approves other people's expenses |
| aman, priya | volunteer123 | Volunteer | Sees open activities, signs up, logs own hours (no money or beneficiary data) |

On the first run the app fills itself with demo data for a made-up NGO in Jharkhand ("Asha Kiran Foundation"):
6 programs, about 57 activities, 16 volunteers, 12 donors, 32 donations, about 70 expenses and 60 beneficiaries.
All dates are counted from today, so the demo always looks current. All people and companies are made up.
To start fresh, stop the app and delete the `data` folder.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` (Mac / Linux). **25 tests**, with the clock fixed at 30 Oct 2026:
JSON, passwords, money (Indian commas, amount in words, financial year), CSV, demo data rules, sign-up rules
(seats, past, twice, inactive, same-day clash), volunteer limits, marking done, hours rules and approval,
receipt numbering, donation checks, "nobody approves their own expense", over-budget approval, program rules,
activity dates, beneficiary duplicates and privacy, roles, logins, sign-in lock-out, report totals, dashboard to-do,
saving to disk, and the HTTP API end to end.

## What it does

| Page | What you can do |
|---|---|
| Home | Money raised and spent this financial year, funds in hand, people reached, volunteer hours this month. Coming-up activities, money in vs out for 12 months, active programs with budget and reach bars, a **Needs attention** list (expenses / hours to approve, activities to close, activities short of volunteers, programs near their budget) and recent history. |
| Programs | Cards per program with budget used and people reached. A program page shows its activities, spending by category, latest expenses, donations given for it and volunteer hours. |
| Activities | Upcoming / past / all, grouped by month. Plan, edit, add volunteers, **mark done** with people reached, or cancel with a reason. |
| Volunteers | Search by name, city or skill. Profile with activities and hours. Export CSV. |
| Hours | Volunteers log their own hours after an activity is done; a program manager approves or rejects them. |
| Donations / Donors | Record a gift and print a **receipt** (number per financial year, amount in words, 80G line). Totals by payment mode. Export CSV. |
| Expenses | Submit a bill for a program. Finance approves or rejects it (with a reason). Export CSV. |
| Beneficiaries | Enrol people in programs, with a warning if the same name already exists in the same village. Breakdown by gender, age group and village. Export CSV. |
| Impact report | Any date range (default: this financial year). Per program: budget, money received, spent, activities, people reached, volunteer hours. Income by donor type, spending by category, top donors and volunteers. Printable. |
| Team, Settings, History | Admin only: logins and roles, NGO details printed on receipts, full activity history. |

## Rules the system enforces

- A volunteer **cannot be on two activities on the same day**, cannot join a full, past, done or cancelled activity.
- An activity can be marked done only on or after its date; hours can be logged only after that, only by people who were on it, 0.5 to 12 hours, once.
- **Nobody can approve their own expense.** An expense that takes a program **over its budget** can only be approved by the admin, with a note.
- A budget cannot be cut below what is already spent. A program cannot be completed while it still has planned activities.
- Receipt numbers run per financial year (`RCPT/2026-27/0001`) and a deleted receipt's number is **never reused**.
- Cash gifts above ₹2,000 are refused; UPI / bank / cheque gifts need a reference number, and the same reference cannot be recorded twice.
- Records that other records depend on (a program with activities, a donor with gifts, a volunteer with history) cannot be deleted — turn them off or complete them instead.

## Security

- Passwords stored with PBKDF2-SHA256 and a random salt; 5 wrong tries lock the username for 5 minutes; sessions end after 12 hours idle.
- Every API call checks the role on the **server**, not just in the browser. Volunteers never receive money or beneficiary data.
- All user text is escaped before it is shown, and a strict Content-Security-Policy blocks inline scripts and styles.
- Requests from other websites are refused (Origin check). CSV exports guard against spreadsheet formula injection.

## Project layout

```
ngo-management-system/
├── backend/
│   ├── pom.xml                       Maven build (optional - run.bat / run.sh use plain javac)
│   └── src/main/java/com/cecil/ngo/
│       ├── App.java                  starts everything
│       ├── model/                    Program, Activity, Volunteer, HoursLog, Donor, Donation, Expense, Beneficiary, User ...
│       ├── service/                  the rules: ProgramService, VolunteerService, FundService, BeneficiaryService,
│       │                             DashboardService, UserService, AuthService, Calc (shared numbers)
│       ├── store/                    DataStore (JSON files in data/) and SeedData (demo data)
│       ├── util/                     Json, Money, Csv, Passwords, Text
│       └── web/                      ApiServer (routes) and Http (helpers, security headers)
│   └── src/test/java/.../AllTests.java   25 tests, no JUnit needed
├── frontend/                         index.html, app.css, app.js, theme.js, favicon.svg
├── run.bat / run.sh                  build and start
└── run-tests.bat / run-tests.sh      build and test
```
