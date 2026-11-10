# Parisar – Smart Digital Campus Platform

**Integrate AI, IoT, cloud and analytics** into one platform that runs a college campus. Parisar connects the
campus's sensors and card readers (**IoT**), learns from the data (**AI**), keeps the data safe in an object
store with checksums and backups (**cloud**), and turns it into charts that help people decide (**analytics**).

| Part | What it does in Parisar |
|---|---|
| **IoT** | 32 devices: a room sensor in every room (temperature, humidity, CO₂, people), a smart electricity meter in every building, water-tank level sensors and **RFID card readers** at classroom doors and the library gate. Devices send JSON to `POST /iot/ingest` with their own secret key. **Smart switches**: lights and AC can be switched from the website. |
| **Smart attendance** | Classes open by themselves from the timetable. A student is marked present when they **tap their ID card** at the classroom door. Teachers can correct the roll call by hand. The 75% attendance rule is checked for every course. |
| **AI** | 1) **Risk model**: logistic regression (written by hand) predicts which students may fail, explains why and suggests what to do. 2) **Anomaly check**: z-score on live sensor data spots unusual readings. 3) **Energy forecast** for the next 12 hours, tested against yesterday. 4) **Campus assistant** "Ask Parisar": answers from live data plus a TF-IDF search over the FAQ and notice board. |
| **Cloud** | Every 5 minutes the sensor history is uploaded to an object-storage "bucket" (NDJSON files, like Amazon S3). There is a daily full backup, a SHA-256 checksum for every object, an integrity check that finds changed files, and service health. |
| **Analytics** | Attendance by course and week, a heat-map of how full each room is by hour, energy per building per day, AI risk by programme, attendance against marks (with correlation), and alerts by kind. |
| **Alerts** | Too hot, stale air (CO₂), low water, a device gone silent, lights or AC left on in an empty room (optional **auto energy saver**), and AI anomalies. Most alerts close by themselves when the value is normal again. |

The backend is plain **Java 17** with no external libraries: the web server, JSON reader, password hashing, the
machine-learning model, the forecaster, the search engine and the simulator all use the JDK only. The frontend is
plain **HTML / CSS / JavaScript**, the same setup as the other projects in this folder.
**Light mode and dark mode** are both built in.

The look is a new **"campus wayfinding signs"** theme. Every university has blue direction signs, building codes and
colour-coded zones, and the whole interface is built from them:

- the menu is a **building directory board** (blue sign rows with sign pictograms);
- the campus map is a **site-plan sign**: each building is a coloured zone block with its code letter (A, B, L, I, H, C);
- every page starts with a red **"you are here"** dot;
- alerts use real **hazard-sign** shapes: a yellow triangle for a warning, a red octagon for critical, a blue circle for information;
- the text is set in Overpass, a typeface based on US highway signs.

Dark mode is **Night signs** (switch it at the bottom of the menu): deep navy with the signs lit up.

