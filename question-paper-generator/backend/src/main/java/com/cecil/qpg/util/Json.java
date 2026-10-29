package com.cecil.qpg.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer (no outside library needed).
 * Objects become LinkedHashMap, arrays become ArrayList, numbers become Long or Double.
 */
public final class Json {
    private Json() {}

    // ------------------------------------------------------------------ writing

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, -1, 0);
        return sb.toString();
    }

    /** Same as write() but with line breaks and two-space indents (used for the data files). */
    public static String pretty(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 2, 0);
        return sb.toString();
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent < 0) return;
        sb.append('\n');
        for (int i = 0; i < indent * level; i++) sb.append(' ');
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v, int indent, int level) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { quote(sb, s); return; }
        if (v instanceof Boolean b) { sb.append(b); return; }
        if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) { sb.append("null"); return; }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.longValue());
            else sb.append(d);
            return;
        }
        if (v instanceof Float f) { write(sb, f.doubleValue(), indent, level); return; }
        if (v instanceof Number n) { sb.append(n); return; }
        if (v instanceof Enum<?> e) { quote(sb, e.name()); return; }
        if (v instanceof JsonAble j) { write(sb, j.toJson(), indent, level); return; }
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, level + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(indent < 0 ? ":" : ": ");
                write(sb, e.getValue(), indent, level + 1);
            }
            newline(sb, indent, level);
            sb.append('}');
            return;
        }
        if (v instanceof Collection<?> c) {
            if (c.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, level + 1);
                write(sb, o, indent, level + 1);
            }
            newline(sb, indent, level);
            sb.append(']');
            return;
        }
        if (v instanceof Object[] arr) { write(sb, List.of(arr), indent, level); return; }
        quote(sb, v.toString());
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
                case '<' -> sb.append("\\u003c"); // safe to place inside HTML
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
        if (text == null) throw new IllegalArgumentException("Empty JSON");
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) throw p.err("Extra text after JSON");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        IllegalArgumentException err(String m) { return new IllegalArgumentException(m + " at position " + i); }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value(int depth) {
            if (depth > 64) throw err("JSON is nested too deeply");
            if (i >= s.length()) throw err("Unexpected end of JSON");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw err("Unexpected character '" + c + "'");
                }
            };
        }

        Object word(String w, Object v) {
            if (!s.startsWith(w, i)) throw err("Bad value");
            i += w.length();
            return v;
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw err("Expected a key");
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("Expected ':'");
                i++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                if (i >= s.length()) throw err("Unclosed object");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err("Expected ',' or '}'");
            }
        }

        List<Object> array(int depth) {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value(depth + 1));
                ws();
                if (i >= s.length()) throw err("Unclosed array");
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') throw err("Expected ',' or ']'");
            }
        }

        String string() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("Unclosed string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw err("Bad escape");
                    char e = s.charAt(i++);
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
                            if (i + 4 > s.length()) throw err("Bad unicode escape");
                            try { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                            catch (NumberFormatException ex) { throw err("Bad unicode escape"); }
                            i += 4;
                        }
                        default -> throw err("Bad escape");
                    }
                } else {
                    if (c < 0x20) throw err("Control character in string");
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = i;
            if (s.charAt(i) == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            boolean dbl = false;
            if (i < s.length() && s.charAt(i) == '.') { dbl = true; i++; while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                dbl = true; i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String t = s.substring(start, i);
            if (t.equals("-") || t.isEmpty()) throw err("Bad number");
            try {
                if (!dbl) {
                    if (t.length() < 18) return Long.parseLong(t);
                    return Double.parseDouble(t);
                }
                return Double.parseDouble(t);
            } catch (NumberFormatException ex) { throw err("Bad number"); }
        }
    }

    // ------------------------------------------------------------------ safe getters

    public static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    public static String str(Map<String, Object> m, String k, String def) {
        String v = str(m, k);
        return v == null ? def : v;
    }

    public static Integer intOrNull(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        if (v == null) return null;
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d != Math.rint(d)) throw new IllegalArgumentException("'" + k + "' must be a whole number");
            if (Math.abs(d) > Integer.MAX_VALUE) throw new IllegalArgumentException("'" + k + "' is too large");
            return (int) d;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("'" + k + "' must be a whole number"); }
    }

    public static int integer(Map<String, Object> m, String k, int def) {
        Integer v = intOrNull(m, k);
        return v == null ? def : v;
    }

    public static Long longOrNull(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("'" + k + "' must be a number"); }
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m == null ? null : m.get(k);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof List ? (List<Object>) v : null;
    }

    public static List<String> strings(Map<String, Object> m, String k) {
        List<Object> l = list(m, k);
        List<String> out = new ArrayList<>();
        if (l != null) for (Object o : l) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    /** Helper to build a map in one line: Json.map("a", 1, "b", "x"). */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Anything that can turn itself into a JSON map. */
    public interface JsonAble {
        Object toJson();
    }
}
