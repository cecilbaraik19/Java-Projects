package com.cecil.certify.cert;

import com.cecil.certify.chain.ChainState;
import com.cecil.certify.chain.MerkleTree;
import com.cecil.certify.chain.Network;
import com.cecil.certify.chain.TxType;
import com.cecil.certify.model.CertificateRecord;
import com.cecil.certify.model.VerifyLogEntry;
import com.cecil.certify.store.DataStore;
import com.cecil.certify.util.Clock;
import com.cecil.certify.util.Crypto;
import com.cecil.certify.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a certificate is genuine. Three ways in:
 *   LINK  - the QR code / share link (certificate ID + secret token): shows the institution's own record
 *   FILE  - the digital certificate file (.pramaan.json) the student received
 *   TYPED - someone types the details printed on a paper certificate
 *
 * The same checks run every time, like the security features of a bank note:
 *   1 ID well-formed  2 network agrees  3 on the chain  4 approved institution  5 digital signature
 *   6 every detail matches its fingerprint  7 not revoked  8 sealed under later blocks
 */
public final class Verifier {

    public enum Verdict { GENUINE, REVOKED, TAMPERED, NOT_FOUND, UNTRUSTED_ISSUER, NETWORK_UNSAFE, BAD_INPUT }

    private final Network network;
    private final DataStore store;
    private final CertificateService certs;

    public Verifier(CertificateService certs) {
        this.certs = certs;
        this.network = certs.network();
        this.store = certs.store();
    }

    // ------------------------------------------------------------------ the three ways in

    public Map<String, Object> verifyLink(String certIdIn, String token, boolean log) {
        String id = CertFields.cleanCertId(certIdIn);
        CertificateRecord rec = id == null ? null : certs.openByToken(id, token);
        if (id != null && rec == null) {
            Result r = new Result(id, "LINK");
            r.check("id", "Certificate ID is well-formed", "pass", "The check letter is correct");
            r.check("link", "Link is valid", "fail", "This link is wrong, expired or was switched off by the holder");
            r.verdict = Verdict.BAD_INPUT;
            r.headline = "This link does not work";
            r.explanation = "The link is wrong, has expired, or the holder switched it off. Ask the holder for a new link, "
                    + "or check the certificate file or the printed details instead.";
            return finish(r, log);
        }
        Map<String, String> fields = rec == null ? Map.of() : rec.fields;
        Result r = core(id, certIdIn, fields, rec == null ? null : rec.salts, "LINK", false);
        if (rec != null && (r.verdict == Verdict.GENUINE || r.verdict == Verdict.REVOKED)) {
            r.record = new LinkedHashMap<>(rec.fields); // holder agreed to show these by sharing the link
        }
        return finish(r, log);
    }

    public Map<String, Object> verifyFile(Map<String, Object> file, boolean log) {
        if (file == null || !"pramaan-certificate/1".equals(Json.str(file, "format"))) {
            Result r = new Result(null, "FILE");
            r.verdict = Verdict.BAD_INPUT;
            r.headline = "This is not a Pramaan certificate file";
            r.explanation = "Choose the .pramaan.json file that came with the certificate.";
            return finish(r, log);
        }
        Map<String, String> fields = strings(Json.obj(file, "fields"));
        Map<String, String> salts = strings(Json.obj(file, "salts"));
        String certIdIn = Json.str(file, "certId");
        Result r = core(CertFields.cleanCertId(certIdIn), certIdIn, fields, salts, "FILE", false);
        Map<String, Object> chainInfo = Json.obj(file, "chain");
        String net = Json.str(chainInfo, "network");
        if (net != null && !net.equals(network.genesisHash)) {
            r.check("networkMatch", "Made for this network", "warn", "The file says it belongs to a different network (genesis " + shortHash(net) + ")");
        }
        return finish(r, log);
    }

    public Map<String, Object> verifyTyped(String certIdIn, Map<String, String> typed, boolean log) {
        Map<String, String> given = new LinkedHashMap<>();
        for (String n : CertFields.NAMES) {
            String v = typed == null ? null : typed.get(n);
            if (v != null && !v.isBlank()) given.put(n, v.trim());
        }
        String id = CertFields.cleanCertId(certIdIn);
        CertificateRecord rec = id == null ? null : store.certificates.get(id);
        if (given.size() < 2 || !given.containsKey("holder")) {
            Result r = new Result(id, "TYPED");
            r.verdict = Verdict.BAD_INPUT;
            r.headline = "Type a few more details";
            r.explanation = "Type at least the name and one more detail exactly as printed on the certificate.";
            return finish(r, log);
        }
        Result r = core(id, certIdIn, given, rec == null ? null : rec.salts, "TYPED", true);
        return finish(r, log);
    }

    // ------------------------------------------------------------------ the checks

