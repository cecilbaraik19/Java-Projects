package com.cecil.pipeline.service;

import com.cecil.pipeline.model.Activity;
import com.cecil.pipeline.model.Deal;
import com.cecil.pipeline.model.Enums.LeadSource;
import com.cecil.pipeline.model.Enums.Role;
import com.cecil.pipeline.model.Enums.Stage;
import com.cecil.pipeline.model.User;
import com.cecil.pipeline.store.DataStore;
import com.cecil.pipeline.util.Json;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Dashboard numbers, the sales forecast, funnel, win/loss and the team leaderboard. */
public class AnalyticsService {

    private final DataStore store;
    private final DealService deals;

    public AnalyticsService(DataStore store, DealService deals) {
        this.store = store;
        this.deals = deals;
    }

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static LocalDate day(String iso) {
        return iso == null ? null : Instant.parse(iso).atZone(ZONE).toLocalDate();
    }

    private List<Deal> scope(String owner) {
        List<Deal> out = new ArrayList<>();
        for (Deal d : store.deals) if (owner == null || owner.equalsIgnoreCase(d.owner)) out.add(d);
        return out;
    }

    // ------------------------------------------------------------------ dashboard

    /** owner = a username for "my numbers", or null for the whole team. */
    public Map<String, Object> dashboard(User me, String owner) {
        synchronized (store) {
            LocalDate today = LocalDate.now();
            YearMonth month = YearMonth.from(today);
            List<Deal> ds = scope(owner);

            long openCount = 0, openValue = 0, weighted = 0, wonMonth = 0, wonMonthCount = 0, lostMonthCount = 0;
            long newThisMonth = 0;
            for (Deal d : ds) {
                if (d.isOpen()) { openCount++; openValue += d.value; weighted += d.weightedValue(); }
                LocalDate closed = day(d.closedAt);
                if (closed != null && YearMonth.from(closed).equals(month)) {
                    if (d.stage == Stage.WON) { wonMonth += d.value; wonMonthCount++; }
                    else if (d.stage == Stage.LOST) lostMonthCount++;
                }
                LocalDate created = day(d.createdAt);
                if (created != null && YearMonth.from(created).equals(month)) newThisMonth++;
            }

            long target = 0;
            for (User u : store.users) {
                if (!u.active) continue;
                if (owner == null ? u.role != Role.ADMIN : u.username.equalsIgnoreCase(owner)) target += u.monthlyTarget;
            }

            Map<String, Object> winStats = winStats(ds, today.minusDays(90));

            // per stage summary for the mini funnel
            List<Object> stages = new ArrayList<>();
            for (Stage s : Stage.openStages()) {
                long c = 0, v = 0, w = 0;
                for (Deal d : ds) if (d.stage == s) { c++; v += d.value; w += d.weightedValue(); }
                stages.add(Json.obj("stage", s, "label", s.label, "count", c, "value", v, "weighted", w));
            }

            // deal lists (views include the health score)
            List<Map<String, Object>> openViews = new ArrayList<>();
            for (Deal d : ds) if (d.isOpen()) openViews.add(deals.view(me, d, today, false));

            List<Object> closingSoon = new ArrayList<>(openViews.stream()
                .filter(v -> v.get("expectedClose") != null)
                .filter(v -> {
                    LocalDate ec = LocalDate.parse((String) v.get("expectedClose"));
                    return !ec.isBefore(today) && !ec.isAfter(today.plusDays(30));
                })
                .sorted(Comparator.comparing(v -> (String) v.get("expectedClose")))
                .limit(8).toList());

            List<Object> attention = new ArrayList<>(openViews.stream()
                .filter(v -> Boolean.TRUE.equals(v.get("stale")) || Boolean.TRUE.equals(v.get("closeOverdue")))
                .sorted(Comparator.comparing((Map<String, Object> v) -> (Long) v.get("value")).reversed())
                .limit(8).toList());

            List<Object> hot = new ArrayList<>(openViews.stream()
                .sorted(Comparator.comparing((Map<String, Object> v) -> (Integer) v.get("health")).reversed()
                    .thenComparing(v -> -(Long) v.get("value")))
                .limit(5).toList());

            List<Object> recentWins = new ArrayList<>();
            ds.stream().filter(d -> d.stage == Stage.WON && d.closedAt != null)
                .sorted(Comparator.comparing((Deal d) -> d.closedAt).reversed()).limit(5)
                .forEach(d -> recentWins.add(deals.view(me, d, today, false)));

            // tasks of the person looking (or of the chosen owner)
            String taskOwner = owner == null ? me.username : owner;
            int overdueTasks = 0, todayTasks = 0;
            List<Object> tasks = new ArrayList<>();
            List<Activity> mine = new ArrayList<>();
            for (Activity a : store.activities) {
                if (a.done || !taskOwner.equalsIgnoreCase(a.owner)) continue;
                if (a.isOverdue(today)) overdueTasks++;
                if (today.equals(a.dueDate)) todayTasks++;
                if (a.dueDate != null && !a.dueDate.isAfter(today.plusDays(2))) mine.add(a);
            }
            mine.sort(Comparator.comparing(a -> a.dueDate));
            for (Activity a : mine.stream().limit(8).toList()) tasks.add(ActivityService.view(store, a, today));

            return Json.obj(
                "scope", owner == null ? "team" : owner,
                "month", month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear(),
                "kpis", Json.obj(
                    "openCount", openCount,
                    "openValue", openValue,
                    "weightedValue", weighted,
                    "wonThisMonth", wonMonth,
                    "wonThisMonthCount", wonMonthCount,
                    "lostThisMonthCount", lostMonthCount,
                    "newThisMonth", newThisMonth,
                    "target", target,
                    "attainment", target == 0 ? null : Math.round(wonMonth * 1000.0 / target) / 10.0,
                    "winRate", winStats.get("winRate"),
                    "avgWonValue", winStats.get("avgWonValue"),
                    "avgCycleDays", winStats.get("avgCycleDays"),
                    "overdueTasks", overdueTasks,
                    "todayTasks", todayTasks),
                "stages", stages,
                "closingSoon", closingSoon,
                "attention", attention,
                "hotDeals", hot,
                "recentWins", recentWins,
                "tasks", tasks);
        }
    }

