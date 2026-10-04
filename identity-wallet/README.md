# IdentityWallet — Digital Identity Wallet

A self-sovereign-style **digital identity wallet**. Trusted organisations (government offices, colleges, transport offices, employers) issue **signed digital credentials** into a person's wallet. The holder keeps them encrypted, and shares **only the details a verifier needs**, for example proving "over 18" without revealing their date of birth. Every share is consented, time-limited, view-limited, revocable and logged.

**Stack:** Java 17 · Spring Boot 3.3 · Spring Data JPA · H2 (MySQL-ready) · React 18 + Vite

---

## How it works

```
 Issuer (e.g. Govt.)                  Holder's wallet                         Verifier (bank, cinema…)
 ───────────────────                  ───────────────                         ────────────────────────
 1. Fill credential form
 2. For each claim:
      d = b64(["salt","name",value])
      digest = SHA-256(d)
 3. payload = {id, issuer, holder,
      _sd:[digests]}            ──►  4. Stores payload + signature
 4. sig = ECDSA_sign(payload)          and the disclosures, encrypted
                                       with the holder's AES-256 key
                                    5. Picks claims to share, recipient,
                                       expiry, view limit (consent)
                                    6. Signs a holder proof with own key
                                    7. Gets a link + QR code          ──►  8. Opens link / scans QR / uploads JSON
                                                                             • issuer in trust registry?
                                                                             • issuer signature valid?
                                                                             • each revealed claim hashes into _sd?
                                                                             • holder proof valid for this audience?
                                                                             • not revoked / expired?
                                    9. Sees who opened it, when, from where
```

### Key design points (useful for the viva)

| Concept | Where | Why |
|---|---|---|
| **Selective disclosure (SD-JWT style)** | `crypto/SelectiveDisclosure` | Issuer signs only salted hashes of each claim. Holder reveals any subset; verifier checks each hash is in the signed list. Hidden claims stay hidden, and salts stop guessing attacks |
| **Derived claims** | `CredentialService.addDerivedClaims` | `age_over_18` / `age_over_21` are computed from the date of birth at issuance, so age can be proved without sharing the birth date (data minimisation) |
| **ECDSA P-256 signatures** | `crypto/EcKeys` | Issuer signatures prove origin + integrity. Holder signatures prove consent and stop replay to a different audience |
| **DIDs** | `EcKeys.didFor` | Each user and issuer gets `did:idw:<hash of public key>`, an identifier tied to their key |
| **Trust registry** | `issuers` table, `GET /api/public/issuers` | Verifiers only accept signatures from registered, non-suspended issuers |
| **Envelope encryption** | `crypto/KeyVault` | Master key (file / env var, outside the DB) wraps each user's data key; data keys encrypt vault files and credential claims. A stolen DB is ciphertext |
| **AES-256-GCM + AAD** | `crypto/AesGcm` | Authenticated encryption: tampering is detected. AAD binds each ciphertext to its row/owner, so blobs can't be swapped between users |
| **Share links** | `ShareService` | Only `SHA-256(token)` is stored; the presentation is encrypted with a key derived from the token. A DB leak gives neither working links nor shared data |
| **Revocation** | `CredentialService.revoke` | Issuer can withdraw a credential; every verifier sees it immediately |
| **Consent controls** | `Share` | Recipient, purpose, expiry (15 min to 30 days), view limit, stop-sharing, access log with IP + device |
| **Password hashing** | `auth/PasswordHasher` | PBKDF2-HMAC-SHA256, random salt, 210,000 iterations, constant-time compare |
| **Two-factor sign-in (TOTP)** | `crypto/Totp`, `AuthService` | RFC 6238 codes (Google/Microsoft Authenticator). Password step returns a 5-minute MFA ticket, not a session |
| **JWT sessions** | `auth/TokenService` | HS256-signed, expiring; separate token "kinds" so an MFA ticket can't be used as a session |
| **Brute-force protection** | `AuthService.login`, `RateLimiter` | 5 wrong passwords lock the account for 5 min; per-IP rate limits on login, MFA, sign-up and verification; same error for unknown email or wrong password; dummy hash to equalise timing |
| **Tamper-evident audit log** | `AuditService` | Every event stores the previous event's hash (like a blockchain). Admin can verify the whole chain |
| **Upload safety** | `VaultService.sniff` | File type decided by magic bytes, not the file name; size-limited; filename sanitised; SHA-256 integrity check on download |
| **IDOR protection** | `CredentialService.owned` etc. | Every read checks ownership and returns 404 for other people's IDs |
| **Light / Dark / System theme** | `ThemeToggle.jsx`, `theme.js` | Toggle in the top bar, login and verifier pages; choice remembered per browser, "System" follows Windows |
| **Security headers** | `WebConfig.securityHeaders` | `nosniff`, `X-Frame-Options: DENY`, `no-referrer`, `Cache-Control: no-store` on API |

