# Swikriti – Document Approval Workflow

**Digitize approvals.** Swikriti (स्वीकृति, "approval") replaces the paper file that goes from desk to desk for
signatures. It is built for **Damodar Consulting Engineers**, a made-up engineering consultancy in Ranchi with
**20 people**. A purchase request, a contract, a drawing or a policy goes through its **approval route**; everybody
signs on screen, deadlines are counted in office hours, and every signature goes into a **hash-chained ledger**.

| Part | What it does |
|---|---|
| **6 document types** | Purchase Requisition (PR), Expense Claim (EXP), Travel Request (TRV), Contract / Work Order (CON), Policy / SOP (POL), Design Drawing (DRG). Each type is a **workflow template** the Document Controller can change. |
| **Approval route** | A route has **stages** (Line manager → Procurement → Finance → Directors). A stage has **seats**: `MANAGER`, `MANAGER2` (manager's manager), `GROUP:Finance` or `USER:U-1003`. A seat is filled when one of its people signs. |
| **ALL, ANY, QUORUM** | ALL seats (two seats = **parallel review**, e.g. Legal and Finance at the same time), ANY one seat, or a **quorum** ("2 of 3 directors"). A stage fails only when it can **no longer get enough "yes"** – one "no" out of three directors does not stop a 2-of-3 stage. |
| **Condition language** | A stage can be needed only sometimes: `amount > 50k`, `amount > 5L and not urgent`, `dept in (Projects, Design)`, `vendor contains "new"` (k = thousand, L = lakh, cr = crore). A small parser with brackets, and/or/not, and "did you mean 'amount'?". |
| **Live route preview** | While you type the form, Swikriti shows who will sign. Change Rs. 38,500 to Rs. 7,00,000 and Finance and the directors appear. |
| **Four eyes** | The author is never on a seat. On some types (PR, CON, DRG) one person may sign **only one stage**. One person may fill only one seat of a stage. |
| **Revisions A, B, C** | Submitting **freezes** the text as a revision with a SHA-256 **fingerprint**. Sent back → the author changes it → **revision B** → the route starts again. "Nothing changed" is refused. |
| **Redlines** | Reviewers mark up single lines in red pencil (numbered balloons in the margin). A new revision closes them. |
| **Changes (diff)** | Compare any two revisions. Changed lines are circled with a **revision cloud**, like on a drawing (LCS algorithm). |
| **Out of office** | "Rahul approves for me from 18 to 26 Nov" – also for work that is already waiting. Chains (A → B → C) work, **circles are refused**. The signature says "Rahul Bhagat for Farah Siddiqui". |
| **Deadlines in office hours** | Each stage has a time limit (8, 16, 24 office hours: Mon–Fri 09:30–18:00, holidays skipped). **Urgent** documents get half. Reminder at 75 %; when overdue the task is **escalated** to the manager. |
| **Approval ledger** | Every submit and decision is one link of a **hash chain** signed with HMAC-SHA256 and a secret key. The Document Controller checks the whole chain; a changed or deleted entry is found. |
| **Certificates** | An approved document gets a verification code (`6506-3B31-276A`) in its title block. **Anybody** can check it without signing in – and can check that a PDF is exactly the approved attachment (SHA-256 in the browser). |
| **Policy library** | Approved policies and SOPs can be read by everybody. Everything else is private: author, cc, approvers, the author's managers, the controller. |
| **Reports** | Median and 85th percentile time to approval, on-time %, the **slowest stage** (bottleneck), approval rate per type, 12 weeks of throughput, who has most waiting. |
| **Controller desk** | Edit workflows (with a live condition check), people and approver groups, holidays, activity log, register as CSV (Excel). |

The backend is plain **Java 17** with no external libraries (web server, JSON, CSV, password hashing, HMAC, the
parser and the algorithms all use the JDK only). The frontend is plain **HTML / CSS / JavaScript**, like the other
projects in this folder. **Light and dark mode** are built in.

The look is a new **"blueprint & title block"** theme from an engineering drawing office:

- every document is a **drawing sheet** with a double frame and zone marks (1–8, A–E) around the edge;
- the signatures are in the **title block** (Written / Checked / Approved), in a handwriting font, with the ledger hash;
- revisions are **triangles** with the letter (Δ A, Δ B) and a **revision table**; reviewers write **redlines**;
- the approval route is drawn as boxes on a dimension line; changed lines get a **revision cloud**;
- fonts: Barlow Condensed (headings, like technical lettering), Public Sans (text), Architects Daughter (signatures and redlines), Overpass Mono (numbers and hashes).

Light mode is **Whiteprint** (pale paper, Prussian-blue ink), dark mode is **Cyanotype** (the classic blueprint) – switch it at the bottom of the menu.

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/com/cecil/swikriti/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/swikriti.jar` |

Then open **http://localhost:8080**. (Set `PORT` to use another port.)

The first start takes a few seconds. It builds the demo company in the `data` folder: 20 people, 6 document types and
**about 130 documents with 5 months of history**. The history is not typed in: it is **played through the real
services** with the clock moved back (people submit, approvers decide hours later – some late, so escalation happens –
some documents are sent back and revised, some rejected). So every number in the reports got there by the normal rules
and the ledger is a valid chain. The last few days are written by hand so each demo user has something to do.
Everything is dated relative to *now*. **All people are made up.** To start fresh, stop the app and delete the `data` folder.

### Demo logins

| Username | Password | Who | Good for |
|---|---|---|---|
| ankit | member123 | Ankit Tirkey, design engineer | A drawing **sent back with redlines**, an approved drawing with **revision B** (see *Changes*), a purchase in review, a draft |
| sunita | manager123 | Sunita Lakra, projects manager | Approvals waiting, one **overdue** (escalated to Arvind) |
| rahul | manager123 | Rahul Bhagat, finance manager | Finance signatures, and **signs for Farah** (out of office) on the 2-of-3 directors stage |
| kavita | manager123 | Kavita Ekka, managing director | The second director's signature for Rs. 12.8 lakh, an SOP to approve |
| neha | admin123 | Neha Kujur, Document Controller | Workflows, people, holidays, **ledger check**, reports, CSV |

Other people: deepak, arvind, farah, imran, priyanka, manish, sanjay, meghna, vivek, pooja, amit, ritu, sneha, anjali, rohit
(password `member123`; managers `manager123`).

### Try it

1. Sign in as **ankit**. Open *Pile cap details – Block B*: it was **sent back**; the redlines are in the margin.
   *Edit* the two lines, then *Submit revision B* (you must say what you changed).
2. Open *Foundation layout – Block B* → **Changes**: revision A against B with revision clouds. The **title block**
   shows Checked and Approved with their ledger hashes and the **verification code**.
3. *New document* → Purchase Requisition. Type an amount of 38,500, then 70,000, then 7,00,000 and watch **Who will sign**.
4. Sign in as **rahul**. The inbox shows *Steel scaffolding* with "For Farah Siddiqui". Approve it: the directors stage
   needed 2 of 3 – Arvind already signed – so the document is **approved** and gets a certificate.
5. Copy the certificate code, sign out and use **Check a certificate** on the sign-in page.
6. Sign in as **sunita**: *Safety nets* is overdue and was **escalated**. Try *Out of office* and name a colleague.
7. Sign in as **neha** → **Approval ledger** (the chain is checked), **Controller desk → Workflows** (edit a condition:
   type `ammount > 5` and see the error), **Reports** (the slowest stage).
8. Switch to **Cyanotype** (dark mode) at the bottom of the menu.

## How it works

```
 website (HTML/CSS/JS) ──/api JSON──► ApiServer ─► AuthService · DocumentService · WorkflowService · AdminService
                                         │         ViewService (read-only pages) · Access (who may read)
                                         │
             core: Condition (parser) · BusinessCalendar (office hours) · Delegations (graph, loop check)
                   Diff (LCS) · Ledger (HMAC hash chain) · DocNumber (financial year) · Stats (median, percentile)
                                         ▼
                 DataStore (memory + JSON files in data/, attachments in data/files/<sha256>.bin)
```

### Life of a document

```
 DRAFT ──submit (revision A, fingerprint)──► IN_REVIEW ──every needed stage signed──► APPROVED (certificate)
                                                │  └──a stage can no longer get enough "yes"──► REJECTED
                                                └──sent back (reason)──► RETURNED ──edit, submit (revision B)──► IN_REVIEW
 DRAFT / IN_REVIEW / RETURNED ──author withdraws (reason)──► WITHDRAWN
```

### The rules of a stage

| | |
|---|---|
| Needed yes votes | ALL → every seat · ANY → 1 · QUORUM → q |
| After each decision | yes ≥ needed → stage done, next stage · yes + still open < needed → **rejected** · sent back → returned |
| Who sits on a seat | the rule's people − the author (− people who signed earlier stages, for "one stage each" types) + their delegates; nobody left → the Document Controller |
| Deadline | `due = now + time limit` in office hours (half for urgent); reminder at 75 % of the office hours; overdue → the candidate's manager is added |
| Template changes | the stages are **copied** into the document at submit – documents on their way keep their route |

### The algorithms

* **Recursive-descent parser** for conditions (`or` → `and` → `not` → `atom`), numbers with k / L / cr, error position
  and **Levenshtein distance** for "did you mean".
* **Office-hours arithmetic**: walk day by day through opening hours, skipping weekends and holidays.
* **Longest common subsequence** (dynamic programming, O(n·m)) for the line diff between revisions.
* **Graph search**: delegations are arrows A → B; following the chain stops at 5 steps and never visits a person twice;
  a new arrow is refused if a **depth-first search** from B can reach A through overlapping dates.
* **HMAC-SHA256 hash chain**: `hash = HMAC(key, seq|at|doc|rev|action|user|onBehalfOf|docHash|prevHash)`.
* **SHA-256 fingerprints** of every revision and attachment; **percentiles** with linear interpolation.
* Document numbers per type and **Indian financial year**: `PR/26-27/0042` (1 April – 31 March).

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` runs **29 tests** without JUnit. The clock is frozen at Monday 16 Nov 2026.
They cover JSON, passwords, CSV (with the Excel formula guard), office hours with holidays, the condition language
and its error messages, the LCS diff, the ledger (wrong key, changed and deleted entries), document numbers, form checks,
conditional stages, four eyes, quorum (including a "no" that does not reject), parallel seats, one-stage-each,
send back and revision B, reject / approve / certificate / public check, withdraw, delegation (chains, loops, waiting
work), deadlines / reminders / escalation / urgent, fallback to the controller, who may read, redlines, attachments,
template rules, account lock, **threads racing to sign the same stage**, the web server (CSRF, cookies, JSON only, no
path tricks) and the whole demo company (no rule is broken anywhere, the ledger is intact).

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8080 | Web port |
| `DATA_DIR` | `data` in this folder | Where the JSON data, the attachments and the ledger key are kept |

## Security and privacy

* Passwords: PBKDF2-HMAC-SHA256, 120,000 rounds, random salt. 5 wrong passwords lock the account for 5 minutes;
  10 sign-in tries per minute per computer; the same message for unknown user and wrong password.
* Sessions: random token in an **HttpOnly, SameSite=Strict** cookie; only its SHA-256 is kept; 12 hours idle.
* CSRF: every change needs the `X-Swikriti` header, a same-site Origin and JSON. CSP, X-Frame-Options and nosniff headers.
  Pages are built without `innerHTML` (no XSS). CSV export guards against formula injection.
* Attachments: only PDF, PNG and JPG, recognised by their **first bytes** (not the file name), at most 1 MB, stored
  under their SHA-256. Downloads are sent as attachments; only images are shown inline.
* The ledger key (`data/ledger.key`) is made once with a secure random generator. **Keep it secret and back it up with the data.**
* A document is private unless you are allowed to read it; the public check shows only what is on the certificate.

## Folders

```
document-approval/
├─ run.bat / run.sh, run-tests.bat / run-tests.sh   start the app / the tests
├─ pom.xml                                           Maven parent (IntelliJ)
├─ backend/
│  ├─ pom.xml
│  ├─ src/com/cecil/swikriti/
│  │  ├─ App.java          starts everything + the background job (reminders and escalation every 5 minutes)
│  │  ├─ model/            User, Template, Stage, Document, Revision, RouteStage, Task, Delegation, Comment, LedgerEntry ...
│  │  ├─ core/             Condition, BusinessCalendar, Delegations, Diff, Ledger, DocNumber, Stats
│  │  ├─ service/          Auth, Document, Workflow, Admin, View, Access, Problem
│  │  ├─ store/            DataStore (memory + JSON files), SeedData (demo company)
│  │  ├─ web/              ApiServer (routes, CSRF, rate limit), Http
│  │  └─ util/             Json, Csv, Passwords, Clock, Text
│  └─ test/com/cecil/swikriti/AllTests.java
├─ frontend/               index.html, app.css (blueprint theme), app.js, theme.js, favicon.svg
└─ data/                   created on first start (not in git)
```

## Limits (honest)

* The people, the company and the history are **made up**; the holidays in the demo are examples – set your own.
* A signature here is a strong electronic record (who, when, which fingerprint), not a legal digital signature
  with a certificate authority (like an Aadhaar eSign or DSC).
* Notifications stay inside the app (no e-mail).
* Fine for one company of a few hundred people; a big company would use a database instead of JSON files.
