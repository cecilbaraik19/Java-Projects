# Smart Learning Difficulty Detector

Find out **which topics each student finds difficult** — and why — from the way they answer quizzes.
Every answer is treated as a "survey reading": was it right, how long did it take compared with the expected time,
was a hint opened, and how sure did the student say they were. The detector turns these readings into a
**Difficulty Index (0–100)** for every student and every topic, explains the reasons in plain words, finds the
**root cause** when a prerequisite topic is weak, and builds an ordered **study route**. Teachers see a class
**heat map**, the questions that trip students up, and can make **support plans** whose effect is tracked.

Plain **Java 17** backend (no external libraries) and a plain **HTML / CSS / JavaScript** frontend,
the same setup as the other projects in this folder. **Light mode and dark mode** are both built in.
The look is a new **"survey map"** theme that matches the topic: parchment paper with contour lines, a map-key
sidebar, grid-reference navigation (A1, A2 …), and **height colours** — green valleys are easy topics, yellow
foothills moderate, orange highlands difficult and dark-red summits very difficult. Each subject is drawn as a
**terrain map**: every topic is a hill whose number of contour rings shows its difficulty, and dashed "trails"
link a prerequisite to the topic that needs it (red when the prerequisite is the root cause). Dark mode is a "night map".

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/main/java/com/cecil/sld/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/learning-difficulty-detector.jar` |

Then open **http://localhost:8080** in your browser. (Set `PORT` to use another port.)

### Demo logins

| Username | Password | Role | What they can do |
|---|---|---|---|
| admin | admin123 | Admin | Everything, plus logins, detector settings (weights and limits) and history |
| neha, arvind | teacher123 | Teacher | Class overview, heat map, topic reports, student maps, support plans, question bank |
| riya | student123 | Student | Strong student with one weak spot (Subnetting) |
| karan | student123 | Student | Struggling student with weak prerequisites |
| any other student (first name, e.g. `nisha`, `aditya`) | student123 | Student | Own map, tests, practice, study route, "how it feels" ratings |

On the first run the app fills itself with demo data: 3 B.Sc. IT subjects (Operating Systems CSIT135, Data Structures,
Computer Networks), 20 topics with prerequisites, 100 questions with hints and explanations, 24 students in two batches
and about ten weeks of quiz history (around 350 attempts). The history comes from a simple "student model", so it has
real patterns: Deadlocks and Subnetting come out hard, a weak Paging topic makes Virtual Memory hard, and so on.
All dates are counted from today, so the demo always looks current. All people are made up.
Signing in for the first time takes a few seconds while passwords are hashed. To start fresh, stop the app and delete the `data` folder.

## How the Difficulty Index is worked out

For one student and one topic, the detector looks at all their answers (newer answers count more — an answer
21 days old counts half) and measures six signals, each from 0 to 1:

| Signal | What it measures | Default weight |
|---|---|---|
| Wrong answers | share of wrong answers (a wrong *easy* question counts more than a wrong *hard* one) | 45 |
| Slow answers | time taken compared with the topic's expected time (twice as long = full signal) | 15 |
| Hints | share of questions where a hint was opened | 10 |
| Wrong while sure | wrong answers given with "I'm sure" — a sign of a **misunderstanding**, not just a gap | 15 |
| Feels hard | the student's own 1–5 rating (only if given) | 10 |
| Getting worse | recent answers worse than earlier ones (needs 6+ answers) | 5 |

`Difficulty Index = 100 × (sum of weight × signal) ÷ (sum of the weights used)`

| Index | Zone on the map | Band |
|---|---|---|
| 0–29 | Valley | Easy |
| 30–49 | Foothills | Moderate |
| 50–69 | Highlands | Difficult |
| 70+ | Summit | Very difficult |
| fewer than 4 answers | — | Not enough data |

The strongest signal becomes the **main cause** (shown as "Many wrong answers", "Wrong while sure", etc.).
If a topic is difficult and one of its **prerequisites** is also difficult, the prerequisite is reported as the
**root cause** and the advice becomes "Revise X first". The **study route** orders difficult topics so a
prerequisite always comes before the topic that needs it. The admin can change every weight and limit.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` (Mac / Linux). **29 tests**, with the clock fixed at 30 Oct 2026:
JSON, passwords, CSV; the engine (easy, very difficult, not enough data, wrong-while-sure, easy-vs-hard wrong answers,
recency, getting worse, self rating, bands from settings); demo data patterns; root causes; study-route order;
tests cover every topic and never send answers; practice focuses on difficult topics; marking, server-side hints,
no double submit, bad answers, quiz expiry; self ratings; prerequisite loops; locked questions; support plans; logins;
sign-in lock-out; settings; saving and loading; and the HTTP API end to end (roles, origin check, security headers).

