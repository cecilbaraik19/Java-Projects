# Sikho-Sikhao – Community Skill Exchange

**Exchange skills locally.** Sikho-Sikhao (सीखो-सिखाओ, "learn and teach") is a **time bank** for one city
(Ranchi in the demo). Neighbours teach each other what they know – guitar, Excel, dhuska, yoga, Sohrai painting,
fixing a fan – and pay with **time credits instead of money**: **one hour of anybody's teaching = one credit**.

| Part | What it does |
|---|---|
| **Cards** | Every member puts "I can teach …" and "I want to learn …" cards on the community board (55 skills in 10 categories). |
| **Matching** | For each card the app finds the best neighbours with a **match score** (0–100): skill fit, nearness, shared free time, trust and level. It explains every score. |
| **Search** | **TF-IDF** search with **Hindi/English synonyms** (*khana* → cooking, *silai* → sewing, *coding* → programming) and **spelling correction** (*gitar* → guitar, Levenshtein distance). |
| **Exchanges** | Request → accept → session → both say "done" → the teacher is paid. Chat with safety checks. Reviews with stars and tags. |
| **Time bank** | 3 welcome credits, **escrow** (credits are held until the session is confirmed), double-entry bookkeeping, balances never below zero, and a **SHA-256 hash-chained ledger** that moderators can check. |
| **Swap circles** | A **graph search** finds direct swaps (*I teach you guitar, you teach me Excel*) and **3-person circles** (A → B → C → A), so people can learn without spending. |
| **Skill circles** | Small group sessions in a public place. 1 credit per seat, held in escrow, paid to the host when it happens. |
| **Locality map** | Where the skills are, drawn as cloth patches. Your travel distance is a stitched circle. Nobody's home is ever shown. |
| **Trust & safety** | Bayesian star rating, reliability (late cancellations and no-shows count), phone numbers hidden until accepted, money talk flagged, block, report, moderator decisions on disputes, pausing accounts. |
| **Community** | Hours per week, skills the neighbourhood needs (wants vs offers), heroes, credit health, localities. |

The backend is plain **Java 17** with no external libraries: the web server, JSON, password hashing, the search
engine, the matcher, the graph search and the ledger all use the JDK only. The frontend is plain
**HTML / CSS / JavaScript**, the same setup as the other projects in this folder. **Light and dark mode** are built in.

The look is a new **"kantha patchwork quilt"** theme. A community is like a kantha quilt: many small pieces of cloth
from many homes, joined with a running stitch.

- every card is a **cloth patch** with a dashed **running stitch** just inside its edge, and a coloured strip for its category;
- time credits are **sewing buttons**; held credits are faded buttons;
- the match score is drawn in an **embroidery hoop** – the thread goes round as far as the score;
- avatars sit in **wooden hoops**; dates are **hanging cloth tags**; free times are a **cross-stitch grid**;
- swaps are **threads with arrows** between people; the timeline of an exchange is a thread with **knots**;
- the menu is an **indigo block-print** strip; fonts are Bitter, Mukta (made in India, has Devanagari) and Kalam (handwriting).

