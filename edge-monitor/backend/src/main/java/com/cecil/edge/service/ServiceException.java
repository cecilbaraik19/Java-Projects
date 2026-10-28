package com.cecil.edge.service;

/** A problem the person can fix (bad input, no permission, not found). Shown to them as the error text. */
public class ServiceException extends RuntimeException {
    private static final long serialVersionUID = 1L;
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
