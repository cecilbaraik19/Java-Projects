# CredentialChain — Blockchain-Based Academic Credential System

Universities issue **verifiable credentials** (degrees, diplomas, certificates). Each one is fingerprinted with SHA-256, signed with the institution's ECDSA key and anchored on a custom proof-of-work blockchain. Anyone, such as an employer, can verify a credential in seconds by scanning a QR code, entering the credential ID or uploading the signed JSON file.

**Stack:** Java 17 · Spring Boot 3.3 · Spring Data JPA · H2 (MySQL-ready) · React 18 + Vite

---

## How it works

```
 Institution                       Blockchain                          Employer
 ───────────                       ──────────                          ────────
 1. Fill credential form
 2. hash = SHA-256(credential)
 3. sig  = ECDSA_sign(hash, privKey)
 4. ISSUE tx {credId, hash, sig} ──► pending pool ──► mined block (PoW)
 5. Full record saved off-chain (DB)
                                                         ◄── 6. Verify by ID / QR / JSON file
                                                              • recompute hash from data
                                                              • compare with on-chain hash
                                                              • check signature with issuer's
                                                                ON-CHAIN public key
                                                              • check REVOKE transactions
                                                              • validate entire chain
```

### Key design points (useful for the viva)

| Concept | Where | Why |
|---|---|---|
| **SHA-256 hashing** | `crypto/HashUtil`, `CredentialData.hash()` | Changing one character of a credential changes its fingerprint completely |
| **ECDSA P-256 signatures** | `crypto/KeyUtil` | Proves *which* institution issued it, and that it hasn't been altered |
| **Proof-of-work** | `Block.mine()` | Each block hash must start with N zeros, so rewriting history is expensive |
| **Merkle root** | `MerkleTree` | One hash in the block header commits to every transaction in it |
| **Hash-linked blocks** | `Block.previousHash` | Editing any block breaks every link after it |
| **On-chain vs off-chain** | `Transaction.dataHash` vs `CredentialEntity` | Only hashes go on-chain, so personal data can be corrected or deleted (privacy, GDPR-style) |
| **Issuer key anchoring** | `REGISTER_ISSUER` tx | Public keys live on the chain, so a hacked DB can't swap in a fake key |
| **Revocation** | `REVOKE` tx | Credentials can be withdrawn; history stays auditable |
| **Password hashing** | `auth/PasswordHasher` | PBKDF2-HMAC-SHA256, random salt, 210,000 iterations; plain passwords are never stored |
| **Login tokens (JWT)** | `auth/TokenService` | HS256-signed token with role and expiry; editing it breaks the signature |
| **Role-based access** | `auth/AuthService` | ADMIN registers institutions, ISSUER issues/revokes, anyone can verify |
| **Brute-force protection** | `AuthService.login` | 5 wrong passwords lock the account for 5 minutes; same error for wrong email or password |

### Transaction types

- `REGISTER_ISSUER`: anchors an institution's public key (self-signed).
- `ISSUE`: records a credential's SHA-256 hash, signed by the issuer.
- `REVOKE`: revokes a credential with a reason. Only the original issuer can do this.

---

## Running the project

### Prerequisites

- JDK 17 or newer
- Maven 3.9+ (or just open `backend` in IntelliJ, which has Maven built in)
- Node.js 18+

### 1. Backend (port 8080)

```bash
cd backend
mvn spring-boot:run
```

On first start the app seeds a demo institution, three credentials (one revoked) and two logins:

| Role | Email | Password | Can do |
|---|---|---|---|
| Admin | `admin@credchain.local` | `admin123` | Register institutions |
| College staff | `registrar@demo-institute.edu` | `demo123` | Issue and revoke credentials |

Verifying a credential needs **no login**. Change the passwords in `application.properties` for real use.

- H2 console: http://localhost:8080/h2-console (JDBC URL `jdbc:h2:file:./data/credchain`, user `sa`, no password)
- Data persists in `backend/data/`. Delete that folder to start a fresh chain.

