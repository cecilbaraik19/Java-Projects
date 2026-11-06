package com.cecil.certify.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny JSON reader and writer (no external library).
 * JSON object -> LinkedHashMap (keeps key order), array -> ArrayList, number -> Long or Double,
 * true/false -> Boolean, null -> null, text -> String.
 */
public final class Json {
    private Json() {}

    /** Limits that stop a huge or very deep request from using up memory. */
    private static final int MAX_DEPTH = 64;

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, -1, 0);
        return sb.toString();
    }

    /** Same as write, but with line breaks and 2-space indent (used for the data files so people can read them). */
    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 2, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v, int indent, int level) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Map<?, ?> m) {
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
        } else if (v instanceof Iterable<?> it) {
            List<Object> list = new ArrayList<>();
            for (Object o : it) list.add(o);
            if (list.isEmpty()) { sb.append("[]"); return; }
            boolean simple = list.stream().noneMatch(o -> o instanceof Map || o instanceof Iterable);
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(simple && indent > 0 ? ", " : ",");
                if (!simple) newline(sb, indent, level + 1);
                write(sb, list.get(i), indent, level + 1);
            }
            if (!simple) newline(sb, indent, level);
            sb.append(']');
        } else if (v instanceof JsonWritable w) {
            write(sb, w.toJson(), indent, level);
        } else {
            quote(sb, v.toString());
        }
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent <= 0) return;
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
                // < > & are escaped so JSON can never close a <script> tag or start HTML
                case '<' -> sb.append("\\u003c");
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
        if (text == null) throw new IllegalArgumentException("No JSON text");
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
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " (at character " + pos + ")");
        }

        void skipSpace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') pos++;
                else break;
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw error("JSON is nested too deeply");
            if (pos >= s.length()) throw error("Unexpected end of JSON");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw error("Unexpected character '" + c + "'");
            }
        }

        void expect(String word) {
            if (!s.startsWith(word, pos)) throw error("Expected " + word);
            pos += word.length();
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipSpace();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipSpace();
                if (pos >= s.length() || s.charAt(pos) != '"') throw error("Expected a key in quotes");
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
                } else {
                    if (c < 0x20) throw error("Control character inside text");
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            boolean decimal = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                decimal = true;
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                decimal = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            String t = s.substring(start, pos);
            if (t.equals("-") || t.isEmpty()) throw error("Bad number");
            try {
                if (!decimal) {
                    if (t.length() < 18) return Long.parseLong(t);
                    return Double.parseDouble(t);
                }
                return Double.parseDouble(t);
            } catch (NumberFormatException ex) {
                throw error("Bad number");
            }
        }
    }

    // ------------------------------------------------------------------ helpers for reading maps

    public static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static String str(Map<String, Object> m, String key, String fallback) {
        String v = str(m, key);
        return v == null ? fallback : v;
    }

    public static long num(Map<String, Object> m, String key, long fallback) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return s.equalsIgnoreCase("true");
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    /** Builds an ordered map from key, value, key, value ... */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Anything that knows how to turn itself into JSON-ready maps and lists. */
    public interface JsonWritable {
        Object toJson();
    }
}
