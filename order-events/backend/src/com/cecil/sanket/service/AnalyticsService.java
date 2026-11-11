package com.cecil.sanket.service;

import com.cecil.sanket.broker.ConsumerGroup;
import com.cecil.sanket.broker.Dispatcher;
import com.cecil.sanket.broker.EventBroker;
import com.cecil.sanket.model.DeadLetter;
import com.cecil.sanket.model.Event;
import com.cecil.sanket.model.Notification;
import com.cecil.sanket.model.Order;
import com.cecil.sanket.model.OrderView;
import com.cecil.sanket.model.Role;
import com.cecil.sanket.model.User;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.util.Json;
import com.cecil.sanket.util.Stats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Everything the screens read: the signal box overview, order lists, traces, the event log and the charts. */
public class AnalyticsService {
    public static final long STUCK_MS = 2 * 60_000L;
    public static final int LAG_KEEP = 180;          // 180 samples x 10 s = 30 minutes

    private final DataStore store;
    private final EventBroker broker;
    private final Dispatcher dispatcher;
    private final OutboxRelay relay;
    private final Deque<Map<String, Object>> lagSamples = new ArrayDeque<>();

    public AnalyticsService(DataStore store, EventBroker broker, Dispatcher dispatcher, OutboxRelay relay) {
        this.store = store;
        this.broker = broker;
        this.dispatcher = dispatcher;
        this.relay = relay;
    }

    // ------------------------------------------------------------------ the signal box (overview)

    public Map<String, Object> overview() {
        long now = Clock.millis();
        long dayStart = Clock.today().atStartOfDay(Clock.ZONE).toInstant().toEpochMilli();
        List<Object> groups = new ArrayList<>();
        for (ConsumerGroup g : dispatcher.groups())
            groups.add(Json.obj("name", g.name, "service", g.service, "job", g.job, "topics", g.topics,
                    "aspect", dispatcher.aspect(g, now), "lag", dispatcher.lag(g), "paused", g.paused,
                    "retrying", g.retrying(), "slow", g.slow(now), "lastError", g.lastError));
        List<Object> topics = new ArrayList<>();
        List<Event> lastMin = broker.since(now - 60_000);
        for (String t : EventBroker.TOPICS.keySet()) {
            long minute = lastMin.stream().filter(e -> e.topic.equals(t)).count();
            topics.add(Json.obj("name", t, "partitions", broker.partitions(t), "events", broker.total(t), "lastMinute", minute));
        }
        List<Event> last5 = broker.since(now - 5 * 60_000);
        List<Double> lat = new ArrayList<>();
        long ordersToday = 0, cancelledToday = 0, revenueToday = 0, confirmed24 = 0, placed24 = 0;
        List<Object> stuck = new ArrayList<>();
        long openDlq;
        synchronized (store) {
            for (OrderView v : store.views.values()) {
                if (v.placedAt >= dayStart) {
                    ordersToday++;
                    if ("CANCELLED".equals(v.status)) cancelledToday++;
                    else if (v.stage("confirmed") > 0) revenueToday += v.total;
                }
                if (v.placedAt >= now - 86_400_000L) {
                    placed24++;
                    if (v.stage("confirmed") > 0) confirmed24++;
                    if (v.stage("confirmed") > 0 && v.placedAt > 0) lat.add((double) (v.stage("confirmed") - v.placedAt));
                }
                if (isStuck(v, now) && stuck.size() < 6) stuck.add(row(v));
            }
            openDlq = store.deadLetters.values().stream().filter(d -> "OPEN".equals(d.state)).count();
        }
        List<Object> latest = new ArrayList<>();
        for (Event e : broker.latest(16)) latest.add(e);
        List<Object> trains = new ArrayList<>();
        for (Event e : lastMin) if (trains.size() < 200)
            trains.add(Json.obj("topic", e.topic, "partition", e.partition, "at", e.at, "type", e.type, "key", e.key));
        Map<String, Object> kpi = Json.obj(
                "ordersToday", ordersToday, "cancelledToday", cancelledToday, "revenueToday", revenueToday,
                "confirmedPct", placed24 == 0 ? null : Math.round(100.0 * confirmed24 / placed24),
                "p50", lat.isEmpty() ? null : Stats.percentile(lat, 50), "p95", lat.isEmpty() ? null : Stats.percentile(lat, 95),
                "eventsPerMin", Stats.round1(last5.size() / 5.0), "totalLag", dispatcher.totalLag(),
                "openDeadLetters", openDlq, "outboxPending", relay.pending(), "stuck", countStuck(now),
                "events", broker.total());
        return Json.obj("kpi", kpi, "groups", groups, "topics", topics, "latest", latest, "trains", trains, "stuck", stuck,
                "brokerDown", broker.isDown(), "now", now);
    }

