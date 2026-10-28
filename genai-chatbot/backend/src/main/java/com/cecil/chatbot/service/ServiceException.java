package com.cecil.chatbot.service;

/** An error with an HTTP status code and a message that is safe to show to the user. */
public class ServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public final int status;

    public ServiceException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ServiceException bad(String msg) { return new ServiceException(400, msg); }
    public static ServiceException unauthorized(String msg) { return new ServiceException(401, msg); }
    public static ServiceException forbidden(String msg) { return new ServiceException(403, msg); }
    public static ServiceException notFound(String msg) { return new ServiceException(404, msg); }
    public static ServiceException conflict(String msg) { return new ServiceException(409, msg); }
    public static ServiceException tooMany(String msg) { return new ServiceException(429, msg); }
}
