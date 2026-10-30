package com.cecil.ngo.service;

/** An error with an HTTP status code: 400 bad input, 401 not signed in, 403 not allowed, 404 not found, 409 conflict. */
public class ApiError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;

    public ApiError(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiError bad(String m) { return new ApiError(400, m); }
    public static ApiError forbidden(String m) { return new ApiError(403, m); }
    public static ApiError notFound(String m) { return new ApiError(404, m); }
    public static ApiError conflict(String m) { return new ApiError(409, m); }
}
