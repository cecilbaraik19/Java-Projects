package com.cecil.codereview.server;

import com.cecil.codereview.report.Json;

import java.util.Locale;

/** Small helper to write a JSON object or array step by step. */
public final class JsonBuilder {
    private final StringBuilder sb = new StringBuilder();
    private boolean needComma;

    public JsonBuilder beginObject() { comma(); sb.append('{'); needComma = false; return this; }
    public JsonBuilder endObject() { sb.append('}'); needComma = true; return this; }
    public JsonBuilder beginArray() { comma(); sb.append('['); needComma = false; return this; }
    public JsonBuilder endArray() { sb.append(']'); needComma = true; return this; }

    /** Writes "name": and expects a value next. */
    public JsonBuilder key(String name) {
        comma();
        sb.append(Json.str(name)).append(':');
        needComma = false;
        return this;
    }

    public JsonBuilder value(String v) { comma(); sb.append(Json.str(v)); needComma = true; return this; }
    public JsonBuilder value(boolean v) { comma(); sb.append(v); needComma = true; return this; }
    public JsonBuilder value(long v) { comma(); sb.append(v); needComma = true; return this; }

    public JsonBuilder value(double v) {
        comma();
        sb.append(String.format(Locale.ROOT, "%.1f", v));
        needComma = true;
        return this;
    }

    /** Inserts JSON text that is already complete (for example a whole report). */
    public JsonBuilder raw(String json) { comma(); sb.append(json.trim()); needComma = true; return this; }

    public JsonBuilder field(String k, String v) { return key(k).value(v); }
    public JsonBuilder field(String k, boolean v) { return key(k).value(v); }
    public JsonBuilder field(String k, long v) { return key(k).value(v); }
    public JsonBuilder field(String k, double v) { return key(k).value(v); }

    private void comma() {
        if (needComma) sb.append(',');
    }

    @Override
    public String toString() { return sb.toString(); }
}
