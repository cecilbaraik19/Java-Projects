# Bijak – Invoice Management System

**Automate invoicing.** Bijak (बीजक, "invoice") replaces the carbon-copy bill book and the Excel sheet of "who has
not paid yet". It is built for **Koel Valley Packaging Pvt. Ltd.**, a made-up maker of corrugated boxes in Adityapur
(Jamshedpur) with 5 staff and 16 customers in 7 states. Invoices get their GST worked out and a gap-free number,
repeating bills go out by themselves, reminders are written on time, and the bank statement is matched to the invoices.

| Part | What it does |
|---|---|
| **GST invoices** | Pick the customer and items; the place of supply decides **CGST + SGST** (same state as the seller) or **IGST** (another state). Tax per line, total rounded to the rupee with a "Round off" line, **amount in words** in lakh and crore. |
| **Gap-free numbers** | A draft has no number. At issue it gets the next number of its series and **financial year** (`KVP/26-27/0042`, 1 April – 31 March). A series stays in **date order**: an invoice cannot be dated before the last one. Numbers are given inside one lock – 16 people issuing at the same second get 16 different numbers. |
| **GSTIN check** | The 15-character GSTIN is checked with its **mod-36 check character**; the state and PAN are read from it. Every one-character typing mistake is caught. |
| **Locked after issue** | An issued invoice never changes. Its content gets a **SHA-256 fingerprint**; if somebody edits the data file by hand, the invoice page shows a red warning. Mistakes are corrected with a **credit note**. |
| **Approval and credit limit** | Sales may issue up to Rs. 2,00,000; above that the Accountant approves (**four eyes**: never your own). If a customer would go above their **credit limit**, Sales is stopped and the Accountant must write why. |
| **Credit notes** | For goods returned or a price cut, against a line of the invoice. Never more than is left on that line. The credit is taken off the invoice; anything more becomes an advance. |
| **Payments** | Receipts (`KVR/26-27/0010`) by UPI, NEFT, RTGS, IMPS, cheque or cash (cash of Rs. 2 lakh or more is refused – section 269ST). Given to the invoices you tick, or **oldest first**. **TDS** kept by the customer counts as paid. A bounced cheque is **voided** and the invoice opens again. |
| **Advances** | Money that is not for any invoice yet waits as an advance and is **used by the next invoice automatically**. |
| **Repeating invoices** | AMC monthly on the 1st, storage on the 31st (**the last day in shorter months**), weekly supply every Monday, quarterly orders. The background job makes, issues and e-mails each one; after downtime it **catches up in date order**. "Check before issue" makes drafts instead. |
| **Payment reminders** | A ladder of 5: 3 days before, on the due date, 7, 15 and 30 days late (changeable). Each level once; after a break **only the highest** is sent. Nothing for disputed invoices (**on hold**) or customers with reminders off. The final notice shows **interest** @18% a year. |
| **Bank matching** | Upload the bank's CSV (columns found by name, debits and lines already imported skipped). For each credit Bijak scores customers: invoice number in the narration (`KVP 0042`, `KVP26270042`…), GSTIN/PAN, the name (fuzzy), and amounts that fit **one invoice, one invoice less TDS, everything owed, or a combination (subset sum)**. "Sure / Likely / Weak" with the reasons. You accept; nothing is booked by itself. |
| **Outbox** | Every e-mail Bijak wrote (invoice, receipt, reminders, credit note) with the full text. In the demo nothing is really sent. |
| **Reports** | Ageing (not due, 1-30, 31-60, 61-90, 90+), **DSO**, how fast each customer pays (median, on-time %), invoiced vs collected for 12 months, and the **GST summary in GSTR-1 shape** (B2B, B2C, credit notes, tax by rate, HSN summary) as a CSV for the CA. |
| **Customer statement** | Opening balance, invoices, payments, TDS and credit notes with a running balance, for any period. Printable. |

The backend is plain **Java 17** with no external libraries (web server, JSON, CSV, password hashing, SHA-256 and all
the algorithms use the JDK only). The frontend is plain **HTML / CSS / JavaScript**, like the other projects in this
folder. **Light and dark mode** are built in.

The look is a new **"carbon-copy bill book"** theme:

- the invoice is a page of an Indian bill book: **Original** (white), **Duplicate** (pink) and **Triplicate** (yellow)
  tabs change the paper, with a **counterfoil** stub and a **perforated tear line** on the left;
- invoice numbers are in red, like a **numbering machine**; figures are typed (Courier Prime);
- a paid invoice gets **PAID punched as holes**, like the perforator that cancels cheques (CANCELLED and DRAFT too);
- the menu is the book's **cloth-bound spine** with its stitched thread; the sign-in page is the **cover**;
- fonts: Zilla Slab (headings), Work Sans (text), Courier Prime (figures), Gochi Hand (signature).

