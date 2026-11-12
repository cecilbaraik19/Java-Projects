package com.cecil.sikho.ledger;

import com.cecil.sikho.model.LedgerEntry;
import com.cecil.sikho.model.User;
import com.cecil.sikho.service.Problem;
import com.cecil.sikho.store.DataStore;
import com.cecil.sikho.util.Clock;
import com.cecil.sikho.util.Json;
import com.cecil.sikho.util.Passwords;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The time bank: "one hour of teaching = one time credit", whatever the skill.
 *
 * Rules
 *   - every new member gets 3 welcome credits from the BANK, so they can start learning straight away;
 *   - a request moves the credits from the learner to ESCROW (so they cannot be spent twice);
 *   - when the session is confirmed, ESCROW pays the teacher; if it is cancelled, ESCROW pays the learner back;
 *   - a member's balance can never go below zero;
 *   - credits are only moved, never changed: the sum over all accounts is always 0 (BANK is negative by the amount
 *     it has given out). This is double-entry bookkeeping.
 *
 * The ledger is append-only and hash-chained: each line stores the SHA-256 of the line before it.
 */
public class TimeBank {
    public static final String BANK = "BANK";
    public static final String ESCROW = "ESCROW";
    public static final int WELCOME = 3;
    public static final String GENESIS = "0".repeat(64);

    private final DataStore store;
    /** Balance of every account, kept up to date with each new line (rebuilt from the ledger at start). */
    private final Map<String, Integer> balances = new HashMap<>();

    public TimeBank(DataStore store) {
        this.store = store;
        rebuild();
    }

    public final void rebuild() {
        synchronized (store) {
            balances.clear();
            for (LedgerEntry e : store.ledger) apply(e);
        }
    }

    private void apply(LedgerEntry e) {
        balances.merge(e.from, -e.amount, Integer::sum);
        balances.merge(e.to, e.amount, Integer::sum);
    }

    public int balance(String account) {
        synchronized (store) { return balances.getOrDefault(account, 0); }
    }

    /** Moves credits now. */
    public LedgerEntry post(String from, String to, int amount, String kind, String ref, String note) {
        return post(from, to, amount, kind, ref, note, Clock.millis());
    }

    /**
     * Adds one line to the ledger. Checks: a positive amount, two different accounts, and a member account never
     * goes below zero (BANK may - it creates credits; ESCROW never can because it only pays back what it holds).
     */
    public LedgerEntry post(String from, String to, int amount, String kind, String ref, String note, long at) {
        if (amount <= 0) throw Problem.bad("The amount must be at least 1 credit.");
        if (from == null || to == null || from.equals(to)) throw Problem.bad("Credits must move between two different accounts.");
        synchronized (store) {
            if (!BANK.equals(from) && balances.getOrDefault(from, 0) < amount) {
                throw Problem.conflict(ESCROW.equals(from) ? "Escrow does not hold enough credits for this."
                        : "Not enough time credits: you have " + balances.getOrDefault(from, 0) + " and this needs " + amount + ".");
            }
            LedgerEntry e = new LedgerEntry();
            e.seq = store.ledger.size() + 1L;
            e.at = at;
            e.from = from;
            e.to = to;
            e.amount = amount;
            e.kind = kind;
            e.ref = ref;
            e.note = note;
            e.prevHash = store.ledger.isEmpty() ? GENESIS : store.ledger.get(store.ledger.size() - 1).hash;
            e.hash = Passwords.sha256(e.content());
            store.ledger.add(e);
            apply(e);
            return e;
        }
    }

    /** Credits held in escrow for one exchange or circle (holds minus pay-outs). */
    public int heldFor(String ref) {
        synchronized (store) {
            int held = 0;
            for (LedgerEntry e : store.ledger) {
                if (ref == null || !ref.equals(e.ref)) continue;
                if (ESCROW.equals(e.to)) held += e.amount;
                if (ESCROW.equals(e.from)) held -= e.amount;
            }
            return held;
        }
    }

