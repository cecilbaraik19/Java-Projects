# Edge Computing Monitoring System

Process IoT data **at the edge**: small computers on site (edge nodes) read the sensors, clean and sum up the data,
check rules and act in milliseconds, and send only a fraction of the data to the cloud.
This app watches a fleet of such edge nodes, shows what each one is doing, and lets you compare how much data
each processing mode saves.

Plain **Java 17** backend (no external libraries) and a plain **HTML / CSS / JavaScript** frontend,
the same setup as the other projects in this folder. New "control room" look, with **light and dark mode**.

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no npm, no real hardware.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/main/java/com/cecil/edge/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/edge-monitor.jar` |

Then open **http://localhost:8080** in your browser.

### Demo logins

| Username | Password | Role |
|---|---|---|
| admin | admin123 | Admin (everything, plus team and settings) |
| priya, kabir | engineer123 | Edge engineer (nodes, sensors, rules, commands, fault lab) |
| arjun | viewer123 | Viewer (looks, acknowledges alerts) |

On the first run the app creates a demo setup: 4 sites, 8 edge nodes, 30 sensors, 10 rules,
14 days of history and some past alerts. All names are made up. A built-in **simulator** plays the part of the
edge nodes and their sensors, so everything moves on its own (a new reading every 5 seconds).
To start fresh, stop the app and delete the `data` folder.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` (Mac / Linux). 27 tests: JSON, passwords, CSV safety, all four
processing modes, rolling z-score, invalid data, every rule type, alert de-duplication, store and forward,
offline detection, mode comparison, rule dry run, sign-in lock-out, permissions, input checks, the ingest API,
saving to disk, demo data, simulator faults and the HTTP API end to end.

## What it does

| Page | What you can do |
|---|---|
| Overview | Live control room: nodes online, readings in vs messages out per minute, data cut today, edge vs cloud decision time, active alerts. Traffic chart, edge pipeline funnel, site map with every node and sensor, data saved per node, activity. |
| Edge nodes | Cards with CPU, memory, board temperature, buffer, mode and data cut. Sites list. Add / edit nodes. |
| Node page | Health and traffic charts, sensors, **"What if?" mode comparison** (sliders for dead-band and window), commands (ping, reboot, send buffer), alerts, 7-day table, API key, real-device instructions. |
| Sensors | Every sensor with live value, normal-range bar, 30-minute trend and state. |
| Sensor page | **At the edge, last 15 minutes**: every reading as a dot - filled = sent to the cloud, hollow = kept at the edge, red = dropped - plus the window summaries. Per-minute (6 h) and per-hour (7 days) charts, rules, alert history. |
| Alerts | Active / open / acknowledged / resolved, filters, acknowledge, resolve with a note, edge vs cloud time, **Export to CSV**. |
| Pipeline & modes | The six steps inside every node with today's numbers, why deciding at the edge is faster, the four modes, and the comparison tool. |
| Rules | Add / edit / switch rules on or off, **Test** a rule on the last hour of data before saving. Built-in health checks listed. |
| Fault lab | 8 one-click scenarios and custom faults: spike, drift, stuck sensor, noise, garbage data, sensor silent, internet down, node power loss, CPU overload. Pause / start the simulator. |
| Reports | 7 or 14 days: raw vs sent data per day, money saved, uptime, alerts per day, MTTA / MTTR, per-node table, top rules and sensors. |
| Settings (admin) | Heartbeat timeout, CPU / temperature / buffer limits, cloud round trip, message sizes, cost per GB, simulator step. |
| Team (admin) | Add people, change roles, reset passwords, turn accounts off. |

## Key ideas, in simple words

- **Edge computing**: do the work close to where the data is made (on site), not in a far-away cloud data centre.
  Faster answers, less data sent, works when the internet is down, raw data stays on site.
- **Edge node / gateway**: a small computer (Raspberry Pi, Jetson, industrial PC) wired to the sensors.
- **Heartbeat**: every few seconds a node says "I am alive" with its CPU, memory, disk and temperature.
  No heartbeat for 30 seconds = **offline**.

### The pipeline inside every node

