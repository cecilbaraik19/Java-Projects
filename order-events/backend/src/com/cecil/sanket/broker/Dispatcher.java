package com.cecil.sanket.broker;

import com.cecil.sanket.model.DeadLetter;
import com.cecil.sanket.model.Event;
import com.cecil.sanket.model.User;
import com.cecil.sanket.service.Problem;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers events to the consumer groups. It runs every 200 ms (a background job) and, for every group and every
 * partition it reads, hands over the events after the group's committed offset, one by one, in order.
 *
 * For each event:
 *   1. the group does not care about this type        -> commit and move on ("skipped")
 *   2. the group has already handled this event id    -> commit and move on ("duplicate")  = idempotent consumer
 *   3. otherwise run the handler (inside one lock with its data changes and the events it emits):
 *        ok     -> publish its new events, remember the id, commit the offset
 *        error  -> try again later: after 1 s, 2 s, 4 s (exponential backoff). The partition waits, so the order
 *                  of events stays correct. After the 4th failed try the event goes to the dead-letter list
 *                  (and a DeadLettered event to the dead-letter topic), the offset is committed and the group goes on.
 */
public class Dispatcher {
    public static final int MAX_ATTEMPTS = 4;
    public static final long BASE_BACKOFF_MS = 1000;

    private final EventBroker broker;
    private final DataStore store;
    private final Map<String, ConsumerGroup> groups = new LinkedHashMap<>();
    private volatile long lastTickAt;
    /** An event is handed over only when it is at least this old. 0 in the live system; the demo-history builder
     *  sets it to a few tens of milliseconds so each hop takes a realistic little time. */
    public volatile long hopDelayMs;

    public Dispatcher(EventBroker broker, DataStore store) {
        this.broker = broker;
        this.store = store;
    }

