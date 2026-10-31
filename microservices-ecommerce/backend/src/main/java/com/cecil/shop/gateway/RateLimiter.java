package com.cecil.shop.gateway;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Rate limiting with a "token bucket".
 * Each visitor (IP address) has a bucket of N tokens. Every request takes one token.
 * Tokens slowly refill (N per minute). Empty bucket -> "429 Too Many Requests".
 * This protects all the services behind the gateway from a flood of requests.
 */
public class RateLimiter {
    private final int perMinute;
    private final LongSupplier clock;
    private final Map<String, double[]> buckets = new HashMap<>(); // key -> {tokens, lastRefillMs}

    public RateLimiter(int perMinute) { this(perMinute, System::currentTimeMillis); }

    public RateLimiter(int perMinute, LongSupplier clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    public synchronized boolean allow(String key) {
        long now = clock.getAsLong();
        if (buckets.size() > 10_000) buckets.clear();
        double[] b = buckets.computeIfAbsent(key, k -> new double[]{perMinute, now});
        double refill = (now - b[1]) * perMinute / 60_000.0;
        b[0] = Math.min(perMinute, b[0] + refill);
        b[1] = now;
        if (b[0] < 1) return false;
        b[0] -= 1;
        return true;
    }
}
