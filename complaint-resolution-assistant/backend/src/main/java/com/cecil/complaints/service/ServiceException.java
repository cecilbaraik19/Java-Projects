package com.cecil.complaints.service;

/**
 * An error that should be shown to the user, with the HTTP status code to send.
 */
public class ServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public ServiceException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static ServiceException badRequest(String msg) {
        return new ServiceException(400, msg);
    }

    public static ServiceException notFound(String msg) {
        return new ServiceException(404, msg);
    }

    public static ServiceException forbidden(String msg) {
        return new ServiceException(403, msg);
    }

    public static ServiceException unauthorized(String msg) {
        return new ServiceException(401, msg);
    }
}