    /** Adds a group and gives it back its saved offsets and counters. */
    public void register(ConsumerGroup g) {
        groups.put(g.name, g);
        synchronized (store) {
            Object s = store.groups.get(g.name);
            if (s instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) s;
                g.restore(m);
            }
        }
    }

    public List<ConsumerGroup> groups() { return new ArrayList<>(groups.values()); }

    public ConsumerGroup group(String name) { return groups.get(name); }

    public static long backoff(int attempt) { return BASE_BACKOFF_MS << Math.max(0, attempt - 1); }

    // ------------------------------------------------------------------ the delivery loop

    /** One round over all groups. Returns how many events were handed over (handled, skipped or failed). */
    public int tick(long now, int maxPerPartition) {
        lastTickAt = now;
        if (broker.isDown()) return 0;
        int n = 0;
        for (ConsumerGroup g : groups.values()) {
            if (g.paused) continue;
            for (String topic : g.topics)
                for (int p = 0; p < broker.partitions(topic); p++) n += deliverSome(g, topic, p, now, maxPerPartition);
        }
        if (n > 0) saveState();
        return n;
    }

    /** Keeps ticking until no group has anything left to read (used by the tests and the demo history). */
    public int drain(int maxRounds) {
        int total = 0;
        for (int i = 0; i < maxRounds; i++) {
            int n = tick(Clock.millis(), 500);
            total += n;
            if (n == 0 && !anyRetryWaiting()) break;
            if (n == 0) Clock.advanceMillis(BASE_BACKOFF_MS);   // frozen clock: jump to the next retry
        }
        return total;
    }

    public boolean anyRetryWaiting() {
        for (ConsumerGroup g : groups.values()) if (!g.paused && g.retrying()) return true;
        return false;
    }

    private int deliverSome(ConsumerGroup g, String topic, int p, long now, int max) {
        String tp = topic + ":" + p;
        int n = 0;
        while (n < max) {
            Long wait = g.nextTryAt.get(tp);
            if (wait != null && now < wait) break;
            if (g.slow(now) && now - g.lastHandledAt < g.slowGapMs) break;
            long off = g.offset(topic, p);
            Event e = broker.read(topic, p, off);
            if (e == null) break;
            if (hopDelayMs > 0 && now - e.at < hopDelayMs) break;
            n++;
            if (!deliver(g, e, tp)) break;
            if (g.slow(now)) break;
        }
        return n;
    }

    /** Returns false when the partition must wait (the handler failed and will be retried). */
    private boolean deliver(ConsumerGroup g, Event e, String tp) {
        synchronized (store) {
            if (!g.handles.contains(e.type)) {
                g.skipped++;
                g.commit(e.topic, e.partition, e.offset + 1);
                return true;
            }
            if (g.hasSeen(e.id)) {
                g.duplicates++;
                g.commit(e.topic, e.partition, e.offset + 1);
                return true;
            }
            try {
                Handler.Out out = new Handler.Out();
                g.handler.handle(e, out);
                for (Handler.Out.Emit x : out.emits())
                    broker.publish(x.topic(), x.type(), x.version(), e.key, e.correlationId, e.id, g.service, x.payload());
                g.markSeen(e.id);
                g.processed++;
                g.lastHandledAt = Clock.millis();
                g.attempts.remove(tp);
                g.nextTryAt.remove(tp);
                if (g.loseCommits > 0) {          // fault: the handler worked but the commit "got lost"
                    g.loseCommits--;
                    g.lostCommits++;
                    return false;                 // the same event will be delivered again next round
                }
                g.commit(e.topic, e.partition, e.offset + 1);
                return true;
            } catch (BrokerDown down) {
                return false;                     // nothing changed; try again when the broker is back
            } catch (Exception ex) {
                int tries = g.attempts.merge(tp, 1, Integer::sum);
                g.retries++;
                g.lastError = e.type + " " + e.id + ": " + message(ex);
                g.lastErrorAt = Clock.millis();
                if (tries >= MAX_ATTEMPTS) {
                    deadLetter(g, e, message(ex), tries);
                    g.attempts.remove(tp);
                    g.nextTryAt.remove(tp);
                    g.commit(e.topic, e.partition, e.offset + 1);
                    return true;
                }
                g.nextTryAt.put(tp, Clock.millis() + backoff(tries));
                return false;
            }
        }
    }

    private static String message(Exception ex) {
        String m = ex.getMessage();
        return m == null || m.isBlank() ? ex.getClass().getSimpleName() : m;
    }

    private void deadLetter(ConsumerGroup g, Event e, String error, int attempts) {
        DeadLetter d = new DeadLetter();
        d.id = store.nextId("DL", 1001);
        d.group = g.name;
        d.eventId = e.id;
        d.topic = e.topic;
        d.partition = e.partition;
        d.offset = e.offset;
        d.type = e.type;
        d.correlationId = e.correlationId;
        d.error = error;
        d.attempts = attempts;
        d.at = Clock.millis();
        d.state = "OPEN";
        store.deadLetters.put(d.id, d);
        g.deadLettered++;
        broker.publish("dead-letter", "DeadLettered", 1, e.key, e.correlationId, e.id, "broker",
                Json.obj("group", g.name, "eventId", e.id, "eventType", e.type, "error", error, "attempts", attempts));
        store.log(null, "dlq.add", d.id, g.name + " gave up on " + e.type + " " + e.id + ": " + error);
        store.markDirty("deadLetters", "audit");
    }

    // ------------------------------------------------------------------ dead letters: redrive / discard

    /** Delivers a dead-lettered event to its group again (after the cause was fixed). */
    public DeadLetter redrive(User actor, String id) {
        if (broker.isDown()) throw new Problem(503, "The broker is down. Try again when it is back.");
        synchronized (store) {
            DeadLetter d = store.deadLetters.get(id);
            if (d == null) throw Problem.notFound("Dead letter");
            if (!"OPEN".equals(d.state)) throw Problem.conflict("This dead letter is already " + d.state.toLowerCase() + ".");
            ConsumerGroup g = groups.get(d.group);
            Event e = broker.read(d.topic, d.partition, d.offset);
            if (g == null || e == null) throw Problem.notFound("Original event");
            if (g.hasSeen(e.id)) {
                d.state = "REDRIVEN";
                d.note = "Already handled before - nothing to do.";
            } else {
                try {
                    Handler.Out out = new Handler.Out();
                    g.handler.handle(e, out);
                    for (Handler.Out.Emit x : out.emits())
                        broker.publish(x.topic(), x.type(), x.version(), e.key, e.correlationId, e.id, g.service, x.payload());
                    g.markSeen(e.id);
                    g.processed++;
                    d.state = "REDRIVEN";
                    d.note = "Handled on redrive" + (out.emits().isEmpty() ? "." : ", emitted " + out.emits().size() + " event(s).");
                } catch (Exception ex) {
                    d.note = "Redrive failed: " + message(ex);
                    store.log(actor.id, "dlq.redrive.fail", d.id, d.note);
                    store.markDirty("deadLetters", "audit");
                    saveState();
                    throw Problem.bad("Still failing: " + message(ex));
                }
            }
            store.log(actor.id, "dlq.redrive", d.id, d.group + " / " + d.type + " " + d.eventId);
            store.markDirty("deadLetters", "audit");
            saveState();
            return d;
        }
    }

    public DeadLetter discard(User actor, String id, String note) {
        synchronized (store) {
            DeadLetter d = store.deadLetters.get(id);
            if (d == null) throw Problem.notFound("Dead letter");
            if (!"OPEN".equals(d.state)) throw Problem.conflict("This dead letter is already " + d.state.toLowerCase() + ".");
            d.state = "DISCARDED";
            d.note = note == null || note.isBlank() ? "Discarded by " + actor.name : note;
            store.log(actor.id, "dlq.discard", d.id, d.group + " / " + d.type + " " + d.eventId);
            store.markDirty("deadLetters", "audit");
            return d;
        }
    }

    // ------------------------------------------------------------------ admin controls

    public void pause(String name, boolean paused, String reason) {
        ConsumerGroup g = need(name);
        synchronized (store) {
            g.paused = paused;
            g.pausedReason = paused ? reason : null;
            saveState();
        }
    }

    /** Moves the group's offsets: "earliest" = replay the whole log, "latest" = skip everything waiting. */
    public void reset(String name, String to) {
        ConsumerGroup g = need(name);
        synchronized (store) {
            if ("earliest".equals(to)) g.clear();
            else if ("latest".equals(to)) {
                for (String t : g.topics) for (int p = 0; p < broker.partitions(t); p++) g.commit(t, p, broker.end(t, p));
                g.attempts.clear();
                g.nextTryAt.clear();
            } else throw Problem.bad("Reset to 'earliest' or 'latest'.");
            saveState();
        }
    }

    public ConsumerGroup need(String name) {
        ConsumerGroup g = groups.get(name);
        if (g == null) throw Problem.notFound("Consumer group");
        return g;
    }

    public long lag(ConsumerGroup g) {
        long lag = 0;
        for (String t : g.topics) for (int p = 0; p < broker.partitions(t); p++) lag += Math.max(0, broker.end(t, p) - g.offset(t, p));
        return lag;
    }

    public long totalLag() {
        long n = 0;
        for (ConsumerGroup g : groups.values()) n += lag(g);
        return n;
    }

    /**
     * The signal shown for a group, like a railway signal:
     *   DANGER  (red)    stopped (paused / crashed) or the broker is down
     *   CAUTION (yellow) retrying, running slowly, or more than 25 events behind
     *   CLEAR   (green)  keeping up
     */
    public String aspect(ConsumerGroup g, long now) {
        if (g.paused || broker.isDown()) return "DANGER";
        if (g.retrying() || g.slow(now) || lag(g) > 25) return "CAUTION";
        return "CLEAR";
    }

    public Map<String, Object> view(ConsumerGroup g, long now) {
        List<Object> parts = new ArrayList<>();
        for (String t : g.topics)
            for (int p = 0; p < broker.partitions(t); p++) {
                long end = broker.end(t, p), off = g.offset(t, p);
                Integer tries = g.attempts.get(t + ":" + p);
                Long next = g.nextTryAt.get(t + ":" + p);
                parts.add(Json.obj("topic", t, "partition", p, "offset", off, "end", end, "lag", Math.max(0, end - off),
                        "attempts", tries == null ? 0 : tries, "retryInMs", next == null ? 0 : Math.max(0, next - now)));
            }
        long openDlq;
        synchronized (store) { openDlq = store.deadLetters.values().stream().filter(d -> d.group.equals(g.name) && "OPEN".equals(d.state)).count(); }
        return Json.obj("name", g.name, "service", g.service, "job", g.job, "topics", g.topics, "handles", new ArrayList<>(g.handles),
                "aspect", aspect(g, now), "lag", lag(g), "processed", g.processed, "skipped", g.skipped,
                "duplicates", g.duplicates, "retries", g.retries, "deadLettered", g.deadLettered, "openDeadLetters", openDlq,
                "lostCommits", g.lostCommits, "paused", g.paused, "pausedReason", g.pausedReason,
                "slow", g.slow(now), "lastError", g.lastError, "lastErrorAt", g.lastErrorAt, "lastHandledAt", g.lastHandledAt,
                "partitions", parts);
    }

    /** Copies every group's offsets and counters into the store (saved with the next flush). */
    public void saveState() {
        synchronized (store) {
            for (ConsumerGroup g : groups.values()) store.groups.put(g.name, g.state());
            store.markDirty("groups");
        }
    }

    public long lastTickAt() { return lastTickAt; }
}
