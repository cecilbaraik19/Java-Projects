# Sangam – Remote Team Collaboration Platform

**Distributed teamwork.** Sangam (संगम, "where rivers meet") is the collaboration platform of **Lumen Labs**, a made-up
travel-software company with **12 people in 11 cities** – from Sydney to San Francisco. Its main idea:

> **Nobody should have to be awake at the same time to work together** – and when a call is needed, the early or
> late hours should be shared fairly.

| Part | What it does |
|---|---|
| **Task board** | Postcard-style board (To do → Doing → Review → Done) with drag and drop. **WIP limits** per column and **2 tasks in Doing per person**. **Four-eyes review**: someone other than the assignee must finish the review. |
| **Waiting tasks** | A task can wait for other tasks. **Loops are refused** (depth-first search), the board shows a red **HOLD** stamp, and the **critical path** (longest chain of unfinished work) is shown on top. |
| **Safe editing** | Two people edit the same task: different fields are **merged automatically**; only the same field changed differently asks the person to choose (**field-level 3-way merge**). |
| **Chat** | Channels, project channels, private channels, direct messages, threads, reactions, @mentions with autocomplete, unread counts. |
| **Quiet hours** | An @mention of somebody who is asleep or in **focus time** waits until their day starts. The sender sees: *"Lena is outside working hours (02:14 in Berlin) – will see it Mon 09:00 her time."* **Urgent** rings through – max 3 a day. **@here** = only people working now. |
| **Live updates** | New messages, moved cards and saved documents appear by themselves in every open browser – **long polling**, written with the JDK only. |
| **Documents** | Every save is a version. Two people save at the same time → **3-way merge** (LCS line diff, like Git). Same line changed differently → conflict markers to fix. Version history, coloured diff, restore. "Lena is editing too" signal. |
| **Async stand-ups** | Yesterday / today / blockers, once per day in **each person's own date**. The digest shows posted, late (day began 2+ h ago), not started yet, day off – and all blockers. |
| **Follow-the-sun handoff** | At the end of the day you hand the work over. The baton goes to the colleague who is working now with the most hours left – or else the one who starts next. "Got it" confirms it. |
| **World clock** | One row per person: their working hours on a 24-hour UTC strip (day = blue, night = dark with stars). Team overlap, follow-the-sun **coverage**, and a matrix of **weekly overlap hours** for every pair. |
| **Fair call times** | Every half hour gets a cost per person (0 in hours … 5 at night). Score = Σ cost × (1 + pain ÷ 3). People who already took bad times are protected – **the pain rotates**. If even the best time hurts someone, Sangam suggests **going async** instead. |
| **Reports** | Tasks per week, cycle time (median, 85th percentile), WIP, overdue, on hold; mention answer time in **clock hours and in working hours**; handoff pick-up time; stand-up participation; call pain per person. |
| **Roles** | Admin, member, and **guest** (a client: sees only their project, can comment, cannot move tasks). Nobody – not even the admin – can read other people's direct messages. |

The backend is plain **Java 17** with no external libraries (web server, JSON, password hashing, long polling, the
diff/merge and the time-zone maths all use the JDK only). The frontend is plain **HTML / CSS / JavaScript**, the same
setup as the other projects in this folder. **Light and dark mode** are built in.

The look is a new **"airmail & world clock"** theme – work travels between time zones like airmail between cities:

- the app is an **envelope** with the red-and-blue airmail border; the menu is the dark-blue **sorting rack**;
- people are **postage stamps** (perforated edge, initials, a presence dot);
- local times are **postmarks** ("BERLIN 09:14" in a round ink stamp);
- tasks are **postcards** with a priority stamp in the corner; a waiting task gets a red **HOLD** rubber stamp;
- the top bar is a row of **city clocks** (blue underline = working, dark = night);
- fonts are Young Serif (headings), Figtree (text) and IBM Plex Mono (times).

