# Kaksh – Meeting Room Booking System

**Avoid booking conflicts.** Kaksh (कक्ष, "room") is the meeting-room booking system of **Nimbus Works**, a made-up
software company in Ranchi with **12 meeting rooms on 3 floors** and 30 people. Its one main rule:

> **A room can never have two bookings at the same time** – counting the few minutes each room needs to get ready
> after a meeting (the *buffer*).

| Part | What it does |
|---|---|
| **Conflict check** | Every booking is checked against a fast **interval index** (a `TreeMap` per room) before it is saved. Half-open times, buffer minutes, maintenance closings. |
| **Helpful "no"** | When a time is taken you get **the nearest free times in that room** and **other rooms that fit** – one click to use them. You can also **join the waitlist** for that exact time. |
| **Room fit score** | Rooms are ranked 0–100: size (45), equipment (25), nearness to your floor (20), instant confirmation (10). Every score explains itself. |
| **Repeating meetings** | Daily, weekdays or weekly, up to 26 dates. All dates are checked first: **all or nothing**, or (if you agree) **skip only the taken dates**. |
| **People conflicts** | Warns when you or anyone you invite is already in another meeting then. |
| **Find a time** | Pick colleagues; Kaksh merges everybody's busy times (**sweep line**) and lists times when all of you – and a fitting room – are free. |
| **Check-in & no-shows** | Check in from 10 min before to 15 min after the start. Nobody checked in → the room is **given back automatically**. **End now** gives back the rest of the time; **+15 / +30 min** only if the room is free after. |
| **Waitlist** | First come, first served. When a room frees up (cancel, no-show, early end, decline, move) it is **booked for the first person waiting** automatically. |
| **Approvals** | The boardroom and the training hall need the facility manager's OK. A request **holds the room** while it waits. |
| **Closing a room** | The facility manager closes a room (AC repair, cleaning). Bookings in that time are **moved to the best free room that fits**, or cancelled if none fits – with a preview first. |
| **Safe edits** | Every booking has a version number. Two people editing the same booking cannot overwrite each other (**optimistic locking**). |
| **Fair use** | One person can organise at most **15 hours of meetings a week**. |
| **Reports** | Use per room, no-shows, average group size against seats (with advice like "small groups take a big room"), a weekday × hour **heat map**, meeting lengths, who books most. |
| **Calendar** | Any booking – or all your upcoming ones – as an **.ics file** for Outlook, Google Calendar or your phone. |

The backend is plain **Java 17** with no external libraries (web server, JSON, password hashing, the conflict index
and the slot finder all use the JDK only). The frontend is plain **HTML / CSS / JavaScript**, the same setup as the
other projects in this folder. **Light and dark mode** are built in.

The look is a new **"hotel front desk & key rack"** theme. Meeting rooms are handed out like hotel rooms:

- the home page is a wooden **key cabinet**: a room's **brass key on its hook = free**; a **guest slip on the hook = in use**
  (who has it, until when);
- the menu is the **mahogany cabinet** with a **brass name plate**; rooms are drawn as **brass key fobs** with the room
  number engraved;
- the day board is a **hotel register**: ruled rows, bookings in register red, your own in mahogany, a red "now" line,
  hatched buffer minutes, dashed ghosts for no-shows;
- a booking is a **guest card** with a red top edge; fonts are Gloock (an engraved sign serif) and Instrument Sans.

