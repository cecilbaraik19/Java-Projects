package com.cecil.edge.util;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, so the project needs no external library.
 *
 * Reading gives plain Java values: Map (object), List (array), String, Double (number), Boolean, or null.
 * Writing accepts the same values plus numbers, enums, arrays and simple "model" objects.
 * Model objects are turned into JSON with reflection: every public, non-static field becomes a key.
 */
public final class Json {
    private Json() { }

    // ------------------------------------------------------------------ writing

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { quote(sb, s); return; }
        if (v instanceof Boolean b) { sb.append(b ? "true" : "false"); return; }
        if (v instanceof Double d) { number(sb, d); return; }
        if (v instanceof Float f) { number(sb, f.doubleValue()); return; }
        if (v instanceof Number n) { sb.append(n.longValue()); return; }
        if (v instanceof Enum<?> e) { quote(sb, e.name()); return; }
        if (v instanceof Character c) { quote(sb, String.valueOf(c)); return; }
        if (v instanceof Map<?, ?> m) {
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
            return;
        }
        if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
            return;
        }
        if (v instanceof double[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); number(sb, arr[i]); }
            sb.append(']');
            return;
        }
        if (v instanceof long[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); sb.append(arr[i]); }
            sb.append(']');
            return;
        }
        if (v instanceof int[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); sb.append(arr[i]); }
            sb.append(']');
            return;
        }
        if (v instanceof Object[] arr) {
            write(sb, List.of(arr));
            return;
        }
        write(sb, toMap(v));
    }

    private static void number(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) { sb.append("null"); return; }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
        else sb.append(d);
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
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** Model object -> ordered map of its public fields. */
    public static Map<String, Object> toMap(Object o) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Field f : o.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                m.put(f.getName(), f.get(o));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ reading

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) throw p.err("extra text after the JSON value");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("JSON object expected");
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("Bad JSON at position " + i + ": " + msg);
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                else break;
            }
        }

        Object value(int depth) {
            if (depth > 64) throw err("too deeply nested");
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': word("true"); return Boolean.TRUE;
                case 'f': word("false"); return Boolean.FALSE;
                case 'n': word("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw err("unexpected character '" + c + "'");
            }
        }

        void word(String w) {
            if (!s.startsWith(w, i)) throw err("expected " + w);
            i += w.length();
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw err("expected a key");
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
                i++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                if (i >= s.length()) throw err("unexpected end");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err("expected ',' or '}'");
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (i >= s.length()) throw err("unexpected end");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw err("expected ',' or ']'");
            }
        }

        String string() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw err("bad escape");
                    char e = s.charAt(i++);
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
                            if (i + 4 > s.length()) throw err("bad unicode escape");
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw err("bad unicode escape");
                            }
                            i += 4;
                        }
                        default -> throw err("bad escape");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Double number() {
            int start = i;
            if (s.charAt(i) == '-') i++;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                else break;
            }
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw err("bad number");
            }
        }
    }

    // ------------------------------------------------------------------ model mapping

    /** Builds a model object from a parsed JSON map, filling public fields with matching names. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> T fromMap(Map<String, Object> m, Class<T> type) {
        try {
            T obj = type.getDeclaredConstructor().newInstance();
            for (Field f : type.getFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !m.containsKey(f.getName())) continue;
                Object v = m.get(f.getName());
                Class<?> ft = f.getType();
                if (v == null) {
                    if (!ft.isPrimitive()) f.set(obj, null);
                } else if (ft == String.class) {
                    f.set(obj, String.valueOf(v));
                } else if (ft == int.class || ft == Integer.class) {
                    f.set(obj, ((Number) v).intValue());
                } else if (ft == long.class || ft == Long.class) {
                    f.set(obj, ((Number) v).longValue());
                } else if (ft == double.class || ft == Double.class) {
                    f.set(obj, ((Number) v).doubleValue());
                } else if (ft == boolean.class || ft == Boolean.class) {
                    f.set(obj, (Boolean) v);
                } else if (ft.isEnum()) {
                    f.set(obj, Enum.valueOf((Class) ft, String.valueOf(v)));
                } else if (List.class.isAssignableFrom(ft)) {
                    Type g = f.getGenericType();
                    Class<?> item = Object.class;
                    if (g instanceof ParameterizedType pt && pt.getActualTypeArguments()[0] instanceof Class<?> c) item = c;
                    List<Object> out = new ArrayList<>();
                    for (Object x : (List<?>) v) {
                        if (item == String.class) out.add(String.valueOf(x));
                        else if (item == Double.class) out.add(((Number) x).doubleValue());
                        else if (item == Long.class) out.add(((Number) x).longValue());
                        else out.add(x);
                    }
                    f.set(obj, out);
                } else {
                    f.set(obj, v);
                }
            }
            return obj;
        } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot read " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ small helpers for request bodies

    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    public static Double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + k + "' must be a number");
        }
    }

    public static Boolean bool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    /** Builds a LinkedHashMap from key, value, key, value ... */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
