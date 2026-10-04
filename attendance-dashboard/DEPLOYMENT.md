# Deployment guide

This runs the whole app (MySQL + Spring Boot + React behind nginx) with Docker Compose.
It works the same on your Windows PC (Docker Desktop) and on a Linux server (VPS).

```
browser ──► nginx (frontend container, port 80)
              ├── /          React build (static files)
              └── /api/...   ──► backend container (Spring Boot, port 8080, not exposed)
                                    └──► db container (MySQL 8.4, not exposed)
```

Only port 80 is published. The backend and database are reachable only inside the Docker network.

---

## 1. Run it locally with Docker Desktop (Windows)

1. Install **Docker Desktop** and start it.
2. In PowerShell, from the project folder:

   ```powershell
   cd "D:\Web-Development\Java Projects\attendance-dashboard"
   Copy-Item .env.example .env
   notepad .env
   ```

3. Change every `CHANGE_ME` value. For `JWT_SECRET`, generate one:

   ```powershell
   [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }))
   ```

4. Build and start:

   ```powershell
   docker compose up -d --build
   ```

   The first build takes a few minutes, because it downloads Maven and npm packages.

5. Open **http://localhost** and log in as `admin` with the `ADMIN_PASSWORD` from `.env`.

Useful commands:

| What | Command |
|---|---|
| See status / health | `docker compose ps` |
| Backend logs | `docker compose logs -f backend` |
| Stop (keeps data) | `docker compose down` |
| Stop and **delete all data** | `docker compose down -v` |

> If port 80 is already in use, set `HTTP_PORT=8081` in `.env` and open http://localhost:8081.

---

## 2. Deploy to a Linux server (VPS)

Any small Ubuntu server works (1 vCPU / 2 GB RAM is enough).

```bash
# install Docker (official script)
curl -fsSL https://get.docker.com | sh

# get the code
git clone https://github.com/cecilbaraik19/Web-Development.git
cd "Web-Development/Java Projects/attendance-dashboard"

cp .env.example .env
nano .env            # change every CHANGE_ME, set SEED_DEMO_DATA=false for real use
docker compose up -d --build
```

Open the firewall for web traffic only. Do **not** open 3306 or 8080:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80
sudo ufw allow 443
sudo ufw enable
```

### HTTPS with a domain (recommended)

Login passwords and tokens must not travel over plain HTTP on the internet. The simplest
option is **Caddy**, which gets and renews a free Let's Encrypt certificate automatically.

1. Point your domain's DNS **A record** (for example `attendance.example.com`) to the server IP.
2. In `.env` set:

   ```
   HTTP_PORT=8081
   PUBLIC_URL=https://attendance.example.com
   ```

3. Install Caddy (`sudo apt install caddy`) and put this in `/etc/caddy/Caddyfile`:

   ```
   attendance.example.com {
       reverse_proxy 127.0.0.1:8081
   }
   ```

4. Run `sudo systemctl reload caddy` and `docker compose up -d`.

Caddy adds the client IP to `X-Forwarded-For`. nginx trusts that header only from the host and
Docker network, then passes the real client IP to the backend. That keeps the office-network (IP)
check-in rule working.

> Only expose port 8081 on localhost. Change the ports line in `docker-compose.yml` to
> `"127.0.0.1:${HTTP_PORT:-80}:80"` so nobody can bypass Caddy.

### QR / GPS check-in on phones

Phone browsers only allow GPS on **HTTPS** pages, so the QR check-in with a geofence needs the
HTTPS setup above. Under **Check-in Security**, set the office location and, if you want, the
office network range (CIDR).

---

## 3. Environment variables

| Variable | Required | Meaning |
|---|---|---|
| `DB_PASSWORD` | yes | Password for the `attendance` MySQL user |
| `DB_ROOT_PASSWORD` | yes | MySQL root password (used only inside the db container) |
| `DB_USERNAME` | no | Default `attendance` |
| `JWT_SECRET` | yes | 32+ random characters. The backend **refuses to start** without it |
| `ADMIN_PASSWORD` | no | First admin password. If empty, a random one is printed once in `docker compose logs backend` |
| `SEED_DEMO_DATA` | no | `true` adds 24 demo employees + manager/employee demo logins |
| `HTTP_PORT` | no | Host port for the web app (default 80) |
| `PUBLIC_URL` | no | The address users open; used for CORS |
| `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD` | no | SMTP server for alert emails. Without them, emails are only listed under **Notifications** |
| `MAIL_FROM`, `ADMIN_EMAIL` | no | Sender address and who gets the daily summary |

**Gmail example:** `MAIL_HOST=smtp.gmail.com`, `MAIL_USERNAME=you@gmail.com`, and for
`MAIL_PASSWORD` an [App Password](https://myaccount.google.com/apppasswords), not your normal password.

The `admin` account is created only on the very first start, when there are no users. After
that, `ADMIN_PASSWORD` is ignored. Change the password in the app.

---

## 4. Backups

All data lives in the Docker volume `attendance-dashboard_db-data`.

**Backup** (creates a .sql file):

```bash
docker compose exec db sh -c 'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" attendance_db' > backup-$(date +%F).sql
```

**Restore:**

```bash
docker compose exec -T db sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" attendance_db' < backup-2026-10-04.sql
```

On PowerShell, use `| Out-File -Encoding utf8 backup.sql` for the backup. For restoring, use
`Get-Content backup.sql | docker compose exec -T db ...`.

Daily backup on Linux (crontab -e):

```
0 2 * * * cd "/root/Web-Development/Java Projects/attendance-dashboard" && docker compose exec -T db sh -c 'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" attendance_db' | gzip > /root/backups/attendance-$(date +\%F).sql.gz
```

Copy backups to a different machine as well. A backup on the same server doesn't help if the server fails.

---

## 5. Updating

```bash
git pull
docker compose up -d --build
```

The database volume is kept. New tables and columns are added automatically on startup
(`spring.jpa.hibernate.ddl-auto=update`). Take a backup before big updates.

---

## 6. Running the prod profile without Docker

You can also run the jar directly against your own MySQL:

```powershell
cd backend
mvn package -DskipTests
$env:SPRING_PROFILES_ACTIVE="prod"
$env:DB_URL="jdbc:mysql://localhost:3306/attendance_db?serverTimezone=Asia/Kolkata"
$env:DB_USERNAME="attendance"
$env:DB_PASSWORD="your-db-password"
$env:JWT_SECRET="a-long-random-string-of-at-least-32-characters"
$env:ADMIN_PASSWORD="YourAdmin1"
java -jar target\attendance-dashboard-1.0.0.jar
```

The database `attendance_db` is created automatically if the MySQL user is allowed to create it.
Then build the frontend with `npm run build` and serve `frontend/dist` with nginx, using `frontend/nginx.conf`.

---

## Production checklist

- [ ] Every `CHANGE_ME` in `.env` replaced, and `.env` **not** committed
- [ ] `SEED_DEMO_DATA=false` (otherwise demo logins `manager` / `employee` exist)
- [ ] HTTPS in front (Caddy or similar)
- [ ] Firewall allows only 22, 80, 443
- [ ] Admin password changed after first login
- [ ] Check-in Security configured (geofence / office network) if you use QR check-in
- [ ] Daily backups copied off the server
