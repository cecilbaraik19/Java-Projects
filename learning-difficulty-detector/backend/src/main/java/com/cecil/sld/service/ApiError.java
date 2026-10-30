package com.cecil.sld.service;

/** An error with an HTTP status and a message that is safe to show to the user. */
public class ApiError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;

    public ApiError(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiError bad(String msg) { return new ApiError(400, msg); }
    public static ApiError forbidden() { return new ApiError(403, "You are not allowed to do this."); }
    public static ApiError notFound(String what) { return new ApiError(404, what + " not found."); }
    public static ApiError conflict(String msg) { return new ApiError(409, msg); }
}
