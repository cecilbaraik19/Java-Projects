package com.cecil.sambandh.service;

import com.cecil.sambandh.model.AuditEntry;
import com.cecil.sambandh.model.Customer;
import com.cecil.sambandh.model.FollowUp;
import com.cecil.sambandh.model.Interaction;
import com.cecil.sambandh.model.Order;
import com.cecil.sambandh.model.Role;
import com.cecil.sambandh.model.Stage;
import com.cecil.sambandh.model.Ticket;
import com.cecil.sambandh.model.User;
import com.cecil.sambandh.store.DataStore;
import com.cecil.sambandh.util.Clock;
import com.cecil.sambandh.util.Json;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The "Switchboard" home page: numbers and lists that show what needs attention today. */
public class DashboardService {
    private final DataStore store;
    private final Insights insights;

    public DashboardService(DataStore store, Insights insights) {
        this.store = store;
        this.insights = insights;
    }

    public Map<String, Object> build(User actor) {
        Insights.Snapshot s = insights.snapshot();
        synchronized (store) {
            LocalDate today = s.today;
            long now = s.now;
            List<Customer> mine = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Customer c : store.customers.values()) {
                if (CustomerService.canSee(actor, c) && !c.erased) { mine.add(c); ids.add(c.id); }
            }

            Map<String, Object> stages = new LinkedHashMap<>();
            for (Stage st : Stage.values()) stages.put(st.name(), 0L);
            Map<String, Object> segments = new LinkedHashMap<>();
            for (String seg : Insights.SEGMENTS) segments.put(seg, 0L);
            int healthy = 0, watch = 0, risk = 0, newThisMonth = 0;
            List<Map<String, Object>> atRisk = new ArrayList<>();
            LocalDate monthStart = today.withDayOfMonth(1);
            for (Customer c : mine) {
                stages.merge(c.stage.name(), 1L, (a, b) -> (Long) a + (Long) b);
                Insights.Rfm r = s.rfm.get(c.id);
                if (r != null) segments.merge(r.segment(), 1L, (a, b) -> (Long) a + (Long) b);
                Insights.Health h = insights.health(c, s);
                if (c.stage != Stage.CHURNED) {
                    switch (h.band()) { case "healthy" -> healthy++; case "watch" -> watch++; default -> risk++; }
                }
                if (Insights.atRisk(c, h)) {
                    Insights.Part worst = h.parts().stream()
                            .min(Comparator.comparingDouble(p -> p.points() / (double) p.max())).orElse(null);
                    atRisk.add(Json.obj("id", c.id, "name", c.name, "company", c.company, "health", h.score(),
                            "reason", worst == null ? "" : worst.note(), "ltvPaise", s.lifetimeValue(c.id),
                            "ownerName", store.userName(c.ownerId)));
                }
                if (!LocalDate.ofInstant(java.time.Instant.ofEpochMilli(c.createdAt), Clock.ZONE).isBefore(monthStart)) newThisMonth++;
            }
            atRisk.sort(Comparator.comparingLong((Map<String, Object> m) -> -((Number) m.get("ltvPaise")).longValue()));

            // money
            long rev30 = 0, revPrev30 = 0;
            Map<String, Long> byMonth = new LinkedHashMap<>();
            YearMonth thisMonth = YearMonth.from(today);
            for (int i = 5; i >= 0; i--) byMonth.put(thisMonth.minusMonths(i).toString(), 0L);
            for (Order o : store.orders.values()) {
                if (!ids.contains(o.customerId) || !o.counts()) continue;
                long age = java.time.temporal.ChronoUnit.DAYS.between(o.date, today);
                if (age < 30) rev30 += o.amountPaise;
                else if (age < 60) revPrev30 += o.amountPaise;
                String ym = YearMonth.from(o.date).toString();
                if (byMonth.containsKey(ym)) byMonth.merge(ym, o.amountPaise, Long::sum);
            }
            List<Object> months = new ArrayList<>();
            for (Map.Entry<String, Long> e : byMonth.entrySet()) {
                YearMonth ym = YearMonth.parse(e.getKey());
                months.add(Json.obj("month", e.getKey(), "label", ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                        "paise", e.getValue()));
            }

            // tickets
            int openTickets = 0, breached = 0;
            for (Ticket t : store.tickets.values()) {
                if (!ids.contains(t.customerId)) continue;
                if (t.open()) openTickets++;
                if (t.breached(now)) breached++;
            }

            // my follow-ups
            List<Map<String, Object>> myFollowUps = new ArrayList<>();
            int overdue = 0, dueToday = 0;
            for (FollowUp f : store.followUps.values()) {
                if (f.done || !actor.id.equals(f.assigneeId) || !ids.contains(f.customerId)) continue;
                if (f.due.isAfter(today)) continue;
                if (f.due.isBefore(today)) overdue++; else dueToday++;
                Customer c = store.customers.get(f.customerId);
                Map<String, Object> x = f.toJson();
                x.put("customerName", c.name);
                x.put("overdue", f.overdue(today));
                myFollowUps.add(x);
            }
            myFollowUps.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("due")))
                    .thenComparing(m -> ActivityService.PRIORITIES.indexOf(String.valueOf(m.get("priority")))));

            // recent conversations
            List<Map<String, Object>> recent = new ArrayList<>();
            store.interactions.values().stream().filter(i -> ids.contains(i.customerId))
                    .sorted(Comparator.comparingLong((Interaction i) -> i.at).reversed()).limit(7)
                    .forEach(i -> {
                        Map<String, Object> x = i.toJson();
                        x.put("customerName", store.customers.get(i.customerId).name);
                        x.put("byName", store.userName(i.byUserId));
                        recent.add(x);
                    });

            // team board (managers)
            List<Object> team = new ArrayList<>();
            if (actor.role.atLeast(Role.MANAGER)) {
                long weekAgo = now - 7 * 86_400_000L;
                for (User u : store.users.values()) {
                    if (!u.active) continue;
                    long owned = mine.stream().filter(c -> u.id.equals(c.ownerId)).count();
                    long talks = store.interactions.values().stream().filter(i -> u.id.equals(i.byUserId) && i.at >= weekAgo && !"NOTE".equals(i.type)).count();
                    long done = store.followUps.values().stream().filter(f -> u.id.equals(f.assigneeId) && f.done && f.doneAt >= weekAgo).count();
                    long late = store.followUps.values().stream().filter(f -> u.id.equals(f.assigneeId) && f.overdue(today)
                            && ids.contains(f.customerId)).count();
                    long revenue = 0;
                    for (Order o : store.orders.values()) {
                        Customer c = store.customers.get(o.customerId);
                        if (c != null && u.id.equals(c.ownerId) && o.counts()
                                && java.time.temporal.ChronoUnit.DAYS.between(o.date, today) < 30) revenue += o.amountPaise;
                    }
                    if (owned == 0 && talks == 0 && u.role == Role.ADMIN) continue;
                    team.add(Json.obj("id", u.id, "name", u.name, "initials", u.initials(), "roleLabel", u.role.label,
                            "customers", owned, "talks7", talks, "done7", done, "overdue", late, "revenue30", revenue));
                }
            }

            return Json.obj(
                    "today", today.toString(),
                    "greetingName", actor.name.split(" ")[0],
                    "scope", actor.role.atLeast(Role.MANAGER) ? "all" : "mine",
                    "customers", mine.size(),
                    "newThisMonth", newThisMonth,
                    "stages", stages,
                    "health", Json.obj("healthy", healthy, "watch", watch, "risk", risk),
                    "revenue30", rev30,
                    "revenuePrev30", revPrev30,
                    "months", months,
                    "openTickets", openTickets,
                    "breachedTickets", breached,
                    "overdue", overdue,
                    "dueToday", dueToday,
                    "myFollowUps", myFollowUps.size() > 8 ? myFollowUps.subList(0, 8) : myFollowUps,
                    "atRisk", atRisk.size() > 6 ? atRisk.subList(0, 6) : atRisk,
                    "atRiskCount", atRisk.size(),
                    "segments", segments,
                    "recent", recent,
                    "team", team);
        }
    }

    /** The activity log (managers and admins), newest first, optionally filtered. */
    public Map<String, Object> audit(User actor, String filter, int limit) {
        if (!actor.role.atLeast(Role.MANAGER)) throw Problem.forbidden("Only managers can read the activity log.");
        List<Object> out = new ArrayList<>();
        synchronized (store) {
            for (int i = store.audit.size() - 1; i >= 0 && out.size() < limit; i--) {
                AuditEntry a = store.audit.get(i);
                if (filter != null && !filter.isBlank()) {
                    String f = filter.toLowerCase(Locale.ROOT);
                    String hay = (a.action + " " + a.target + " " + a.detail + " " + store.userName(a.userId)).toLowerCase(Locale.ROOT);
                    if (!hay.contains(f)) continue;
                }
                Map<String, Object> x = a.toJson();
                x.put("userName", a.userId == null ? "—" : store.userName(a.userId));
                out.add(x);
            }
        }
        return Json.obj("entries", out);
    }
}
