package com.cecil.pipeline.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reader and writer, so the project needs no external libraries.
 *
 * Reading gives back: Map (objects), List (arrays), String, Long or Double (numbers), Boolean, or null.
 * Writing accepts the same types, plus any other Number, Enum (written as its name) and arrays of objects.
 */
public final class Json {

    private Json() { }

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, -1, 0);
        return sb.toString();
    }

    /** Same as {@link #write} but with line breaks and indentation, used for the data files. */
    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 2, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v, int indent, int level) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.longValue());
            else sb.append(d);
        } else if (v instanceof Float f) {
            writeValue(sb, f.doubleValue(), indent, level);
        } else if (v instanceof Number n) {
            sb.append(n);
        } else if (v instanceof Enum<?> e) {
            writeString(sb, e.name());
        } else if (v instanceof Map<?, ?> m) {
            writeObject(sb, m, indent, level);
        } else if (v instanceof Collection<?> c) {
            writeArray(sb, c, indent, level);
        } else if (v instanceof Object[] arr) {
            writeArray(sb, List.of(arr), indent, level);
        } else {
            writeString(sb, v.toString());
        }
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent < 0) return;
        sb.append('\n');
        for (int i = 0; i < indent * level; i++) sb.append(' ');
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> m, int indent, int level) {
        if (m.isEmpty()) { sb.append("{}"); return; }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            newline(sb, indent, level + 1);
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(indent < 0 ? ":" : ": ");
            writeValue(sb, e.getValue(), indent, level + 1);
        }
        newline(sb, indent, level);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Collection<?> c, int indent, int level) {
        if (c.isEmpty()) { sb.append("[]"); return; }
        sb.append('[');
        boolean first = true;
        for (Object o : c) {
            if (!first) sb.append(',');
            first = false;
            newline(sb, indent, level + 1);
            writeValue(sb, o, indent, level + 1);
        }
        newline(sb, indent, level);
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
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
                // escaped so JSON is always safe to drop inside an HTML page
                case '<' -> sb.append("\\u003c");
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
        if (text == null) throw new IllegalArgumentException("No JSON text");
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) throw p.error("Unexpected text after the JSON value");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) o;
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 64;
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " (at character " + pos + ")");
        }

        void skipWs() {
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
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        Object literal(String word, Object val) {
            if (!s.startsWith(word, pos)) throw error("Invalid value");
            pos += word.length();
            return val;
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') throw error("Expected a property name");
                String key = string();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw error("Expected ':'");
                pos++;
                skipWs();
                m.put(key, value(depth + 1));
                skipWs();
                if (pos >= s.length()) throw error("Unexpected end of JSON");
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw error("Expected ',' or '}'");
            }
        }

        List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                skipWs();
                list.add(value(depth + 1));
                skipWs();
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
                if (pos >= s.length()) throw error("Unclosed string");
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
                            if (pos + 4 > s.length()) throw error("Bad unicode escape");
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("Bad unicode escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("Bad escape");
                    }
                } else if (c < 0x20) {
                    throw error("Control character inside string");
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            boolean isDouble = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { isDouble = true; pos++; }
                else break;
            }
            String num = s.substring(start, pos);
            try {
                if (!isDouble) {
                    if (num.length() > 18) return Double.parseDouble(num);
                    return Long.parseLong(num);
                }
                return Double.parseDouble(num);
            } catch (NumberFormatException e) {
                throw error("Bad number '" + num + "'");
            }
        }
    }

    // ------------------------------------------------------------------ helpers for reading maps

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public static String str(Map<String, Object> m, String key, String def) {
        String v = str(m, key);
        return v == null ? def : v;
    }

    public static long lng(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Math.round(Double.parseDouble(s.trim())); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }

    public static Integer intOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return (int) Math.round(Double.parseDouble(s.trim())); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objects(Map<String, Object> m, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list(m, key)) if (o instanceof Map) out.add((Map<String, Object>) o);
        return out;
    }

    /** Builds an ordered map from key, value, key, value ... pairs. */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
