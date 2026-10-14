# AI Customer Complaint Resolution Assistant

A Java web app that reads customer complaints and **recommends how to solve them**.

For every complaint, the AI works out:

- **What it is about** (billing, delivery, refund, account, technical, product quality, service)
- **How the customer feels** (very negative → positive) and emotions like anger or worry
- **How urgent it is** (low → critical), with the reasons shown
- **The best solutions** from the knowledge base, ranked with a match score
- **A ready-to-send reply** written for that customer

It runs **fully offline** with plain Java 17+. No database, no Spring, no internet, no API key needed.

---

## Folders

| Folder | What is inside |
|---|---|
| `backend/` | Java server: REST API, AI engine, data storage, tests |
| `frontend/` | Website: agent console and customer portal (HTML, CSS, JavaScript) |

The backend also serves the frontend files, so you start **one** program and get both.

## How to run

You only need **Java JDK 17 or newer**.

**Windows** – double-click `run.bat` (in the main folder or inside `backend/`).
**macOS / Linux / Git Bash** – `./run.sh`
**IntelliJ** – open the main folder, then right-click `backend/src/main/java/com/cecil/complaints/App.java` → **Run 'App.main()'**.

Then open:

| Page | Address |
|---|---|
| Agent console | http://localhost:8080 |
| Customer portal | http://localhost:8080/portal |

| Login | Password | Role |
|---|---|---|
| admin | admin123 | Admin (can edit knowledge base, add team, delete) |
| priya | agent123 | Agent |
| rahul | agent123 | Agent |

On first run the app creates a `data/` folder with 12 demo complaints and 21 knowledge-base solutions.
Delete the `data/` folder to start fresh.

**Run the tests:** `run-tests.bat` (Windows) or `./run-tests.sh` → 32 tests.

**With Maven (optional):** in `backend/` run `mvn package`, then `java -jar target/complaint-assistant.jar`.

### Settings (optional environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8080 | Web port |
| `DATA_DIR` | `data` | Where JSON data is saved |
| `FRONTEND_DIR` | `frontend` or `../frontend` | Where the website files are |
| `APP_TIMEZONE` | Asia/Kolkata | Time zone for charts |
| `ANTHROPIC_API_KEY` | (none) | Turns on the "Polish with LLM" button for replies |
| `CLAUDE_MODEL` | claude-sonnet-5-5 | Model used for polishing |

---

## Features

**Agent console**
- Dashboard: open, overdue and critical counts, average resolve time, on-time %, customer rating, "handle these first" queue, 14-day trend
- Complaint list with search and filters (status, priority, category, agent, overdue) and CSV export
- Complaint page: complaint text with the important words highlighted, AI reading (topic + confidence, mood meter, priority reasons, key words), top 3 suggested solutions with steps, editable reply, notes, full activity history
- Live AI preview while logging a new complaint
- Knowledge base: search, add, edit, deactivate solutions (admin)
- Analytics: category / mood / priority / status breakdowns, AI accuracy, most-used solutions, agent performance
- Team management (admin)
- Dark mode and light mode, works on phones

**Workflow**
- Auto-assign to the agent with the fewest open complaints
- Deadlines (SLA) by priority: Critical 4h, High 24h, Medium 48h, Low 72h
- SLA monitor runs every minute and escalates overdue complaints
- Auto-escalation for safety risks, legal threats and critical fraud
- Duplicate detection for repeat complaints from the same customer
- Status flow: Open → In progress → Escalated → Resolved → Closed (re-open allowed)

**Customer portal**
- Submit a complaint and get a reference number
- Track status, read the reply, rate the resolution 1–5 stars

**Security**
- Passwords hashed with PBKDF2 + salt, login lockout after 5 wrong tries
- Token sessions that expire after 8 hours, admin-only actions checked on the server
- Rate limits on public forms, request size limit, JSON-only writes (CSRF guard)
- All output escaped (no XSS), strict Content-Security-Policy, safe static file serving
- CSV export blocks spreadsheet formula injection

---

## How the AI works (simple version)

