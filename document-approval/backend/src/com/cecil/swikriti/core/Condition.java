package com.cecil.swikriti.core;

import com.cecil.swikriti.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The small condition language for "is this stage needed?", for example
 *
 *     amount > 50000
 *     amount >= 5L and not urgent            (k = thousand, L = lakh, cr = crore: 5L = 5,00,000)
 *     dept in (Projects, Design) or vendor contains "new"
 *
 * Fields:  amount (number) · dept, type, vendor, title (text) · urgent (yes/no)
 * Compare: = != > >= < <=   contains   in ( ... )      Join: and, or, not, ( )
 *
 * It is a RECURSIVE-DESCENT parser: one Java method per grammar rule,
 *     or  := and ("or" and)*
 *     and := not ("and" not)*
 *     not := "not" not | atom
 *     atom := "(" or ")" | field [op value | "in" "(" value {"," value} ")"]
 * "and" binds tighter than "or", like * before + in maths. An empty condition means "always".
 */
public final class Condition {
    public static final List<String> NUMBERS = List.of("amount");
    public static final List<String> TEXTS = List.of("dept", "type", "vendor", "title");
    public static final List<String> FLAGS = List.of("urgent");

    /** A part of the tree that can be checked against the facts of one document. */
    public interface Node { boolean test(Map<String, Object> facts); }