    /** An order that is still open but has not moved for 2 minutes before it was confirmed. */
    public static boolean isStuck(OrderView v, long now) {
        return v.open() && v.stage("confirmed") == 0 && !"CANCEL_REQUESTED".equals(v.status) && now - v.updatedAt > STUCK_MS;
    }

    private long countStuck(long now) {
        synchronized (store) { return store.views.values().stream().filter(v -> isStuck(v, now)).count(); }
    }

    // ------------------------------------------------------------------ orders

    private Map<String, Object> row(OrderView v) {
        Map<String, Object> m = Json.obj("orderId", v.orderId, "customer", store.customerName(v.customerId), "city", v.city,
                "total", v.total, "units", v.units, "status", v.status, "reason", v.reason, "paymentMethod", v.paymentMethod,
                "placedAt", v.placedAt, "updatedAt", v.updatedAt, "lastEvent", v.lastEvent, "awb", v.awb, "courier", v.courier);
        m.put("stuck", isStuck(v, Clock.millis()));
        return m;
    }

    public Map<String, Object> orders(User u, Map<String, String> q) {
        String status = q.getOrDefault("status", "");
        String text = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        long now = Clock.millis();
        synchronized (store) {
            List<OrderView> all = new ArrayList<>(store.views.values());
            all.sort((a, b) -> Long.compare(b.placedAt, a.placedAt));
            List<Object> out = new ArrayList<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String s : OrderView.STATUSES) counts.put(s, 0);
            int stuck = 0;
            for (OrderView v : all) {
                if (u.role == Role.CUSTOMER && !u.customerId.equals(v.customerId)) continue;
                counts.merge(v.status, 1, Integer::sum);
                boolean st = isStuck(v, now);
                if (st) stuck++;
                if (!status.isEmpty() && !(status.equals("STUCK") ? st : status.equals(v.status))) continue;
                if (!text.isEmpty()) {
                    String hay = (v.orderId + " " + store.customerName(v.customerId) + " " + v.city + " " + v.awb).toLowerCase(Locale.ROOT);
                    if (!hay.contains(text)) continue;
                }
                if (out.size() < 150) out.add(row(v));
            }
            counts.put("STUCK", stuck);
            return Json.obj("orders", out, "counts", counts);
        }
    }

    public Map<String, Object> orderDetail(User u, String id) {
        synchronized (store) {
            OrderView v = store.views.get(id);
            Order o = store.orders.get(id);
            if (o == null && v == null) throw Problem.notFound("Order");
            String customerId = o != null ? o.customerId : v.customerId;
            if (u.role == Role.CUSTOMER && !u.customerId.equals(customerId)) throw Problem.notFound("Order");
            Map<String, Object> m = Json.obj("orderId", id, "view", v == null ? null : row(v),
                    "stages", v == null ? Map.of() : v.stages, "order", o, "customer", store.customers.get(customerId),
                    "cancelAsked", Json.bool(store.meta, "cancelAsked." + id, false));
            if (v != null) m.put("lines", v.lines);
            else if (o != null) m.put("lines", o.linesPayload());
            if (u.role.atLeast(Role.STAFF)) {
                m.put("reservation", store.reservations.get(id));
                m.put("payment", store.payments.get(id));
                m.put("shipment", store.shipments.get(id));
                m.put("saga", store.sagas.get(id));
                List<Object> dl = new ArrayList<>();
                for (DeadLetter d : store.deadLetters.values()) if (id.equals(d.correlationId)) dl.add(d);
                m.put("deadLetters", dl);
            }
            List<Object> msgs = new ArrayList<>();
            for (Notification n : store.notifications.values()) if (id.equals(n.orderId)) msgs.add(n);
            m.put("messages", msgs);
            m.put("trace", trace(id));
            return m;
        }
    }

    /**
     * Distributed tracing for one order: every event with the same correlation id, and for each event which
     * consumer groups have handled it, are still waiting, or gave up (dead letter).
     */
    public List<Object> trace(String orderId) {
        List<Object> out = new ArrayList<>();
        Map<String, String> dead = new HashMap<>();
        synchronized (store) {
            for (DeadLetter d : store.deadLetters.values()) if (orderId.equals(d.correlationId)) dead.put(d.eventId + "/" + d.group, d.state);
        }
        for (Event e : broker.trace(orderId)) {
            List<Object> consumers = new ArrayList<>();
            for (ConsumerGroup g : dispatcher.groups()) {
                if (!g.topics.contains(e.topic) || !g.handles.contains(e.type)) continue;
                String state;
                String d = dead.get(e.id + "/" + g.name);
                if (d != null && !"REDRIVEN".equals(d)) state = "DEAD";
                else if (g.offset(e.topic, e.partition) > e.offset) state = "DONE";
                else state = "WAITING";
                consumers.add(Json.obj("group", g.name, "state", state));
            }
            Map<String, Object> m = e.toJson();
            m.put("consumers", consumers);
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ the event log

    public Map<String, Object> eventLog(Map<String, String> q) {
        String topic = q.getOrDefault("topic", "orders");
        if (!EventBroker.TOPICS.containsKey(topic)) throw Problem.bad("Unknown topic.");
        int partition;
        try { partition = Integer.parseInt(q.getOrDefault("partition", "0")); } catch (NumberFormatException e) { partition = 0; }
        if (partition < 0 || partition >= broker.partitions(topic)) throw Problem.bad("Unknown partition.");
        long before;
        try { before = Long.parseLong(q.getOrDefault("before", "-1")); } catch (NumberFormatException e) { before = -1; }
        String type = q.getOrDefault("type", "");
        List<Object> events = new ArrayList<>(broker.page(topic, partition, before, 40, type));
        List<Object> parts = new ArrayList<>();
        for (int p = 0; p < broker.partitions(topic); p++) parts.add(Json.obj("partition", p, "end", broker.end(topic, p)));
        List<Object> readers = new ArrayList<>();
        for (ConsumerGroup g : dispatcher.groups())
            if (g.topics.contains(topic)) readers.add(Json.obj("group", g.name, "offset", g.offset(topic, partition)));
        List<String> types = new ArrayList<>();
        for (var s : com.cecil.sanket.broker.Schemas.ALL) if (s.topic().equals(topic) && !types.contains(s.type())) types.add(s.type());
        return Json.obj("topic", topic, "partition", partition, "events", events, "partitions", parts, "readers", readers,
                "types", types, "end", broker.end(topic, partition));
    }

    // ------------------------------------------------------------------ charts

    /** Called every 10 seconds: remembers each group's lag for the lag chart. */
    public void sampleLag() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("at", Clock.millis());
        for (ConsumerGroup g : dispatcher.groups()) s.put(g.name, dispatcher.lag(g));
        synchronized (lagSamples) {
            lagSamples.addLast(s);
            while (lagSamples.size() > LAG_KEEP) lagSamples.pollFirst();
        }
    }

    public Map<String, Object> analytics() {
        long now = Clock.millis();
        // 1) events per minute, last 60 minutes, by topic
        long minuteNow = now - Math.floorMod(now, 60_000L);
        Map<String, long[]> perTopic = new LinkedHashMap<>();
        for (String t : EventBroker.TOPICS.keySet()) perTopic.put(t, new long[60]);
        for (Event e : broker.since(minuteNow - 59 * 60_000L)) {
            int i = (int) ((e.at - (minuteNow - 59 * 60_000L)) / 60_000L);
            if (i >= 0 && i < 60) perTopic.get(e.topic)[i]++;
        }
        List<Object> minutes = new ArrayList<>();
        for (int i = 0; i < 60; i++) minutes.add(minuteNow - (59 - i) * 60_000L);
        Map<String, Object> throughput = new LinkedHashMap<>();
        for (var t : perTopic.entrySet()) {
            List<Long> l = new ArrayList<>();
            for (long x : t.getValue()) l.add(x);
            throughput.put(t.getKey(), l);
        }

        // 2) orders by hour (last 72 h), 3) latency, 4) outcomes, 5) revenue by day, 6) mix
        long hourNow = now - Math.floorMod(now, 3_600_000L);
        long from = hourNow - 71 * 3_600_000L;
        long[] placed = new long[72], cancelled = new long[72];
        List<Double> lat = new ArrayList<>(), hopReserve = new ArrayList<>(), hopPay = new ArrayList<>(), hopConfirm = new ArrayList<>();
        Map<String, Long> outcomes = new LinkedHashMap<>();
        for (String k : List.of("DELIVERED", "DISPATCHED", "CONFIRMED", "IN_PROGRESS", "OUT_OF_STOCK", "PAYMENT_DECLINED", "CUSTOMER")) outcomes.put(k, 0L);
        TreeMap<String, long[]> byDay = new TreeMap<>();
        Map<String, Long> methods = new LinkedHashMap<>(), cities = new LinkedHashMap<>(), products = new HashMap<>();
        Map<String, String> productNames = new HashMap<>();
        long oldApp = 0, total = 0;
        synchronized (store) {
            for (OrderView v : store.views.values()) {
                if (v.placedAt <= 0) continue;
                total++;
                int h = (int) ((v.placedAt - from) / 3_600_000L);
                if (v.placedAt >= from && h < 72) {
                    placed[h]++;
                    if ("CANCELLED".equals(v.status)) cancelled[h]++;
                }
                long conf = v.stage("confirmed");
                if (conf > 0) {
                    lat.add((double) (conf - v.placedAt));
                    if (v.stage("reserved") > 0) hopReserve.add((double) (v.stage("reserved") - v.placedAt));
                    if (v.stage("paid") > 0 && v.stage("reserved") > 0) hopPay.add((double) (v.stage("paid") - v.stage("reserved")));
                    if (v.stage("paid") > 0) hopConfirm.add((double) (conf - v.stage("paid")));
                }
                String outcome = switch (v.status) {
                    case "DELIVERED", "DISPATCHED", "CONFIRMED" -> v.status;
                    case "CANCELLED" -> v.reason == null ? "CUSTOMER" : v.reason;
                    default -> "IN_PROGRESS";
                };
                outcomes.merge(outcome, 1L, Long::sum);
                String day = LocalDate.ofInstant(Instant.ofEpochMilli(v.placedAt), Clock.ZONE).toString();
                long[] d = byDay.computeIfAbsent(day, k -> new long[3]);
                if (conf > 0) d[0] += v.total;
                if (v.stage("refunded") > 0) d[1] += v.total;
                d[2]++;
                methods.merge(v.paymentMethod == null ? "?" : v.paymentMethod, 1L, Long::sum);
                if (v.city != null) cities.merge(v.city, 1L, Long::sum);
                if (conf > 0 && !"CANCELLED".equals(v.status))
                    for (Object o : v.lines) if (o instanceof Map<?, ?> m) {
                        String sku = String.valueOf(m.get("sku"));
                        products.merge(sku, m.get("qty") instanceof Number n ? n.longValue() : 1L, Long::sum);
                        productNames.put(sku, String.valueOf(m.get("name")));
                    }
            }
            for (Order o : store.orders.values()) if ("APP_OLD".equals(o.channel)) oldApp++;
        }
        List<Object> hours = new ArrayList<>();
        for (int i = 0; i < 72; i++) hours.add(Json.obj("at", from + i * 3_600_000L, "placed", placed[i], "cancelled", cancelled[i]));
        long[] edges = {250, 500, 1000, 2000, 5000, 30_000, Long.MAX_VALUE};
        String[] labels = {"< 0.25 s", "0.25–0.5 s", "0.5–1 s", "1–2 s", "2–5 s", "5–30 s", "≥ 30 s"};
        long[] buckets = new long[edges.length];
        for (double x : lat) for (int i = 0; i < edges.length; i++) if (x < edges[i]) { buckets[i]++; break; }
        List<Object> hist = new ArrayList<>();
        for (int i = 0; i < edges.length; i++) hist.add(Json.obj("label", labels[i], "count", buckets[i]));
        List<Object> days = new ArrayList<>();
        for (var d : byDay.entrySet()) days.add(Json.obj("day", d.getKey(), "revenue", d.getValue()[0], "refunds", d.getValue()[1], "orders", d.getValue()[2]));
        List<Object> top = new ArrayList<>();
        products.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(8)
                .forEach(e -> top.add(Json.obj("sku", e.getKey(), "name", productNames.get(e.getKey()), "units", e.getValue())));
        List<Object> lag;
        synchronized (lagSamples) { lag = new ArrayList<>(lagSamples); }
        List<String> groupNames = new ArrayList<>();
        for (ConsumerGroup g : dispatcher.groups()) groupNames.add(g.name);
        return Json.obj("minutes", minutes, "throughput", throughput, "hours", hours,
                "latency", Json.obj("histogram", hist, "count", lat.size(),
                        "p50", pct(lat, 50), "p95", pct(lat, 95), "p99", pct(lat, 99),
                        "hops", List.of(
                                Json.obj("label", "Placed → stock held", "median", pct(hopReserve, 50), "p95", pct(hopReserve, 95)),
                                Json.obj("label", "Stock held → paid", "median", pct(hopPay, 50), "p95", pct(hopPay, 95)),
                                Json.obj("label", "Paid → confirmed", "median", pct(hopConfirm, 50), "p95", pct(hopConfirm, 95)))),
                "outcomes", outcomes, "days", days, "methods", methods, "cities", cities, "top", top,
                "oldAppShare", total == 0 ? 0 : Math.round(100.0 * oldApp / Math.max(1, total)),
                "lag", lag, "groups", groupNames,
                "today", ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), Clock.ZONE).toLocalDate().toString());
    }

    private static Object pct(List<Double> xs, double p) { return xs.isEmpty() ? null : Stats.percentile(xs, p); }

}
