package com.cecil.kaushal.core;

import com.cecil.kaushal.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tiny SEARCH LANGUAGE for finding people by skills, e.g.
 *
 *     java >= 4 and (aws or azure >= 2) and not "machine learning"
 *     kubernetes:3 react             (":" means ">=", two terms side by side mean AND)
 *     python >= advanced
 *
 * Grammar (a recursive-descent parser, one method per line; "or" binds weakest):
 *     query := or
 *     or    := and ( OR and )*
 *     and   := not ( [AND] not )*
 *     not   := NOT not | atom
 *     atom  := "(" or ")" | skill [ op level ]          op: >= > = <= < :     level: 1-5 or a level name
 *
 * A skill name can be several words without quotes ("machine learning"): the parser tries the longest run of words
 * (up to 4) that is a known skill name, symbol or synonym. A skill alone means "level 1 or more".
 */
public final class SkillQuery {

    /** Turns a name into a skill id (or null), and gives "did you mean" ideas. */
    public interface Names {
        String resolve(String name);
        List<String> suggest(String name);
        String label(String skillId);
    }

    public static final class Error extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final List<String> suggestions;
        Error(String m, List<String> s) { super(m); suggestions = s; }
    }

    /** A parsed query. test() checks one person; skills() lists the skills it mentions (for ranking). */
    public interface Node {
        boolean test(Map<String, Integer> levels);
        void skills(Set<String> into);
        String show(Names n);
    }

    record Term(String skillId, String op, int level) implements Node {
        public boolean test(Map<String, Integer> lv) {
            int h = lv.getOrDefault(skillId, 0);
            return switch (op) {
                case ">" -> h > level;
                case "=" -> h == level;
                case "<=" -> h <= level;
                case "<" -> h < level;
                default -> h >= level;
            };
        }
        public void skills(Set<String> into) { if (!op.startsWith("<")) into.add(skillId); }
        public String show(Names n) { return n.label(skillId) + " " + op + " " + level; }
    }

    record And(Node a, Node b) implements Node {
        public boolean test(Map<String, Integer> lv) { return a.test(lv) && b.test(lv); }
        public void skills(Set<String> into) { a.skills(into); b.skills(into); }
        public String show(Names n) { return "(" + a.show(n) + " AND " + b.show(n) + ")"; }
    }

    record Or(Node a, Node b) implements Node {
        public boolean test(Map<String, Integer> lv) { return a.test(lv) || b.test(lv); }
        public void skills(Set<String> into) { a.skills(into); b.skills(into); }
        public String show(Names n) { return "(" + a.show(n) + " OR " + b.show(n) + ")"; }
    }

    record Not(Node a) implements Node {
        public boolean test(Map<String, Integer> lv) { return !a.test(lv); }
        public void skills(Set<String> into) { }
        public String show(Names n) { return "NOT " + a.show(n); }
    }

    // ------------------------------------------------------------------ tokens

    enum T { WORD, TEXT, OP, LP, RP, AND, OR, NOT, END }
    record Tok(T type, String text, int at) { }

    static List<Tok> tokens(String q) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        while (i < q.length()) {
            char c = q.charAt(i);
            if (Character.isWhitespace(c) || c == ',') { i++; continue; }
            if (c == '(') { out.add(new Tok(T.LP, "(", i++)); continue; }
            if (c == ')') { out.add(new Tok(T.RP, ")", i++)); continue; }
            if (c == '&') { out.add(new Tok(T.AND, "and", i)); i += q.startsWith("&&", i) ? 2 : 1; continue; }
            if (c == '|') { out.add(new Tok(T.OR, "or", i)); i += q.startsWith("||", i) ? 2 : 1; continue; }
            if (c == '!' && !q.startsWith("!=", i)) { out.add(new Tok(T.NOT, "not", i++)); continue; }
            if (c == '>' || c == '<' || c == '=' || c == ':') {
                int start = i;
                String op = (i + 1 < q.length() && q.charAt(i + 1) == '=' && c != '=' && c != ':') ? c + "=" : String.valueOf(c);
                i += op.length();
                if (op.equals(":")) op = ">=";
                out.add(new Tok(T.OP, op, start));
                continue;
            }
            if (c == '"') {
                int end = q.indexOf('"', i + 1);
                if (end < 0) throw new Error("A quote \" is not closed.", List.of());
                out.add(new Tok(T.TEXT, q.substring(i + 1, end), i));
                i = end + 1;
                continue;
            }
            int start = i;
            while (i < q.length() && !Character.isWhitespace(q.charAt(i)) && "()<>=:\",&|".indexOf(q.charAt(i)) < 0) i++;
            String w = q.substring(start, i);
            switch (w.toLowerCase()) {
                case "and" -> out.add(new Tok(T.AND, w, start));
                case "or" -> out.add(new Tok(T.OR, w, start));
                case "not" -> out.add(new Tok(T.NOT, w, start));
                default -> out.add(new Tok(T.WORD, w, start));
            }
        }
        out.add(new Tok(T.END, "", q.length()));
        return out;
    }

    // ------------------------------------------------------------------ parser

    private final List<Tok> toks;
    private final Names names;
    private int pos;

    private SkillQuery(List<Tok> toks, Names names) { this.toks = toks; this.names = names; }

    public static Node parse(String query, Names names) {
        if (query == null || query.isBlank()) throw new Error("Type what you are looking for, e.g.  java >= 3 and aws", List.of());
        if (query.length() > 300) throw new Error("The search is too long.", List.of());
        SkillQuery p = new SkillQuery(tokens(query), names);
        Node n = p.or();
        if (p.peek().type != T.END) throw new Error("Did not understand '" + p.peek().text + "'.", List.of());
        return n;
    }

    private Tok peek() { return toks.get(pos); }
    private Tok next() { return toks.get(pos++); }

    private Node or() {
        Node n = and();
        while (peek().type == T.OR) { next(); n = new Or(n, and()); }
        return n;
    }

    private Node and() {
        Node n = not();
        while (true) {
            T t = peek().type;
            if (t == T.AND) { next(); n = new And(n, not()); }
            else if (t == T.WORD || t == T.TEXT || t == T.NOT || t == T.LP) n = new And(n, not());   // side by side = AND
            else return n;
        }
    }

    private Node not() {
        if (peek().type == T.NOT) { next(); return new Not(not()); }
        return atom();
    }

    private Node atom() {
        Tok t = peek();
        if (t.type == T.LP) {
            next();
            Node n = or();
            if (next().type != T.RP) throw new Error("A bracket ( is not closed.", List.of());
            return n;
        }
        String id;
        if (t.type == T.TEXT) {
            next();
            id = names.resolve(t.text);
            if (id == null) throw unknown(t.text);
        } else if (t.type == T.WORD) {
            id = null;
            for (int k = Math.min(4, wordsAhead()); k >= 1 && id == null; k--) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < k; j++) sb.append(j > 0 ? " " : "").append(toks.get(pos + j).text);
                String found = names.resolve(sb.toString());
                if (found != null) { id = found; pos += k; }
            }
            if (id == null) throw unknown(t.text);
        } else {
            throw new Error(t.type == T.END ? "The search ends too early." : "Expected a skill name before '" + t.text + "'.", List.of());
        }
        String op = ">=";
        int level = 1;
        if (peek().type == T.OP) {
            op = next().text;
            Tok lv = next();
            level = Levels.parse(lv.text);
            if (lv.type != T.WORD || level < 0) throw new Error("After " + op + " write a level 1-5 (or Aware ... Expert).", List.of());
        }
        if (op.equals("<=") || op.equals("<") || op.equals("=")) level = Math.max(op.equals("<") ? 1 : 0, level);
        return new Term(id, op, level);
    }

    private int wordsAhead() {
        int n = 0;
        while (toks.get(pos + n).type == T.WORD) n++;
        return n;
    }

    private Error unknown(String word) {
        List<String> s = names.suggest(word);
        return new Error("No skill called '" + Text.clean(word, 40) + "'." + (s.isEmpty() ? "" : " Did you mean " + String.join(", ", s) + "?"), s);
    }

    /** The skills a query asks for (used to sort the results: best levels in those skills first). */
    public static Set<String> skillsOf(Node n) {
        Set<String> s = new LinkedHashSet<>();
        n.skills(s);
        return s;
    }
}