Dark mode is **Night porter** (switch it at the bottom of the menu).

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/com/cecil/kaksh/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/kaksh.jar` |

Then open **http://localhost:8080**. (Set `PORT` to use another port.)

The first start takes a few seconds. It builds the demo office in the `data` folder: 12 rooms, 30 people and about
**1,000 bookings** – 4 weeks of history and 2 weeks ahead. The history is not typed in: it is **played through the
real services** with the clock moved back (people book the evening before, some bookings clash and are refused,
some people join the waitlist, most check in, some never come, some finish early, the facility manager approves the
boardroom). So every number in the reports got there by the normal rules. Everything is dated relative to *today*.
**All people are made up.** To start fresh, stop the app and delete the `data` folder.

### Demo logins

| Username | Password | Who | Good for |
|---|---|---|---|
| priya | member123 | Priya Sharma, Engineering lead, floor 2 | A meeting starting now (check in), a daily stand-up, waiting for Karo |
| arjun | member123 | Arjun Mehta, Sales | A boardroom request waiting for approval |
| neha | member123 | Neha Tirkey, Design | Holds Karo – cancel it and Priya gets the room |
| admin | admin123 | Sunita Kujur, facility manager | Approvals, closing rooms, room settings, reports, activity log |

### Try it

1. Sign in as **priya**. The **front desk** shows the key rack. If the office is open, her *Sprint review* starts at the
   next quarter hour: press **Check in**. (Wait 15 minutes without checking in, and the room is given back.)
2. **Book a room**: choose Damodar, the next working day, 11:00, 1 hour. It is taken by Arjun's client demo. Kaksh
   shows **free times in Damodar** and **other rooms free at that time**. Click one and book. Or press **Join the waitlist**.
3. Try 11:00 in Damodar **with a 10-minute buffer**: a meeting until 12:00 means the next one can start at 12:15 (quarter hours).
4. Make it **repeat every weekday, 10 dates**: if some dates are taken, you can book only the free ones.
5. **Find a time**: press *Add my team* and *Find free times*.
6. **Day register**: click an empty part of a row to book that room at that time.
7. Sign in as **neha** → **My bookings** → *Wireframe walkthrough* in Karo (next working day, 15:00) → **Cancel**.
   Sign in as **priya** again: **Messages** says Karo is now hers – the waitlist did it.
8. Sign in as **admin** → **Facility desk**: approve or decline Arjun's *Quarterly business review*; **close a room**
   (press *Show what happens* first – you see which bookings move where); look at **How rooms are used**.

## How it works

```
 website (HTML/CSS/JS) ──/api JSON──► ApiServer ─► AuthService · BookingService · ViewService (privacy)
                                                         │              │
                                       RoomCalendar ◄────┤              │
                                  (TreeMap per room =    │        SlotFinder (nearest times, room fit,
                                   the conflict index)   │        find a time, free gaps)
                                                         ▼
                                       DataStore (memory + JSON files in data/)
```

### Life of a booking

```
 PENDING ──approve──► CONFIRMED ──check in──► CHECKED_IN ──end time / "End now"──► COMPLETED
    │ decline / not approved in time   │ cancel     │ nobody checks in by start + 15 min
    ▼                                  ▼            ▼
 DECLINED / EXPIRED                CANCELLED     RELEASED (no-show: the room is given back → waitlist)
