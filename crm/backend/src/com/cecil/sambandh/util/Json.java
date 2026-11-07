package com.cecil.sambandh.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, written with the JDK only (no external library).
 *
 * Reading gives back plain Java objects:
 *   object -> LinkedHashMap, array -> ArrayList, text -> String,
 *   number -> Long (whole numbers) or Double, true/false -> Boolean, null -> null.
 * Writing accepts the same kinds of objects (plus any Number, enum and JsonWritable).
 */
public final class Json {

    /** Any object that knows how to turn itself into a Map for JSON. */
    public interface Writable {
        Map<String, Object> toJson();
    }

    private Json() { }

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, -1, 0);
        return sb.toString();
    }

    /** Same as write, but with new lines and 2-space indents (used for the data files, easy to read in Notepad). */
    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 2, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int indent, int level) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Writable w) { write(sb, w.toJson(), indent, level); return; }
        if (v instanceof String s) { quote(sb, s); return; }
        if (v instanceof Enum<?> e) { quote(sb, e.name()); return; }
        if (v instanceof Boolean b) { sb.append(b); return; }
        if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) { sb.append("null"); return; }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) { sb.append(d.longValue()); return; }
            sb.append(d); return;
        }
        if (v instanceof Float f) { write(sb, f.doubleValue(), indent, level); return; }
        if (v instanceof Number n) { sb.append(n.longValue()); return; }
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, level + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(indent >= 0 ? ": " : ":");
                write(sb, e.getValue(), indent, level + 1);
            }
            newline(sb, indent, level);
            sb.append('}');
            return;
        }
        if (v instanceof Iterable<?> it) {
            List<Object> items = new ArrayList<>();
            it.forEach(items::add);
            if (items.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                newline(sb, indent, level + 1);
                write(sb, items.get(i), indent, level + 1);
            }
            newline(sb, indent, level);
            sb.append(']');
            return;
        }
        if (v instanceof Object[] arr) { write(sb, List.of(arr), indent, level); return; }
        quote(sb, v.toString());
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent < 0) return;
        sb.append('\n');
        sb.append(" ".repeat(indent * level));
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
                case '<' -> sb.append("\\u003c");   // safe even if the JSON ends up inside HTML
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
                case ' ' -> sb.append("\\u2028");
                case ' ' -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ reading

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("No JSON given");
        Parser p = new Parser(text);
        p.skipSpace();
        Object v = p.value(0);
        p.skipSpace();
        if (p.pos != text.length()) throw p.error("Extra text after the JSON value");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 64;
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " (at character " + pos + ")");
        }

        void skipSpace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                else break;
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw error("JSON is nested too deeply");
            if (pos >= s.length()) throw error("Unexpected end of JSON");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        Object word(String w, Object result) {
            if (!s.startsWith(w, pos)) throw error("Expected " + w);
            pos += w.length();
            return result;
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipSpace();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipSpace();
                if (pos >= s.length() || s.charAt(pos) != '"') throw error("Expected a name in quotes");
                String key = string();
                skipSpace();
                if (pos >= s.length() || s.charAt(pos) != ':') throw error("Expected ':'");
                pos++;
                skipSpace();
                m.put(key, value(depth + 1));
                skipSpace();
                if (pos >= s.length()) throw error("Unexpected end of JSON");
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw error("Expected ',' or '}'");
            }
        }

        List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipSpace();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                skipSpace();
                list.add(value(depth + 1));
                skipSpace();
                if (pos >= s.length()) throw error("Unexpected end of JSON");
                char c = s.charAt(pos++);
                if (c == ']') return list;
                if (c != ',') throw error("Expected ',' or ']'");
            }
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw error("Text is missing its closing quote");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw error("Bad escape");
                    char e = s.charAt(pos++);
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
                            if (pos + 4 > s.length()) throw error("Bad \\u escape");
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("Bad \\u escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("Bad escape \\" + e);
                    }
                } else if (c < 0x20) {
                    throw error("Control character inside text");
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            boolean fraction = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { fraction = true; pos++; }
                else break;
            }
            String num = s.substring(start, pos);
            try {
                if (!fraction && num.length() < 18) return Long.parseLong(num);
                return Double.parseDouble(num);
            } catch (NumberFormatException e) {
                throw error("Bad number " + num);
            }
        }
    }

    // ------------------------------------------------------------------ helpers for reading maps

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static String str(Map<String, Object> m, String key, String fallback) {
        String v = str(m, key);
        return v == null ? fallback : v;
    }

    public static long num(Map<String, Object> m, String key, long fallback) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    public static double dbl(Map<String, Object> m, String key, double fallback) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes");
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List<?> l) return (List<Object>) l;
        return new ArrayList<>();
    }

    public static List<String> strings(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : list(m, key)) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    /** Builds a LinkedHashMap from key, value, key, value ... (keeps the order, allows null values). */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
