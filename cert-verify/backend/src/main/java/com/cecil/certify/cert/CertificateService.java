package com.cecil.certify.cert;

import com.cecil.certify.chain.ChainState;
import com.cecil.certify.chain.MerkleTree;
import com.cecil.certify.chain.Network;
import com.cecil.certify.chain.Transaction;
import com.cecil.certify.chain.TxType;
import com.cecil.certify.model.CertificateRecord;
import com.cecil.certify.model.Issuer;
import com.cecil.certify.model.Role;
import com.cecil.certify.model.User;
import com.cecil.certify.store.DataStore;
import com.cecil.certify.util.Clock;
import com.cecil.certify.util.Crypto;
import com.cecil.certify.util.Csv;
import com.cecil.certify.util.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Issuing, revoking and sharing certificates, and approving institutions.
 * Every change is written to the chain first; only when the network accepts the block is the
 * off-chain record saved.
 */
public final class CertificateService {

    public static final int MAX_BATCH = 500;
    public static final long SHARE_DAYS_MAX = 365;
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9]{1,7}");

    private final Network network;
    private final DataStore store;

    public CertificateService(Network network, DataStore store) {
        this.network = network;
        this.store = store;
    }

    public Network network() { return network; }
    public DataStore store() { return store; }

    // ------------------------------------------------------------------ institutions

    /** The registrar approves a new institution: a new key pair is made and its public key goes on the chain. */
    public synchronized Issuer registerIssuer(String code, String name, String city) {
        String id = code == null ? "" : code.trim().toUpperCase();
        if (!CODE.matcher(id).matches()) throw new IllegalArgumentException("Code must be 2-8 capital letters or digits, starting with a letter (e.g. KVIT)");
        if (name == null || name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Give the institution's full name (up to 120 characters)");
        if (store.issuers.containsKey(id)) throw new IllegalArgumentException("An institution with code " + id + " already exists");
        KeyPair kp = Crypto.newKeyPair();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("issuerId", id);
        p.put("name", name.trim());
        p.put("publicKey", Crypto.encodePublic(kp.getPublic()));
        Transaction tx = Transaction.create(TxType.REGISTER_ISSUER, Clock.now(), Transaction.REGISTRAR, p, network.registrarKey());
        network.submit(List.of(tx));
        Issuer i = new Issuer();
        i.id = id;
        i.name = name.trim();
        i.city = city == null ? "" : city.trim();
        i.publicKey = p.get("publicKey");
        i.createdAt = Clock.now();
        store.putPrivateKey(id, kp.getPrivate());
        store.issuers.put(id, i);
        store.saveIssuers();
        return i;
    }

    public synchronized void suspendIssuer(String id, String reason) {
        if (!store.issuers.containsKey(id)) throw new IllegalArgumentException("Unknown institution");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("issuerId", id);
        p.put("reason", reason == null || reason.isBlank() ? "Suspended by the registrar" : limit(reason, 200));
        network.submit(List.of(Transaction.create(TxType.SUSPEND_ISSUER, Clock.now(), Transaction.REGISTRAR, p, network.registrarKey())));
    }

    // ------------------------------------------------------------------ issuing

    /** Checks and tidies the fields typed by the institution. The "issuer" field is always filled in by the app. */
    public Map<String, String> cleanFields(Map<String, String> in, Issuer issuer) {
        Map<String, String> f = new LinkedHashMap<>();
        for (int i = 0; i < CertFields.NAMES.length; i++) {
            String n = CertFields.NAMES[i];
            String v = n.equals("issuer") ? issuer.name : in.getOrDefault(n, "");
            v = v == null ? "" : v.replaceAll("\\s+", " ").trim();
            if (n.equals("issueDate") && v.isEmpty()) v = Clock.today().toString();
            if (v.isEmpty()) throw new IllegalArgumentException(CertFields.LABELS[i] + " is required");
            if (v.length() > CertFields.MAX_LENGTH[i]) throw new IllegalArgumentException(CertFields.LABELS[i] + " is too long");
            if (n.equals("issueDate")) {
                try {
                    LocalDate d = LocalDate.parse(v);
                    if (d.isAfter(Clock.today())) throw new IllegalArgumentException("Date of issue cannot be in the future");
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Date of issue must look like 2026-06-30");
                }
            }
            f.put(n, v);
        }
        return f;
    }

    private Transaction issueTx(CertificateRecord r, PrivateKey key) {
        List<String> leaves = CertFields.fieldHashes(r.fields, r.salts);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("certId", r.certId);
        p.put("issuerId", r.issuerId);
        p.put("fieldNames", String.join(",", CertFields.NAMES));
        p.put("fieldHashes", String.join(",", leaves));
        p.put("root", MerkleTree.root(leaves));
        return Transaction.create(TxType.ISSUE_CERT, Clock.now(), r.issuerId, p, key);
    }

    private CertificateRecord newRecord(User by, Issuer issuer, Map<String, String> fields, String holderUsername) {
        CertificateRecord r = new CertificateRecord();
        do { r.certId = CertFields.newCertId(); } while (store.certificates.containsKey(r.certId));
        r.issuerId = issuer.id;
        r.fields = fields;
        r.salts = CertFields.newSalts();
        r.issuedBy = by.username;
        r.issuedAt = Clock.now();
        r.holderUsername = holderUsername == null ? "" : holderUsername.trim().toLowerCase();
        if (!r.holderUsername.isEmpty()) {
            User h = store.users.get(r.holderUsername);
            if (h == null || h.role != Role.STUDENT) throw new IllegalArgumentException("No student account called '" + holderUsername + "'");
        }
        return r;
    }

    private Issuer issuerOf(User by) {
        if (by.role != Role.ISSUER) throw new SecurityException("Only institution staff can issue certificates");
        Issuer issuer = store.issuers.get(by.issuerId);
        if (issuer == null) throw new IllegalStateException("Your institution is not set up");
        ChainState st = network.consensus().state;
        ChainState.IssuerEntry e = st == null ? null : st.issuers.get(issuer.id);
        if (e == null) throw new IllegalStateException("Your institution is not approved on the network");
        if (e.suspendedAt != 0) throw new IllegalStateException("Your institution was suspended by the registrar, so it cannot issue certificates");
        return issuer;
    }

    public synchronized CertificateRecord issue(User by, Map<String, String> fieldsIn, String holderUsername) {
        Issuer issuer = issuerOf(by);
        CertificateRecord r = newRecord(by, issuer, cleanFields(fieldsIn, issuer), holderUsername);
        Transaction tx = issueTx(r, store.privateKey(issuer.id));
        Network.Commit c = network.submit(List.of(tx));
        r.txId = tx.id();
        r.block = c.block.index;
        store.certificates.put(r.certId, r);
        store.saveCertificates();
        return r;
    }

    public static final class BatchResult {
        public List<CertificateRecord> issued = new ArrayList<>();
        public List<String> errors = new ArrayList<>();
        public int block = -1;
        public long mineMillis;
    }

    /**
     * Issues many certificates from CSV text in ONE block. First row = headings:
     *   holder, rollNo, programme, result, session, issueDate (optional), username (optional)
     * If any row has a mistake nothing is issued, and every mistake is listed.
     */
    public synchronized BatchResult issueBatch(User by, String csvText) {
        Issuer issuer = issuerOf(by);
        BatchResult res = new BatchResult();
        List<List<String>> rows = Csv.parse(csvText == null ? "" : csvText);
        if (rows.size() < 2) throw new IllegalArgumentException("The CSV needs a heading row and at least one certificate row");
        if (rows.size() - 1 > MAX_BATCH) throw new IllegalArgumentException("At most " + MAX_BATCH + " certificates at a time");
        List<String> head = new ArrayList<>();
        for (String h : rows.get(0)) head.add(h.trim().replaceAll("[^A-Za-z]", "").toLowerCase());
        for (String need : List.of("holder", "rollno", "programme", "result", "session")) {
            if (!head.contains(need)) throw new IllegalArgumentException("Missing column '" + need + "'. Headings must be: holder, rollNo, programme, result, session, issueDate, username");
        }
        String batchId = "B" + Clock.now();
        List<Transaction> txs = new ArrayList<>();
        List<CertificateRecord> recs = new ArrayList<>();
        java.util.Set<String> seenRolls = new java.util.HashSet<>();
        PrivateKey key = store.privateKey(issuer.id);
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            Map<String, String> in = new LinkedHashMap<>();
            for (int c = 0; c < head.size() && c < row.size(); c++) in.put(head.get(c), row.get(c));
            Map<String, String> f = new LinkedHashMap<>();
            f.put("holder", in.getOrDefault("holder", ""));
            f.put("rollNo", in.getOrDefault("rollno", ""));
            f.put("programme", in.getOrDefault("programme", ""));
            f.put("result", in.getOrDefault("result", ""));
            f.put("session", in.getOrDefault("session", ""));
            f.put("issueDate", in.getOrDefault("issuedate", ""));
            try {
                Map<String, String> clean = cleanFields(f, issuer);
                if (!seenRolls.add(CertFields.normalise(clean.get("rollNo"))))
                    throw new IllegalArgumentException("Roll no. " + clean.get("rollNo") + " appears twice in this file");
                CertificateRecord r = newRecord(by, issuer, clean, in.getOrDefault("username", ""));
                r.batchId = batchId;
                recs.add(r);
                txs.add(issueTx(r, key));
            } catch (IllegalArgumentException e) {
                res.errors.add("Row " + (i + 1) + ": " + e.getMessage());
            }
        }
        if (!res.errors.isEmpty()) return res;
        Network.Commit c = network.submit(txs);
        for (int i = 0; i < recs.size(); i++) {
            CertificateRecord r = recs.get(i);
            r.txId = txs.get(i).id();
            r.block = c.block.index;
            store.certificates.put(r.certId, r);
        }
        store.saveCertificates();
        res.issued = recs;
        res.block = c.block.index;
        res.mineMillis = c.block.mineMillis;
        return res;
    }

    public synchronized CertificateRecord revoke(User by, String certId, String reason) {
        CertificateRecord r = store.certificates.get(certId);
        if (r == null) throw new IllegalArgumentException("No such certificate");
        if (by.role != Role.ISSUER || !r.issuerId.equals(by.issuerId))
            throw new SecurityException("Only the institution that issued this certificate can revoke it");
        if (r.revoked) throw new IllegalArgumentException("This certificate is already revoked");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Please give a reason (it is recorded on the chain)");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("certId", certId);
        p.put("reason", limit(reason.trim(), 200));
        Transaction tx = Transaction.create(TxType.REVOKE_CERT, Clock.now(), r.issuerId, p, store.privateKey(r.issuerId));
        network.submit(List.of(tx));
        r.revoked = true;
        r.revokeReason = p.get("reason");
        r.revokedAt = tx.time;
        store.saveCertificates();
        return r;
    }

    // ------------------------------------------------------------------ links and files

    /**
     * The permanent token printed in the QR code of a certificate: HMAC(server secret, certId).
     * Without it, knowing a certificate ID alone does not show anybody's details.
     */
    public String qrToken(String certId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(store.serverSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(("qr:" + certId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(h, 12));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The holder makes a time-limited share link. The token is shown ONCE; only its hash is stored. */
    public synchronized Map<String, Object> createShareLink(User holder, String certId, String label, long days) {
        CertificateRecord r = ownedBy(holder, certId);
        if (days < 1 || days > SHARE_DAYS_MAX) throw new IllegalArgumentException("A link can last from 1 to " + SHARE_DAYS_MAX + " days");
        if (r.shareLinks.stream().filter(s -> !s.disabled && s.expiresAt > Clock.now()).count() >= 20)
            throw new IllegalArgumentException("You already have 20 active links for this certificate. Switch some off first.");
        String token = "s" + Crypto.randomToken(15);
        CertificateRecord.ShareLink s = new CertificateRecord.ShareLink();
        s.id = Crypto.randomHex(4);
        s.tokenHash = Crypto.sha256Hex(token);
        s.label = label == null ? "" : limit(label.trim(), 60);
        s.createdAt = Clock.now();
        s.expiresAt = Clock.now() + days * 86_400_000L;
        r.shareLinks.add(s);
        store.saveCertificates();
        return Json.map("id", s.id, "token", token, "expiresAt", s.expiresAt, "label", s.label);
    }

    public synchronized void disableShareLink(User holder, String certId, String linkId) {
        CertificateRecord r = ownedBy(holder, certId);
        for (CertificateRecord.ShareLink s : r.shareLinks) {
            if (s.id.equals(linkId)) { s.disabled = true; store.saveCertificates(); return; }
        }
        throw new IllegalArgumentException("No such link");
    }

    public CertificateRecord ownedBy(User holder, String certId) {
        CertificateRecord r = store.certificates.get(certId);
        if (r == null || holder.role != Role.STUDENT || !holder.username.equals(r.holderUsername))
            throw new SecurityException("This is not your certificate");
        return r;
    }

    /**
     * Checks a link token. Returns the record if the token is the certificate's QR token or an active share link.
     * Counts a view for share links.
     */
    public synchronized CertificateRecord openByToken(String certId, String token) {
        CertificateRecord r = store.certificates.get(certId);
        if (r == null || token == null || token.isEmpty()) return null;
        if (Crypto.sameText(qrToken(certId), token)) return r;
        String h = Crypto.sha256Hex(token);
        for (CertificateRecord.ShareLink s : r.shareLinks) {
            if (Crypto.sameText(s.tokenHash, h) && !s.disabled && s.expiresAt > Clock.now()) {
                s.views++;
                store.saveCertificates();
                return r;
            }
        }
        return null;
    }

    /** The holder's own copy of the certificate (a .pramaan.json file): fields + salts + where it lives on the chain. */
    public Map<String, Object> certificateFile(CertificateRecord r) {
        Issuer i = store.issuers.get(r.issuerId);
        return Json.map(
                "format", "pramaan-certificate/1",
                "about", "Digital copy of a certificate. Check it at the Verify page of Pramaan. Changing any value makes it fail.",
                "certId", r.certId,
                "issuerId", r.issuerId,
                "issuerKeyFingerprint", i == null ? "" : Crypto.keyFingerprint(i.publicKey),
                "fields", new LinkedHashMap<String, Object>(r.fields),   // copies, so changing the file never changes the record
                "salts", new LinkedHashMap<String, Object>(r.salts),
                "chain", Json.map("network", network.genesisHash, "block", r.block, "tx", r.txId));
    }

    private static String limit(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
