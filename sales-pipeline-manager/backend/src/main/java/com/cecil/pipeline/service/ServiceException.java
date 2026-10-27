package com.cecil.pipeline.service;

/** An error with an HTTP status code and a message that is safe to show to the user. */
public class ServiceException extends RuntimeException {

    public final int status;

    public ServiceException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ServiceException bad(String msg) { return new ServiceException(400, msg); }
    public static ServiceException forbidden(String msg) { return new ServiceException(403, msg); }
    public static ServiceException notFound(String what) { return new ServiceException(404, what + " not found"); }
    public static ServiceException conflict(String msg) { return new ServiceException(409, msg); }
}