Dark mode is **Lantern night**: deep indigo cloth with light thread (switch it at the bottom of the menu).

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/com/cecil/sikho/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/sikho-sikhao.jar` |

Then open **http://localhost:8080**. (Set `PORT` to use another port.)

The first start takes a few seconds. It builds the demo community in the `data` folder: **46 members in 12
localities of Ranchi**, 128 cards, about **12 weeks of history** (70+ completed sessions with reviews, circles, a
dispute and reports). The history is not typed in – it is **played through the real services** with the clock moved
back, so every credit in the ledger got there by the normal rules. Everything is dated relative to *today*.
**All people are made up.** To start fresh, stop the app and delete the `data` folder.

### Demo logins

| Username | Password | Who | Good for |
|---|---|---|---|
| ananya | member123 | Ananya Singh, Lalpur – teaches guitar | A full home page: a request to answer, a session to confirm, a review to write, a swap and two swap circles |
| ravi | member123 | Ravi Kumar, Bariatu – teaches Excel, wants guitar | The other side of the direct swap with Ananya |
| meera | member123 | Meera Oraon, Kanke – joined 2 days ago | A brand-new member with welcome credits |
| admin | admin123 | Rekha Devi – moderator | Reports, a dispute, members, the ledger check, activity log |

Or press **Join the community** on the sign-in page to make your own account (you get 3 credits).

### Try it

1. Sign in as **ananya**. **My patch** shows what needs you: **Accept** Kabir's guitar request, press **Yes, done**
   for yesterday's lesson with Arjun (Arjun already confirmed → you earn 2 credits), and **Review** Kavita's tiffin class.
2. Look at **Matches for what you want to learn**: open *Why this match?* to see the five parts of the score.
3. **Find skills**: search *khana*, *silai*, *coding* or *gitar*. Change the distance slider and "My level".
4. Open a card: the **free-time grid** shows red where you are both free. Send a request – the credits go to **escrow**.
5. **Swap circles**: see the direct swap with Ravi and the circle Ananya → Imran → Priya → Ananya.
6. **Skill circles**: join *Sohrai wall painting* for 1 credit, then leave again (the credit comes back).
7. **Time wallet**: every credit move with a running balance. Give a credit to a neighbour.
8. Sign in as **admin** → **Reports & disputes**: Vikram asked Pooja for ₹500 "fees" (the message was flagged),
   Aman cancelled late twice, and Rohit says Manoj did not come (Manoj says nobody opened the gate). Decide them.
9. **Ledger check**: all hash links, no negative balances, sum = 0, escrow = what open exchanges need. Now stop the
   app, open `data/ledger.json`, change one `amount`, start again and press **Check again** – the broken line is named.

## How it works

```
 website (HTML/CSS/JS) ──/api JSON──► ApiServer ─► AuthService · ListingService · ExchangeService · CircleService
                                          │          ModerationService · InsightService · Views (what others may see)
                                          │                    │
                                          ▼                    ▼
                         Matcher (TF-IDF, distance,       TimeBank (escrow, hash-chained ledger, verify)
                         free time, trust) · SwapFinder              │
                                          └──────────► DataStore (memory + JSON files in data/)
```

### Life of an exchange

```
 REQUESTED ──accept──► ACCEPTED ──both "done"──► COMPLETED   (escrow pays the teacher)
     │ decline/cancel/expire  │ cancel   │ "it did not happen"
     ▼                        ▼          ▼
 DECLINED / CANCELLED / EXPIRED     DISPUTED ──moderator──► COMPLETED (teacher) / CANCELLED (refund) / split
 (credits go back to the learner)
```

| Rule | |
|---|---|
| A session | 1–3 hours, at least 1 hour and at most 30 days ahead |
| Learner asks (on a "teach" card) | credits go to escrow **at once** |
| Teacher offers (on a "want" card) | credits go to escrow **when the learner accepts** |
| Nobody answers before the time | **expired**, credits back |
| One person says done, 3 days pass | **completed** automatically |
| Nobody says anything for 7 days after | **not confirmed**, credits back |
| Cancel an accepted session < 24 h before | counts as a **late cancellation** |
| Background job | every minute |

### Match score

`score = 40% skill fit + 25% nearness + 15% shared free time + 15% trust + 5% level fit`

* **Skill fit**: 1.0 same skill · 0.4 same category · else the TF-IDF cosine of the search words (× 1.6, max 1).
* **Nearness**: `e^(−km / 3)` – 1.0 next door, 0.72 at 1 km, 0.37 at 3 km. Online cards get 0.7.
  In-person cards beyond your travel limit are left out.
* **Free time**: shared slots ÷ the smaller set of slots (21 slots: 7 days × morning/afternoon/evening).
* **Trust**: 60% rating + 40% reliability.
* **Level**: a teacher above the learner's level fits best.

### Trust

* **Rating** (Bayesian average): `(C × m + sum of stars) / (C + reviews)`, with m = community average and C = 3.
  So one 5★ review does not beat fifty 4.8★ reviews.
* **Reliability**: `(completed + 2) / (completed + let-downs + 3)` – a new member starts at 67%.
  Let-downs = late cancellations + disputes decided against you.

### Search

TF-IDF: `weight = (count in card / words in card) × ln(1 + N / cards with the word)`, then cosine similarity.
Before that, synonyms are added (*khana* → cooking, *bagwani* → gardening, *angrezi* → english …) and unknown words
of 5+ letters are corrected to the nearest known word with **Levenshtein distance** ≤ 1 (≤ 2 for 8+ letters).

### Swap finder

A directed graph: an arrow **T → L** when T offers a skill that L wants and they live close enough (within both
travel limits, or online). A **2-cycle** is a direct swap; a **3-cycle** is a swap circle. Each cycle is listed once
by starting from its smallest member id.

### Time bank (ledger)

Accounts are members plus **BANK** (gives welcome credits) and **ESCROW**. Every move is one line: from, to, amount,
kind (WELCOME, HOLD, RELEASE, REFUND, GIFT, ADJUST), the exchange or circle, a note, the previous line's hash and its
own **SHA-256 hash**. The check proves: the chain is unbroken, numbers have no gaps, no member ever went below zero,
**all balances add up to 0** (double entry) and escrow holds exactly what the open exchanges and circles need.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` runs **29 tests** without JUnit. The clock is frozen at Friday
13 Nov 2026, 10:30 IST, so results never change. They cover JSON, passwords, Levenshtein, synonyms and spelling,
distances, welcome credits and no-negative rule, ledger tampering, gift limit, escrow on request, decline, both-done
payment, 3-day auto-complete, expiry and not-confirmed, late cancellation, teacher offers, request rules, safety
(hidden phone, money), reviews, disputes (split and fault), circles (join/leave/complete/auto-cancel), matcher ranking
and filters, swap pairs and circles, Bayesian trust, joining and account lock, suspension clean-up, private data in
views, the whole demo community (its ledger must verify) and the web server (CSRF, cookies, roles, static files).

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8080 | Web port |
| `DATA_DIR` | `data` in this folder | Where the JSON data is kept |

