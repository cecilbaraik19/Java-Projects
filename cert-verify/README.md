# Pramaan – Blockchain Certificate Verification

**Prevents fake certificates.** When a college or skill board issues a certificate, Pramaan seals its
**fingerprint** on a small blockchain run by three nodes. Anyone – an employer, a university, a visa office – can
then check a certificate in seconds, **without signing in**:

1. **Scan the QR code** on the paper (or open the link the student shared),
2. **drop in the certificate file** (`.pramaan.json`) the student received, or
3. **type the details printed on the paper.**

Pramaan answers **GENUINE**, **FORGED** (and names the exact line that was changed – *"Result was changed"*),
**FAKE** (never issued), **REVOKED** (cancelled by the institution, with the reason) or **UNTRUSTED**
(not from an approved institution), and shows all 8 security checks behind the answer.

Plain **Java 17** backend (no external libraries – the blockchain, proof of work, Merkle trees, digital signatures,
QR-code maker, JSON reader, web server and password hashing are all written with the JDK only) and a plain
**HTML / CSS / JavaScript** frontend, the same setup as the other projects in this folder. **Light mode and dark
mode** are both built in.

The look is a new **"security print & UV lamp"** theme, taken from bank notes: the certificate is drawn on
security paper with **guilloché** rosettes and borders, coloured **security fibres**, a **holographic seal**,
**red serial numbers** in two corners, micro-text, and a **metallic security thread** runs across the top of every
page. Verdicts are **rubber stamps**. Dark mode is the **UV lamp** (the button at the top right): the paper turns
dark violet, the fibres glow, and **hidden UV ink** ("PRAMAAN · KVIT") lights up on the certificate – just like
checking a real note under ultraviolet light.

*Pramaan* (प्रमाण) means "proof" – and also "certificate".

## Run it

You only need **Java 17 or newer** (JDK). No internet, no database server, no npm.

| How | What to do |
|---|---|
| Windows | Double-click `run.bat` |
| Mac / Linux | `./run.sh` |
| IntelliJ | Open this folder, wait for Maven to load, open `backend/src/main/java/com/cecil/certify/App.java`, click Run |
| Maven | `cd backend` then `mvn package` and `java -jar target/cert-verify.jar` |

Then open **http://localhost:8080** in your browser. (Set `PORT` to use another port.)

The first start takes a few seconds: it creates the blockchain and the demo data (real keys, real signatures,
real proof of work).

### Demo logins

| Username | Password | Role | Can do |
|---|---|---|---|
| *(nobody)* | – | Public | **Verify** any certificate, look at the **Ledger** |
| priya | student123 | Student | Her B.Tech degree + a Python course certificate: download, print, make share links |
| meera | issuer123 | Staff, Koel Valley Institute of Technology (KVIT) | Issue one or a CSV batch, revoke, see who checked |
| farhan | issuer123 | Staff, Subarnarekha University (SUBU) | Same, for SUBU |
| joseph | issuer123 | Staff, Damodar Skill Development Board (DSB) | Same, for DSB |
| admin | admin123 | Registrar | Approve / suspend institutions, **Nodes & Tamper lab**, **Forgery test**, activity, people |

Also `aman` / `sneha` (student123) and `rpa.office` (issuer123, but that institution is suspended).

The demo network has 4 institutions and about 50 certificates spread over 14 blocks. **All institutions, people and
marks are made up.** To start fresh, stop the app and delete the `data` folder.

### Try it

1. Open **Verify** (no login). Under **Try an example** click **Genuine degree** – stamp says GENUINE, all 8 checks
   are green. Now click **Marks edited** – stamp says **FORGED** and the *Result* line is red.
2. With the genuine file loaded, change **one letter** of the name in the form and press **Check** – FORGED again.
   Add extra spaces or capital letters instead – still GENUINE (same text, written differently).
