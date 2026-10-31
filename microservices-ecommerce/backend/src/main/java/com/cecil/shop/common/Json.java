package com.cecil.shop.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer (no external library needed).
 * JSON objects become LinkedHashMap, arrays become ArrayList,
 * whole numbers become Long, decimals become Double.
 * All services talk to each other in JSON over HTTP, so this class is used everywhere.
 */
public final class Json {
    private Json() {}

    // ------------------------------------------------------------------ writing

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v instanceof Object[] arr) {
            write(sb, List.of(arr));
        } else if (v instanceof Enum<?> e) {
            quote(sb, e.name());
        } else {
            quote(sb, v.toString());
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
                case '<' -> sb.append("\\u003c"); // safe to embed anywhere
                case '>' -> sb.append("\\u003e");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ reading

    public static Object parse(String text) {
        if (text == null || text.isBlank()) return null;
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) throw new IllegalArgumentException("Extra text after JSON at position " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (v == null) return new LinkedHashMap<>();
        if (!(v instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value(int depth) {
            if (depth > 64) throw new IllegalArgumentException("JSON nested too deeply");
            if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = s.charAt(i);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw new IllegalArgumentException("Bad JSON at position " + i);
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw new IllegalArgumentException("Expected a name at position " + i);
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value(depth + 1));
                ws();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("Expected , or } at position " + (i - 1));
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                char c = next();
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("Expected , or ] at position " + (i - 1));
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("Unclosed string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw new IllegalArgumentException("Bad \\u escape");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("Bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            boolean dec = false;
            if (i < s.length() && s.charAt(i) == '.') { dec = true; i++; while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                dec = true; i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String t = s.substring(start, i);
            if (t.equals("-") || t.isEmpty()) throw new IllegalArgumentException("Bad number at position " + start);
            if (!dec) {
                try { return Long.parseLong(t); } catch (NumberFormatException ignored) { return Double.parseDouble(t); }
            }
            return Double.parseDouble(t);
        }

        char peek() { if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON"); return s.charAt(i); }
        char next() { char c = peek(); i++; return c; }
        void expect(char c) { if (next() != c) throw new IllegalArgumentException("Expected '" + c + "' at position " + (i - 1)); }
    }

    // ------------------------------------------------------------------ helpers for Map documents

    /** Builds an ordered map: Json.obj("a", 1, "b", 2). */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put(String.valueOf(kv[k]), kv[k + 1]);
        return m;
    }

    public static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    public static String str(Map<String, Object> m, String k, String def) {
        String v = str(m, k);
        return v == null ? def : v;
    }

    public static long lng(Map<String, Object> m, String k) { return lng(m, k, 0); }

    public static long lng(Map<String, Object> m, String k, long def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) {
                try { return (long) Double.parseDouble(s.trim()); } catch (NumberFormatException e2) { return def; }
            }
        }
        return def;
    }

    public static double dbl(Map<String, Object> m, String k, double def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return s.equalsIgnoreCase("true");
        return def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> m, String k) {
        return map(m == null ? null : m.get(k));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object v) {
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    public static List<Object> list(Map<String, Object> m, String k) {
        return list(m == null ? null : m.get(k));
    }

    /** Deep copy, so a caller cannot change a stored document by accident. */
    public static Map<String, Object> copy(Map<String, Object> m) {
        return parseObject(write(m));
    }
}