### 2. Frontend (port 5173)

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. API calls are proxied to `:8080`.

### 3. Run the tests

```bash
cd backend
mvn test
```

- `BlockchainCoreTest`: hashing, signatures, mining, Merkle root and tamper detection.
- `CredentialFlowIntegrationTest`: the full REST lifecycle: register → issue → mine → verify → forge → tamper → restore → revoke.

### Single JAR (optional)

```bash
cd frontend && npm run build:jar      # builds React into backend/src/main/resources/static
cd ../backend && mvn package
java -jar target/credential-chain-1.0.0.jar   # UI + API on http://localhost:8080
```

---

## Demo script (5 minutes)

1. **Dashboard**: show the stats, the linked blocks and "Chain integrity verified".
2. **Sign in** as `registrar@demo-institute.edu` / `demo123`, open the **Issuer Portal** and issue a credential. It appears as **Pending**.
3. **Dashboard → Mine block**: watch the proof-of-work run (nonce and time) and the status turn **Active**.
4. **Credential page**: show the certificate and QR code, then click **Download signed JSON**.
5. **Verify**: enter the ID and walk through the green checks.
6. **Forged file**: edit the grade in the downloaded JSON and upload it. Verification shows **Invalid** with a hash mismatch.
7. **Attack lab (Chain Explorer)** — sign in as admin first:
   - *Forge grade* (edits the DB) → verify by ID → **Invalid**, because the data no longer matches the on-chain hash.
   - *Tamper block 2* → *Validate entire chain* → the broken block turns red and the issues are listed.
   - *Restore everything* → valid again.
8. **Revoke** a credential from the Issuer Portal → verify it → **Revoked**, with the reason shown.

---

## REST API

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/login` | – | `{email, password}` → `{token, user}` |
| GET | `/api/auth/me` | Bearer | Current user |
| POST | `/api/auth/change-password` | Bearer | `{currentPassword, newPassword}` |
| POST | `/api/institutions` | Bearer (ADMIN) | Register an institution + its staff login. Returns the API key **once** |
| GET | `/api/institutions` | – | List institutions |
| POST | `/api/credentials` | Bearer (ISSUER) or `X-API-Key` | Issue a credential |
| POST | `/api/credentials/{id}/revoke` | Bearer (ISSUER) or `X-API-Key` | `{reason}` → revoke |
| GET | `/api/credentials/mine` | Bearer (ISSUER) or `X-API-Key` | Credentials issued by me |
| GET | `/api/credentials?studentId=` | – | Student wallet lookup |
| GET | `/api/credentials/{id}` | – | Credential + live status |
| GET | `/api/credentials/{id}/document` | – | Download the signed JSON |
| GET | `/api/verify/{id}` | – | Verify by ID |
| POST | `/api/verify/document` | – | Verify an uploaded JSON document |
| GET | `/api/chain` | – | Full chain |
| GET | `/api/chain/blocks/{i}` | – | One block |
| GET | `/api/chain/pending` | – | Pending pool |
| POST | `/api/chain/mine` | – | Mine pending transactions |
| GET | `/api/chain/validate` | – | Full integrity report |
| GET | `/api/stats` | – | Dashboard numbers |
| POST | `/api/demo/tamper-credential/{id}` | Bearer (ADMIN) | Attack sim: edit the DB |
| POST | `/api/demo/tamper-block/{i}` | Bearer (ADMIN) | Attack sim: edit a block in memory |
| POST | `/api/demo/restore` | Bearer (ADMIN) | Undo both |

---

## Project structure

```
credential-chain/
├── backend/                         Spring Boot
│   └── src/main/java/com/cecil/credchain/
│       ├── blockchain/   Block, Transaction, MerkleTree, Blockchain (ledger service), JPA entities
│       ├── crypto/       HashUtil (SHA-256), KeyUtil (ECDSA)
│       ├── credential/   CredentialData, VerifiableCredential, CredentialService, VerificationService
│       ├── institution/  Institution, InstitutionService
│       ├── web/          REST controllers, error handling, demo/attack endpoints
│       └── config/       CORS, demo data seeder
└── frontend/                        React + Vite
    └── src/
        ├── pages/        Dashboard, IssuerPortal, Verify, StudentWallet, Explorer, Institutions, CredentialPage
        └── components/   CredentialCertificate (with QR), VerificationReport, Hash, StatusBadge, Toast