## What it does

| Page | Who | What you can do |
|---|---|---|
| My map | Student | Readings (attempts, right answers, topics surveyed, peaks), a terrain map per subject, right answers by week, next steps, help from teachers |
| Take a survey | Student | **Full test** (2 questions per topic) or **Practise my peaks** (adaptive: most questions from difficult topics, easier ones first). One question at a time, with "How sure are you?", optional hint and a timer. Results show explanations and how each topic moved |
| Study route | Student | Ordered list of difficult topics with the main cause and what to do |
| How it feels | Student | Rate each topic 1–5 |
| Overview | Teacher / Admin | Class readings, class terrain map, highest peaks, slipping topics, students needing help, support plans due, recent attempts |
| Heat map | Teacher / Admin | Every student × every topic, coloured by index, corner mark for root causes, class averages; export CSV |
| Topics / topic report | Teacher / Admin | Class average per topic; spread of the class, main causes, **questions that trip students up** (right %, time, sure-but-wrong, most-chosen wrong answer) |
| Students / student map | Teacher / Admin | Peaks per student; full map, readings with reasons, study route, support plans, attempts |
| Support plans | Teacher / Admin | Extra class, worksheet, peer tutor …; the index at the start is saved so you can see if it helped |
| Question bank | Teacher / Admin | Topics (unit, order, expected time, prerequisites, study tip) and questions (options, level, hint, explanation) |
| Logins, Detector settings, History | Admin | Users and roles, weights and limits, full activity history |

## Rules the system enforces

- The right answers never leave the server until a quiz is submitted; hints are given by the server so their use is recorded honestly.
- A quiz can be submitted once, only by the student who started it, only with its own questions, and within 3 hours.
- A question that students have already answered cannot change its options or right answer (turn it off and add a new one).
- Records with history are turned off instead of deleted (questions with answers, topics with questions).
- Prerequisites must be in the same subject and can never form a loop.
- One open support plan per student and topic; the due date cannot be in the past.
- There is always at least one active admin; roll numbers and usernames are unique.

## Security

- Passwords stored with PBKDF2-SHA256 and a random salt; 5 wrong tries lock the username for 5 minutes; sessions end after 12 hours idle.
- Session cookie is HttpOnly and SameSite=Strict; requests from other websites are refused (Origin check).
- Every API call checks the role on the **server**. Students can only see their own map and attempts.
- All user text is escaped before it is shown, and a strict Content-Security-Policy blocks inline scripts and styles.
- The server listens on localhost only. CSV exports guard against spreadsheet formula injection.

## Project layout

```
learning-difficulty-detector/
├── backend/
│   ├── pom.xml                       Maven build (optional - run.bat / run.sh use plain javac)
│   └── src/main/java/com/cecil/sld/
│       ├── App.java                  starts everything
│       ├── model/                    Subject, Topic, Question, Attempt, AttemptItem, SelfReport, SupportPlan, User, Settings, Band ...
│       ├── service/                  DifficultyEngine (the detector), QuizService, CatalogService, PlanService,
│       │                             DashboardService, UserService, AuthService
│       ├── store/                    DataStore (JSON files in data/) and SeedData (demo data)
│       ├── util/                     Json, Csv, Passwords, Text
│       └── web/                      ApiServer (routes) and Http (helpers, security headers)
│   └── src/test/java/.../AllTests.java   29 tests, no JUnit needed
├── frontend/                         index.html, app.css, app.js, theme.js, favicon.svg, topo.svg (contour pattern)
├── run.bat / run.sh                  build and start
└── run-tests.bat / run-tests.sh      build and test
```