    /** A mistake in a condition, with the position (0-based) so the editor can point at it. */
    public static final class Error extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public final int at;
        Error(String msg, int at) { super(msg + " (at character " + (at + 1) + ")"); this.at = at; }
    }

    private final String src;
    private final List<Tok> toks;
    private int i;

    private record Tok(String kind, String text, int at) { }   // kind: word, num, str, op, ( ) , end

    private Condition(String src) { this.src = src; this.toks = lex(src); }

    /** Parses a condition. Blank = always true. Throws Condition.Error with a friendly message. */
    public static Node parse(String text) {
        if (text == null || text.isBlank()) return f -> true;
        Condition c = new Condition(text);
        Node n = c.or();
        if (!c.peek().kind.equals("end")) throw new Error("Unexpected '" + c.peek().text + "'", c.peek().at);
        return n;
    }

    /** null when fine, else the error message. */
    public static String check(String text) {
        try { parse(text); return null; } catch (Error e) { return e.getMessage(); }
    }

    public static boolean test(String text, Map<String, Object> facts) { return parse(text).test(facts); }

    // ------------------------------------------------------------------ the grammar

    private Node or() {
        Node left = and();
        while (isWord("or")) { i++; Node a = left, b = and(); left = f -> a.test(f) || b.test(f); }
        return left;
    }

    private Node and() {
        Node left = not();
        while (isWord("and")) { i++; Node a = left, b = not(); left = f -> a.test(f) && b.test(f); }
        return left;
    }

    private Node not() {
        if (isWord("not")) { i++; Node a = not(); return f -> !a.test(f); }
        return atom();
    }

    private Node atom() {
        Tok t = peek();
        if (t.kind.equals("(")) {
            i++;
            Node n = or();
            expect(")", "Missing ')'");
            return n;
        }
        if (!t.kind.equals("word")) throw new Error(t.kind.equals("end") ? "The condition stops too early" : "Expected a field name like amount or dept", t.at);
        String field = t.text.toLowerCase(Locale.ROOT);
        i++;
        if (FLAGS.contains(field)) {
            return f -> Boolean.TRUE.equals(f.get(field));
        }
        if (!NUMBERS.contains(field) && !TEXTS.contains(field)) throw new Error("Unknown field '" + t.text + "'" + suggest(field), t.at);
        Tok op = peek();
        if (op.kind.equals("word") && op.text.equalsIgnoreCase("in")) {
            i++;
            expect("(", "Expected '(' after in");
            List<Object> values = new ArrayList<>();
            values.add(value(field));
            while (peek().kind.equals(",")) { i++; values.add(value(field)); }
            expect(")", "Missing ')' after the list");
            return f -> {
                for (Object v : values) if (same(f.get(field), v)) return true;
                return false;
            };
        }
        if (op.kind.equals("word") && op.text.equalsIgnoreCase("contains")) {
            if (!TEXTS.contains(field)) throw new Error("'contains' works only on text fields", op.at);
            i++;
            String v = String.valueOf(value(field));
            return f -> Text.key(String.valueOf(f.getOrDefault(field, ""))).contains(Text.key(v));
        }
        if (!op.kind.equals("op")) throw new Error("Expected = != > >= < <= contains or in after '" + field + "'", op.at);
        i++;
        String o = op.text;
        if (TEXTS.contains(field) && !(o.equals("=") || o.equals("!="))) throw new Error("Text can only use = != contains in", op.at);
        Object v = value(field);
        return f -> {
            Object have = f.get(field);
            if (NUMBERS.contains(field)) {
                double a = have instanceof Number n ? n.doubleValue() : 0, b = ((Number) v).doubleValue();
                return switch (o) {
                    case "=" -> a == b; case "!=" -> a != b; case ">" -> a > b;
                    case ">=" -> a >= b; case "<" -> a < b; default -> a <= b;
                };
            }
            boolean eq = same(have, v);
            return o.equals("=") ? eq : !eq;
        };
    }

    private Object value(String field) {
        Tok t = peek();
        i++;
        if (NUMBERS.contains(field)) {
            if (!t.kind.equals("num")) throw new Error("Expected a number for " + field + " (like 50000, 50k or 5L)", t.at);
            return number(t);
        }
        if (t.kind.equals("str") || t.kind.equals("word") || t.kind.equals("num")) return t.text;
        throw new Error("Expected a value", t.at);
    }

    private static boolean same(Object have, Object want) {
        if (have instanceof Number a && want instanceof Number b) return a.doubleValue() == b.doubleValue();
        return Text.key(String.valueOf(have == null ? "" : have)).equals(Text.key(String.valueOf(want)));
    }

    private Double number(Tok t) {
        String s = t.text.toLowerCase(Locale.ROOT).replace("_", "");
        double mult = 1;
        if (s.endsWith("cr")) { mult = 1e7; s = s.substring(0, s.length() - 2); }
        else if (s.endsWith("l")) { mult = 1e5; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("k")) { mult = 1e3; s = s.substring(0, s.length() - 1); }
        try { return Double.parseDouble(s) * mult; } catch (NumberFormatException e) { throw new Error("Bad number '" + t.text + "'", t.at); }
    }

    private static String suggest(String word) {
        String best = null;
        int bestD = 3;
        List<String> all = new ArrayList<>(NUMBERS);
        all.addAll(TEXTS);
        all.addAll(FLAGS);
        for (String f : all) {
            int d = Text.distance(word, f);
            if (d < bestD) { bestD = d; best = f; }
        }
        return best == null ? ". Fields are amount, dept, type, vendor, title, urgent" : " - did you mean '" + best + "'?";
    }

    private boolean isWord(String w) { Tok t = peek(); return t.kind.equals("word") && t.text.equalsIgnoreCase(w); }
    private Tok peek() { return toks.get(Math.min(i, toks.size() - 1)); }
    private void expect(String kind, String msg) {
        if (!peek().kind.equals(kind)) throw new Error(msg, peek().at);
        i++;
    }

    // ------------------------------------------------------------------ splitting the text into tokens

    private static List<Tok> lex(String s) {
        if (s.length() > 300) throw new Error("A condition can be at most 300 characters", 300);
        List<Tok> out = new ArrayList<>();
        int p = 0;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (Character.isWhitespace(c)) { p++; continue; }
            int start = p;
            if (c == '(' || c == ')' || c == ',') { out.add(new Tok(String.valueOf(c), String.valueOf(c), p)); p++; continue; }
            if ("=!<>".indexOf(c) >= 0) {
                String two = p + 1 < s.length() ? s.substring(p, p + 2) : "";
                if (two.equals(">=") || two.equals("<=") || two.equals("!=") || two.equals("==")) {
                    out.add(new Tok("op", two.equals("==") ? "=" : two, p)); p += 2; continue;
                }
                if (c == '!') throw new Error("Use != for 'not equal'", p);
                out.add(new Tok("op", String.valueOf(c), p)); p++; continue;
            }
            if (c == '"' || c == '\'') {
                int end = s.indexOf(c, p + 1);
                if (end < 0) throw new Error("Text is missing its closing quote", p);
                out.add(new Tok("str", s.substring(p + 1, end), start));
                p = end + 1; continue;
            }
            if (Character.isDigit(c)) {
                while (p < s.length() && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p) == '.' || s.charAt(p) == '_')) p++;
                out.add(new Tok("num", s.substring(start, p), start)); continue;
            }
            if (Character.isLetter(c)) {
                while (p < s.length() && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p) == '_' || s.charAt(p) == '-')) p++;
                out.add(new Tok("word", s.substring(start, p), start)); continue;
            }
            throw new Error("Unexpected character '" + c + "'", p);
        }
        out.add(new Tok("end", "end", s.length()));
        return out;
    }
}