    /** Lines that touch one account, newest first, with a running balance. */
    public List<Map<String, Object>> statement(String account, int limit) {
        synchronized (store) {
            List<Map<String, Object>> out = new ArrayList<>();
            int running = 0;
            List<Map<String, Object>> all = new ArrayList<>();
            for (LedgerEntry e : store.ledger) {
                if (!account.equals(e.from) && !account.equals(e.to)) continue;
                int change = account.equals(e.to) ? e.amount : -e.amount;
                running += change;
                String other = account.equals(e.to) ? e.from : e.to;
                all.add(Json.obj("seq", e.seq, "at", e.at, "kind", e.kind, "change", change, "balance", running,
                        "other", other, "otherName", store.userName(other), "ref", e.ref, "note", e.note));
            }
            for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) out.add(all.get(i));
            return out;
        }
    }

    /** Gifts: a member gives some of their credits to a neighbour (max 5 credits a day). */
    public LedgerEntry gift(User from, User to, int amount, String note) {
        if (to == null) throw Problem.notFound("Member");
        if (from.id.equals(to.id)) throw Problem.bad("You cannot give credits to yourself.");
        if (amount < 1 || amount > 5) throw Problem.bad("You can give 1 to 5 credits at a time.");
        if (note == null || note.isBlank()) throw Problem.bad("Add a short note to say why.");
        synchronized (store) {
            if (!store.canAct(to)) throw Problem.bad("That member cannot receive credits right now.");
            long dayAgo = Clock.millis() - 86_400_000L;
            int today = 0;
            for (LedgerEntry e : store.ledger) if ("GIFT".equals(e.kind) && from.id.equals(e.from) && e.at > dayAgo) today += e.amount;
            if (today + amount > 5) throw Problem.bad("You can give at most 5 credits in 24 hours (you have given " + today + ").");
            LedgerEntry e = post(from.id, to.id, amount, "GIFT", null, note);
            store.log(from.id, "credit.gift", to.id, amount + " credit(s): " + note);
            store.notify(to.id, from.name + " gave you " + amount + " time credit" + (amount == 1 ? "" : "s") + ": \"" + note + "\"", "wallet");
            store.save("ledger", "notes", "audit");
            return e;
        }
    }

    /** Where all the credits are now: given out by the bank, with members, in escrow. */
    public Map<String, Object> summary() {
        synchronized (store) {
            int members = 0, zero = 0;
            for (User u : store.users.values()) {
                int b = balances.getOrDefault(u.id, 0);
                members += b;
                if (b == 0 && store.canAct(u)) zero++;
            }
            return Json.obj("issued", -balances.getOrDefault(BANK, 0), "inMembers", members,
                    "inEscrow", balances.getOrDefault(ESCROW, 0), "membersAtZero", zero, "lines", store.ledger.size());
        }
    }

    /**
     * Checks the whole ledger:
     *   1. every line's hash is right and points to the line before (nobody edited the file);
     *   2. the line numbers run 1, 2, 3 ... without gaps;
     *   3. no member account was ever below zero;
     *   4. all balances add up to 0 (double entry) and ESCROW holds exactly what the open exchanges and circles hold.
     */
    public Map<String, Object> verify(Map<String, Integer> expectedEscrowByRef) {
        synchronized (store) {
            List<Object> problems = new ArrayList<>();
            String prev = GENESIS;
            Map<String, Integer> bal = new HashMap<>();
            Map<String, Integer> escrowByRef = new LinkedHashMap<>();
            long seq = 0;
            for (LedgerEntry e : store.ledger) {
                seq++;
                if (e.seq != seq) problems.add("Line " + e.seq + ": expected number " + seq + " (a line is missing or moved).");
                if (!prev.equals(e.prevHash)) problems.add("Line " + e.seq + ": does not point to the line before it (chain broken).");
                if (!Passwords.sha256(e.content()).equals(e.hash)) problems.add("Line " + e.seq + ": its contents were changed (hash does not match).");
                if (e.amount <= 0) problems.add("Line " + e.seq + ": amount is not positive.");
                bal.merge(e.from, -e.amount, Integer::sum);
                bal.merge(e.to, e.amount, Integer::sum);
                if (!BANK.equals(e.from) && bal.get(e.from) < 0) problems.add("Line " + e.seq + ": " + store.userName(e.from) + " went below zero.");
                if (e.ref != null) {
                    if (ESCROW.equals(e.to)) escrowByRef.merge(e.ref, e.amount, Integer::sum);
                    if (ESCROW.equals(e.from)) escrowByRef.merge(e.ref, -e.amount, Integer::sum);
                }
                prev = e.hash;
            }
            int sum = 0;
            for (int v : bal.values()) sum += v;
            if (sum != 0) problems.add("All balances add up to " + sum + " instead of 0.");
            int escrow = bal.getOrDefault(ESCROW, 0);
            int expected = 0;
            for (int v : expectedEscrowByRef.values()) expected += v;
            if (escrow != expected) problems.add("Escrow holds " + escrow + " credits but the open exchanges and circles need " + expected + ".");
            for (Map.Entry<String, Integer> r : escrowByRef.entrySet()) {
                int want = expectedEscrowByRef.getOrDefault(r.getKey(), 0);
                if (r.getValue() != want) problems.add(r.getKey() + ": escrow holds " + r.getValue() + " but should hold " + want + ".");
            }
            int members = 0, membersCredits = 0, zero = 0;
            for (User u : store.users.values()) {
                members++;
                int b = bal.getOrDefault(u.id, 0);
                membersCredits += b;
                if (b == 0) zero++;
            }
            return Json.obj("ok", problems.isEmpty(), "lines", store.ledger.size(), "problems", problems,
                    "issued", -bal.getOrDefault(BANK, 0), "inMembers", membersCredits, "inEscrow", escrow,
                    "members", members, "membersAtZero", zero,
                    "lastHash", store.ledger.isEmpty() ? GENESIS : store.ledger.get(store.ledger.size() - 1).hash);
        }
    }
}
