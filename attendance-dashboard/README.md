# Employee Attendance Dashboard

Track employee attendance with a Spring Boot REST API and a React dashboard that has **light and dark mode**.

## Features

- **Dashboard** – live KPIs (checked in, late, on leave, absent, attendance rate), a stacked daily-status chart (7/14/30 days), department attendance rates, recent check-ins and who hasn't arrived yet. Auto-refreshes every 30 seconds.
- **Attendance** – one-click check-in / check-out for today; pick any past date to correct a record, mark leave or absence, or add a note.
- **Employees** – add, edit, deactivate or delete employees; view each person's last 30 days.
- **Reports** – per-employee summary for any date range (present, late, half day, leave, absent, hours, %), sortable, filter by department, **export to CSV**.
- **Light / Dark mode** – toggle in the sidebar; follows your OS setting on first visit and remembers your choice.

### Attendance rules (change in `application.properties`)

| Rule | Default |
|---|---|
| Office start | 09:30 |
| Late after | start + 15 min grace (09:45) |
| Half day | worked less than 4.5 h |
| Weekend | Saturday, Sunday (excluded from reports) |

## Tech stack

| Layer | Tech |
|---|---|
| Backend | Java 17+, Spring Boot 3.3, Spring Data JPA, Bean Validation |
| Database | H2 (file-based, `backend/data/`) – MySQL ready |
| Frontend | React 18, Vite 5, React Router, Recharts |

## Project structure

```
attendance-dashboard/
├── backend/                         Spring Boot API (port 8080)
│   └── src/main/java/com/cecil/attendance/
│       ├── model/        Employee, AttendanceRecord, AttendanceStatus
│       ├── repository/   Spring Data JPA repositories
│       ├── service/      EmployeeService, AttendanceService, ReportService
│       ├── controller/   REST controllers
│       ├── dto/          Request/response records
│       ├── config/       Properties, CORS, Clock, demo DataSeeder
│       └── exception/    ApiException + global error handler
└── frontend/                        React app (port 5173)
    └── src/
        ├── pages/        Dashboard, Attendance, Employees, Reports
        ├── components/   StatusBadge, Modal, Toast, Icons, ...
        ├── theme.jsx     Light/dark theme context + chart colors
        └── index.css     Theme tokens (CSS variables) and styles
```

## How to run

You need **JDK 17+**, **Maven** (or IntelliJ IDEA) and **Node.js 18+**.

### 1. Backend

```bash
cd backend
mvn spring-boot:run
```

Or in IntelliJ: open the `backend` folder → run `AttendanceApplication`.

The first run creates 24 demo employees and ~6 weeks of attendance history.
H2 console: http://localhost:8080/h2-console (JDBC URL `jdbc:h2:file:./data/attendance-db`, user `sa`, no password).

Run the tests with `mvn test`.

### 2. Frontend (new terminal)

```bash
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**. Vite forwards `/api` calls to the backend on port 8080.

> To reset the demo data, stop the backend and delete the `backend/data` folder.

## REST API

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/employees?activeOnly=false` | List employees |
| GET | `/api/employees/departments` | Department names |
| POST / PUT / DELETE | `/api/employees[/{id}]` | Create / update / delete employee |
| GET | `/api/employees/{id}/attendance?from=&to=` | One employee's history |
| GET | `/api/attendance?date=YYYY-MM-DD` | Daily board (all active employees) |
| POST | `/api/attendance/check-in` `{employeeId}` | Check in now |
| POST | `/api/attendance/check-out` `{employeeId}` | Check out now |
| PUT | `/api/attendance` | Create/correct a record for any past date |
| DELETE | `/api/attendance/{id}` | Clear a record |
| GET | `/api/reports/stats?date=` | KPI numbers |
| GET | `/api/reports/trend?days=14` | Daily status counts |
| GET | `/api/reports/departments?date=` | Department rates |
| GET | `/api/reports/summary?from=&to=` | Per-employee summary |
| GET | `/api/reports/summary.csv?from=&to=` | Same, as CSV download |

## Switching to MySQL

1. Uncomment the `mysql-connector-j` dependency in `backend/pom.xml`.
2. In `application.properties`, comment out the H2 lines and uncomment the MySQL lines (set your password).
3. Restart – tables are created automatically.
