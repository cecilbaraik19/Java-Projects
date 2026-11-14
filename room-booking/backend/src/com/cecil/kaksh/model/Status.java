package com.cecil.kaksh.model;

/**
 * The life of a booking.
 *
 *   PENDING ──approve──► CONFIRMED ──check in──► CHECKED_IN ──end time / "end now"──► COMPLETED
 *      │ decline / not approved in time    │ cancel     │ nobody checks in by start + 15 min
 *      ▼                                   ▼            ▼
 *   DECLINED / EXPIRED                 CANCELLED     RELEASED (a "no-show": the room is given back)
 */
public enum Status {
    PENDING, CONFIRMED, CHECKED_IN, COMPLETED, CANCELLED, DECLINED, RELEASED, EXPIRED;

    /** Does a booking in this state keep the room busy? (PENDING also holds it, so nobody grabs it while the manager decides.) */
    public boolean holdsRoom() {
        return this == PENDING || this == CONFIRMED || this == CHECKED_IN || this == COMPLETED;
    }

    /** Still going to happen or happening now. */
    public boolean open() {
        return this == PENDING || this == CONFIRMED || this == CHECKED_IN;
    }
}