```

PENDING, CONFIRMED, CHECKED_IN and COMPLETED **hold the room**. A background job runs every 30 seconds.

### The conflict check

Two meetings in the same room clash when

```
new [s, e)  and  old [s2, e2)  clash  ⇔  s < e2 + buffer  AND  s2 < e + buffer
```

Each room keeps its busy times in a `TreeMap<start, …>` (a red-black tree). To check `[s, e)` we take only the entries
that start before `e + buffer` (`headMap`) and walk backwards; we stop as soon as an entry starts so early that even
the **longest** busy time in that room could not reach `s`. Cost: **O(log n + k)** instead of O(n). A test compares it
with the slow "check everything" way on 3,000 random cases.

**Two people at the same moment:** every change runs inside `synchronized (store)`, so the *check* and the *save*
happen together and nobody can slip in between. A test starts 16 threads that book the same slot at once: exactly one wins.

| Rule | |
|---|---|
| Open | Monday–Saturday, 08:00–20:00 (India time); closed on Sunday |
| Times | start and end on a quarter hour; 15 min to 4 h (facility manager: 10 h); up to 60 days ahead |
| Buffer | 0–15 min per room, kept free after every meeting |
| Check-in | from 10 min before to 15 min after the start; else released |
| Fair use | 15 h of meetings organised per person per week |
| Repeat | daily (Mon–Sat), weekdays (Mon–Fri) or weekly; 2–26 dates |

### Room fit score (0–100)

| Part | Points | |
|---|---|---|
| Size | 45 | best when the group fills 50–100% of the seats; an empty big room scores low |
| Equipment | 25 | everything asked for is there (a room missing it is not offered at all) |
| Nearness | 20 | same floor 20, one floor away 10 |
| Instant | 10 | does not need approval |

### Suggestions when a time is taken

* **Nearest times, same room:** steps of 15 minutes earlier and later on the same day, then the same time on the next
  3 open days. Times when an invited person is busy count as 2 hours further away, so better times come first.
* **Other rooms, same time:** all free rooms that fit, best fit score first.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` runs **29 tests** without JUnit. The clock is frozen at Monday 16 Nov 2026,
08:00 IST. They cover JSON, passwords, the office rules, the conflict index (and 3,000 random cases against a slow check),
repeats, clashes with suggestions, buffers, size and equipment, all-or-nothing series, people conflicts, fair use,
approvals, check-in and no-shows, the waitlist, ending early, extending, optimistic locking, moving, cancelling a
series, closing a room, **16 threads at once**, privacy, find-a-time, free gaps, the .ics file, account lock, the web
server (CSRF, cookies, other websites, roles, path tricks) and the whole demo office (no overlaps allowed).

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8080 | Web port |
| `DATA_DIR` | `data` in this folder | Where the JSON data is kept |

## Security and privacy

* Passwords: PBKDF2-HMAC-SHA256, 120,000 rounds, random salt. 5 wrong passwords lock the account for 5 minutes;
  10 sign-in tries per minute per computer; the same message for unknown user and wrong password.
* Sessions: random token in an **HttpOnly, SameSite=Strict** cookie; only its SHA-256 is kept; 10 hours idle.
* CSRF: every change needs the `X-Kaksh` header, a same-site Origin and JSON. CSP, X-Frame-Options and nosniff headers.
  Pages are built without `innerHTML` (no XSS).
* **Private meetings**: others see "Private meeting" and no names – on the board, in suggestions and in calendar files.
* Every important action goes to the activity log.

## Folders

```
room-booking/
├─ run.bat / run.sh, run-tests.bat / run-tests.sh   start the app / the tests
├─ pom.xml                                           Maven parent (IntelliJ)
├─ backend/
│  ├─ pom.xml
│  ├─ src/com/cecil/kaksh/
│  │  ├─ App.java          starts everything + the background job
│  │  ├─ model/            User, Room, Booking, Status, Series, Block, WaitEntry, Note, AuditEntry, Role
│  │  ├─ core/             RoomCalendar (conflict index), Rules, Recurrence, SlotFinder (suggestions, fit, find a time)
│  │  ├─ service/          BookingService, ViewService (privacy, reports), AuthService, Ics, Format, Problem
│  │  ├─ store/            DataStore (memory + JSON files), SeedData (demo office)
│  │  ├─ web/              ApiServer (routes, CSRF, rate limit), Http
│  │  └─ util/             Json, Passwords, Clock, Text
│  └─ test/com/cecil/kaksh/AllTests.java
├─ frontend/               index.html, app.css (front-desk theme), app.js, theme.js, favicon.svg
└─ data/                   created on first start (not in git)
```

## Limits (honest)

* The people, rooms and history are **made up**.
* There is no e-mail, SMS or Outlook sync: notifications are inside the app; calendars get an .ics file.
* Check-in is a button. A real office would use a tablet outside each room or a QR code on the door.
* Data is kept in memory and saved as JSON files – fine for one office. A bigger company would use a database, where
  the same rule is enforced with a transaction (or a PostgreSQL *exclusion constraint* on `tstzrange`).