Light mode is **Bill book**, dark mode is **Carbon paper** (the deep indigo sheet with pale violet type) – switch it at
the bottom of the menu. "Print all 3 copies" prints Original, Duplicate and Triplicate on three A4 pages.

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/com/cecil/bijak/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/bijak.jar` |

Then open **http://localhost:8080**. (Set `PORT` to use another port.)

The first start takes a few seconds. It builds the demo company in the `data` folder: 5 people, 16 customers, 14 items,
7 repeating invoices and **about 180 invoices with 5½ months of history**. The history is not typed in: it is **played
through the real services** with the clock moved back – every day the automation runs (repeating invoices, reminders),
Sales raise invoices, the Accountant approves the big ones and records each customer's payments according to their habit
(early, on time, late, very late, in lumps, with TDS). A cheque bounces, boxes come back damaged, an invoice is cancelled,
one is disputed. The money of the **last week is left in a bank statement** for you to match.
Everything is dated relative to *now*. **All people and companies are made up.** To start fresh, stop the app and delete the `data` folder.

### Demo logins

| Username | Password | Who | Good for |
|---|---|---|---|
| meera | owner123 | Meera Hansdah, Owner | Everything, **Owner's desk** (company, prefixes, approval limit, reminder days, people) |
| arjun | accounts123 | Arjun Mahato, Accountant | An invoice **waiting for approval**, **12 bank lines to match**, payments, credit notes |
| salma | sales123 | Salma Parveen, Sales | A draft **sent back** by Arjun, a draft in progress, new invoices |
| vikram | audit123 | Vikram Agarwal, CA (Auditor) | Reads everything, changes nothing: reports, GST, activity log |

Also: rohit / sales123 (Sales).

### Try it

1. Sign in as **salma** → **New invoice** → Dalma Pharma, BOPP tape, 50. Watch the CGST/SGST and total appear.
   Change the customer to Deccan (Karnataka): it becomes **IGST**. Issue it – it gets the next number.
2. Open **Kalinga Steel Fabricators**' draft (sent back: "50 rolls, not 60"), change line 1 and issue it again.
3. Sign in as **arjun**. *Today* shows the Hooghly invoice **waiting for approval** – approve it.
4. **Bank matching** → look at the reasons ("invoice named in the narration", "amount equals 2 invoices together",
   "less 2% TDS") → **Accept all sure matches**. The two-invoice Kalinga line stays for you: accept it by hand.
5. Open any paid invoice: **PAID** is punched in; switch **Duplicate / Triplicate**; **Print all 3 copies**.
6. Open an open invoice → **Make credit note** (10 boxes returned) → the invoice balance drops.
7. **Repeating invoices** → *New repeating invoice* → pick "On 31" and watch the next dates: 30 Nov, 31 Dec, 31 Jan, 28 Feb…
8. **Outbox** → *Reminders*: friendly, due today, overdue, second notice, final notice with interest.
9. **Reports and GST** → choose last month → *Download for the CA*.
10. Switch to **Carbon paper** (dark mode) at the bottom of the menu.

## How it works

```
 website (HTML/CSS/JS) ──/api JSON──► ApiServer ─► AuthService · CustomerService · InvoiceService · PaymentService
                                         │         AutomationService (repeating + reminders) · AdminService
                                         │         ViewService (read-only pages and reports) · Letters (e-mail texts)
                                         │
     core: Tax (GST) · Gstin (check character) · FinYear (numbers) · Schedule (dates) · Dunning (reminder ladder)
           BankMatch (scoring, subset sum, FIFO) · Stats (median, percentile, ageing)        util: Money (paise, words)
                                         ▼
                 DataStore (memory + JSON files in data/: invoices, payments, customers, outbox ...)
```

### Life of an invoice

```
 DRAFT ──issue──► ISSUED ──part payment──► PART_PAID ──rest──► PAID
   │  └─Sales, above the limit─► PENDING_APPROVAL ──approve (someone else)──► ISSUED
   │                                         └──send back (why)──► DRAFT
 ISSUED ──cancel (nothing paid or credited, why)──► CANCELLED     (the number stays, no gap)
 "Overdue" is not a status: it is worked out every time (due date passed, something still owed).
