package com.cecil.shop.common;

/** An error with an HTTP status code. Thrown by any service; turned into {"error": "..."} JSON. */
public class ApiError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;

    public ApiError(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiError bad(String msg) { return new ApiError(400, msg); }
    public static ApiError notFound(String msg) { return new ApiError(404, msg); }
    public static ApiError forbidden(String msg) { return new ApiError(403, msg); }
    public static ApiError conflict(String msg) { return new ApiError(409, msg); }
    public static ApiError unavailable(String msg) { return new ApiError(503, msg); }
}
