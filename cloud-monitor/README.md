# CloudPulse — Cloud Resource Monitoring Dashboard

A real-time dashboard for monitoring cloud infrastructure. It has a **Java Spring Boot** backend and an interactive **React** frontend. Metrics stream live over WebSocket every 3 seconds.

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Spring Web, Spring WebSocket (STOMP), Spring Data JPA, Spring Security + JWT |
| Metrics | OSHI (real CPU/RAM/disk/network of your PC) + a simulator for 10 cloud resources (AWS / Azure / GCP) |
| Database | H2 in-memory (default) — MySQL ready |
| Frontend | React 19 + Vite, Tailwind CSS 4, Recharts, Framer Motion, Lucide icons, Axios, React Router, STOMP.js |

## What you need installed

- **JDK 21** or newer
- **IntelliJ IDEA** (Community is fine). It has Maven built in.
- **Node.js 20+** (check with `node -v`)

## How to run

### 1. Start the backend (port 8080)

1. In IntelliJ, choose **File → Open** and select the `backend` folder. Trust the project and let Maven download its dependencies (this takes 1–3 minutes the first time).
2. Open `src/main/java/com/cecil/cloudmonitor/CloudMonitorApplication.java` and click the green ▶ Run button.
3. Wait for `Started CloudMonitorApplication` in the console.

If you have Maven installed, you can also run it from the terminal: `cd backend` then `mvn spring-boot:run`.

### 2. Start the frontend (port 5173)

```bash
cd frontend
npm install      # first time only
npm run dev
```

Open **http://localhost:5173**.

### Demo logins

| User | Password | Can do |
|---|---|---|
| `admin` | `admin123` | Everything: start/stop/restart resources, create/edit/delete alert rules |
| `viewer` | `viewer123` | Read only |

## Features

- **Login** with JWT tokens and two roles (Admin / Viewer)
- **Live overview** with count-up stat cards, sparklines, a health donut, top CPU consumers, recent alerts, and a per-provider breakdown with cost
- **Live charts** with time ranges (Live, 15m, 1h, 6h, 24h). Click legend items to hide or show series, and hover for tooltips
- **Resources page** with search, filters (provider, type, status), sorting, and a grid/table view toggle
- **Resource detail** with animated gauges, network rates, uptime, cost, history charts and that resource's alerts
- **Start / Stop / Restart** actions with a confirmation dialog (admin only)
- **Alerts** that fire live and appear as pop-up notifications, with acknowledge / acknowledge all
- **Alert rules** you can create, edit, enable/disable and delete (metric, threshold slider, target resource, severity)
- **Dark / light mode**, pause/resume live updates, and a responsive layout that works on mobile
- **Your own PC** shows up as a resource with real metrics read through OSHI

## How it works

```
React (5173) ──REST /api/**──►  Spring Boot (8080)
     ▲                              │  every 3s: MonitoringService.collect()
     └──── WebSocket /ws (STOMP) ◄──┤    ├─ OSHI → this PC's real metrics
           /topic/live   (metrics)  │    ├─ SimulationService → cloud resources
           /topic/alerts (alerts)   │    ├─ AlertService → checks rules, raises alerts
                                    │    └─ saves history every 15s → H2 / MySQL
```

On first start the backend seeds 24 hours of history, so the 6h and 24h charts aren't empty.

## REST API

| Method | Endpoint | Notes |
|---|---|---|
| POST | `/api/auth/login` | `{username, password}` → JWT |
| GET | `/api/auth/me` | current user |
| GET | `/api/overview` | totals, averages, cost |
| GET | `/api/overview/history?range=1h` | `15m`, `1h`, `6h`, `24h` |
| GET | `/api/resources` | all resources with latest metrics |
| GET | `/api/resources/{id}` | one resource |
| GET | `/api/resources/{id}/metrics?range=1h` | history for charts |
| GET | `/api/resources/{id}/alerts` | alerts for a resource |
| POST | `/api/resources/{id}/action` | `{action: START\|STOP\|RESTART}`, admin |
| GET | `/api/alerts`, `/api/alerts/active` | alert history / unacknowledged |
| POST | `/api/alerts/{id}/ack`, `/api/alerts/ack-all` | acknowledge |
| GET/POST/PUT/DELETE | `/api/alerts/rules[/{id}]` | rule management (write = admin) |

All endpoints except login need `Authorization: Bearer <token>`. The WebSocket also checks the token when it connects.

## Switching to MySQL

1. Create a MySQL user (the database itself is created automatically).
2. In `backend/src/main/resources/application.properties`, comment out the H2 lines and uncomment the MySQL lines, then set your password.
3. Restart the backend.

## Database console (H2)

While the backend is running, open http://localhost:8080/h2-console and use JDBC URL `jdbc:h2:mem:cloudmonitor`, user `sa`, and an empty password.

## Project structure

```
cloud-monitor/
├── backend/                      Spring Boot
│   └── src/main/java/com/cecil/cloudmonitor/
│       ├── config/               Security, WebSocket, demo data seeder
│       ├── controller/           REST endpoints
│       ├── dto/                  Request/response objects
│       ├── model/                JPA entities + enums
│       ├── repository/           Spring Data repositories
│       ├── security/             JWT service, filter, WebSocket auth
│       └── service/              Monitoring, simulation, OSHI, alerts
└── frontend/                     React + Vite
    └── src/
        ├── api/                  Axios client (adds JWT)
        ├── components/           Layout, charts, gauge, UI kit, toasts
        ├── context/              Auth, theme, live WebSocket data
        ├── hooks/                useHistory (time-range data)
        ├── pages/                Login, Dashboard, Resources, ResourceDetail, Alerts
        └── utils/                Formatting helpers
```

## Planned next

Cost analytics page, logs viewer, CSV/PDF export, and email alerts.
