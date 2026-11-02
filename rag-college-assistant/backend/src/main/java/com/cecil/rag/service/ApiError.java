package com.cecil.rag.service;

/** An error with an HTTP status code and a message that is safe to show to the user. */
public class ApiError extends RuntimeException {
    public final int status;

    public ApiError(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiError bad(String msg) { return new ApiError(400, msg); }
    public static ApiError forbidden() { return new ApiError(403, "You do not have permission to do this."); }
    public static ApiError notFound(String what) { return new ApiError(404, what + " not found."); }
}
