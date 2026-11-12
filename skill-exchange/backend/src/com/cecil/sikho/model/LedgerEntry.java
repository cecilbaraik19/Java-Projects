package com.cecil.sikho.model;

import com.cecil.sikho.util.Json;

import java.util.Map;

/**
 * One line in the time-bank ledger: amount credits moved from one account to another.
 * Accounts are member ids plus two special ones: BANK (gives welcome credits) and ESCROW (holds credits
 * until a session is confirmed). Each line keeps the SHA-256 hash of the line before it, so changing any old
 * line breaks the chain and the check finds it.
 */
public class LedgerEntry implements Json.Writable {
    public long seq;
    public long at;
    public String from;
    public String to;
    public int amount;
    public String kind;
    /** The exchange or skill circle this is about (or null). */
    public String ref;
    public String note;
    public String prevHash;
    public String hash;

    /** The text that is hashed: every field except the hash itself. */
    public String content() {
        return seq + "|" + at + "|" + from + "|" + to + "|" + amount + "|" + kind + "|" + (ref == null ? "" : ref)
                + "|" + (note == null ? "" : note) + "|" + prevHash;
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("seq", seq, "at", at, "from", from, "to", to, "amount", amount, "kind", kind, "ref", ref,
                "note", note, "prevHash", prevHash, "hash", hash);
    }

    public static LedgerEntry fromJson(Map<String, Object> m) {
        LedgerEntry e = new LedgerEntry();
        e.seq = Json.num(m, "seq", 0);
        e.at = Json.num(m, "at", 0);
        e.from = Json.str(m, "from");
        e.to = Json.str(m, "to");
        e.amount = (int) Json.num(m, "amount", 0);
        e.kind = Json.str(m, "kind");
        e.ref = Json.str(m, "ref");
        e.note = Json.str(m, "note");
        e.prevHash = Json.str(m, "prevHash");
        e.hash = Json.str(m, "hash");
        return e;
    }
}
