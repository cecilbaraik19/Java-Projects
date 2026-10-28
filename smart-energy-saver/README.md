# Smart Energy Saver

Reduce energy consumption at home or in a small office: see where every unit (kWh) goes,
switch appliances on and off from one screen, avoid costly peak hours, and follow savings tips
that show the rupees, units and CO₂ they save every month.

Plain **Java 17** backend (no external libraries) and a plain **HTML / CSS / JavaScript** frontend,
the same setup as the other projects in this folder. **Light mode and dark mode** are both built in.

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/main/java/com/cecil/energy/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/smart-energy-saver.jar` |

Then open **http://localhost:8080** in your browser.

### Demo logins

| Username | Password | Role |
|---|---|---|
| admin | admin123 | Admin (manages the team and everything else) |
| meera | manager123 | Energy manager (appliances, rates, applies tips) |
| rohan, ananya | member123 | Member (switches appliances, logs usage) |

On the first run the app fills itself with a demo home: 7 rooms, 28 appliances and 6 months of daily usage
that follows the seasons (AC in summer, geyser in winter). All names are made up.
To start fresh, stop the app and delete the `data` folder.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` (Mac / Linux). 24 tests: JSON, passwords, login lock-out,
permissions, slab bill maths, peak-hour maths, live on/off measuring, auto-off timer, usage records,
estimates, meter readings, every kind of savings tip, spike detection, planner, energy score, rate checks,
CSV safety, saving to disk, demo data and the HTTP API end to end.

## What it does

| Page | What you can do |
|---|---|
| Dashboard | Today's units, this month vs target, expected bill vs budget, power being used right now and its cost per hour, CO₂. Alerts, a 30-day chart, the **energy score**, use by room, peak / off-peak split, biggest users, top tips, what is on now, recent activity. |
| Live control | Every appliance as a tile with an on/off switch, grouped by room. Shows how long each has been on and the auto-off countdown. "Switch all off" (keeps fridge and Wi-Fi on). |
| Appliances | Table with search, room / type filters and sorting; rooms can be added and renamed. Each appliance has its own page: units and cost per month, 30-day chart, standby waste, tips, records and history. |
| Usage & meter | Daily usage records (live, typed in, estimated) with filters and **Export to CSV**. Main meter readings and a **meter check** that shows how much use is not tracked. |
| Savings tips | All tips with units, ₹ and CO₂ saved per month, cost and payback time. Apply, "not now", or try in the planner. |
| Savings planner | "What if?" Change hours, start time, AC temperature, star rating or cut standby; see the new bill instantly. |
| Reports | Units and bills per month, power by hour of day, by type and room, bill step by step, savings from applied tips. |
| Rates & goals | Slab rates, fixed charge, duty, peak / off-peak hours and %, CO₂ factor, monthly target and budget, and a bill calculator. |
| Team (admin) | Add people, change roles, reset passwords, turn accounts off. |

### Key ideas, in simple words

- **Unit (kWh)** = watts × hours ÷ 1000. A 2000 W geyser for 30 minutes uses 1 unit.
- **Standby power**: some appliances use power even when "off" (set-top box, printer). The app counts it as waste.
- **Slab rates**: the first 100 units are cheapest, the next 100 cost more, and so on. 150 units = 100 × ₹5.50 + 50 × ₹6.50 = ₹875.
- **Time of day (ToD)**: units used 18:00–22:00 (peak) cost 20% more; units at night 22:00–06:00 (off-peak) cost 10% less.
  Washing, water pumping and EV charging can move to the night: same units, smaller bill.
- **Bill** = slab charge + peak extra − off-peak discount + duty (5%) + fixed charge.
- **Forecast** = units used so far this month ÷ days passed × days in the month.
- **Live measuring**: switching an appliance on stores the time; switching it off works out the hours
  (split by day and by peak / off-peak) and adds them to that day's record, like a smart plug.
- **Auto-off**: an appliance can switch itself off after N minutes (good for geysers). The server checks every minute.
- **Estimates**: days with no record are filled from each appliance's normal hours, so charts have no holes.
  A real record always replaces an estimate.
