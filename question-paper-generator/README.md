# Question Paper Generator

Make exam question papers from a question bank. Teachers keep every question in one place
(by subject, unit, marks, difficulty and Bloom level), describe the paper they want in a **blueprint**,
and the generator picks a fair mix of easy, medium and hard questions from every unit.
It can make **Set A, B, C** with different questions, and the paper then goes through
**review → approval → release**, with a printable paper and answer key.

Plain **Java 17** backend (no external libraries) and a plain **HTML / CSS / JavaScript** frontend,
the same setup as the other projects in this folder. **Light mode and dark mode** are both built in.
The look is a "chalkboard and index card" theme, and the paper itself is shown as a real A4 sheet.

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/main/java/com/cecil/qpg/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/question-paper-generator.jar` |

Then open **http://localhost:8080** in your browser.

### Demo logins

| Username | Password | Role |
|---|---|---|
| meera | hod12345 | Head of Department (approves and releases papers) |
| rohan, ananya | faculty123 | Faculty (add questions, make papers) |
| admin | admin123 | Admin (also manages the team) |

On the first run the app fills itself with demo data: 4 subjects (Operating Systems CSIT135, Java, DBMS,
Computer Networks) with their units, about 190 real exam questions, 4 blueprints and 4 papers in different stages
(one released, one approved, one waiting for review, one draft). All people are made up.
To start fresh, stop the app and delete the `data` folder.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` (Mac / Linux). 26 tests: JSON, passwords, CSV, near-duplicate
detection, difficulty maths, the generator (follows the blueprint, same seed = same paper, sets differ,
bank too small, no near-duplicates, avoids recent questions, MCQ shuffle), paper analysis, sign-in and lock-out,
roles, question and blueprint form checks, CSV import / export, the review flow, swap / move, delete rules,
subjects, saving to disk and the HTTP API end to end.

## What it does