```

## Start everything with one command (Windows)

From the `credential-chain` folder in PowerShell:

```powershell
.\start-dev.ps1            # backend (H2) + frontend, opens the browser
.\start-dev.ps1 -Mysql     # same, but with the MySQL database
```

Or just **double-click `start-dev.bat`**. Two windows open (backend and frontend); close them to stop.
If Windows blocks the script: `powershell -ExecutionPolicy Bypass -File .\start-dev.ps1`.
The script finds Maven on your PATH or the copy bundled with IntelliJ, so a separate Maven install is not needed.

## Bulk issue from CSV / Excel

Sign in as college staff → **Bulk Issue**. Download the template, fill it in Excel, then
**File → Save As → CSV UTF-8**, and drop the file in. Every row is checked first (missing
fields, future dates, wrong date format, duplicates); only valid rows are issued. Dates can be
`yyyy-mm-dd` or `dd-mm-yyyy`. Download the results CSV to get each student's credential ID
and verify link.

## Open it on your phone (QR codes)

QR codes point to the address the site was opened with, so open the site through the address
your phone can reach, **then** issue/download certificates.

**Same Wi-Fi (quickest):**
```bash
cd frontend
npm run dev -- --host
```
Vite prints a `Network:` address like `http://192.168.1.5:5173`. Open that address (on the PC too)
and allow Node.js through Windows Firewall if asked. Phones on the same Wi-Fi can now scan the QR codes.

**Anywhere on the internet (free, no account) with Cloudflare Tunnel:**
```bash
winget install --id Cloudflare.cloudflared
cloudflared tunnel --url http://localhost:5173
```
It prints a public `https://<random>.trycloudflare.com` address. Open that address, and any phone can
scan the QR codes. The address changes every time you start the tunnel; stop it with `Ctrl + C`.

> ⚠️ Before sharing a public link, sign in and **change the demo passwords** (click your name in the
> sidebar → *Change password*). The demo accounts box on the login page is hidden on public addresses.

## Using MySQL instead of H2

The app runs on H2 by default. To use MySQL:

1. Create the database and a dedicated user (in `mysql` as root):
   ```sql
   CREATE DATABASE IF NOT EXISTS credchain;
   CREATE USER IF NOT EXISTS 'credchain'@'localhost' IDENTIFIED BY 'credchain123';
   GRANT ALL PRIVILEGES ON credchain.* TO 'credchain'@'localhost';
   FLUSH PRIVILEGES;
   ```
2. Run with the `mysql` profile:
   - IntelliJ: **Edit Configurations → Active profiles:** `mysql`
   - Terminal: `mvn spring-boot:run -Dspring-boot.run.profiles=mysql`
3. Settings are in `application-mysql.properties`. Override the login with environment variables `MYSQL_USER` / `MYSQL_PASSWORD`.

The app's MySQL user can only access the `credchain` database (least privilege), not the whole server.

## Limitations and future work

- **Private keys are stored server-side** so the demo can sign on the institution's behalf. In production, each institution would hold its own key (hardware wallet or HSM) and sign in the browser.
- The chain runs on a single node. Future work: peer-to-peer nodes with longest-chain consensus, or porting the contract to Ethereum or Hyperledger Fabric.
- Future work for login: 2FA for registrars, password reset by email, and a fixed `credchain.jwt-secret` so sessions survive restarts.
- Possible additions: W3C DID support, IPFS for document storage, and batch issuing from CSV.