Dark mode is **Night mail** (switch it at the bottom of the menu).

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/com/cecil/sangam/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/sangam.jar` |

Then open **http://localhost:8080**. (Set `PORT` to use another port.)

The first start takes a few seconds. It builds the demo workspace in the `data` folder: 12 people, 3 projects,
about **50 tasks, 260 messages, 85 stand-ups, 30 handoffs and 11 calls**. The two weeks of history are not typed in:
they are **played through the real services** with the clock moved back (people post stand-ups when THEIR day starts,
start and finish tasks – some moves are refused by the WIP and waiting rules – ask questions and get answers when the
other person's day begins, hand over at the end of the day, edit documents (one edit is merged automatically), and
book calls with the fair-time finder). So every number in the reports got there by the normal rules. Everything is
dated relative to *now*. **All people are made up.** To start fresh, stop the app and delete the `data` folder.

### Demo logins

| Username | Password | Who | Good for |
|---|---|---|---|
| asha | member123 | Asha Oraon, engineering lead, Ranchi | Reviews waiting, mentions, handoffs from Mei |
| lena | member123 | Lena Vogel, designer, Berlin | The other side of a live chat or a document conflict |
| diego | member123 | Diego Ramos, product manager, São Paulo | Calls he organised (and the pain he took) |
| kwame | member123 | Kwame Mensah, DevOps, Accra | A person in a third time zone |
| admin | admin123 | Priya Lakra, workspace admin | Admin desk: people, projects, WIP limits, activity log |
| client | guest1234 | Hana Ito, Kestrel Travel (client), San Francisco | Guest view: only the website project |

**Tip:** open two different browsers (or one normal and one private window), sign in as **asha** and **lena**, and open
the same channel or document – you see each other's changes live.

### Try it

1. Sign in as **asha**. The **desk** shows your tasks, reviews waiting for you, mentions (answered or not), the baton
   handed to you, your stand-up and who is around right now – with everybody's local time.
2. **World clock**: see who works when. Change the day: a weekday shows 24 h of follow-the-sun coverage, but **no hour
   when everybody overlaps**. The matrix shows which pairs never meet.
3. Open **#orb**, type `@le` and pick Lena. If it is night in Berlin, Sangam tells you *when* she will see it.
4. **Orbit board**: drag a card to Doing. Try a card with a **HOLD** stamp – it is refused ("waits for ORB-3").
   Try to start a third task for one person – refused (2 per person). Move a task to Review, then try to finish your
   own review – refused (four eyes).
5. Open a task and press **Edit**. In another browser (as lena) change the same task's title and save first. Save yours:
   Sangam keeps your other fields and asks only about the title.
6. **Documents** → *Release 3.2 plan* → **Edit**. As lena, change the same line and save first. Save yours: you get the
   text with `<<<<<<<` markers. Fix it and save. Look at **Versions** – v4 says *"combined with Lena Vogel's changes"*.
7. **Calls**: pick Diego, Jack and Lena (São Paulo, Sydney, Berlin) and **Find fair times**: Sangam says even the best
   time hurts somebody and suggests going async. The **fairness ledger** shows who paid for past calls.
8. **Orbit → Handoff**: write a handoff. The list shows who gets the baton and why ("working now, 6 h left" /
   "starts in 3 h").
9. Sign in as **client**: Hana sees only the website project, can comment, but cannot move tasks.

## How it works

```
 website (HTML/CSS/JS) ──/api JSON──► ApiServer ─► AuthService · PeopleService · WorkService · ChatService
        ▲                                │          DocService · RhythmService · MeetingService · ViewService
        │  long polling                  │                │
        └──────── /api/events ◄── EventBus (numbered events, ring buffer of 2,000)
                                         │
              core: WorkHours (time zones) · DependencyGraph · TextDiff (LCS + 3-way merge)
                    MeetingFinder (fair times) · Mentions
                                         ▼
                         DataStore (memory + JSON files in data/)
```

### Life of a task

```
 TODO ──start──► DOING ──send to review──► REVIEW ──someone else approves──► DONE
   ▲ refused if it waits for     │ refused if the person has 2     │ refused if Review is full
   │ an unfinished task          │ in Doing, or Doing is full      │
   └──────────────────── reopen (people waiting for it are told) ◄─┘
```

### Time zones (WorkHours)

All times are stored in **UTC milliseconds**. `java.time` turns them into each person's local time – including summer
time changes (Berlin is UTC+2 in July and UTC+1 in November) and half-hour zones (India is UTC+05:30).

| Question | How |
|---|---|
| Is Lena working now? | local weekday is a working day AND local time is between her start and end |
| When is she back? | the first working-day start after now (skips weekends) |
| How bad is 07:00 for her? | 0 in hours · 1 up to 1 h outside · 2 up to 2 h · 3 more · 4 day off · 5 night (22:00–07:00) |
| Hours two people overlap per week | both lists of working intervals are merged with **two pointers** |
| Team strip | the UTC day in 96 quarter hours; count who works in each |

### Fair call times

```
 score(time) = Σ over people   cost(person, time) × (1 + pain(person) / 3)