| Page | What you can do |
|---|---|
| Dashboard | Bank size, never-used questions, papers by status, papers waiting for your review, your drafts (with the HOD's note if sent back), bank strength per subject (easy / medium / hard), coming exams, recent activity. |
| Question bank | Search and filter by subject, unit, kind, difficulty, Bloom level, text or tag. Add / edit questions (MCQ options with the correct one ticked, True/False, fill in the blank, short, long, numerical). A **live duplicate check** warns while you type. Archive, restore, delete. **Import / export CSV** (with a check step before importing). |
| Coverage | For each subject: a heat map of units × difficulty and units × kind of question. Red boxes are empty. Lists thin spots ("Unit 3 has no hard questions"). |
| Blueprints | Paper patterns: sections (kind, questions printed, questions to attempt, marks each), difficulty mix %, units, general instructions, "avoid questions used in the last N days". The editor checks the bank **live** and says how many sets can be made with no repeats. Copy a blueprint to change it. |
| Generate paper | Pick a blueprint, 1-4 sets, exam date, optional title and seed. |
| Paper | The paper as a printed A4 sheet. Show the **answer key** and the **unit / difficulty / Bloom tags** on top of it. In a draft, swap any question (automatic or choose from a list), move questions up / down, edit the header, or make the whole paper again. Side panel: **balance score**, planned vs actual difficulty, marks by unit, Bloom levels, generator notes, history. Print the paper or the answer key (only the sheet prints), or download them as text. |
| Subjects & units | Subjects with their units in order (HOD adds and edits). |
| Paper settings (HOD) | Institute and department printed on papers; how alike two questions must be to count as duplicates. |
| Team (admin) | Add people, change roles, reset passwords, turn accounts off. |

### How the generator chooses questions

For each place in a section, it scores every question that fits (same kind and marks, right subject and units,
not archived) and takes the one with the **lowest score**:

| Part of the score | Why |
|---|---|
| a small random number (0-1) | different papers each time; the **seed** makes it repeatable |
| +10 if it is not the difficulty still needed (+5 if that difficulty is still needed later) | keeps the easy / medium / hard mix |
| +1.5 for each question already taken from the same unit | spreads questions over all units |
| +5 if another set of the same exam already has it | Set A, B, C get different questions |
| +3 if it was in a released paper in the last N days | avoids repeating last exam's questions |
| +0.2 for each time it was used before | gives fresh questions a chance |

Questions that are almost the same as one already chosen are skipped. "Almost the same" uses
**Jaccard similarity**: important words in both ÷ all important words (common words like "explain", "the" are ignored).
60% or more counts as a near-duplicate (changeable in Paper settings).

The mix becomes whole numbers with the **largest remainder** method: 7 questions at 30 / 50 / 20 % =
2.1 / 3.5 / 1.4 → 2 / 3 / 1 (= 6) → the biggest leftover (.5) gets the last one → **2 / 4 / 1**.

If the bank runs short, the generator takes what it can and writes a **note** on the paper
(for example "Section D: only 2 of 4 questions found").

### Balance score (0-100)

| Part | Points | Full points when |
|---|---|---|
| Mix | 50 | the easy / medium / hard % match the blueprint (minus 1 point per % of the biggest gap) |
| Units | 30 | every unit of the subject has at least one question |
| Thinking | 20 | 40% or more of the marks test Bloom level 3+ (apply, analyse, evaluate, create) |

### Review flow

Draft → **Send for review** (teacher) → **Approve** or **Send back with a note** (HOD) → **Release** (HOD, on exam day).
Only drafts can be changed. Releasing locks the paper and marks its questions as used today,
so the next papers avoid them. Each paper keeps a **copy** of its questions, so editing the bank later never changes
an approved or released paper. Released papers cannot be deleted.

### Who can do what

| | Faculty | HOD | Admin |
|---|---|---|---|
| See the bank, blueprints and papers; generate papers | ✔ | ✔ | ✔ |
| Add questions; edit / delete own questions and blueprints; edit own drafts | ✔ | ✔ | ✔ |
| Edit anyone's questions, blueprints and drafts; manage subjects and settings | | ✔ | ✔ |
| Approve, send back, release papers | | ✔ | ✔ |
| Manage the team | | | ✔ |

## CSV format (import / export)

```
subject,unit,type,difficulty,bloom,marks,question,options,answer,tags
CSIT135,2,MCQ,EASY,REMEMBER,1,A program in execution is called a,Thread | Process | File | Job,Process,process
CSIT135,4,SHORT,MEDIUM,UNDERSTAND,5,Explain paging with a diagram.,,Pages and frames...,paging
```

- type: `MCQ`, `TRUE_FALSE`, `FILL_BLANK`, `SHORT`, `LONG`, `NUMERICAL`
- difficulty: `EASY`, `MEDIUM`, `HARD` (default MEDIUM) · bloom: `REMEMBER`…`CREATE` or `L1`…`L6` (default UNDERSTAND)
- MCQ options go in one cell separated by `|`; the answer must be one of them.
- Rows with mistakes, or that are already in the bank, are skipped and listed.
- In Excel use *File → Save As → CSV UTF-8*. "Download a sample file" in the import box gives a ready example.

## Light and dark mode

The app follows the computer's setting the first time. The **Dark mode / Light mode** button switches it,
and the browser remembers the choice. The paper sheet stays white in both modes, like real paper.

## Folder layout

```
question-paper-generator/
├── backend/                         Java backend (also serves the website)
│   ├── pom.xml
│   ├── run.bat / run.sh             build + start
│   ├── run-tests.bat / run-tests.sh
│   └── src/
│       ├── main/java/com/cecil/qpg/
│       │   ├── App.java             starts everything
│       │   ├── model/               User, Subject, Question, Blueprint, Paper, Event, Settings, Enums
│       │   ├── store/               DataStore (JSON files), SeedData + SeedQuestions (demo data)
│       │   ├── generator/           PaperGenerator (choosing questions, swap, bank check, analysis)
│       │   ├── service/             Auth, Users, Bank (questions, subjects, CSV), Blueprints, Papers, Dashboard
│       │   ├── web/                 ApiServer (routes), Http (helpers, security headers)
│       │   └── util/                Json, Passwords, Csv, Text (cleaning, similarity)
│       └── test/java/com/cecil/qpg/AllTests.java
├── frontend/                        index.html, app.css, app.js, theme.js, favicon.svg
├── data/                            created on first run (users, subjects, questions, blueprints, papers, events, settings)
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
- Every API call checks the role (faculty / HOD / admin) on the server, not just in the browser.
- All user text is escaped before it is shown, and a strict Content-Security-Policy blocks inline scripts and styles.
- Requests from other websites are rejected; static files cannot be read outside the `frontend` folder.
- Request bodies are limited to 1 MB; every number, date and text length from a form is checked.
- CSV export blocks spreadsheet formula injection (`=`, `+`, `-`, `@`).
- Data files are written to a temp file first and then moved, so a crash never leaves a half-written file.

## API (for reference)

All API calls except login need the header `Authorization: Bearer <token>`.

| Method | Path | What |
|---|---|---|
| POST | /api/auth/login, /api/auth/logout, /api/auth/password | sign in / out, change password |
| GET | /api/auth/me, /api/meta, /api/dashboard, /api/events?ref=&limit= | me; lists; home page; history |
| GET/POST/PUT | /api/users, /api/users/{username} | team (admin) |
| GET/POST/PUT/DELETE | /api/subjects, /api/subjects/{code} | subjects and units |
| GET | /api/coverage?subject= | questions per unit, difficulty and kind |
| GET/POST | /api/questions?subject=&unit=&type=&difficulty=&bloom=&q=&archived=&sort=&limit=&offset= | list / add |
| GET/PUT/DELETE | /api/questions/{id} | one question (with papers and similar questions) |
| POST | /api/questions/{id}/archive, /restore | archive / restore |
| POST | /api/questions/similar | `{subjectCode, text, exceptId}` near-duplicates |
| GET / POST | /api/questions/export.csv, /api/questions/template.csv, /api/questions/import | CSV (`{csv, dryRun}`) |
| GET/POST/PUT/DELETE | /api/blueprints, /api/blueprints/{id} | blueprints (each with a bank check) |
| POST | /api/blueprints/check, /api/blueprints/{id}/copy | check an unsaved blueprint; copy |
| POST | /api/papers/generate | `{blueprintId, sets, examDate, title, seed}` |
| GET/PUT/DELETE | /api/papers?status=&subject=&q=, /api/papers/{id} | list; one paper with analysis; edit header; delete |
| GET | /api/papers/{id}/candidates?section=&item=, /api/papers/{id}/export.txt?key=1 | swap choices; text download |
| POST | /api/papers/{id}/swap, /move, /regenerate | change a draft |
| POST | /api/papers/{id}/submit, /approve, /send-back, /release | review flow |
| GET/PUT | /api/settings | paper header and duplicate limit |
