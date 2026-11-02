package com.cecil.rag.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, so the project needs no external library.
 * JSON objects become LinkedHashMap, arrays become ArrayList, numbers become Long or Double.
 */
public final class Json {
    private Json() {}

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, -1, 0);
        return sb.toString();
    }

    /** Pretty output with 2-space indent (used for the files in the data folder). */
    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 2, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v, int indent, int level) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { quote(sb, s); return; }
        if (v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) sb.append("0");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.longValue());
            else sb.append(d);
            return;
        }
        if (v instanceof Float f) { write(sb, f.doubleValue(), indent, level); return; }
        if (v instanceof Number) { sb.append(v); return; }
        if (v instanceof Enum<?> e) { quote(sb, e.name()); return; }
        if (v instanceof JsonAware ja) { write(sb, ja.toJson(), indent, level); return; }
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, level + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(indent > 0 ? ": " : ":");
                write(sb, e.getValue(), indent, level + 1);
            }
            newline(sb, indent, level);
            sb.append('}');
            return;
        }
        if (v instanceof Iterable<?> it) {
            Iterator<?> i = it.iterator();
            if (!i.hasNext()) { sb.append("[]"); return; }
            sb.append('[');
            boolean first = true;
            while (i.hasNext()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, level + 1);
                write(sb, i.next(), indent, level + 1);
            }
            newline(sb, indent, level);
            sb.append(']');
            return;
        }
        if (v instanceof double[] arr) {
            List<Object> l = new ArrayList<>();
            for (double d : arr) l.add(d);
            write(sb, l, indent, level);
            return;
        }
        if (v instanceof Object[] arr) { write(sb, List.of(arr), indent, level); return; }
        quote(sb, v.toString());
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent <= 0) return;
        sb.append('\n');
        for (int i = 0; i < indent * level; i++) sb.append(' ');
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
                case '<' -> sb.append("\\u003c"); // safe to place inside HTML
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
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
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw new IllegalArgumentException("Extra text after JSON at " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) o;
    }

    private static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value(int depth) {
            if (depth > 200) throw new IllegalArgumentException("JSON nested too deep");
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
            i++; ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw new IllegalArgumentException("Expected key at " + i);
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value(depth + 1));
                ws();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("Expected , or } at " + i);
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                char c = next();
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("Expected , or ] at " + i);
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
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> sb.append(e);
                    }
                } else sb.append(c);
            }
        }

        Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (n.isEmpty()) throw new IllegalArgumentException("Unexpected character at " + start);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            try { return Long.parseLong(n); } catch (NumberFormatException e) { return Double.parseDouble(n); }
        }

        char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
        char next() { if (i >= s.length()) throw new IllegalArgumentException("Unexpected end"); return s.charAt(i++); }
        void expect(char c) { if (next() != c) throw new IllegalArgumentException("Expected " + c + " at " + (i - 1)); }
    }

    // ------------------------------------------------------------------ helpers for reading maps

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m == null ? null : m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    public static long num(Map<String, Object> m, String k, long def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
        return def;
    }

    public static double dbl(Map<String, Object> m, String k, double def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        return def;
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return s.equalsIgnoreCase("true");
        return def;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    /** Builds a map quickly: Json.map("a", 1, "b", "x"). */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Things that know how to turn themselves into JSON-ready maps. */
    public interface JsonAware {
        Map<String, Object> toJson();
    }
}
