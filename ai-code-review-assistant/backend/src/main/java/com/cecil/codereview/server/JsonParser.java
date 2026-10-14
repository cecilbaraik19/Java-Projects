package com.cecil.codereview.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader (no library needed).
 * Turns JSON text into Java objects:
 *   object -> Map, array -> List, string -> String,
 *   number -> Double, true/false -> Boolean, null -> null
 */
public final class JsonParser {
    private final String s;
    private int i;

    private JsonParser(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        JsonParser p = new JsonParser(text);
        p.skip();
        Object v = p.value();
        p.skip();
        if (p.i != p.s.length()) throw p.error("Unexpected text after JSON value");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) v;
    }

    private Object value() {
        if (i >= s.length()) throw error("Unexpected end of JSON");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || Character.isDigit(c)) return number();
                throw error("Unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++; // {
        skip();
        if (peek() == '}') { i++; return map; }
        while (true) {
            skip();
            if (peek() != '"') throw error("Expected a key in quotes");
            String key = string();
            skip();
            expect(':');
            skip();
            map.put(key, value());
            skip();
            if (peek() == ',') { i++; continue; }
            expect('}');
            return map;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++; // [
        skip();
        if (peek() == ']') { i++; return list; }
        while (true) {
            skip();
            list.add(value());
            skip();
            if (peek() == ',') { i++; continue; }
            expect(']');
            return list;
        }
    }

    private String string() {
        i++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    if (i + 4 > s.length()) throw error("Bad \\u escape");
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: sb.append(e); // \" \\ \/
            }
        }
        throw error("String is not closed");
    }

    private Double number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw error("Bad number");
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw error("Expected " + word);
        i += word.length();
        return v;
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) throw error("Expected '" + c + "'");
        i++;
    }

    private void skip() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("Invalid JSON at position " + i + ": " + msg);
    }
}
