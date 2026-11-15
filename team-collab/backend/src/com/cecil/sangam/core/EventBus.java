package com.cecil.sangam.core;

import com.cecil.sangam.util.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Live updates without WebSockets: "long polling".
 *
 * Every change (new message, task moved, document saved ...) becomes an Event with a number that only goes up.
 * The browser asks "anything after number 1234?". If there is, it gets it at once. If not, the request WAITS
 * (up to about 20 seconds) until something happens – so new messages appear in about the time the network takes,
 * not after a polling delay. Then the browser asks again.
 *
 * Only the newest 2,000 events are kept (a ring buffer). A browser that was away longer gets "reset" and simply
 * reloads its page.
 */
public final class EventBus {
    public static final int KEEP = 2000;

    /** Who may see the event: listed people, or members of a channel, or members of a project (or everybody). */
    public record Event(long seq, long at, String type, List<String> userIds, String channelId, String projectId,
                        Map<String, Object> data) implements Json.Writable {
        @Override
        public Map<String, Object> toJson() {
            return Json.obj("seq", seq, "at", at, "type", type, "channelId", channelId, "projectId", projectId, "data", data);
        }
    }

    public record Batch(long seq, boolean reset, List<Event> events) { }

    private final Deque<Event> ring = new ArrayDeque<>();
    private long seq;

    public synchronized long seq() { return seq; }

    public synchronized Event publish(String type, List<String> userIds, String channelId, String projectId, Map<String, Object> data) {
        Event e = new Event(++seq, System.currentTimeMillis(), type, userIds, channelId, projectId, data);
        ring.addLast(e);
        while (ring.size() > KEEP) ring.removeFirst();
        notifyAll();   // wake every waiting browser request
        return e;
    }

    /**
     * Events after `since` that this person may see. Waits up to waitMs for the first one.
     * since < 0 means "I just opened the page – tell me the current number only".
     *
     * The visibility check runs OUTSIDE this object's lock: it looks at the data store, and the services lock the
     * store first and then publish here. Taking the two locks in opposite orders could freeze both (a deadlock).
     */
    public Batch await(long since, long waitMs, Predicate<Event> visible) throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            List<Event> fresh = new ArrayList<>();
            long now;
            synchronized (this) {
                if (since < 0 || since > seq) return new Batch(seq, false, List.of());
                if (!ring.isEmpty() && since < ring.peekFirst().seq() - 1) return new Batch(seq, true, List.of());
                if (since == seq) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) return new Batch(seq, false, List.of());
                    wait(left);
                }
                for (Event e : ring) if (e.seq() > since) fresh.add(e);
                now = seq;
            }
            List<Event> out = new ArrayList<>();
            for (Event e : fresh) if (visible.test(e)) out.add(e);
            if (!out.isEmpty()) return new Batch(now, false, out);
            since = Math.max(since, now);     // nothing for this person yet – keep waiting from here
            if (System.currentTimeMillis() >= deadline) return new Batch(since, false, List.of());
        }
    }
}
