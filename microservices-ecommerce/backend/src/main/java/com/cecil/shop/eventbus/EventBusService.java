package com.cecil.shop.eventbus;

import com.cecil.shop.common.ApiError;
import com.cecil.shop.common.Config;
import com.cecil.shop.common.Json;
import com.cecil.shop.common.Req;
import com.cecil.shop.common.Router;
import com.cecil.shop.common.ServiceBase;
import com.cecil.shop.common.ServiceClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * EVENT BUS (a small message broker, like Kafka or RabbitMQ) - for ASYNCHRONOUS communication.
 *
 *  - A service PUBLISHES an event to a TOPIC, e.g. "order.confirmed", and does not wait for anyone.
 *  - Services that SUBSCRIBED to that topic get the event delivered (POST /events).
 *  - Delivery is "at least once": if the receiver is down we RETRY (after 0.5 s, 2 s, 5 s).
 *  - If all tries fail the event goes to the DEAD-LETTER QUEUE, where the admin can replay it.
 *
 * So the order service does not need to know who cares about an order. The cart, notification
 * and other services just listen. This is called "loose coupling".
 */
public class EventBusService extends ServiceBase {
    private static final long[] RETRY_DELAYS = {0, 500, 2000, 5000};
    private static final String SUBS = "subscriptions";

    private final Deque<Map<String, Object>> recent = new ArrayDeque<>();
    private final List<Map<String, Object>> deadLetters = new ArrayList<>();
    private final ScheduledExecutorService delivery =
            Executors.newScheduledThreadPool(4, r -> { Thread t = new Thread(r, "event-delivery"); t.setDaemon(true); return t; });
    private long nextEventId = 1, published, delivered, retried, dead;
    /** Shorter waits for tests. */
    public long retryScale = 1;

    public EventBusService(Config cfg) { super(Config.EVENT_BUS, cfg); }

    @Override public String role() { return "Message broker: publish / subscribe events"; }

    @Override
    protected void routes(Router r) {
        r.post("/publish", this::publish);
        r.post("/subscribe", this::subscribe);
        r.get("/subscriptions", req -> store.all(SUBS));
        r.get("/events", this::events);
        r.get("/dead-letters", req -> { synchronized (this) { return new ArrayList<>(deadLetters); } });
        r.post("/dead-letters/{id}/replay", this::replay);
        r.get("/bus-stats", req -> busStats());
    }

    private Object subscribe(Req r) {
        Map<String, Object> b = r.json();
        String topic = requireText(b, "topic", "Topic", 80);
        String service = requireText(b, "service", "Service", 60);
        String path = Json.str(b, "path", "/events");
        synchronized (this) {
            Map<String, Object> existing = store.findFirst(SUBS, s -> topic.equals(Json.str(s, "topic")) && service.equals(Json.str(s, "service")));
            if (existing == null) store.insert(SUBS, Json.obj("topic", topic, "service", service, "path", path, "since", System.currentTimeMillis()));
        }
        return Json.obj("ok", true);
    }

    private Object publish(Req r) {
        Map<String, Object> b = r.json();
        String topic = requireText(b, "topic", "Topic", 80);
        Map<String, Object> ev;
        synchronized (this) {
            String given = Json.str(b, "id");
            if (given != null && !given.matches("[A-Za-z0-9-]{4,60}")) given = null;
            if (given != null) {
                for (Map<String, Object> old : recent) {
                    if (given.equals(Json.str(old, "id"))) return Json.obj("ok", true, "id", given, "duplicate", true);
                }
            }
            ev = Json.obj("id", given != null ? given : "evt-" + (nextEventId++), "topic", topic, "source", Json.str(b, "source", r.caller()),
                    "traceId", Json.str(b, "traceId", r.traceId), "payload", Json.map(b.get("payload")),
                    "createdAt", Json.lng(b, "createdAt", System.currentTimeMillis()), "publishedAt", System.currentTimeMillis(),
                    "deliveries", new ArrayList<>());
            for (Map<String, Object> s : store.all(SUBS)) {
                if (topicMatches(Json.str(s, "topic"), topic)) {
                    Json.list(ev, "deliveries").add(Json.obj("service", Json.str(s, "service"), "path", Json.str(s, "path"),
                            "status", "PENDING", "attempts", 0L, "lastError", ""));
                }
            }
            recent.addFirst(ev);
            while (recent.size() > 300) recent.pollLast();
            published++;
        }
        List<Object> ds = Json.list(ev, "deliveries");
        for (int i = 0; i < ds.size(); i++) schedule(ev, i, 0);
        return Json.obj("ok", true, "id", ev.get("id"), "subscribers", ds.size());
    }

