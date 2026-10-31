package com.cecil.shop.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** A response with a custom status code or extra headers. Handlers can also just return a Map or List (status 200). */
public class Resp {
    public final int status;
    public final Object body;          // Map/List (sent as JSON) or byte[] (sent as-is)
    public String contentType = "application/json; charset=utf-8";
    public final Map<String, String> headers = new LinkedHashMap<>();

    public Resp(int status, Object body) {
        this.status = status;
        this.body = body;
    }

    public static Resp created(Object body) { return new Resp(201, body); }

    public Resp header(String k, String v) { headers.put(k, v); return this; }
}
