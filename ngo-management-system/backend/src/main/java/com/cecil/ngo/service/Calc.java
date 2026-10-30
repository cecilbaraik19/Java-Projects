package com.cecil.ngo.service;

import com.cecil.ngo.model.Activity;
import com.cecil.ngo.model.Beneficiary;
import com.cecil.ngo.model.Donation;
import com.cecil.ngo.model.Enums.ActivityStatus;
import com.cecil.ngo.model.Enums.Review;
import com.cecil.ngo.model.Expense;
import com.cecil.ngo.model.HoursLog;
import com.cecil.ngo.model.Program;
import com.cecil.ngo.store.DataStore;
import com.cecil.ngo.util.Json;
import com.cecil.ngo.util.Money;

import java.time.LocalDate;
import java.util.Map;

/**
 * Numbers that several screens need (money spent, people reached, hours given...).
 * Call these only while holding the store lock: synchronized (store) { ... }.
 * from / to are optional date limits (null = no limit), both inclusive.
 */
public final class Calc {
    private Calc() {}

    static boolean inRange(String date, LocalDate from, LocalDate to) {
        if (date == null) return false;
        LocalDate d = LocalDate.parse(date);
        return (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to));
    }

    /** Approved spending for a program. */
    public static long spent(DataStore s, String programId, LocalDate from, LocalDate to) {
        long t = 0;
        for (Expense e : s.expenses)
            if (e.status == Review.APPROVED && e.programId.equals(programId) && inRange(e.date, from, to)) t += e.amount;
        return t;
    }

    /** Spending still waiting for approval. */
    public static long pending(DataStore s, String programId) {
        long t = 0;
        for (Expense e : s.expenses) if (e.status == Review.PENDING && e.programId.equals(programId)) t += e.amount;
        return t;
    }

    /** Donations given for this program (earmarked). */
    public static long raised(DataStore s, String programId, LocalDate from, LocalDate to) {
        long t = 0;
        for (Donation d : s.donations) if (programId.equals(d.programId) && inRange(d.date, from, to)) t += d.amount;
        return t;
    }

    /** People reached at activities that are done. */
    public static int reached(DataStore s, String programId, LocalDate from, LocalDate to) {
        int t = 0;
        for (Activity a : s.activities)
            if (a.status == ActivityStatus.DONE && a.programId.equals(programId) && inRange(a.date, from, to)) t += a.reached;
        return t;
    }

    public static int activities(DataStore s, String programId, ActivityStatus st, LocalDate from, LocalDate to) {
        int t = 0;
        for (Activity a : s.activities)
            if (a.programId.equals(programId) && a.status == st && inRange(a.date, from, to)) t++;
        return t;
    }

    public static int enrolled(DataStore s, String programId) {
        int t = 0;
        for (Beneficiary b : s.beneficiaries) if (b.programIds.contains(programId)) t++;
        return t;
    }

    /** Approved volunteer hours at this program's activities. */
    public static double programHours(DataStore s, String programId, LocalDate from, LocalDate to) {
        double t = 0;
        for (HoursLog h : s.hours) {
            if (h.status != Review.APPROVED || !inRange(h.date, from, to)) continue;
            Activity a = s.activity(h.activityId);
            if (a != null && a.programId.equals(programId)) t += h.hours;
        }
        return t;
    }

    public static double volunteerHours(DataStore s, String volunteerId, Review st, LocalDate from, LocalDate to) {
        double t = 0;
        for (HoursLog h : s.hours)
            if (h.volunteerId.equals(volunteerId) && h.status == st && inRange(h.date, from, to)) t += h.hours;
        return t;
    }

    /** A program with all its numbers, as the screens show it. */
    public static Map<String, Object> programView(DataStore s, Program p) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) p.toJson();
        long spent = spent(s, p.id, null, null);
        int reached = reached(s, p.id, null, null);
        m.put("spent", spent);
        m.put("pendingSpend", pending(s, p.id));
        m.put("left", p.budget - spent);
        m.put("used", Money.percent(spent, p.budget));
        m.put("raised", raised(s, p.id, null, null));
        m.put("reached", reached);
        m.put("reachPct", Money.percent(reached, p.targetBeneficiaries));
        m.put("enrolled", enrolled(s, p.id));
        m.put("done", activities(s, p.id, ActivityStatus.DONE, null, null));
        m.put("planned", activities(s, p.id, ActivityStatus.PLANNED, null, null));
        m.put("hours", round1(programHours(s, p.id, null, null)));
        m.put("causeLabel", p.cause.label);
        m.put("statusLabel", p.status.label);
        return m;
    }

    public static double round1(double v) { return Math.round(v * 10) / 10.0; }

    public static Map<String, Object> mini(Program p) {
        return Json.map("id", p.id, "name", p.name, "cause", p.cause, "status", p.status, "location", p.location);
    }
}
