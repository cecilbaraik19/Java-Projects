package com.cecil.chatbot.safety;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * "Sliding window" rate limit: each person may send at most N messages in any 60 seconds.
 * Stops one person (or a script) from using up the AI quota for everyone.
 */
public class RateLimiter {

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final LongSupplier clockMs;

    public RateLimiter(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    /** Records a message. Returns 0 if allowed, or the seconds to wait. */
    public long tryAcquire(String user, int perMinute) {
        if (perMinute <= 0) return 0;
        long now = clockMs.getAsLong();
        Deque<Long> q = hits.computeIfAbsent(user, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() <= now - 60_000) q.pollFirst();
            if (q.size() >= perMinute) {
                return Math.max(1, (q.peekFirst() + 60_000 - now + 999) / 1000);
            }
            q.addLast(now);
            return 0;
        }
    }
}
