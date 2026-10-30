package com.cecil.sld.util;

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
 * A small JSON reader and writer, so the project needs no outside library.
 *
 * Reading:  Json.parse(text)  gives back Map / List / String / Double / Boolean / null.
 * Writing:  Json.write(obj)   accepts the same types, plus numbers, enums and
 *           simple model objects (their public fields are written).
 * Binding:  Json.bind(map, Type.class) fills a model object from a parsed map.
 */
public final class Json {
    private Json() {}

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { writeString(sb, s); return; }
        if (v instanceof Boolean b) { sb.append(b); return; }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) { sb.append(v); return; }
        if (v instanceof Number n) { writeNumber(sb, n.doubleValue()); return; }
        if (v instanceof Enum<?> e) { writeString(sb, e.name()); return; }
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
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
                writeValue(sb, o);
            }
            sb.append(']');
            return;
        }
        if (v.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(v);
            List<Object> list = new ArrayList<>(len);
            for (int i = 0; i < len; i++) list.add(java.lang.reflect.Array.get(v, i));
            writeValue(sb, list);
            return;
        }
        // model object: write its public, non-static fields
        sb.append('{');
        boolean first = true;
        for (Field f : v.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, f.getName());
                sb.append(':');
                writeValue(sb, f.get(v));
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        }
        sb.append('}');
    }

    private static void writeNumber(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) { sb.append("null"); return; }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
        else sb.append(d);
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
                case '<' -> sb.append("\\u003c");   // safe even if JSON ends up inside HTML
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
        if (p.pos != text.length()) throw p.error("Extra text after JSON value");
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
            if (depth > 64) throw error("JSON nested too deeply");
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
                            try { sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16)); }
                            catch (NumberFormatException ex) { throw error("Bad unicode escape"); }
                            pos += 4;
                        }
                        default -> throw error("Bad escape \\" + e);
                    }
                } else {
                    if (c < 0x20) throw error("Control character in string");
                    sb.append(c);
                }
            }
        }

        Double number() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            while (pos < s.length() && "0123456789.eE+-".indexOf(s.charAt(pos)) >= 0) pos++;
            try { return Double.parseDouble(s.substring(start, pos)); }
            catch (NumberFormatException ex) { throw error("Bad number"); }
        }
    }

    // ------------------------------------------------------------------ binding

    /** Fill a new object of class {@code type} from a parsed JSON map (by public field name). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> T bind(Object source, Class<T> type) {
        return (T) convert(source, type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convert(Object v, Type target) {
        if (v == null) return defaultFor(target);
        if (target instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            if (List.class.isAssignableFrom(raw)) {
                List<Object> out = new ArrayList<>();
                if (v instanceof List<?> l) for (Object o : l) out.add(convert(o, pt.getActualTypeArguments()[0]));
                return out;
            }
            if (Map.class.isAssignableFrom(raw)) {
                Map<String, Object> out = new LinkedHashMap<>();
                if (v instanceof Map<?, ?> m)
                    for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), convert(e.getValue(), pt.getActualTypeArguments()[1]));
                return out;
            }
            return v;
        }
        Class<?> c = (Class<?>) target;
        if (c == Object.class) return v;
        if (c == String.class) return String.valueOf(v);
        if (c == int.class || c == Integer.class) return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
        if (c == long.class || c == Long.class) return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
        if (c == double.class || c == Double.class) return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v));
        if (c == boolean.class || c == Boolean.class) return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
        if (c.isEnum()) return Enum.valueOf((Class) c, String.valueOf(v));
        if (v instanceof Map<?, ?> m) {
            try {
                Object obj = c.getDeclaredConstructor().newInstance();
                for (Field f : c.getFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) continue;
                    if (m.containsKey(f.getName())) f.set(obj, convert(m.get(f.getName()), f.getGenericType()));
                }
                return obj;
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Cannot build " + c.getSimpleName(), ex);
            }
        }
        throw new IllegalArgumentException("Cannot read " + c.getSimpleName() + " from " + v);
    }

    private static Object defaultFor(Type t) {
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0.0;
        if (t == boolean.class) return false;
        if (t instanceof ParameterizedType pt && List.class.isAssignableFrom((Class<?>) pt.getRawType())) return new ArrayList<>();
        return null;
    }
}
