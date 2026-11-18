package com.cecil.swikriti.model;

import com.cecil.swikriti.util.Json;

import java.util.Map;

/**
 * One link of the approval ledger (a hash chain, like a tiny private blockchain without mining):
 *   hash = HMAC-SHA256(secret, seq | at | docId | rev | action | userId | onBehalfOf | docHash | prev)
 * Every entry includes the hash of the entry before it, so changing or deleting any old entry breaks every later link.
 */
public record LedgerEntry(long seq, long at, String docId, int rev, String action, String userId, String onBehalfOf,
                          String docHash, String prev, String hash) implements Json.Writable {

    /** The exact text that is signed. */
    public String payload() {
        return seq + "|" + at + "|" + docId + "|" + rev + "|" + action + "|" + userId + "|" + (onBehalfOf == null ? "" : onBehalfOf)
                + "|" + docHash + "|" + prev;
    }

    @Override public Map<String, Object> toJson() {
        return Json.obj("seq", seq, "at", at, "docId", docId, "rev", rev, "action", action, "userId", userId,
                "onBehalfOf", onBehalfOf, "docHash", docHash, "prev", prev, "hash", hash);
    }

    public static LedgerEntry fromJson(Map<String, Object> m) {
        return new LedgerEntry(Json.num(m, "seq", 0), Json.num(m, "at", 0), Json.str(m, "docId"), (int) Json.num(m, "rev", 0),
                Json.str(m, "action"), Json.str(m, "userId"), Json.str(m, "onBehalfOf"), Json.str(m, "docHash"),
                Json.str(m, "prev"), Json.str(m, "hash"));
    }
}
