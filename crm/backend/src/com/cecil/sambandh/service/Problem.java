package com.cecil.sambandh.service;

/**
 * A mistake the person can fix (wrong input, not allowed, not found).
 * The web layer turns it into an HTTP error with a friendly message.
 */
public class Problem extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;
    /** Optional extra details for the browser (for example the possible duplicates). */
    public transient Object data;

    public Problem(int status, String message) {
        super(message);
        this.status = status;
    }

    public Problem with(Object data) {
        this.data = data;
        return this;
    }

    public static Problem bad(String message) { return new Problem(400, message); }
    public static Problem forbidden(String message) { return new Problem(403, message); }
    public static Problem notFound(String what) { return new Problem(404, what + " not found."); }
    public static Problem conflict(String message) { return new Problem(409, message); }
}
