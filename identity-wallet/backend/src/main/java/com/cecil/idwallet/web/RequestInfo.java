package com.cecil.idwallet.web;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestInfo {
    private RequestInfo() {}

    public static String ip(HttpServletRequest r) {
        String f = r.getHeader("X-Forwarded-For");
        String ip = (f != null && !f.isBlank()) ? f.split(",")[0].trim() : r.getRemoteAddr();
        return ip.length() > 64 ? ip.substring(0, 64) : ip;
    }

    public static String userAgent(HttpServletRequest r) {
        String ua = r.getHeader("User-Agent");
        if (ua == null) return "unknown";
        return ua.length() > 300 ? ua.substring(0, 300) : ua;
    }
}