    private Result core(String id, String typedId, Map<String, String> fields, Map<String, String> salts,
                        String method, boolean partial) {
        Result r = new Result(id, method);

        // 1. ID
        if (id == null) {
            r.check("id", "Certificate ID is well-formed", "fail", "'" + safe(typedId) + "' is not a Pramaan ID (they look like PRM-7K2Q-9XWD-M)");
            r.verdict = Verdict.NOT_FOUND;
            r.headline = "Not a valid certificate ID";
            r.explanation = "Genuine Pramaan IDs look like PRM-7K2Q-9XWD-M. Check for a typing mistake. If the ID on the paper really looks like this, the certificate is fake.";
            return r;
        }
        if (!CertFields.checkLetterOk(id)) {
            r.check("id", "Certificate ID is well-formed", "fail", "The last letter (check letter) does not match - probably a typing mistake");
            r.verdict = Verdict.NOT_FOUND;
            r.headline = "This ID has a mistake in it";
            r.explanation = "The check letter at the end does not fit the rest of the ID. Look at the paper again and re-type it. If it is printed like this, it was never made by Pramaan.";
            return r;
        }
        r.check("id", "Certificate ID is well-formed", "pass", "The check letter is correct");

        // 2. Network
        Network.Consensus c = network.consensus();
        r.agreeing = c.agreeing;
        if (!c.ok) {
            r.check("network", "Network agrees", "fail", c.message);
            r.verdict = Verdict.NETWORK_UNSAFE;
            r.headline = "Cannot check right now";
            r.explanation = "The network's nodes do not agree with each other, so no answer is safe. The registrar must repair the nodes.";
            return r;
        }
        r.check("network", "Network agrees", "pass", c.message + " (" + String.join(", ", c.agreeing) + ")");
        ChainState st = c.state;

        // 3. On the chain?
        ChainState.CertEntry e = st.certs.get(id);
        if (e == null) {
            r.check("onchain", "Recorded on the blockchain", "fail", "No institution ever issued " + id);
            r.verdict = Verdict.NOT_FOUND;
            r.headline = "FAKE - this certificate was never issued";
            r.explanation = "No approved institution has recorded a certificate with this ID. A real certificate is always recorded the moment it is issued.";
            return r;
        }
        r.issuerId = e.issuerId;
        List<String> holding = network.nodesHolding(t -> t.type == TxType.ISSUE_CERT && id.equals(t.get("certId"))
                && t.id().equals(e.issueTx.id()));
        r.chain = Json.map("block", e.block, "blockHash", e.blockHash, "tx", e.issueTx.id(),
                "issuedAt", e.issueTx.time, "confirmations", st.height - e.block,
                "nodesHolding", holding, "height", st.height);
        r.check("onchain", "Recorded on the blockchain", "pass", "Block " + e.block + ", found on " + holding.size() + " of " + network.nodes.size() + " nodes");

        // 4. Institution
        ChainState.IssuerEntry is = st.issuers.get(e.issuerId);
        r.issuer = Json.map("id", is.id, "name", is.name, "keyFingerprint", Crypto.keyFingerprint(is.publicKey),
                "approvedInBlock", is.registeredBlock, "suspendedAt", is.suspendedAt, "suspendReason", is.suspendReason);
        boolean trusted = is.activeAt(e.issueTx.time);
        if (!trusted) {
            r.check("issuer", "Issued by an approved institution", "fail", is.name + " was not approved at the time of issue");
        } else if (is.suspendedAt != 0) {
            r.check("issuer", "Issued by an approved institution", "warn", is.name + " was approved when this was issued, but was suspended later ("
                    + is.suspendReason + "). Certificates issued before that stay valid.");
        } else {
            r.check("issuer", "Issued by an approved institution", "pass", is.name + " (" + is.id + "), approved by the registrar in block " + is.registeredBlock);
        }

        // 5. Signature
        boolean sigOk = Crypto.verify(Crypto.decodePublic(is.publicKey), e.issueTx.signingText(), e.issueTx.signature);
        r.check("signature", "Institution's digital signature", sigOk ? "pass" : "fail",
                sigOk ? "Signed with " + is.id + "'s key " + Crypto.keyFingerprint(is.publicKey) : "The signature does not match the institution's key");

        // 6. Fields
        String[] onChain = e.issueTx.get("fieldHashes").split(",");
        boolean rootOk = MerkleTree.root(List.of(onChain)).equals(e.issueTx.get("root"));
        List<String> changed = new ArrayList<>();
        int checkedCount = 0;
        for (int i = 0; i < CertFields.NAMES.length; i++) {
            String n = CertFields.NAMES[i];
            String given = fields.get(n);
            String status;
            if (given == null || (partial && given.isBlank())) {
                status = "unchecked";
            } else if (salts == null || salts.get(n) == null || i >= onChain.length) {
                status = "unchecked";
            } else if (Crypto.sameText(CertFields.fieldHash(n, given, salts.get(n)), onChain[i])) {
                status = "match";
                checkedCount++;
            } else {
                status = "changed";
                checkedCount++;
                changed.add(CertFields.LABELS[i]);
            }
            Map<String, Object> f = Json.map("name", n, "label", CertFields.LABELS[i], "status", status);
            if (!"LINK".equals(r.method)) f.put("value", given == null ? "" : given);
            r.fields.add(f);
        }
        r.changedFields = changed;
        if (!rootOk || !changed.isEmpty()) {
            r.check("fields", "Every detail matches its fingerprint", "fail",
                    changed.isEmpty() ? "The fingerprint on the chain is damaged" : "Changed: " + String.join(", ", changed));
        } else if (checkedCount == 0) {
            r.check("fields", "Every detail matches its fingerprint", "fail", "No details could be checked");
        } else if (checkedCount < CertFields.NAMES.length) {
            r.check("fields", "Every detail matches its fingerprint", "pass", checkedCount + " of " + CertFields.NAMES.length + " details checked, all match");
        } else {
            r.check("fields", "Every detail matches its fingerprint", "pass", "All 7 details match the fingerprint sealed on the chain");
        }

        // 7. Revoked?
        if (e.revokeTx != null) {
            r.revocation = Json.map("reason", e.revokeTx.get("reason"), "time", e.revokeTx.time, "block", e.revokeBlock);
            r.check("revocation", "Not revoked", "fail", "Revoked on block " + e.revokeBlock + ": " + e.revokeTx.get("reason"));
        } else {
            r.check("revocation", "Not revoked", "pass", "No revocation recorded");
        }

        // 8. Depth
        int conf = st.height - e.block;
        r.check("depth", "Sealed under later blocks", conf >= 1 ? "pass" : "info",
                conf == 0 ? "In the newest block" : "Buried under " + conf + " later block" + (conf == 1 ? "" : "s") + " - changing it would mean re-doing all of them");

        // Verdict (most serious first)
        if (!rootOk || !changed.isEmpty() || checkedCount == 0) {
            r.verdict = Verdict.TAMPERED;
            r.headline = changed.isEmpty() ? "FORGED - details could not be matched" : "FORGED - this certificate was altered";
            r.explanation = changed.isEmpty()
                    ? "The details given do not match what the institution recorded."
                    : "The ID is real, but these details are not what the institution recorded: " + String.join(", ", changed)
                    + ". Someone changed the certificate after it was issued.";
        } else if (!trusted || !sigOk) {
            r.verdict = Verdict.UNTRUSTED_ISSUER;
            r.headline = "Not from a trusted institution";
            r.explanation = "The record exists but its institution or signature cannot be trusted.";
        } else if (e.revokeTx != null) {
            r.verdict = Verdict.REVOKED;
            r.headline = "REVOKED - no longer valid";
            r.explanation = is.name + " cancelled this certificate. Reason: " + e.revokeTx.get("reason");
        } else {
            r.verdict = Verdict.GENUINE;
            r.headline = "GENUINE";
            r.explanation = partial && checkedCount < CertFields.NAMES.length
                    ? "Issued by " + is.name + ". The " + checkedCount + " details you typed match exactly."
                    : "Issued by " + is.name + " and unchanged since. Every detail matches the fingerprint sealed on the blockchain.";
        }
        return r;
    }

