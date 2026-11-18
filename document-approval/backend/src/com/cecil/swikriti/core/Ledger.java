package com.cecil.swikriti.core;

import com.cecil.swikriti.model.LedgerEntry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * The approval ledger: a HASH CHAIN of every submit, approve, reject, send-back and withdraw.
 *
 *   entry 1: hash1 = HMAC(secret, "1|...|prev=GENESIS")
 *   entry 2: hash2 = HMAC(secret, "2|...|prev=hash1")
 *   entry 3: hash3 = HMAC(secret, "3|...|prev=hash2") ...
 *
 * HMAC-SHA256 is a hash with a secret key (kept in data/ledger.key). Without the key nobody can make a valid entry,
 * and because every entry contains the previous hash, changing ONE old entry breaks the check of all later entries.
 * verify() walks the chain and reports the first broken link.
 */
public final class Ledger {
    public static final String GENESIS = "GENESIS";

    private Ledger() { }

    public static String sign(byte[] secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return hex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC not available", e);
        }
    }

    public static LedgerEntry make(byte[] secret, long seq, long at, String docId, int rev, String action, String userId,
                                   String onBehalfOf, String docHash, String prev) {
        LedgerEntry draft = new LedgerEntry(seq, at, docId, rev, action, userId, onBehalfOf, docHash, prev, null);
        return new LedgerEntry(seq, at, docId, rev, action, userId, onBehalfOf, docHash, prev, sign(secret, draft.payload()));
    }

    /** Result of checking the whole chain. brokenAt = seq of the first bad entry (0 = none). */
    public record Check(boolean ok, int checked, long brokenAt, String reason) { }

    public static Check verify(List<LedgerEntry> chain, byte[] secret) {
        String prev = GENESIS;
        long expectSeq = 1;
        int n = 0;
        for (LedgerEntry e : chain) {
            n++;
            if (e.seq() != expectSeq) return new Check(false, n, e.seq(), "entry number " + expectSeq + " is missing");
            if (!prev.equals(e.prev())) return new Check(false, n, e.seq(), "does not point to the entry before it");
            String want = sign(secret, e.payload());
            if (!MessageDigest.isEqual(want.getBytes(StandardCharsets.US_ASCII), String.valueOf(e.hash()).getBytes(StandardCharsets.US_ASCII)))
                return new Check(false, n, e.seq(), "its content was changed after it was written");
            prev = e.hash();
            expectSeq++;
        }
        return new Check(true, n, 0, null);
    }

    public static String sha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }
}
