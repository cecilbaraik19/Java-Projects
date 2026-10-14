package com.cecil.codereview.report;

/** Tiny JSON helpers (no external library needed). */
public final class Json {
    private Json() { }

    /** Returns the text as a quoted JSON string. */
    public static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Collects the values of every "field":"..." string in a JSON text, in order.
     * Good enough for reading simple API responses.
     */
    public static java.util.List<String> stringValues(String json, String field) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String key = "\"" + field + "\"";
        int idx = 0;
        while ((idx = json.indexOf(key, idx)) >= 0) {
            int i = idx + key.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length() || json.charAt(i) != ':') { idx = i; continue; }
            i++;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length() || json.charAt(i) != '"') { idx = i; continue; }
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '"') break;
                if (c == '\\' && i + 1 < json.length()) {
                    char n = json.charAt(++i);
                    switch (n) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (i + 4 < json.length()) {
                                sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                i += 4;
                            }
                            break;
                        default: sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
                i++;
            }
            out.add(sb.toString());
            idx = i;
        }
        return out;
    }
}
