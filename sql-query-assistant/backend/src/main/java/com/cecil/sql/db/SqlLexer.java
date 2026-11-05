package com.cecil.sql.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Step 1 of running SQL: cut the text into tokens (words, numbers, 'strings', symbols).
 * Example: SELECT name FROM students WHERE cgpa > 8
 *   -> KEYWORD(SELECT) IDENT(name) KEYWORD(FROM) IDENT(students) KEYWORD(WHERE) IDENT(cgpa) SYMBOL(>) NUMBER(8)
 */
public final class SqlLexer {

    public enum Kind { KEYWORD, IDENT, NUMBER, STRING, SYMBOL, EOF }

    public record Token(Kind kind, String text, int pos) {
        public boolean is(String s) { return (kind == Kind.KEYWORD || kind == Kind.SYMBOL) && text.equalsIgnoreCase(s); }
        @Override public String toString() { return kind + "(" + text + ")"; }
    }

    public static final Set<String> KEYWORDS = Set.of(
            "SELECT", "DISTINCT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "ASC", "DESC", "LIMIT", "OFFSET",
            "AS", "AND", "OR", "NOT", "IN", "BETWEEN", "LIKE", "IS", "NULL", "TRUE", "FALSE", "JOIN", "INNER", "LEFT",
            "OUTER", "ON", "CASE", "WHEN", "THEN", "ELSE", "END",
            // words we recognise only to block them with a clear message
            "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER", "TRUNCATE", "REPLACE", "MERGE", "GRANT",
            "REVOKE", "EXEC", "EXECUTE", "CALL", "ATTACH", "PRAGMA", "UNION", "INTO", "SET", "WITH", "RIGHT", "FULL", "CROSS");

    public static List<Token> tokenize(String sql) {
        if (sql == null) throw new SqlException("The query is empty.");
        List<Token> out = new ArrayList<>();
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {          // -- comment to end of line
                while (i < n && sql.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {          // /* comment */
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) throw new SqlException("A /* comment */ is not closed.", i);
                i = end + 2;
                continue;
            }
            int start = i;
            if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                String w = sql.substring(start, i);
                out.add(new Token(KEYWORDS.contains(w.toUpperCase()) ? Kind.KEYWORD : Kind.IDENT, w, start));
            } else if (c == '`' || c == '"') {                                 // `quoted name` or "quoted name"
                int end = sql.indexOf(c, i + 1);
                if (end < 0) throw new SqlException("A quoted name is not closed.", i);
                out.add(new Token(Kind.IDENT, sql.substring(i + 1, end), start));
                i = end + 1;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
                while (i < n && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) i++;
                out.add(new Token(Kind.NUMBER, sql.substring(start, i), start));
            } else if (c == '\'') {                                            // 'text', '' means one quote
                StringBuilder sb = new StringBuilder();
                i++;
                while (true) {
                    if (i >= n) throw new SqlException("A 'text value' is not closed with a quote.", start);
                    char d = sql.charAt(i);
                    if (d == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') { sb.append('\''); i += 2; continue; }
                        i++;
                        break;
                    }
                    sb.append(d);
                    i++;
                }
                out.add(new Token(Kind.STRING, sb.toString(), start));
            } else {
                String two = i + 1 < n ? sql.substring(i, i + 2) : "";
                if (two.equals("<=") || two.equals(">=") || two.equals("<>") || two.equals("!=") || two.equals("||")) {
                    out.add(new Token(Kind.SYMBOL, two, start));
                    i += 2;
                } else if ("(),.*=<>+-/%;".indexOf(c) >= 0) {
                    out.add(new Token(Kind.SYMBOL, String.valueOf(c), start));
                    i++;
                } else {
                    throw new SqlException("Unexpected character '" + c + "'.", i);
                }
            }
        }
        out.add(new Token(Kind.EOF, "", n));
        return out;
    }
}
