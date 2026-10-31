package com.cecil.shop.common;

import java.util.function.LongSupplier;

/**
 * Circuit breaker - stops calling a service that keeps failing.
 *
 *   CLOSED     normal. Calls go through. Count failures in a row.
 *   OPEN       after 3 failures in a row. Calls are refused at once ("fail fast") for 10 seconds,
 *              so we do not wait on a dead service and do not pile more load on it.
 *   HALF_OPEN  after the 10 seconds, ONE trial call is let through.
 *              Success -> CLOSED again. Failure -> OPEN again.
 *
 * Same idea as the fuse box at home: it "trips" to protect the rest of the house.
 */
public class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    public final String name;
    private final int threshold;
    private final long openMs;
    private final LongSupplier clock;

    private State state = State.CLOSED;
    private int failuresInRow;
    private long openedAt;
    private boolean trialRunning;
    private long totalFailures, totalRejected, totalCalls;

    public CircuitBreaker(String name) { this(name, 3, 10_000, System::currentTimeMillis); }

    public CircuitBreaker(String name, int threshold, long openMs, LongSupplier clock) {
        this.name = name;
        this.threshold = threshold;
        this.openMs = openMs;
        this.clock = clock;
    }

    /** Ask before calling. false = do not call, the circuit is open. */
    public synchronized boolean allow() {
        if (state == State.OPEN && clock.getAsLong() - openedAt >= openMs) {
            state = State.HALF_OPEN;
            trialRunning = false;
        }
        if (state == State.OPEN || (state == State.HALF_OPEN && trialRunning)) {
            totalRejected++;
            return false;
        }
        if (state == State.HALF_OPEN) trialRunning = true;
        totalCalls++;
        return true;
    }

    public synchronized void success() {
        failuresInRow = 0;
        trialRunning = false;
        state = State.CLOSED;
    }

    public synchronized void failure() {
        totalFailures++;
        failuresInRow++;
        trialRunning = false;
        if (state == State.HALF_OPEN || failuresInRow >= threshold) {
            state = State.OPEN;
            openedAt = clock.getAsLong();
        }
    }

    public synchronized State state() {
        if (state == State.OPEN && clock.getAsLong() - openedAt >= openMs) return State.HALF_OPEN;
        return state;
    }

    public synchronized java.util.Map<String, Object> info() {
        long wait = state == State.OPEN ? Math.max(0, openMs - (clock.getAsLong() - openedAt)) : 0;
        return Json.obj("service", name, "state", state().name(), "failuresInRow", failuresInRow,
                "retryInMs", wait, "calls", totalCalls, "failures", totalFailures, "rejected", totalRejected,
                "threshold", threshold, "openSeconds", openMs / 1000);
    }

    public synchronized void reset() {
        state = State.CLOSED;
        failuresInRow = 0;
        trialRunning = false;
    }
}