```

### Money is kept in paise

Every amount is a whole number of **paise** in a `long` (Rs. 1 = 100 paise). Never a `double`: 0.1 + 0.2 is not 0.3 in
floating point, but 10 + 20 paise is always 30. Rounding is "half away from zero", the way people round money.

### The algorithms

* **GST**: per line `taxable = qty × rate − discount`; same state → `CGST = SGST = taxable × rate/2`, else
  `IGST = taxable × rate`; rounded per line, then the total to the rupee (round off at most 50 paise).
* **Amount in words**, Indian system: crore (1,00,00,000), lakh (1,00,000), thousand, hundred.
* **GSTIN check character**: each of the first 14 characters is a number 0-35, every second one doubled,
  `sum += product / 36 + product % 36`, check = `(36 − sum % 36) % 36`.
* **Gap-free numbering**: counter per series and financial year, taken inside a `synchronized` block only at issue.
* **Dates for repeating bills**: the wanted day (anchor) is kept apart from the next date, so 31 Jan → 28 Feb → 31 Mar.
* **Reminder ladder**: the highest level whose day has come; send it only if higher than what was sent.
* **Simple interest**: `balance × rate × days late / 365`.
* **Bank matching**: points for clues (max 100); **subset sum** by trying every combination of the 12 oldest open
  invoices (2¹² = 4,096 bit masks; fewer invoices win, then older ones); **FIFO** when the amount fits nothing exactly;
  **Levenshtein distance** for names with a typo; a **regular expression** for the many ways an invoice number is typed.
* **Ageing buckets, median and 85th percentile** (linear interpolation), **DSO** = owed ÷ last 90 days' sales × 90.
* **SHA-256 fingerprint** of every issued invoice.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` runs **30 tests** without JUnit. The clock is frozen at Monday 16 Nov 2026.
They cover JSON, money and rounding, amount in words, the GSTIN check (every one-character typo is caught), GST in and
out of the state, financial years, repeating dates (31st, leap year, quarterly, weekly), the reminder ladder and
interest, bank matching (numbers, names, TDS, subset sum), CSV, drafts, gap-free numbers and date order, approval and
four eyes, credit limits, credit notes, cancelling, payments and their checks, advances, voiding, the catch-up of
repeating invoices, reminders after downtime and on hold, bank import and auto-matching, statements and ageing, the GST
report, roles, account lock, **16 threads issuing at once**, the web server (CSRF, cookies, JSON only, no path tricks) and
the whole demo company (every series gap-free and in date order, every total adds up, every fingerprint intact).

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8080 | Web port |
| `DATA_DIR` | `data` in this folder | Where the JSON data is kept |

Inside the app (Owner's desk): company and bank details, prefixes, approval limit, reminder days, interest, terms.

## Security and privacy

* Passwords: PBKDF2-HMAC-SHA256, 120,000 rounds, random salt. 5 wrong passwords lock the account for 5 minutes;
  10 sign-in tries per minute per computer; the same message for unknown user and wrong password.
* Sessions: random token in an **HttpOnly, SameSite=Strict** cookie; only its SHA-256 is kept; 12 hours idle.
* CSRF: every change needs the `X-Bijak` header, a same-site Origin and JSON. CSP, X-Frame-Options and nosniff headers.
  Pages are built without `innerHTML` (no XSS). CSV export guards against formula injection (`=SUM(...)`).
* Roles: Owner (everything), Accountant (money), Sales (customers and invoices up to the limit), Auditor (read only).
* The web server listens on 127.0.0.1 only (this computer).

## Folders

```
invoice-management/
├─ run.bat / run.sh, run-tests.bat / run-tests.sh   start the app / the tests
├─ pom.xml                                           Maven parent (IntelliJ)
├─ backend/
│  ├─ pom.xml
│  ├─ src/com/cecil/bijak/
│  │  ├─ App.java          starts everything + the background job (repeating invoices and reminders every 10 minutes)
│  │  ├─ model/            Invoice, Line, Customer, Item, Payment, Alloc, Recurring, BankLine, Mail, Settings, User ...
│  │  ├─ core/             Tax, Gstin, FinYear, Schedule, Dunning, BankMatch, Stats
│  │  ├─ service/          Auth, Customer, Invoice, Payment, Automation, View, Admin, Letters, Access, Problem
│  │  ├─ store/            DataStore (memory + JSON files), SeedData (demo company)
│  │  ├─ web/              ApiServer (routes, CSRF, rate limit), Http
│  │  └─ util/             Json, Csv, Money, Passwords, Clock, Text
│  └─ test/com/cecil/bijak/AllTests.java
├─ frontend/               index.html, app.css (bill-book theme), app.js, theme.js, favicon.svg
└─ data/                   created on first start (not in git)
```

## Limits (honest)

* The people, the companies and the history are **made up**. GST rules are simplified for learning: no e-invoice IRN
  or QR from the government portal, no e-way bill, no reverse charge, no exports under LUT, no composition dealers.
* E-mails are written to the outbox, not sent.
* The GST report has the shape of GSTR-1 but is not the official JSON for upload.
* Fine for one company with a few thousand invoices a year; a big company would use a database instead of JSON files.
