package com.cecil.certify.chain;

import com.cecil.certify.util.Crypto;
import com.cecil.certify.util.Json;

import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * One signed record inside a block, for example "KVIT issued certificate PRM-7K2Q-9XWD-M with fingerprint 3fa9...".
 *
 * NO personal data is ever put in a transaction. A certificate is stored only as salted hashes, so the chain
 * can be public while names, marks and roll numbers stay with the institution and the student.
 *
 * The signature covers the "signing text": type + time + signer + every payload value (keys sorted).
 * Change one character and the signature no longer matches.
 */
public final class Transaction implements Json.JsonWritable {
    public static final String REGISTRAR = "REGISTRAR";

    public final TxType type;
    public final long time;
    /** Who signed it: "REGISTRAR" or an institution code such as "KVIT". */
    public final String signer;
    /** Sorted map so the signing text is always built in the same order. */
    public final TreeMap<String, String> payload;
    public String signature;

    public Transaction(TxType type, long time, String signer, Map<String, String> payload, String signature) {
        this.type = type;
        this.time = time;
        this.signer = signer;
        this.payload = new TreeMap<>(payload);
        this.signature = signature;
    }

    public static Transaction create(TxType type, long time, String signer, Map<String, String> payload, PrivateKey key) {
        Transaction tx = new Transaction(type, time, signer, payload, null);
        tx.signature = Crypto.sign(key, tx.signingText());
        return tx;
    }

    /** The exact text that is signed. Built the same way every time (canonical form). */
    public String signingText() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type.name());
        m.put("time", time);
        m.put("signer", signer);
        m.put("payload", payload);
        return Json.write(m);
    }

    /** Transaction ID = hash of the signing text. */
    public String id() {
        return Crypto.sha256Hex(signingText());
    }

    /** Hash used as a leaf of the block's Merkle tree. Includes the signature too. */
    public String leafHash() {
        return MerkleTree.leaf(signingText() + "|" + signature);
    }

    public String get(String key) {
        return payload.get(key);
    }

    @Override
    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id());
        m.put("type", type.name());
        m.put("time", time);
        m.put("signer", signer);
        m.put("payload", payload);
        m.put("signature", signature);
        return m;
    }

    public static Transaction fromJson(Map<String, Object> m) {
        Map<String, Object> p = Json.obj(m, "payload");
        Map<String, String> payload = new TreeMap<>();
        if (p != null) p.forEach((k, v) -> payload.put(k, v == null ? "" : String.valueOf(v)));
        return new Transaction(
                TxType.valueOf(Json.str(m, "type")),
                Json.num(m, "time", 0),
                Json.str(m, "signer"),
                payload,
                Json.str(m, "signature"));
    }

    /** A deep copy (used when one node's copy is tampered with in the Tamper lab). */
    public Transaction copy() {
        return new Transaction(type, time, signer, payload, signature);
    }
}
