package com.cecil.chatbot.util;

/**
 * A tiny, safe calculator for the demo model: + - * / % ^ and brackets.
 * It is a "recursive descent parser": expression = term (+|- term)*, term = power (*|/|% power)*,
 * power = unary (^ power)?, unary = -unary | number | (expression).
 * It never runs code - it only reads numbers and operators.
 */
public final class MathEval {

    private final String s;
    private int pos;

    private MathEval(String s) { this.s = s.replaceAll("\\s+", "").replace('x', '*').replace('×', '*').replace('÷', '/'); }

    /** Returns the answer, or throws IllegalArgumentException if the text is not a valid sum. */
    public static double eval(String text) {
        if (text == null || text.length() > 200) throw new IllegalArgumentException("too long");
        MathEval m = new MathEval(text);
        double v = m.expr(0);
        if (m.pos != m.s.length()) throw new IllegalArgumentException("unexpected '" + m.s.charAt(m.pos) + "'");
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException("not a number (divide by zero?)");
        return v;
    }

    /** 84.0 -> "84", 0.333333333 -> "0.333333" */
    public static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        String t = String.format(java.util.Locale.ROOT, "%.6f", v);
        return t.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private double expr(int depth) {
        if (depth > 40) throw new IllegalArgumentException("too many brackets");
        double v = term(depth);
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '+') { pos++; v += term(depth); }
            else if (c == '-') { pos++; v -= term(depth); }
            else break;
        }
        return v;
    }

    private double term(int depth) {
        double v = power(depth);
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '*') { pos++; v *= power(depth); }
            else if (c == '/') { pos++; v /= power(depth); }
            else if (c == '%') { pos++; v %= power(depth); }
            else break;
        }
        return v;
    }

    private double power(int depth) {
        double base = unary(depth);
        if (pos < s.length() && s.charAt(pos) == '^') {
            pos++;
            return Math.pow(base, power(depth));
        }
        return base;
    }

    private double unary(int depth) {
        if (pos >= s.length()) throw new IllegalArgumentException("unexpected end");
        char c = s.charAt(pos);
        if (c == '-') { pos++; return -unary(depth); }
        if (c == '+') { pos++; return unary(depth); }
        if (c == '(') {
            pos++;
            double v = expr(depth + 1);
            if (pos >= s.length() || s.charAt(pos) != ')') throw new IllegalArgumentException("missing )");
            pos++;
            return v;
        }
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
        if (start == pos) throw new IllegalArgumentException("expected a number");
        try {
            return Double.parseDouble(s.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number");
        }
    }
}