```

The lowest score wins. When a call is booked, each person's cost is **added to their pain**; people who had a good time
lose 0.5 pain. So the person in the "odd" time zone is not always the one at 22:00. Cancelling a future call gives the
points back. If the best time still costs someone 3 or more, Sangam suggests an async update instead.

### Documents: diff and 3-way merge

* **Diff** = Longest Common Subsequence of lines (dynamic programming; common first and last lines are skipped first).
  A test compares it with a slow LCS on 500 random edits.
* **3-way merge**: base = the version both people started from. Lines both kept are fixed points. Between them:
  only I changed it → mine; only they changed it → theirs; both the same → fine; both different → **conflict markers**.
  Neighbouring changes with the same number of lines are compared line by line, so "I edited line 2, you edited line 3"
  is not a conflict.

### Live updates (long polling)

Every change publishes a numbered event. The browser asks `/api/events?since=1234`. If there is something new, it gets it
at once; if not, the request **waits** (up to 20 seconds) until something happens – then the browser asks again. Only
events the person may see are sent (project members, channel members). The visibility check runs outside the event
lock, because the services lock the data store first – taking two locks in opposite orders could freeze both (a
**deadlock**).

| Rule | |
|---|---|
| WIP | 2 tasks in Doing per person; per project: Doing 4–6, Review 2–3 (admin can change) |
| Review | when on, a task must go through Review and someone else must finish it |
| Waiting tasks | at most 8; same project; no loops; a started task cannot get new unfinished blockers |
| Urgent | rings through quiet hours; at most 3 per person per day |
| Stand-up | one per person per local date (posting again updates it); "late" = 2 h after the day began |
| Calls | 15–120 min, on quarter hours, up to 60 days ahead, max 12 people, nobody in two calls at once |
| Messages | edit for 24 hours; delete your own (admin: any) |

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` runs **29 tests** without JUnit. The clock is frozen at Monday 16 Nov 2026,
08:00 UTC. They cover JSON, passwords, working hours in every zone (India +05:30, Berlin summer/winter time, Sydney
tomorrow), call costs, the team strip and pair overlap (against a brute-force check), fair times and pain rotation,
booking/cancelling calls, loops/topological order/critical path, blocked tasks, WIP limits, four-eyes review,
field-level merge, ordering, the line diff (500 random cases), 3-way merge, document saves and conflicts, mentions,
quiet hours, urgent and focus time, @here, threads and unread counts, privacy (guests, direct messages), stand-ups,
handoff choice, long polling, **16 threads at once** (WIP still holds), account lock, the web server (CSRF, cookies,
other websites, live events) and the whole demo workspace (no rule is broken anywhere).

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8080 | Web port |
| `DATA_DIR` | `data` in this folder | Where the JSON data is kept |

## Security and privacy

* Passwords: PBKDF2-HMAC-SHA256, 120,000 rounds, random salt. 5 wrong passwords lock the account for 5 minutes;
  10 sign-in tries per minute per computer; the same message for unknown user and wrong password.
* Sessions: random token in an **HttpOnly, SameSite=Strict** cookie; only its SHA-256 is kept; 12 hours idle.
* CSRF: every change needs the `X-Sangam` header, a same-site Origin and JSON. CSP, X-Frame-Options and nosniff headers.
  Pages are built without `innerHTML` (no XSS).
* **Access**: guests see only their projects and the people in them; private channels and direct messages only for
  their members – not even the admin can read them. Search and live events follow the same rules.
* Every important action goes to the activity log.

## Folders

```
team-collab/
├─ run.bat / run.sh, run-tests.bat / run-tests.sh   start the app / the tests
├─ pom.xml                                           Maven parent (IntelliJ)
├─ backend/
│  ├─ pom.xml
│  ├─ src/com/cecil/sangam/
│  │  ├─ App.java          starts everything + the background job
│  │  ├─ model/            User, Project, Task, Channel, Message, Doc, Standup, Handoff, Meeting, Note, AuditEntry ...
│  │  ├─ core/             WorkHours, DependencyGraph, TextDiff, MeetingFinder, EventBus, Mentions
│  │  ├─ service/          Work, Chat, Doc, Rhythm (stand-ups, handoffs), Meeting, People, View, Auth, Access, Problem
│  │  ├─ store/            DataStore (memory + JSON files), SeedData (demo workspace)
│  │  ├─ web/              ApiServer (routes, CSRF, rate limit, long polling), Http
│  │  └─ util/             Json, Passwords, Clock, Text
│  └─ test/com/cecil/sangam/AllTests.java
├─ frontend/               index.html, app.css (airmail theme), app.js, theme.js, favicon.svg
└─ data/                   created on first start (not in git)
```

## Limits (honest)

* The people, projects and history are **made up**.
* No video or voice: calls are planned here and held in your usual video tool.
* No e-mail or phone push: notifications are inside the app (they wait during quiet hours).
* Long polling is fine for one team; a big company would use WebSockets or Server-Sent Events behind a load balancer,
  and a database instead of JSON files.
* Working hours are one block per day (no split shifts or night shifts) and public holidays are not known.
