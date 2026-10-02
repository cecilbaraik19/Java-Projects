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

On first start the app seeds a demo institution and three credentials (one revoked). Look for this in the console:

```
Demo issuer seeded: Demo Institute of Technology (INST-XXXXXXXX)
Demo API key: demo-issuer-key
```

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
2. **Issuer Portal**: sign in with `demo-issuer-key` and issue a credential. It appears as **Pending**.
3. **Dashboard → Mine block**: watch the proof-of-work run (nonce and time) and the status turn **Active**.
4. **Credential page**: show the certificate and QR code, then click **Download signed JSON**.
5. **Verify**: enter the ID and walk through the green checks.
6. **Forged file**: edit the grade in the downloaded JSON and upload it. Verification shows **Invalid** with a hash mismatch.
7. **Attack lab (Chain Explorer)**:
   - *Forge grade* (edits the DB) → verify by ID → **Invalid**, because the data no longer matches the on-chain hash.
   - *Tamper block 2* → *Validate entire chain* → the broken block turns red and the issues are listed.
   - *Restore everything* → valid again.
8. **Revoke** a credential from the Issuer Portal → verify it → **Revoked**, with the reason shown.

---

## REST API

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/institutions` | – | Register an institution. Returns the API key **once** |
| GET | `/api/institutions` | – | List institutions |
| POST | `/api/auth/login` | – | `{apiKey}` → institution |
| POST | `/api/credentials` | `X-API-Key` | Issue a credential |
| POST | `/api/credentials/{id}/revoke` | `X-API-Key` | `{reason}` → revoke |
| GET | `/api/credentials/mine` | `X-API-Key` | Credentials issued by me |
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
| POST | `/api/demo/tamper-credential/{id}` | – | Attack sim: edit the DB |
| POST | `/api/demo/tamper-block/{i}` | – | Attack sim: edit a block in memory |
| POST | `/api/demo/restore` | – | Undo both |

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

## Switching to MySQL

1. In `pom.xml`, uncomment the `mysql-connector-j` dependency.
2. In `application.properties`, comment out the H2 datasource lines and uncomment the MySQL ones.

## Limitations and future work

- **Private keys are stored server-side** so the demo can sign on the institution's behalf. In production, each institution would hold its own key (hardware wallet or HSM) and sign in the browser.
- The chain runs on a single node. Future work: peer-to-peer nodes with longest-chain consensus, or porting the contract to Ethereum or Hyperledger Fabric.
- API-key auth is intentionally simple. Future work: JWT, role-based access, and 2FA for registrars.
- Possible additions: W3C DID support, IPFS for document storage, and batch issuing from CSV.