```
complaint text
   │
   ├─ 1. Clean the text      → lowercase, remove filler words, cut endings (refunded → refund)
   ├─ 2. Mood                → word scores (-3..+3), "not" flips, "very" boosts, CAPS and !!! add anger
   ├─ 3. Topic               → Naive Bayes: which category's words does it look most like?
   ├─ 4. Key words           → TF-IDF: words that are common here but rare elsewhere
   ├─ 5. Duplicate check     → same customer + very similar text (cosine similarity ≥ 0.45)
   ├─ 6. Priority            → points: mood, urgent words, safety, legal, fraud, VIP, repeats
   ├─ 7. Best solutions      → score = 55% text match + 25% same topic + 10% helpful rate + 10% solved similar case
   └─ 8. Reply               → tone fits the mood + solution's template + promised time
```

**It learns:**
- Every resolved complaint is added to the topic classifier's training data
- When an agent corrects the category, the classifier retrains straight away
- "Was this suggestion right? Yes / No" changes each solution's helpful rate, which affects future rankings
- Solutions that fixed similar past complaints get a boost

---

## Project structure

```
complaint-resolution-assistant/
├── run.bat / run.sh                 shortcut: start the app
├── run-tests.bat / run-tests.sh     shortcut: run the tests
├── pom.xml                          root project file (lets IntelliJ find the backend)
│
├── backend/                         ── BACKEND (Java) ──
│   ├── run.bat / run.sh             build + start
│   ├── run-tests.bat / run-tests.sh build + test
│   ├── pom.xml                      optional Maven build
│   └── src/
│       ├── main/java/com/cecil/complaints/
│       │   ├── App.java                 starts everything
│       │   ├── ai/                      the AI engine
│       │   │   ├── AiEngine.java            runs the full pipeline
│       │   │   ├── TextProcessor.java       tokenize, stop words, stemming
│       │   │   ├── SentimentAnalyzer.java   mood + emotions
│       │   │   ├── ComplaintClassifier.java Naive Bayes topic classifier
│       │   │   ├── TfIdf.java               TF-IDF vectors + cosine similarity
│       │   │   ├── PriorityEngine.java      explainable priority points
│       │   │   ├── SolutionRecommender.java ranks knowledge-base solutions
│       │   │   ├── ResponseGenerator.java   writes the reply
│       │   │   └── LlmClient.java           optional LLM polish
│       │   ├── model/                   Complaint, Solution, User, enums
│       │   ├── service/                 business rules (complaints, KB, auth, analytics)
│       │   ├── store/                   JSON file storage + demo data
│       │   ├── web/                     HTTP server, routes, security
│       │   └── util/                    JSON parser, password hashing
│       └── test/java/.../AllTests.java  32 tests
│
└── frontend/                        ── FRONTEND (website) ──
    ├── index.html, app.js           agent console
    ├── portal.html, portal.js       customer portal
    ├── app.css                      styles, dark/light mode
    └── favicon.svg                  icon
```

## REST API (main endpoints)

All `/api` calls except login and `/api/public/*` need `Authorization: Bearer <token>`.

| Method | Path | What it does |
|---|---|---|
| POST | `/api/auth/login` | Sign in, returns a token |
| GET | `/api/complaints?status=&priority=&category=&assignedTo=&q=&overdue=` | List / search |
| POST | `/api/complaints` | Create + analyse |
| GET | `/api/complaints/{id}` | Full details with AI results |
| PUT | `/api/complaints/{id}/status` · `/assign` · `/priority` · `/category` | Update |
| POST | `/api/complaints/{id}/apply` | Use a solution (re-drafts reply) |
| POST | `/api/complaints/{id}/feedback` | Mark a suggestion helpful or not |
| POST | `/api/complaints/{id}/respond` | Send reply (optionally resolve) |
| POST | `/api/complaints/{id}/notes` · `/reanalyze` | Add note / re-run AI |
| POST | `/api/ai/analyze` | Preview analysis without saving |
| GET/POST/PUT/DELETE | `/api/solutions` | Knowledge base |
| GET | `/api/analytics` | Dashboard numbers |
| GET | `/api/export/complaints.csv` | CSV export |
| POST | `/api/public/complaints` · `/track` · `/rate` | Customer portal |

---

Made by Cecil Baraik — B.Sc. IT, Amity University Jharkhand.