1. **Ingest** - count each reading (this is what would go to the cloud without edge computing).
2. **Validate** - drop impossible values (outside the sensor's physical limits, e.g. 999 °C).
3. **Analyse** - rolling average and standard deviation, rate of change, minute and hour summaries.
4. **Detect** - check every rule locally and raise an alert at once (no cloud round trip).
5. **Reduce** - the node's **mode** decides what to send.
6. **Forward** - send to the cloud, or keep it in the **store-and-forward buffer** while the link is down.

### The four modes

| Mode | What leaves the node | Typical saving |
|---|---|---|
| RAW | every reading (comparison point) | 0% |
| FILTER (dead-band) | a reading only if it moved more than the dead-band (default 2% of the normal range) since the last one sent | 50-70% |
| AGGREGATE (window) | one summary (min, max, average, count) per time window (default 60 s) | 85-95% |
| SMART | window summaries + any jump bigger than 2 x dead-band + any reading that raised an alert | 60-90% |

**Data cut %** = 100 - (bytes sent ÷ raw bytes × 100). One message is counted as 512 bytes (JSON + MQTT/HTTP headers + TLS);
a summary as 640 bytes. Money uses ₹60 per GB (mobile data + cloud ingestion). Both can be changed in Settings.

### Rules

| Condition | Fires when |
|---|---|
| Goes above / below | the value passes the threshold (optionally for at least N seconds) |
| Rate of change | it changed more than the threshold per minute, compared with the reading about a minute earlier |
| Unusual (z-score) | z = (value - recent average) ÷ recent standard deviation is at least the threshold (last 120 readings) |
| No data | the sensor sent nothing for the threshold in seconds |

One alert per rule and sensor: if it keeps firing, the same alert is updated ("repeated 2x") instead of making new ones.
Built-in health checks: node offline, cloud link down, high CPU, node overheating, buffer almost full, garbage readings.
**Edge vs cloud time**: the node measures how long it took to decide (about 4 ms incl. the local network hop);
the cloud time is the same plus a 180 ms round trip.

## Connect a real device (optional)

Node `pump-gw-02` is a slot for a real device. Turn it on (Edit), click **New API key**, then post readings:

```
curl -X POST http://localhost:8080/api/ingest -H "X-Node-Key: edge_..." -H "Content-Type: application/json" \
  -d '{"health":{"cpu":21,"mem":44,"disk":31,"temp":49},"readings":[{"sensorId":"S-129","value":5.4},{"sensorId":"S-130","value":21.5}]}'
```

The answer says how many readings were accepted, which alerts fired and hands over any waiting commands.
(The edge pipeline for this node runs inside the app, standing in for software on the device.)

## Folder layout

```
edge-monitor/
├── backend/
│   ├── pom.xml
│   ├── run.bat / run.sh                build + start
│   ├── run-tests.bat / run-tests.sh
│   └── src/
│       ├── main/java/com/cecil/edge/
│       │   ├── App.java                starts everything, timer for the simulator
│       │   ├── model/                  User, Site, EdgeNode, Sensor, Rule, Alert, Command, Event, DayStat, HourAgg, Fault, Settings, Enums
│       │   ├── edge/                   EdgeEngine (the pipeline), ModeLogic (4 modes), RollingStats, Ring, runtimes
│       │   ├── sim/                    Simulator (fake nodes and sensors, fault lab)
│       │   ├── store/                  DataStore (JSON files), SeedData (demo data)
│       │   ├── service/                Hub (one lock), Auth, Node, Alert, Analytics, Settings services
│       │   ├── web/                    ApiServer (routes), Http (helpers)
│       │   └── util/                   Json, Passwords, Csv, Num
│       └── test/java/com/cecil/edge/AllTests.java
├── frontend/                           index.html, app.css, app.js, theme.js, favicon.svg
├── data/                               created on first run
├── pom.xml                             root Maven file for IntelliJ
├── run.bat / run.sh                    shortcuts to backend/run.*
└── run-tests.bat / run-tests.sh
```

## Settings (optional)

Environment variables: `PORT` (default `8080`), `HOST` (`127.0.0.1`; use `0.0.0.0` to open it to your network),
`DATA_DIR` (default `data/` in the project folder).

## Security

- Passwords: PBKDF2-SHA256, random salt, 120,000 rounds. 5 wrong passwords lock the username for 5 minutes.
- Sessions: random 256-bit tokens, end after 12 hours without use. Node API keys are stored only as SHA-256 hashes and shown once.
- Every API call checks the role on the server. Cross-site writes are blocked (Origin check).
- Strict Content-Security-Policy (no inline scripts or styles); all text is escaped before it is shown.
- Request bodies limited to 1 MB; every number and text from a form is range-checked; static files cannot be read outside `frontend`.
- CSV export blocks spreadsheet formulas; data files are written to a temp file first, then moved into place.

## API (for reference)

All calls except login and ingest need `Authorization: Bearer <token>`.

| Method | Path | What |
|---|---|---|
| POST | /api/auth/login, /logout, /password | sign in / out, change password |
| GET | /api/auth/me, /api/meta, /api/live, /api/dashboard | me; lists; ticker; overview |
| GET/POST/PUT/DELETE | /api/sites, /api/sites/{id} | sites |
| GET/POST | /api/nodes?site=&status=&q= | list / add (answer has the API key once) |
| GET/PUT/DELETE | /api/nodes/{id} | node page data / edit / delete |
| POST | /api/nodes/{id}/commands, /api/nodes/{id}/key | queue PING, REBOOT, SET_MODE, FLUSH_BUFFER; new API key |
| GET | /api/nodes/{id}/compare?deadband=&window= | replay the last hour through all 4 modes |
| GET/POST/PUT/DELETE | /api/sensors, /api/sensors/{id} | sensors and sensor page data |
| GET/POST/PUT/DELETE | /api/rules, /api/rules/{id}; POST /api/rules/test | rules; dry run |
| GET | /api/alerts?status=&severity=&node=&q=, /api/alerts/{id}, /api/alerts/export.csv | alerts |
| POST | /api/alerts/{id}/ack, /api/alerts/{id}/resolve, /api/alerts/ack-all | act on alerts |
| GET/POST/DELETE | /api/faults, /api/faults/{id}; POST /api/simulator | fault lab |
| GET | /api/reports?days=7, /api/events, /api/commands | reports, activity, commands |
| GET/PUT | /api/settings; GET/POST/PUT /api/users | settings, team |
| POST | /api/ingest (header X-Node-Key) | readings from a real device |