---

## Running the project

### Prerequisites

- JDK 17 or newer
- Node.js 18+
- Maven is **not** required: `backend\mvnw.cmd` downloads it automatically (IntelliJ's built-in Maven also works)

### Quick start (Windows, one command)

```powershell
cd "D:\Web-Development\Java Projects\identity-wallet"
.\start-dev.ps1
```

This opens two windows (backend on 8080, website on 5173), waits until both are ready and opens the browser.
If PowerShell says scripts are disabled, double-click **`start-dev.cmd`** instead.
Stop everything with `.\stop-dev.ps1` (or `stop-dev.cmd`, or just close the two windows).

### Manual start

### 1. Backend (port 8080)

```bash
cd backend
.\mvnw.cmd spring-boot:run      # or: mvn spring-boot:run
```

On first start it seeds three demo issuers, two holders and sample credentials:

| Role | Email | Password | Can do |
|---|---|---|---|
| Wallet holder | `aarav@wallet.demo` | `User@1234` | 4 credentials (one revoked), a share, vault items |
| Wallet holder | `priya@wallet.demo` | `User@1234` | Empty wallet: issue something to her |
| Issuer (Govt.) | `registrar@identity.demo` | `Issuer@123` | Issue / revoke national IDs etc. |
| Issuer (College) | `admissions@dit.demo` | `Issuer@123` | Issue student IDs |
| Issuer (RTO) | `licensing@rto.demo` | `Issuer@123` | Issue driving licences |
| Admin | `admin@idwallet.local` | `Admin@123` | Register / suspend issuers, users, audit chain |

Verifying needs **no login**. Change the passwords in `application.properties` for real use.

- H2 console: http://localhost:8080/h2-console (JDBC URL `jdbc:h2:file:./data/idwallet`, user `sa`, no password). Look at `credentials.disclosures_enc` and `vault_items.file_enc`: all ciphertext.
- Data lives in `backend/data/` together with **`master.key`**. Delete the whole `data` folder to start fresh. Never delete only `master.key`, or existing data can't be decrypted.

### 2. Frontend (port 5173)

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

### 3. Tests

```bash
cd backend
mvn test
```

- `CryptoTest`: TOTP against the RFC 6238 test vector, AES-GCM tamper detection, ECDSA, PBKDF2, disclosure digests.
- `WalletFlowTest`: full flow over HTTP: issue, selective share, verify, **forged claim is rejected**, view limit, revocation, other users get 404, vault encryption + fake-PDF rejection, MFA login and account lockout, audit chain intact.

### One-jar build (optional)

```bash
cd frontend && npm run build:jar      # puts the React build into backend/src/main/resources/static
cd ../backend && mvn package
java -jar target/identity-wallet-1.0.0.jar     # everything on http://localhost:8080
```

Set `idwallet.public-url=http://localhost:8080` in that case so share links point to the right place.

### MySQL

```sql
CREATE DATABASE idwallet;
```
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```
Edit username/password in `application-mysql.properties` (or set `MYSQL_PASSWORD`).

### Open share links on your phone

The QR code contains `idwallet.public-url`. To scan it with a phone, expose the frontend with a tunnel (`cloudflared tunnel --url http://localhost:5173`) and set `idwallet.public-url` to the tunnel address.

---

## Demo script (5 minutes)

1. Sign in as **registrar@identity.demo**, issue a *National Identity Card* to `priya@wallet.demo` with a date of birth.
2. Sign in as **priya@wallet.demo** → *My credentials* → open it → *Show signed data*: the payload contains only digests.
3. *Share selected details* → "Age check only" → recipient "City Cinema", 1 view → QR appears.
4. Open the link in a private window: **Verified**, only "Age over 18: Yes" is visible, other details "kept private".
5. Open it again: refused (view limit). Back in *Sharing & consent*, the access log shows both attempts.
6. Click *Save signed copy (JSON)* on a fresh share, change one character inside the `credential` text (e.g. the title), upload it on */verify*: **Not verified**, "Issuer signature" fails.
7. As the issuer, revoke the credential: the saved JSON now fails "Credential status".
8. *Security* → set up an authenticator app, sign out and back in with the code.
9. As **admin**, *Audit log* → *Verify hash chain*.

---

## API overview

| Method | Path | Who |
|---|---|---|
| POST | `/api/auth/register`, `/login`, `/mfa` | public |
| GET | `/api/auth/me`, `/activity` | any user |
| POST | `/api/auth/mfa/setup`, `/mfa/confirm`, `/mfa/disable`, `/password` | any user |
| GET | `/api/wallet/dashboard`, `/credentials`, `/credentials/{id}`, `/shares` | holder |
| POST | `/api/wallet/shares`, `/shares/{id}/revoke` | holder |
| DELETE | `/api/wallet/credentials/{id}` | holder |
| GET/POST/DELETE | `/api/vault`, `/api/vault/{id}`, `/api/vault/{id}/file` | holder |
| GET/POST | `/api/issuer/me`, `/templates`, `/credentials`, `/credentials/{id}/revoke` | issuer |
| GET/POST | `/api/admin/stats`, `/issuers`, `/issuers/{id}/trusted`, `/users`, `/users/{id}/active`, `/audit`, `/audit/verify` | admin |
| GET | `/api/public/share/{token}` | anyone (verifier) |
| POST | `/api/public/verify` (presentation JSON) | anyone |
| GET | `/api/public/issuers` (trust registry) | anyone |

---

## Project structure

```
identity-wallet/
├── backend/src/main/java/com/cecil/idwallet/
│   ├── crypto/    AesGcm, EcKeys, KeyVault, SelectiveDisclosure, Totp, CryptoUtil
│   ├── auth/      PasswordHasher, TokenService, AuthUser, RateLimiter
│   ├── domain/    UserAccount, Issuer, Credential, Share, AccessLog, VaultItem, AuditEvent
│   ├── repo/      Spring Data repositories
│   ├── service/   Auth, Credential, Share, Verify, Vault, Admin, Dashboard, Audit
│   ├── web/       REST controllers + error handling
│   └── config/    WebConfig (auth resolver, headers), DataSeeder
└── frontend/src/
    ├── pages/     Login, Dashboard, Wallet, CredentialPage, Shares, Vault, Security, IssuerPortal, Admin, Verify
    └── components/
```

## Limitations / future work

- Keys are custodial (held server-side, encrypted). A production wallet would keep the holder's key on the phone (secure enclave) or use WebAuthn passkeys.
- The format is SD-JWT-*style* JSON, not the exact IETF/W3C wire format; switching to real SD-JWT VC or W3C VC 2.0 would allow interoperability with other wallets.
- Revocation is a live database lookup; a W3C Bitstring Status List would let verifiers check offline.
- TOTP codes can be re-used within their 30-second window; add a "last used step" check, plus recovery codes.
- Rate limits are in memory (per server); use Redis behind a load balancer.