    /** Win rate, average won deal size and average days to win, for deals closed on or after 'since'. */
    private Map<String, Object> winStats(List<Deal> ds, LocalDate since) {
        long won = 0, lost = 0, wonValue = 0, cycleDays = 0;
        for (Deal d : ds) {
            LocalDate closed = day(d.closedAt);
            if (closed == null || closed.isBefore(since)) continue;
            if (d.stage == Stage.WON) {
                won++;
                wonValue += d.value;
                cycleDays += Math.max(0, ChronoUnit.DAYS.between(day(d.createdAt), closed));
            } else if (d.stage == Stage.LOST) {
                lost++;
            }
        }
        Double rate = won + lost == 0 ? null : Math.round(won * 1000.0 / (won + lost)) / 10.0;
        return Json.obj(
            "won", won, "lost", lost, "winRate", rate,
            "avgWonValue", won == 0 ? 0L : wonValue / won,
            "avgCycleDays", won == 0 ? null : Math.round(cycleDays * 10.0 / won) / 10.0);
    }

    // ------------------------------------------------------------------ reports

    public Map<String, Object> reports(String owner, int months) {
        int m = Math.max(3, Math.min(months, 24));
        synchronized (store) {
            LocalDate today = LocalDate.now();
            List<Deal> ds = scope(owner);
            return Json.obj(
                "funnel", funnel(ds),
                "monthly", monthly(ds, today, m),
                "forecast", forecast(ds, today, 6),
                "bySource", bySource(ds),
                "lostReasons", lostReasons(ds),
                "stageTime", stageTime(ds),
                "leaderboard", leaderboard(today),
                "velocity", velocity(ds, today),
                "allTime", winStats(ds, LocalDate.of(1970, 1, 1)));
        }
    }

    /** How many deals reached each stage, and the % that moved on from the stage before. */
    List<Object> funnel(List<Deal> ds) {
        List<Object> out = new ArrayList<>();
        Stage[] steps = { Stage.LEAD, Stage.QUALIFIED, Stage.PROPOSAL, Stage.NEGOTIATION, Stage.WON };
        long prev = -1;
        for (Stage s : steps) {
            long n = 0, v = 0;
            for (Deal d : ds) if (d.reached(s)) { n++; v += d.value; }
            Double conv = prev <= 0 ? null : Math.round(n * 1000.0 / prev) / 10.0;
            out.add(Json.obj("stage", s, "label", s.label, "count", n, "value", v, "conversion", conv));
            prev = n;
        }
        return out;
    }