    private Map<String, Object> finish(Result r, boolean log) {
        if (log) {
            VerifyLogEntry le = new VerifyLogEntry();
            le.time = Clock.now();
            le.certId = r.certId == null ? "" : r.certId;
            le.issuerId = r.issuerId == null ? "" : r.issuerId;
            le.method = r.method;
            le.verdict = r.verdict.name();
            le.changedFields = String.join(", ", r.changedFields);
            store.addVerification(le);
        }
        return r.toJson();
    }

    // ------------------------------------------------------------------ result

    static final class Result {
        final String certId;
        final String method;
        Verdict verdict = Verdict.BAD_INPUT;
        String headline = "";
        String explanation = "";
        String issuerId;
        Map<String, Object> issuer;
        Map<String, Object> chain;
        Map<String, Object> revocation;
        Map<String, String> record;
        List<String> agreeing = new ArrayList<>();
        List<String> changedFields = new ArrayList<>();
        final List<Object> checks = new ArrayList<>();
        final List<Object> fields = new ArrayList<>();

        Result(String certId, String method) {
            this.certId = certId;
            this.method = method;
        }

        void check(String key, String label, String status, String detail) {
            checks.add(Json.map("key", key, "label", label, "status", status, "detail", detail));
        }

        Map<String, Object> toJson() {
            return Json.map("verdict", verdict.name(), "headline", headline, "explanation", explanation,
                    "certId", certId, "method", method, "checks", checks, "fields", fields,
                    "changedFields", changedFields, "issuer", issuer, "chain", chain, "revocation", revocation,
                    "record", record, "agreeing", agreeing, "checkedAt", Clock.now());
        }
    }

    private static Map<String, String> strings(Map<String, Object> m) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m != null) m.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        return out;
    }

    private static String shortHash(String h) {
        return h == null || h.length() < 12 ? String.valueOf(h) : h.substring(0, 12) + "…";
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