## Security and privacy

* Passwords: PBKDF2-HMAC-SHA256, 120,000 rounds, random salt. 5 wrong passwords lock the account for 5 minutes;
  10 sign-ins per minute and 5 new accounts per hour per computer; the same message for unknown user and wrong password.
* Sessions: random token in an **HttpOnly, SameSite=Strict** cookie; only its SHA-256 is kept; 12 hours idle.
* CSRF: every change needs the `X-Sikho` header, a same-site Origin and JSON. CSP, X-Frame-Options and nosniff headers.
  Pages are built without `innerHTML` (no XSS).
* **Privacy**: others see only your locality and a distance **rounded up to 0.5 km** – never your home position.
  Your phone number is shown only to a partner **after** an exchange is accepted; numbers and e-mails typed into chat
  before that are hidden.
* **Money**: messages about money (₹, rupees, UPI, fees …) get a warning and are counted for moderators.
* Block (they cannot see your cards or contact you), report, disputes, warnings, pausing accounts (open exchanges and
  circles are cancelled and credits returned). Every important action goes to the activity log.

## Folders

```
skill-exchange/
├─ run.bat / run.sh, run-tests.bat / run-tests.sh   start the app / the tests
├─ pom.xml                                           Maven parent (IntelliJ)
├─ backend/
│  ├─ pom.xml
│  ├─ src/com/cecil/sikho/
│  │  ├─ App.java          starts everything + the background job
│  │  ├─ model/            User, Locality, Skill, Listing, Exchange, Message, Review, Circle, LedgerEntry, Report, Note ...
│  │  ├─ store/            DataStore (memory + JSON files), SeedData (demo community)
│  │  ├─ ledger/           TimeBank (escrow, hash chain, verify)
│  │  ├─ match/            SearchIndex (TF-IDF, synonyms, Levenshtein), Matcher, SwapFinder, Trust, Geo (haversine)
│  │  ├─ service/          Auth, Listing, Exchange, Circle, Moderation, Insight, Views, Safety
│  │  ├─ web/              ApiServer (routes, CSRF, rate limits), Http
│  │  └─ util/             Json, Passwords, Clock, Text
│  └─ test/com/cecil/sikho/AllTests.java
├─ frontend/               index.html, app.css (kantha theme), app.js, theme.js, favicon.svg
└─ data/                   created on first start (not in git)
```

## Limits (honest)

* The people and the history are **made up**; the locality positions are approximate.
* The map is drawn by the app (no map tiles), so it shows localities, not streets.
* There is no SMS/e-mail: notifications are inside the app.
* Data is kept in memory and saved as JSON files – fine for one neighbourhood; a city-wide version would use a
  database (for example PostgreSQL with PostGIS for distances).
* The search understands words, synonyms and spelling mistakes, but not full sentences like a large language model.
