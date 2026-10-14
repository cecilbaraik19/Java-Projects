package com.cecil.complaints.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, dependency-free JSON parser and writer.
 *
 * Parsing returns: Map (LinkedHashMap), List, String, Long, Double, Boolean or null.
 * Writing accepts: Map, Collection, arrays of Object, String, Number, Boolean, Enum, null.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ writing

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, false, 0);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, true, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean pretty, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Enum<?> e) {
            quote(sb, e.name());
        } else if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append(d.longValue());
            } else {
                sb.append(d);
            }
        } else if (v instanceof Float f) {
            write(sb, f.doubleValue(), pretty, indent);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> en : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, pretty, indent + 1);
                quote(sb, String.valueOf(en.getKey()));
                sb.append(pretty ? ": " : ":");
                write(sb, en.getValue(), pretty, indent + 1);
            }
            if (!map.isEmpty()) {
                newline(sb, pretty, indent);
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, pretty, indent + 1);
                write(sb, o, pretty, indent + 1);
            }
            if (!c.isEmpty()) {
                newline(sb, pretty, indent);
            }
            sb.append(']');
        } else if (v instanceof Object[] arr) {
            write(sb, List.of(arr), pretty, indent);
        } else {
            quote(sb, v.toString());
        }
    }

    private static void newline(StringBuilder sb, boolean pretty, int indent) {
        if (pretty) {
            sb.append('\n');
            sb.append("  ".repeat(indent));
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&') {
                        // escape control chars and HTML-sensitive chars for safety
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ parsing

    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("Empty JSON");
        }
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) {
            throw new JsonException("Unexpected trailing characters at " + p.pos);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) {
            throw new JsonException("Expected a JSON object");
        }
        return (Map<String, Object>) o;
    }

    public static class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String msg) {
            super(msg);
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;
        private int depth;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw new JsonException("Unexpected end of JSON");
            }
            return s.charAt(pos);
        }

        Object value() {
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw new JsonException("Unexpected character '" + c + "' at " + pos);
                }
            };
        }

        Object literal(String word, Object val) {
            if (!s.startsWith(word, pos)) {
                throw new JsonException("Invalid literal at " + pos);
            }
            pos += word.length();
            return val;
        }

        Map<String, Object> object() {
            if (++depth > 64) {
                throw new JsonException("JSON nested too deeply");
            }
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') {
                pos++;
                depth--;
                return map;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw new JsonException("Expected key at " + pos);
                }
                String key = string();
                skipWs();
                if (peek() != ':') {
                    throw new JsonException("Expected ':' at " + pos);
                }
                pos++;
                skipWs();
                map.put(key, value());
                skipWs();
                char c = peek();
                pos++;
                if (c == '}') {
                    break;
                }
                if (c != ',') {
                    throw new JsonException("Expected ',' or '}' at " + (pos - 1));
                }
            }
            depth--;
            return map;
        }

        List<Object> array() {
            if (++depth > 64) {
                throw new JsonException("JSON nested too deeply");
            }
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') {
                pos++;
                depth--;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value());
                skipWs();
                char c = peek();
                pos++;
                if (c == ']') {
                    break;
                }
                if (c != ',') {
                    throw new JsonException("Expected ',' or ']' at " + (pos - 1));
                }
            }
            depth--;
            return list;
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new JsonException("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new JsonException("Bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new JsonException("Bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean isDouble = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isDouble = true;
                    pos++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, pos);
            try {
                return isDouble ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
            } catch (NumberFormatException ex) {
                throw new JsonException("Bad number '" + num + "'");
            }
        }
    }

    // ------------------------------------------------------------------ helpers for reading maps

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static String str(Map<String, Object> m, String key, String def) {
        String v = str(m, key);
        return v == null ? def : v;
    }

    public static double num(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static long lng(Map<String, Object> m, String key, long def) {
        return (long) num(m, key, def);
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    public static List<String> strList(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : list(m, key)) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mapList(Map<String, Object> m, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list(m, key)) {
            if (o instanceof Map) {
                out.add((Map<String, Object>) o);
            }
        }
        return out;
    }
}