    /** Won value, won/lost counts and new deals for each of the last N months (oldest first). */
    List<Object> monthly(List<Deal> ds, LocalDate today, int months) {
        Map<YearMonth, long[]> rows = new LinkedHashMap<>(); // wonValue, wonCount, lostCount, newCount, newValue
        YearMonth now = YearMonth.from(today);
        for (int i = months - 1; i >= 0; i--) rows.put(now.minusMonths(i), new long[5]);
        for (Deal d : ds) {
            LocalDate closed = day(d.closedAt);
            if (closed != null) {
                long[] r = rows.get(YearMonth.from(closed));
                if (r != null) {
                    if (d.stage == Stage.WON) { r[0] += d.value; r[1]++; }
                    else if (d.stage == Stage.LOST) r[2]++;
                }
            }
            long[] c = rows.get(YearMonth.from(day(d.createdAt)));
            if (c != null) { c[3]++; c[4] += d.value; }
        }
        List<Object> out = new ArrayList<>();
        rows.forEach((ym, r) -> out.add(Json.obj("month", ym.toString(), "label", label(ym),
            "wonValue", r[0], "wonCount", r[1], "lostCount", r[2], "newCount", r[3], "newValue", r[4])));
        return out;
    }

    /**
     * Expected revenue for the next N months, by expected close date.
     * Open deals whose close date already passed are counted in the current month (they are late, not gone).
     *   pipeline = full value of open deals, weighted = value x probability,
     *   commit = deals at 75% or more, won = already won in that month.
     */
    List<Object> forecast(List<Deal> ds, LocalDate today, int months) {
        YearMonth now = YearMonth.from(today);
        Map<YearMonth, long[]> rows = new LinkedHashMap<>(); // pipeline, weighted, commit, won, count
        for (int i = 0; i < months; i++) rows.put(now.plusMonths(i), new long[5]);
        for (Deal d : ds) {
            if (d.stage == Stage.WON) {
                long[] r = rows.get(YearMonth.from(day(d.closedAt)));
                if (r != null) r[3] += d.value;
                continue;
            }
            if (!d.isOpen()) continue;
            YearMonth ym = d.expectedClose == null ? null : YearMonth.from(d.expectedClose);
            if (ym != null && ym.isBefore(now)) ym = now;
            long[] r = ym == null ? null : rows.get(ym);
            if (r == null) continue;
            r[0] += d.value;
            r[1] += d.weightedValue();
            if (d.effectiveProbability() >= 75) r[2] += d.value;
            r[4]++;
        }
        List<Object> out = new ArrayList<>();
        rows.forEach((ym, r) -> out.add(Json.obj("month", ym.toString(), "label", label(ym),
            "pipeline", r[0], "weighted", r[1], "commit", r[2], "won", r[3], "count", r[4],
            "expected", r[3] + r[1])));
        return out;
    }

    List<Object> bySource(List<Deal> ds) {
        List<Object> out = new ArrayList<>();
        for (LeadSource s : LeadSource.values()) {
            long n = 0, won = 0, lost = 0, wonValue = 0, open = 0;
            for (Deal d : ds) {
                if (d.source != s) continue;
                n++;
                if (d.stage == Stage.WON) { won++; wonValue += d.value; }
                else if (d.stage == Stage.LOST) lost++;
                else open++;
            }
            if (n == 0) continue;
            out.add(Json.obj("source", s, "label", s.label, "count", n, "open", open, "won", won, "lost", lost,
                "wonValue", wonValue, "winRate", won + lost == 0 ? null : Math.round(won * 1000.0 / (won + lost)) / 10.0));
        }
        out.sort(Comparator.comparing((Object o) -> (Long) ((Map<?, ?>) o).get("wonValue")).reversed());
        return out;
    }

