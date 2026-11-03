package com.cecil.mail.util;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, so the project needs no external library.
 *
 *  Json.write(object)  -> text.   Handles Map, List, arrays, String, numbers, true/false, null, enums and records.
 *  Json.parse(text)    -> Map / List / String / Long / Double / Boolean / null.
 */
public final class Json {

    private Json() { }

    // ------------------------------------------------------------------ writing

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(o, sb);
        return sb.toString();
    }

    private static void write(Object o, StringBuilder sb) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String s) {
            string(s, sb);
        } else if (o instanceof Double d) {
            sb.append(d.isNaN() || d.isInfinite() ? "0" : trimDouble(d));
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Enum<?> e) {
            string(e.name(), sb);
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                string(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(',');
                first = false;
                write(x, sb);
            }
            sb.append(']');
        } else if (o.getClass().isArray()) {           // int[], int[][], String[] ...
            sb.append('[');
            int n = java.lang.reflect.Array.getLength(o);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                write(java.lang.reflect.Array.get(o, i), sb);
            }
            sb.append(']');
        } else if (o.getClass().isRecord()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (RecordComponent rc : o.getClass().getRecordComponents()) {
                try {
                    rc.getAccessor().setAccessible(true);
                    m.put(rc.getName(), rc.getAccessor().invoke(o));
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
            write(m, sb);
        } else if (o instanceof JsonWritable w) {
            write(w.toJson(), sb);
        } else {
            string(o.toString(), sb);
        }
    }

    private static String trimDouble(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<' -> sb.append("\\u003c");   // safe even if JSON ends up inside HTML
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** Objects that know how to turn themselves into a Map for JSON. */
    public interface JsonWritable {
        Map<String, Object> toJson();
    }

    // ------------------------------------------------------------------ reading

    public static Object parse(String text) {
        Parser p = new Parser(text == null ? "" : text);
        p.skipSpace();
        Object v = p.value(0);
        p.skipSpace();
        if (p.i != p.s.length()) throw new IllegalArgumentException("Extra text after JSON at position " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseList(String text) {
        if (text == null || text.isBlank()) return new ArrayList<>();
        Object v = parse(text);
        if (!(v instanceof List)) throw new IllegalArgumentException("Expected a JSON array");
        return (List<Object>) v;
    }

    // small helpers to read typed values out of a parsed object
    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public static long num(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return dflt;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean dflt) {
        Object v = m.get(key);
        return v instanceof Boolean b ? b : dflt;
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 64;
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        void skipSpace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nested too deeply");
            skipSpace();
            if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = s.charAt(i);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            skipSpace();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipSpace();
                if (peek() != '"') throw new IllegalArgumentException("Expected a key at position " + i);
                String key = string();
                skipSpace();
                expect(':');
                m.put(key, value(depth + 1));
                skipSpace();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("Expected , or } at position " + (i - 1));
            }
        }

        List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            i++;
            skipSpace();
            if (peek() == ']') { i++; return list; }
            while (true) {
                list.add(value(depth + 1));
                skipSpace();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw new IllegalArgumentException("Expected , or ] at position " + (i - 1));
            }
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = next();
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw new IllegalArgumentException("Bad \\u escape");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e);
                }
            }
        }

        Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (n.isEmpty()) throw new IllegalArgumentException("Unexpected character at position " + start);
            try {
                if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
                return Long.parseLong(n);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Bad number: " + n);
            }
        }

        char peek() {
            if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            return s.charAt(i);
        }

        char next() {
            char c = peek();
            i++;
            return c;
        }

        void expect(char c) {
            if (next() != c) throw new IllegalArgumentException("Expected '" + c + "' at position " + (i - 1));
        }
    }
}