3. Click **Made-up ID** (FAKE – never issued) and **Revoked** (shows the institution's reason).
4. Press **UV lamp** at the top right and look at a certificate.
5. Sign in as **priya** → open the degree → see the QR code. Make a **share link** for an employer, open it in a
   private window, then **Switch off** the link and try again.
6. Sign in as **meera** → **Issue**. The certificate preview updates as you type. Press **Sign & seal on the
   chain** – it is signed with KVIT's private key and sealed into a new block. **Batch issue** → *Use example* seals
   many certificates in **one** block.
7. Sign in as **admin** → **Nodes & lab** → run **Edit a record** on Node B. Node B turns red, A and C still agree,
   and **Now try the forged certificate** shows it is still caught. Try the other two attacks, then attack **two**
   nodes the same way (the "51% attack") and press **Undo all attacks**.
8. **Forgery test** → **Run the test**: hundreds of forgeries, how many were caught, and false alarms.

## How it works

```
 ISSUE (institution)                                         VERIFY (anyone)
 ───────────────────                                         ───────────────
 7 fields: name, roll no., programme, result,                certificate file / typed details / QR link
           session, date, institution                                        │
     │  + a random 16-byte SALT for each field                               ▼
     ▼                                                       same hashing with the same salts
 field fingerprint = SHA-256("leaf:" name=value|salt)                        │
     ▼                                                                       ▼
 certificate fingerprint = Merkle root of the 7               compare with the 7 fingerprints on the chain
     ▼                                                         → which field changed?
 signed with the institution's PRIVATE key (ECDSA P-256)      check the signature with the PUBLIC key
     ▼                                                         that the registrar put on the chain
 new block: index | time | previous hash | Merkle root of     2 of 3 nodes must hold the same valid chain
 records | nonce  → proof of work (hash starts 0000)          revoked? institution approved at that time?
     ▼
 sent to Node A, B, C – each checks it; kept if 2 of 3 accept
```

| Part | What it does | Where |
|---|---|---|
| Block, proof of work | header = index, time, previous hash, Merkle root, difficulty, nonce; try nonces until SHA-256 starts with `0000` | `chain/Block` |
| Merkle tree | many hashes → one root; "leaf:" / "node:" prefixes; odd node carried up (no duplicate-leaf trick) | `chain/MerkleTree` |
| Signed records | 5 types: genesis, institution approved, institution suspended, certificate issued, certificate revoked | `chain/Transaction`, `chain/TxType` |
| Chain rules | checks every block from the first: hash, link, work, Merkle root, signatures, who may do what | `chain/ChainValidator` |
| 3 nodes + consensus | each node keeps its own file; writing needs 2 of 3 to accept; reading trusts only a 2-of-3 majority; repair copies the majority | `chain/Network`, `chain/Node` |
| Salted field fingerprints | 7 fields, case/space-insensitive, salted so the public chain gives nothing away | `cert/CertFields` |
| Certificate IDs | `PRM-7K2Q-9XWD-M`: random, no confusing letters (I L O U), last letter is a **check letter** that catches every single typo | `cert/CertFields` |
| Issue / batch / revoke / share links | writes to the chain first, then saves the institution's record | `cert/CertificateService` |
| Verifier | the 8 checks and the verdict | `cert/Verifier` |
| Forgery test bench | 12 kinds of attack, hundreds of tries, counts caught and false alarms | `cert/ForgeryBench` |
| Tamper lab | 3 insider attacks on one node's copy | `service/LabService` |
| QR codes | own encoder: byte mode, level M, versions 1–10, Reed-Solomon, 8 masks | `qr/QrCode` |

**What is on the chain, what is not.** The chain is public (see **Ledger**), so it holds **no personal data**:
only certificate IDs, salted fingerprints, institution names and keys, signatures and revocation reasons. Names and
marks stay with the institution (`data/certificates.json`) and with the student (their file). Without the salts,
nobody can guess a value from its fingerprint.

**The 8 checks:** ID well-formed (check letter) · network agrees (2 of 3) · recorded on the chain · approved
institution at the time of issue · institution's digital signature · every detail matches its fingerprint ·
not revoked · sealed under later blocks.

### The Tamper lab (why a blockchain helps)

| Attack on ONE node | What happens |
|---|---|
| Edit a fingerprint inside a block | That node fails its own check: *"Merkle root does not match"*. A and C out-vote it. |
| Edit + redo the proof of work for every later block | Hashes and links look fine again, but the **institution's signature** no longer matches. Only its private key could fix that. |
| Delete a revocation + re-seal | Every record left is genuinely signed, so the copy passes its own check – but it **differs from the other two**, so it is out-voted. |
| The same attack on **two** nodes | The attacker becomes the majority (a **51% attack**) and wins. That is why real networks need many independent nodes. **Undo all attacks** puts things back. |

You can also edit `data/nodes/node-b.json` in Notepad and press **Re-read files from disk**.

## Run the tests

`run-tests.bat` (Windows) or `./run-tests.sh` (Mac / Linux). **53 tests**, clock fixed at 6 Nov 2026: JSON, CSV,
passwords, ECDSA, Merkle proofs, IDs and check letters (all 31 single-character typos caught), field fingerprints,
QR code structure; genesis, proof of work, edited contents / header / link / genesis / weak work all caught,
unknown institution / stolen name / fake registrar refused, double issue refused; genuine / changed marks / one
letter / spaces / fake ID / typo / not-a-file / typed / QR link / share links (views, switch-off, expiry) /
revoke rules / suspended institution / batch all-or-nothing / input checks; all 3 lab attacks, writing with a
broken node, the 51% attack, no-majority; forgery bench 100% with 0 false alarms; account lock and session expiry;
saving and reloading, hand-edited node file caught; and the web server (CSP, path tricks, cookie flags, CSRF,
public verify, roles, other people's certificates, issuing with QR, rate limit).

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8080 | web port |
| `DATA_DIR` | `data` folder of the project | where everything is saved |
| `PUBLIC_URL` | `http://localhost:PORT` | the address inside QR codes. Set it to your computer's Wi-Fi address (e.g. `http://192.168.1.5:8080`) to scan QR codes with a phone on the same Wi-Fi (the server listens only on this computer, so you also need to change `127.0.0.1` in `ApiServer.start` for that) |
| `DIFFICULTY` | 4 | proof-of-work zeros (each extra zero = 16× more work) |

## Security

- **Forgery-proof by maths**: SHA-256 fingerprints of every field, sealed with ECDSA P-256 signatures, inside a
  hash-linked chain with proof of work, copied on 3 nodes, majority rule.
- **No personal data on the chain**; salts stop guessing. A certificate ID alone shows nothing – the QR code
  carries a secret token (HMAC of the ID), share links have their own tokens (only their hashes are stored),
  expire, and can be switched off.
- **Only approved institutions can write**: the registrar's key is in the first block; institutions' keys are added
  by the registrar; the institution name is filled in by the app, never typed.
- Typed checks need the ID plus at least 2 details and are rate-limited (30 checks a minute per computer).
- Passwords hashed with PBKDF2-SHA256 + salt · HttpOnly SameSite=Strict session cookie, 12-hour idle limit · 5 wrong
  passwords lock the account for 5 minutes · same message for unknown user and wrong password · staff only see
  their own institution's certificates, students only their own (others look "not found") · CSRF check (JSON only,
  same origin) · strict Content-Security-Policy (no inline scripts or styles) · all text escaped before it is
  shown · rate limits · server listens only on 127.0.0.1.

## Folders

```
cert-verify/
├── run.bat / run.sh, run-tests.bat / run-tests.sh
├── frontend/                        index.html, app.css, app.js, theme.js, favicon.svg,
│                                    paper.svg, paper-uv.svg, grain.svg (background and stamp texture)
├── backend/src/main/java/com/cecil/certify/
│   ├── App.java                     starts everything
│   ├── chain/                       Block, Transaction, TxType, MerkleTree, ChainValidator, ChainState, Node, Network
│   ├── cert/                        CertFields, CertificateService, Verifier, ForgeryBench
│   ├── qr/                          QrCode
│   ├── service/                     AuthService, LabService
│   ├── store/                       DataStore (JSON files), SeedData (demo)
│   ├── model/                       User, Role, Issuer, CertificateRecord, VerifyLogEntry
│   ├── util/                        Json, Crypto, Passwords, Csv, Clock
│   └── web/                         ApiServer (routes), Http
├── backend/src/test/java/com/cecil/certify/AllTests.java
└── data/                            created on first run:
    ├── network.json, nodes/node-a.json, node-b.json, node-c.json, lab-backup.json
    ├── users.json, issuers.json, certificates.json, verifications.json
    └── keys/                        registrar.key, KVIT.key ... , server.secret
```

## Limits (honest)

- **It is a demo network**: three nodes run inside one program on one computer, so they are not really
  independent. A real system puts each node with a different organisation, on different machines.
- Private keys are kept as files in `data/keys/`. A real system keeps them in a hardware security module; a stolen
  institution key could issue real-looking certificates until the registrar suspends that institution.
- Proof of work here is small (`0000`, about 65,000 tries) so it runs fast on a laptop. The real protection in a
  permissioned network like this is the **signatures + majority**, not the work.
- The chain proves a certificate was **issued and not changed**. It cannot prove that the marks the staff typed
  were correct in the first place – that is still the institution's job.
- Certificates issued before Pramaan cannot be checked, and someone could still make a fake website that looks like
  Pramaan – always type the address yourself.
- Data is kept in memory and saved as JSON files – fine for thousands of certificates, not millions.
- Fonts come from Google Fonts; without internet the page uses normal system fonts (still works).