    List<Object> lostReasons(List<Deal> ds) {
        Map<String, long[]> m = new LinkedHashMap<>();
        for (Deal d : ds) {
            if (d.stage != Stage.LOST) continue;
            String r = d.lostReason == null || d.lostReason.isBlank() ? "Not given" : d.lostReason;
            long[] x = m.computeIfAbsent(r, k -> new long[2]);
            x[0]++;
            x[1] += d.value;
        }
        List<Object> out = new ArrayList<>();
        m.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
            .forEach(e -> out.add(Json.obj("reason", e.getKey(), "count", e.getValue()[0], "value", e.getValue()[1])));
        return out;
    }

    /** Average number of days deals spend in each open stage before moving on. */
    List<Object> stageTime(List<Deal> ds) {
        Map<Stage, long[]> m = new LinkedHashMap<>();
        for (Stage s : Stage.openStages()) m.put(s, new long[2]);
        for (Deal d : ds) {
            for (int i = 0; i + 1 < d.history.size(); i++) {
                Deal.StageChange a = d.history.get(i), b = d.history.get(i + 1);
                long[] x = m.get(a.to);
                if (x == null) continue;
                x[0] += Math.max(0, ChronoUnit.DAYS.between(day(a.at), day(b.at)));
                x[1]++;
            }
        }
        List<Object> out = new ArrayList<>();
        m.forEach((s, x) -> out.add(Json.obj("stage", s, "label", s.label,
            "avgDays", x[1] == 0 ? null : Math.round(x[0] * 10.0 / x[1]) / 10.0, "samples", x[1])));
        return out;
    }

    /**
     * Sales velocity = (open deals x win rate x average won value) / average days to win.
     * It tells how much money the pipeline brings in per day.
     */
    Map<String, Object> velocity(List<Deal> ds, LocalDate today) {
        Map<String, Object> w = winStats(ds, today.minusDays(180));
        long open = ds.stream().filter(Deal::isOpen).count();
        Double rate = (Double) w.get("winRate");
        Double cycle = (Double) w.get("avgCycleDays");
        long avg = (Long) w.get("avgWonValue");
        Long perDay = rate == null || cycle == null || cycle <= 0 ? null : Math.round(open * (rate / 100.0) * avg / cycle);
        return Json.obj("openDeals", open, "winRate", rate, "avgWonValue", avg, "avgCycleDays", cycle,
            "perDay", perDay, "perMonth", perDay == null ? null : perDay * 30);
    }

    /** One row per active sales person (reps and managers) for this month. */
    List<Object> leaderboard(LocalDate today) {
        YearMonth month = YearMonth.from(today);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (User u : store.users) {
            if (!u.active || u.role == Role.ADMIN) continue;
            long wonMonth = 0, wonCount = 0, openValue = 0, weighted = 0, openCount = 0, won = 0, lost = 0, acts = 0;
            for (Deal d : store.deals) {
                if (!u.username.equals(d.owner)) continue;
                LocalDate closed = day(d.closedAt);
                if (d.isOpen()) { openCount++; openValue += d.value; weighted += d.weightedValue(); }
                if (d.stage == Stage.WON) {
                    won++;
                    if (closed != null && YearMonth.from(closed).equals(month)) { wonMonth += d.value; wonCount++; }
                } else if (d.stage == Stage.LOST) lost++;
            }
            for (Activity a : store.activities) {
                if (a.done && u.username.equals(a.owner) && a.doneAt != null && !day(a.doneAt).isBefore(today.minusDays(30))) acts++;
            }
            rows.add(Json.obj(
                "username", u.username, "displayName", u.displayName, "role", u.role,
                "wonThisMonth", wonMonth, "wonThisMonthCount", wonCount, "target", u.monthlyTarget,
                "attainment", u.monthlyTarget == 0 ? null : Math.round(wonMonth * 1000.0 / u.monthlyTarget) / 10.0,
                "openCount", openCount, "openValue", openValue, "weightedValue", weighted,
                "winRate", won + lost == 0 ? null : Math.round(won * 1000.0 / (won + lost)) / 10.0,
                "activities30d", acts));
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (Long) r.get("wonThisMonth")).reversed()
            .thenComparing(r -> -(Long) r.get("weightedValue")));
        return new ArrayList<>(rows);
    }

    private static String label(YearMonth ym) {
        return ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + String.valueOf(ym.getYear()).substring(2);
    }
}