    private void schedule(Map<String, Object> ev, int index, int attempt) {
        delivery.schedule(() -> deliver(ev, index, attempt), RETRY_DELAYS[attempt] / retryScale, TimeUnit.MILLISECONDS);
    }

    private void deliver(Map<String, Object> ev, int index, int attempt) {
        Map<String, Object> d;
        Map<String, Object> message;
        synchronized (this) {
            d = Json.map(Json.list(ev, "deliveries").get(index));
            d.put("attempts", (long) attempt + 1);
            message = Json.obj("id", ev.get("id"), "topic", ev.get("topic"), "source", ev.get("source"), "payload", ev.get("payload"));
        }
        String error;
        try {
            String target = discovery.pick(Json.str(d, "service"));
            ServiceClient.Result res = client.callUrl(target, "POST", Json.str(d, "path"), message, null,
                    Json.str(ev, "traceId"), 3000, Json.str(d, "service"));
            if (res.ok()) {
                synchronized (this) { d.put("status", "DELIVERED"); d.put("lastError", ""); delivered++; }
                return;
            }
            error = res.error();
        } catch (ApiError e) {
            error = e.getMessage();
        } catch (Exception e) {
            error = e.getClass().getSimpleName();
        }
        synchronized (this) {
            d.put("lastError", error);
            if (attempt + 1 < RETRY_DELAYS.length) {
                d.put("status", "RETRYING");
                retried++;
            } else {
                d.put("status", "DEAD");
                dead++;
                deadLetters.add(0, Json.obj("id", ev.get("id") + ":" + Json.str(d, "service"), "eventId", ev.get("id"),
                        "topic", ev.get("topic"), "service", d.get("service"), "error", error, "at", System.currentTimeMillis(), "index", (long) index));
                while (deadLetters.size() > 200) deadLetters.remove(deadLetters.size() - 1);
                return;
            }
        }
        schedule(ev, index, attempt + 1);
    }

    private Object replay(Req r) {
        r.requireAdmin();
        String id = r.param("id");
        Map<String, Object> dl;
        Map<String, Object> ev = null;
        synchronized (this) {
            dl = deadLetters.stream().filter(x -> id.equals(Json.str(x, "id"))).findFirst().orElse(null);
            if (dl == null) throw ApiError.notFound("Dead letter not found");
            for (Map<String, Object> e : recent) if (Json.str(e, "id").equals(Json.str(dl, "eventId"))) ev = e;
            if (ev == null) throw ApiError.conflict("The original event is too old to replay");
            deadLetters.remove(dl);
            Map<String, Object> d = Json.map(Json.list(ev, "deliveries").get((int) Json.lng(dl, "index")));
            d.put("status", "RETRYING");
        }
        schedule(ev, (int) Json.lng(dl, "index"), 0);
        return Json.obj("ok", true);
    }

    private synchronized Object events(Req r) {
        int limit = (int) Math.min(300, Math.max(1, Json.lng(Json.obj("l", r.q("limit")), "l", 60)));
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> e : recent) {
            if (out.size() >= limit) break;
            out.add(Json.copy(e));
        }
        return out;
    }

    private synchronized Map<String, Object> busStats() {
        return Json.obj("published", published, "delivered", delivered, "retried", retried, "dead", dead,
                "deadLetters", deadLetters.size(), "subscriptions", store.all(SUBS).size());
    }

    @Override
    public synchronized void stop() {
        delivery.shutdownNow();
        super.stop();
    }
}