*Parisar* (परिसर) means "campus".

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database server, no npm, no sensors.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/com/cecil/parisar/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/parisar-campus.jar` |

Then open **http://localhost:8080** in your browser. (Set `PORT` to use another port.)

The first start takes about 10 seconds. It builds the demo campus in the `data` folder: the made-up
**Kanchan Institute of Technology, Ranchi** with 6 buildings, 16 rooms, 32 IoT devices, 120 students in B.Sc. IT,
BCA and B.Tech CSE, 8 courses with a weekly timetable, **6 weeks of RFID attendance** and **14 days of sensor
history**. Everything is dated relative to *today*, so the demo always looks fresh. **All people and numbers are
made up.** To start fresh, stop the app and delete the `data` folder.

Real hardware is not needed. A built-in **IoT simulator** plays every device and sends one reading per device every
15 seconds, through exactly the same code path a real device uses. Real devices can send data at the same time.

### Demo logins

| Username | Password | Role | Can do |
|---|---|---|---|
| admin | admin123 | Campus admin | Everything: devices and keys, smart switches, **cloud console**, **IoT simulator** (cause faults), retrain the AI model, user accounts, activity log |
| rkumar | faculty123 | Faculty (IT) | Runs CSIT131 and EVS101: roll call, extra classes, his students and mentees with AI risk, alerts, energy, analytics, notices |
| pmunda | faculty123 | Faculty (IT) | Runs CSIT135 and CSIT141 |
| soraon | faculty123 | Faculty (CSE) | Runs CSE301 and CSE305 |
| aekka | faculty123 | Faculty (BCA) | Runs BCA201 and BCA205 |
| aditi | student123 | Student | Her own attendance, AI report, classes, map, assistant, notices (a student doing well) |
| rohit | student123 | Student | Same, for a student the AI marks as **high risk** |

### Try it

1. Sign in as **admin**. The **overview** shows the campus right now: people on campus, classes running, power,
   devices online, open alerts and high-risk students. Five problems were planted for the demo: the server-room AC
   has failed, the hostel water tank is leaking, a room sensor has gone silent, the seminar hall was left on while
   empty, and the lab complex meter shows a power spike that the AI anomaly check caught.
2. Open the **Live campus map** and pick a building, then a room. You see the live readings, the last 24 hours and
   the **smart switches**. Switch the seminar hall's lights and AC off and its energy-waste alert closes by itself.
3. Open **Classes**. In timetable hours a class is **Live** and students' card taps arrive one by one. Outside
   timetable hours (or on a Sunday), press **Extra class** and then **Simulate card taps**. Watch the roll call fill
   up and the room's people count go up on the map.
4. Open **Students & AI** and click **Rohit Mahato**. You see his attendance in each course ("attend the next 22 in
   a row to reach 75%"), his week-by-week attendance, what the card readers saw, and the **AI risk report**: a 90%
   chance of failing, the reasons, and suggested actions. Open **How the AI model works** for accuracy, precision,
   recall, AUC, the confusion matrix, the learned weights and the training loss curve.
5. **Ask Parisar**: try *"Where can I study now?"*, *"Temperature in the server room"*, *"Any open alerts?"*,
   *"Library timings"*, *"I lost my ID card"*. Sign in as **rohit** and ask *"Am I at risk of failing?"*.
6. **Energy**: the last 36 hours, the forecast for the next 12 with a likely range, cost and CO₂, and a back-test
   on yesterday. Switch on the **auto energy saver**.
7. **Admin → IoT simulator**: cause a fault. For example, choose *Ventilation fails* in a busy room, or *Water tank
   leaks*. Watch the alert appear on the **Alerts** board and then **Fix now**.
8. **Cloud**: press **Sync now**, **Back up now** and **Check integrity**. Then open `data/cloud/telemetry/` on
   your computer, change a character in one file and run **Check integrity** again. The damaged object is reported.

### Send a reading like a real device

The IoT-lab room sensor has a known demo key. With the app running:

```
curl -X POST http://localhost:8080/iot/ingest -H "Content-Type: application/json" -H "X-Device-Id: D-ENV-I02" -H "X-Device-Key: demo-iot-lab-key" -d "{\"temp\":23.5,\"humidity\":50,\"co2\":680,\"occupancy\":8}"
```

The answer lists the accepted and rejected values and any alert raised. Send `"temp": 37` and a **Too hot**
alert appears. In **IoT devices** the admin can make a new key for any device, or add a device. Each key is shown
only once with a ready-made curl command. A card reader message is `{"cardId":"RF-XXXXXX"}` (find a card number on a
student's page) or `{"heartbeat":true}`. Real hardware such as an ESP32 or a Raspberry Pi sends exactly the same
HTTP request.

## How it works

```
 devices (or the simulator) ──HTTP JSON──►  /iot/ingest ─► IotService ─► rules + AI anomaly ─► alerts
                                                │                │
                                                ▼                ▼
                                         AcademicService     DataStore (memory + JSON files in data/)
                                         (RFID → attendance)     │  telemetry: 10-min points (24 h), hourly points (14 days)
                                                                 ▼
 website ◄── /api (JSON) ──  AnalyticsService · RiskModel · EnergyForecaster · Assistant      CloudService ──► data/cloud
                                                                                               (sync every 5 min, daily backup, SHA-256)
```

### Background jobs

| Every | Job |
|---|---|
| 15 seconds | IoT simulator: one reading from every device; card taps for running classes; library visitors |
| 1 minute | Open/close timetable classes; report devices silent for 5 minutes |
| 5 minutes | Cloud sync of the finished 10-minute sensor points |
| 1 hour | Daily cloud backup, if the last one is older than 24 hours |

### Alert rules

| Alert | Raised | Closes by itself |
|---|---|---|
| Too hot | 32 °C (critical at 36 °C) | below 30 °C |
| Stale air | CO₂ 1200 ppm (critical at 2000) | below 1000 ppm |
| Low water | tank 20% (critical at 10%) | above 30% |
| Device silent | nothing for 5 minutes (critical after 30) | when it sends again |
| Energy waste | lights/AC on in an empty room for 30 minutes | when switched off or people come back |
| Unusual reading (AI) | a value more than 4 standard deviations from the last 4 hours (and a big jump in real units) | when readings are normal again |

There is only one open alert per device and rule. A worse value raises the severity of the alert that is already open.

### AI risk model (logistic regression)

* **Inputs** (each standardised as z = (x − mean) ÷ std): attendance % from the RFID taps, internal marks %,
  assignments handed in %, library visits in 30 days from the library gate, and hours per week on the learning portal.
* **Formula:** P(fail) = sigmoid(b + w₁z₁ + … + w₅z₅), where sigmoid(x) = 1 / (1 + e^−x).
* **Learning:** batch gradient descent on the log-loss with L2 regularisation, using last year's made-up batch of
  600 students: 75% to learn from, 25% kept aside to test.
* **Result on the test set:** about 88% accuracy and an AUC of about 0.94. The model finds that attendance matters most.
* **Risk bands:** HIGH from 60%, MEDIUM from 30%.
* **Explanation:** each input's push = weight × z. The biggest pushes towards "fail" are shown as reasons, each
  with a suggested action (for teachers) or a tip (for the student).

### Energy forecast

The forecast for hour h = **profile(h) × level**:

* **profile(h)** is the average power at that hour over the last 14 days, kept separately for weekdays and weekends.
* **level** compares the last 3 hours with their profile, limited to between 0.6 and 1.5.
* The shaded range is ±1.5 × the standard deviation of yesterday's errors.
* **MAPE** (mean absolute percentage error) is measured by forecasting yesterday using only the days before it.
  It is usually 5–10% on the demo data.

### Campus assistant

1. **Live questions**: the assistant spots keywords for free rooms, room climate, attendance, risk, classes, energy,
   water and alerts. It answers from live data, and only with data the person is allowed to see.
2. **Other questions**: a **TF-IDF** search with **cosine similarity** over 14 FAQ entries and the notice board.
   Title words count double. A match below 0.12 means "not found".

Everything runs on this computer. No question is sent to an outside AI service.

### Attendance maths

To reach 75% you need (attended + k) ÷ (held + k) ≥ 0.75, so **k = ⌈(0.75 × held − attended) ÷ 0.25⌉**
classes in a row.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` runs **41 tests** without JUnit (nothing to download). The clock is
frozen at Tuesday 10 Nov 2026, 10:30 IST, so the results never change. The tests cover:

* JSON, passwords, words and statistics;
* sensor buckets, sign-in and account lock;
* device keys, value checks and the rate limit;
* every alert rule and the anomaly maths;
* smart switches, device registration and key rotation;
* timetable classes, RFID taps (present / already / not enrolled / no class) and library visits;
* hand corrections, the 75% maths and who-can-see-whom;
* model accuracy and AUC, learned weights and explanations;
* TF-IDF search and the assistant's roles;
* cloud sync once-only, tamper detection and safe object keys;
* the demo history and the energy forecast error;
* the simulator and fault injection;
* the web server: CSRF header, roles, static files and device ingest.

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8080 | Web port |
| `DATA_DIR` | `data` in this folder | Where the JSON data and the cloud folder are kept |
| `SIMULATOR` | on | `off` = no made-up readings, only real devices |

## Security

* Passwords: PBKDF2-HMAC-SHA256 with 120,000 rounds and a random salt. 5 wrong passwords lock the account for 5
  minutes. There is a limit of 10 sign-in attempts per minute per computer, and the same message for "no such user"
  and "wrong password".
* Sessions: a random token in an **HttpOnly, SameSite=Strict** cookie. Only its SHA-256 hash is kept, and it ends
  after 12 hours without use.
* CSRF: every change needs the `X-Parisar` header, a same-site Origin and JSON.
* Content-Security-Policy, X-Frame-Options and nosniff headers are sent. Pages are built without `innerHTML`, so
  there is no XSS.
* Devices: each device has its own secret key, and only its SHA-256 hash is stored. Keys are compared in constant
  time, and each device may send at most 120 messages a minute. Every value is checked against its physical range.
* Roles are checked on the server: students see only their own data, and teachers see only their courses and mentees.
* Cloud: object keys are only taken from the manifest (no free paths), and SHA-256 integrity checks find changed files.
* The activity log records sign-ins, switches, alerts, roll-call corrections, device keys, faults, backups and more.

## Folders

```
smart-campus/
├─ run.bat / run.sh, run-tests.bat / run-tests.sh   start the app / the tests
├─ pom.xml                                           Maven parent (IntelliJ)
├─ backend/
│  ├─ pom.xml
│  ├─ src/com/cecil/parisar/
│  │  ├─ App.java              starts everything + background jobs
│  │  ├─ model/                User, Building, Room, Device, Point, Alert, Student, Course, ClassSession, Notice ...
│  │  ├─ store/                DataStore (memory + JSON files), SeedData (demo campus)
│  │  ├─ iot/                  IotService (ingest, rules, anomaly, switches), Simulator
│  │  ├─ ai/                   RiskModel, EnergyForecaster, Assistant
│  │  ├─ cloud/                CloudService (bucket, sync, backup, integrity)
│  │  ├─ service/              AuthService, AcademicService (classes, RFID, notices), AnalyticsService
│  │  ├─ web/                  ApiServer (routes, CSRF, /iot/ingest), Http
│  │  └─ util/                 Json, Passwords, Clock, Stats, Text
│  └─ test/com/cecil/parisar/AllTests.java
├─ frontend/                   index.html, app.css (wayfinding theme), app.js, theme.js, favicon.svg
└─ data/                       created on first start (not in git)
   └─ cloud/                   the simulated cloud bucket: telemetry/*.ndjson, backups/*.json, manifest.json
```

## Limits (honest)

* The sensors, the students and last year's batch are **simulated**, so the AI numbers show that the method works,
  not real-world accuracy.
* The "cloud" is a folder on this computer that behaves like object storage. Moving to AWS S3 or Azure Blob would
  only replace the two small upload methods in `CloudService`.
* Devices talk HTTP + JSON. MQTT (common in IoT) would need a broker. HTTPS needs a reverse proxy in front.
* All data is kept in memory and saved as JSON files. That is fine for one campus, but a big university would use a
  database (for example PostgreSQL + TimescaleDB for sensor data).
* The assistant uses keywords and TF-IDF, not a large language model, so it understands simple questions only.
