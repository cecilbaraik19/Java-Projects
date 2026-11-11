package com.cecil.sanket.service;

import com.cecil.sanket.broker.BrokerDown;
import com.cecil.sanket.broker.EventBroker;
import com.cecil.sanket.model.Event;
import com.cecil.sanket.model.OutboxEntry;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.util.Clock;

import java.util.ArrayList;
import java.util.List;

/**
 * The outbox relay (part of the "transactional outbox" pattern).
 *
 * Commands such as "place order" never talk to the broker directly. They save their change AND an outbox row
 * in one step. This relay runs every 200 ms, takes the unsent rows in order, publishes each one and marks it sent.
 *
 * Why? If the order service wrote the order and then crashed (or the broker was down) before publishing,
 * the rest of the system would never hear about the order. With the outbox the row waits until the broker is
 * back. The customer can still place orders while the broker is down.
 *
 * (If the relay crashed right after publishing but before marking the row sent, the row would be sent twice.
 * That is why every consumer is idempotent.)
 */
public class OutboxRelay {
    private final DataStore store;
    private final EventBroker broker;
    public volatile long published;

    public OutboxRelay(DataStore store, EventBroker broker) {
        this.store = store;
        this.broker = broker;
    }

    /** Publishes waiting rows (oldest first). Stops at the first failure so the order is kept. Returns how many were sent. */
    public int tick() {
        synchronized (store) {
            int n = 0;
            for (OutboxEntry e : store.outbox.values()) {
                if (e.sent()) continue;
                try {
                    Event ev = broker.publish(e.topic, e.type, e.version, e.key, e.correlationId, null, e.source, e.payload);
                    e.sentAt = Clock.millis();
                    e.eventId = ev.id;
                    e.attempts++;
                    e.lastError = null;
                    n++;
                } catch (BrokerDown down) {
                    e.attempts++;
                    e.lastError = down.getMessage();
                    store.markDirty("outbox");
                    break;
                }
            }
            if (n > 0) { published += n; store.markDirty("outbox"); }
            return n;
        }
    }

    public long pending() {
        synchronized (store) { return store.outbox.values().stream().filter(e -> !e.sent()).count(); }
    }

    public List<OutboxEntry> rows(boolean pendingOnly, int limit) {
        synchronized (store) {
            List<OutboxEntry> all = new ArrayList<>(store.outbox.values());
            List<OutboxEntry> out = new ArrayList<>();
            for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
                OutboxEntry e = all.get(i);
                if (pendingOnly && e.sent()) continue;
                out.add(e);
            }
            return out;
        }
    }
}