- **Spike**: if the last 7 days average more than 30% over the 4 weeks before, the app warns
  (in the demo the fridge's door seal is "leaking").
- **CO₂** = units × 0.72 kg (India's grid average, changeable). One tree absorbs about 21 kg CO₂ a year.

### Savings tips (all worked out from your own data)

| Tip | Rule | Saving |
|---|---|---|
| AC temperature | AC set below 24 °C | 6% of AC power for each degree |
| Standby | standby ≥ 2 W and ≥ 2.5 units a month | 90% of standby units (smart plug ~₹700) |
| Upgrade | 1–3 star appliance, ≥ 15 units a month, pays back within 20 years | difference to 5-star |
| LED | bulbs over 12 W, tubes over 30 W | change to 9 W / 20 W LED |
| BLDC fan | fan over 50 W | change to 28 W BLDC fan |
| Peak shift | washing machine, pump, geyser, iron, EV charger running in peak hours | rupees only (same units) |
| Geyser timer | geyser on more than 45 minutes a day | auto-off after 30 minutes |
| Spike | use jumped over 30% | the extra units |

Rupee savings use the real slab bill (before vs after), so cutting units also moves the home into cheaper slabs.
**Payback** = cost ÷ rupees saved per month.

### Energy score (0–100)

| Part | Points | Full points when |
|---|---|---|
| Monthly target | 30 | expected units are at least 10% under target |
| Standby waste | 15 | standby is under 3% of units |
| Peak-hour use | 15 | under 20% of units are in peak hours |
| Efficient appliances | 15 | average star rating (by units) is 4.5 or more |
| AC temperature | 10 | every AC is set to 24 °C or warmer |
| Trend | 15 | last 30 days used at least 5% less than the 30 days before |

80+ Excellent, 60–79 Good, 40–59 Fair, below 40 Needs work. The dashboard shows every part and why.

### Who can do what

| | Member | Energy manager | Admin |
|---|---|---|---|
| See everything, switch appliances on / off, "all off" | ✔ | ✔ | ✔ |
| Add usage records and meter readings, edit own records | ✔ | ✔ | ✔ |
| Add / edit / delete appliances and rooms, edit any record | | ✔ | ✔ |
| Apply or hide tips, change rates and goals | | ✔ | ✔ |
| Manage the team | | | ✔ |

## Light and dark mode

The app follows the computer's setting (light or dark) the first time.
The **Dark mode / Light mode** button in the top bar (and on the sign-in page) switches it, and the browser remembers the choice.
All colours are CSS variables, so both modes use the same layout.

## Folder layout

```
smart-energy-saver/
├── backend/                         Java backend (also serves the website)
│   ├── pom.xml
│   ├── run.bat / run.sh             build + start
│   ├── run-tests.bat / run-tests.sh
│   └── src/
│       ├── main/java/com/cecil/energy/
│       │   ├── App.java             starts everything, 1-minute timer
│       │   ├── model/               User, Room, Appliance, UsageLog, MeterReading, Settings, TipRecord, Event, Enums
│       │   ├── store/               DataStore (JSON files), SeedData (demo home)
│       │   ├── service/             Auth, Appliance (live switch), Usage, Tip, Settings, Analytics
│       │   ├── insights/            EnergyMath, BillCalculator, Insights (tips, score, alerts)
│       │   ├── web/                 ApiServer (routes), Http (helpers)
│       │   └── util/                Json, Passwords, Csv, Num
│       └── test/java/com/cecil/energy/AllTests.java
├── frontend/                        index.html, app.css, app.js, theme.js, favicon.svg
├── data/                            created on first run (users, rooms, appliances, usage, meter, tips, events, settings)
├── pom.xml                          root Maven file for IntelliJ
├── run.bat / run.sh                 shortcuts to backend/run.*
└── run-tests.bat / run-tests.sh
```

## Settings (optional)

Set these as environment variables before starting:

- `PORT` – port number (default `8080`)
- `HOST` – `127.0.0.1` by default (only your computer). Use `0.0.0.0` to open it to your local network.
- `DATA_DIR` – where to keep the data files (default `data/` in the project folder)

The default electricity rates are sample values. Change them on the **Rates & goals** page to match your own bill.

## Security

- Passwords are stored as PBKDF2-SHA256 hashes with a random salt, never as plain text.
- 5 wrong passwords lock that username for 5 minutes.
- Sessions use random 256-bit tokens that expire after 12 hours of no use.
- Every API call checks the role (member / manager / admin) on the server, not just in the browser.
- All user text is escaped before it is shown, and a strict Content-Security-Policy blocks inline scripts and styles.
- Cross-site POST requests are rejected; static files cannot be read outside the `frontend` folder.
- Request bodies are limited to 1 MB; every number and date from a form is range-checked.
- CSV export blocks spreadsheet formula injection (`=`, `+`, `-`, `@`).
- Data files are written to a temp file first and then moved, so a crash never leaves a half-written file.

## API (for reference)

All API calls except login need the header `Authorization: Bearer <token>`.

| Method | Path | What |
|---|---|---|
| POST | /api/auth/login | `{username, password}` → `{token, user}` |
| POST | /api/auth/logout, /api/auth/password | sign out, change password |
| GET | /api/auth/me, /api/meta | me; lists (types, roles, rooms) |
| GET/POST/PUT | /api/users, /api/users/{username} | team (admin) |
| GET/POST/PUT/DELETE | /api/rooms, /api/rooms/{id} | rooms |
| GET/POST | /api/appliances?room=&type=&q= | list / add |
| GET/PUT/DELETE | /api/appliances/{id} | one appliance with stats, tips, records, history |
| POST | /api/appliances/{id}/power | `{on: true/false}` |
| POST | /api/appliances/all-off | `{keepAlwaysOn}` |
| GET/POST/PUT/DELETE | /api/usage, /api/usage/{id} | usage records (filters: appliance, room, from, to, source, limit) |
| GET | /api/usage/export.csv | CSV download (same filters) |
| POST | /api/usage/autofill | `{from, to}` fill missing days with estimates |
| GET/POST/DELETE | /api/meter, /api/meter/{id} | meter readings |
| GET | /api/tips | open tips, done tips, totals |
| POST | /api/tips/{key}/apply, /dismiss, /restore | act on a tip |
| POST | /api/planner | `{changes:[{applianceId, hoursPerDay, startHour, setTemp, starRating, standbyOff}]}` |
| GET | /api/dashboard | dashboard numbers |
| GET | /api/reports?months=6 | monthly, hourly, by type / room, savings |
| GET | /api/events?appliance=&limit= | activity history |
| GET/PUT | /api/settings | rates and goals |
| GET | /api/settings/bill?units=&peak= | bill calculator |
