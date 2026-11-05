package com.cecil.sql.ai;

import com.cecil.sql.ai.Lexicon.Entry;
import com.cecil.sql.ai.NlTokenizer.Word;
import com.cecil.sql.db.*;
import com.cecil.sql.util.Clock;
import com.cecil.sql.util.Json;

import java.time.LocalDate;
import java.util.*;

/**
 * The offline English-to-SQL engine (a rule-based "semantic parser").
 *
 *  1. Tokenize        "top 5 students from Ranchi by cgpa"  -> top | 5 | students | from | Ranchi | by | cgpa
 *  2. Link words      students = TABLE, Ranchi = VALUE of students.city, cgpa = COLUMN, top = CUE(highest), 5 = NUMBER
 *  3. Build a meaning main table, conditions, aggregate, group, order, limit
 *  4. Find JOINs      shortest path between the tables through the foreign keys (breadth-first search)
 *  5. Write SQL       SELECT name, city, cgpa FROM students WHERE city = 'Ranchi' ORDER BY cgpa DESC LIMIT 5;
 *  6. Confidence      how many of the question's words were understood; below 60% the app says "not sure"
 *
 * Nothing here needs the internet. Every value placed in the SQL comes from the database itself or is a
 * number, and quotes are doubled, so a question cannot inject its own SQL.
 */
public final class NlToSql {

    public static final double NOT_SURE_BELOW = 0.60;

    // ================================================================== output
    /** One coloured piece of the question in the UI, e.g. "Ranchi" -> value of students.city. */
    public record Chip(int start, int end, String text, String kind, String meaning) {}

    public static final class Result implements Json.JsonWritable {
        public String sql;
        public double confidence;
        public boolean notSure;
        public String error;                       // set when no SQL could be written
        public String mainTable;
        public final List<Chip> chips = new ArrayList<>();
        public final List<String> unknown = new ArrayList<>();
        public final List<String> assumptions = new ArrayList<>();

        @Override public Object toJson() {
            List<Object> c = new ArrayList<>();
            for (Chip ch : chips) c.add(Json.map("start", ch.start, "end", ch.end, "text", ch.text, "kind", ch.kind, "meaning", ch.meaning));
            return Json.map("sql", sql, "confidence", Math.round(confidence * 100) / 100.0, "notSure", notSure,
                    "error", error, "mainTable", mainTable, "chips", c, "unknown", unknown, "assumptions", assumptions);
        }
    }

    // ================================================================== cue words
    enum Cue {
        COUNT, SUM, AVG, MAX, MIN,          // explicit aggregate words
        MOST, LEAST,                        // superlatives (highest, best, cheapest ...)
        GT, GE, LT, LE, EQ, NE, BETWEEN,    // comparisons
        NOT, GROUP, GROUP_AFTER, BY, ORDER, ASC, DESC, ALPHA, TOP, BOTTOM, LIMIT,
        DISTINCT, WHO, WHICH, DETAILS, HOW_MUCH, LIST,
        AFTER, BEFORE, SINCE, THIS_YEAR, LAST_YEAR, THIS_MONTH, LAST_MONTH, TODAY, YESTERDAY, LAST_N, MONTH,
        STARTS, ENDS, CONTAINS, NAMED, OUT_OF_STOCK, AND, OR
    }

    /** A cue word plus an optional hint, e.g. "cheapest" = LEAST with hint "rupees". */
    record CueInfo(Cue cue, String hint) {}

    static final String[] MONTHS = {"january", "february", "march", "april", "may", "june", "july", "august",
            "september", "october", "november", "december"};
    private static final Map<String, CueInfo> CUES = new HashMap<>();
    private static void cue(Cue c, String hint, String... phrases) {
        for (String p : phrases) CUES.put(String.join(" ", NlTokenizer.stems(p)), new CueInfo(c, hint));
    }
    static {
        cue(Cue.COUNT, null, "how many", "number of", "count", "count of", "no of", "total number of", "how many number of", "counts", "headcount", "strength");
        cue(Cue.SUM, null, "total", "sum", "sum of", "overall", "altogether", "combined", "in total", "total of");
        cue(Cue.AVG, null, "average", "avg", "mean", "average of", "on average");
        cue(Cue.MAX, null, "maximum", "max", "maximum of the", "peak");
        cue(Cue.MIN, null, "minimum", "min");
        cue(Cue.MOST, null, "highest", "largest", "biggest", "greatest", "most", "the most", "best", "top", "topper", "toppers", "high", "higher");
        cue(Cue.MOST, "rupees", "most expensive", "costliest", "priciest", "expensive", "richest", "highest paid", "best paid");
        cue(Cue.MOST, "date", "latest", "newest", "most recent", "recent", "recently", "last joined", "youngest");
        cue(Cue.LEAST, null, "lowest", "smallest", "least", "fewest", "worst", "weakest", "poorest");
        cue(Cue.LEAST, "rupees", "cheapest", "least expensive", "lowest priced", "lowest paid");
        cue(Cue.LEAST, "date", "oldest", "earliest", "first joined", "senior most", "most senior");
        cue(Cue.GT, null, "more than", "greater than", "above", "over", "higher than", "exceeding", "exceed", "exceeds",
                "bigger than", "larger than", "beyond", ">", "greater", "more");
        cue(Cue.GE, null, "at least", "atleast", "minimum of", "not less than", "or more", "and above", "or above", ">=", "no less than");
        cue(Cue.LT, null, "less than", "below", "under", "lower than", "fewer than", "smaller than", "<", "cheaper than", "lesser than");
        cue(Cue.LE, null, "at most", "atmost", "up to", "upto", "maximum of", "not more than", "or less", "and below", "or below", "<=", "no more than", "within");
        cue(Cue.EQ, null, "equal to", "equals", "exactly", "=", "is equal to", "of exactly");
        cue(Cue.NE, null, "!=", "not equal to", "other than", "apart from", "except", "excluding", "except for");
        cue(Cue.BETWEEN, null, "between", "in the range", "from range");
        cue(Cue.NOT, null, "not", "non", "no", "isn't", "aren't", "don't", "doesn't", "without", "never", "outside");
        cue(Cue.GROUP, null, "per", "each", "every", "for each", "for every", "in each", "across", "grouped by", "group by",
                "break down by", "breakdown by", "split by", "distribution of", "by each", "of each", "in every");
        cue(Cue.GROUP_AFTER, null, "wise");
        cue(Cue.BY, null, "by", "according to", "based on", "in terms of");
        cue(Cue.ORDER, null, "sorted by", "sort by", "order by", "ordered by", "arranged by", "ranked by", "in order of", "sort", "sorted", "rank", "ranked", "arrange");
        cue(Cue.ASC, null, "ascending", "low to high", "lowest first", "smallest first", "increasing", "a to z", "asc");
        cue(Cue.DESC, null, "descending", "high to low", "highest first", "largest first", "decreasing", "z to a", "desc", "reverse");
        cue(Cue.ALPHA, null, "alphabetically", "alphabetical", "alphabetic", "in alphabetical order", "by name");
        cue(Cue.TOP, null, "top", "first", "leading");
        cue(Cue.BOTTOM, null, "bottom", "last");
        cue(Cue.LIMIT, null, "limit", "only");
        cue(Cue.DISTINCT, null, "different", "distinct", "unique", "various", "all the different");
        cue(Cue.WHO, null, "who", "whom", "whose", "name of", "names of", "who all");
        cue(Cue.WHICH, null, "which", "what");
        cue(Cue.DETAILS, null, "details", "all details", "full details", "everything", "all information", "all info", "all columns", "complete details", "all data");
        cue(Cue.HOW_MUCH, null, "how much");
        cue(Cue.LIST, null, "list", "show", "display", "give", "get", "find", "fetch", "tell", "print", "search", "retrieve", "view", "see", "select");
        cue(Cue.AFTER, null, "after", "later than", "post");
        cue(Cue.BEFORE, null, "before", "prior to", "earlier than");
        cue(Cue.SINCE, null, "since", "from the year", "onwards");
        cue(Cue.THIS_YEAR, null, "this year", "current year", "in the current year");
        cue(Cue.LAST_YEAR, null, "last year", "previous year", "past year");
        cue(Cue.THIS_MONTH, null, "this month", "current month");
        cue(Cue.LAST_MONTH, null, "last month", "previous month", "past month");
        cue(Cue.TODAY, null, "today");
        cue(Cue.YESTERDAY, null, "yesterday");
        cue(Cue.LAST_N, "day", "in the last", "in the past", "during the last", "within the last", "past", "last");
        cue(Cue.STARTS, null, "starting with", "starts with", "start with", "beginning with", "begins with", "begin with", "starting from", "name starts with", "name starting with");
        cue(Cue.ENDS, null, "ending with", "ends with", "end with", "ending in");
        cue(Cue.CONTAINS, null, "containing", "contains", "contain", "having the word", "with the word", "like", "includes", "including");
        cue(Cue.NAMED, null, "named", "called", "by the name", "with name", "with the name");
        cue(Cue.OUT_OF_STOCK, null, "out of stock", "not in stock", "sold out", "no stock", "zero stock");
        cue(Cue.AND, null, "and", "also", "as well as", "plus");
        cue(Cue.OR, null, "or", "either");
        for (String m : MONTHS) cue(Cue.MONTH, m, m, m.substring(0, 3));
    }

    /** Small words that carry no meaning for the query. */
    static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "a", "an", "the", "of", "in", "on", "at", "to", "for", "from", "with", "having", "has", "have", "had", "is", "are",
            "was", "were", "be", "been", "being", "do", "does", "did", "there", "please", "me", "us", "i", "we", "you", "our",
            "my", "their", "them", "they", "its", "it", "this", "these", "those", "that", "than", "all", "any", "some", "can",
            "could", "would", "will", "shall", "should", "want", "need", "know", "like", "kindly", "pls", "plz", "data",
            "record", "records", "row", "rows", "entry", "entries", "info", "information", "who", "whom", "where", "when", "whose",
            "what", "which", "how", "much", "many", "much", "as", "into", "about", "and", "or", "also", "both", "currently",
            "now", "right", "presently", "ever", "so", "far", "yet", "do", "is", "am", "his", "her", "he", "she", "him",
            "work", "works", "working", "study", "studying", "studies", "enrolled", "registered", "teach", "teaches",
            "taught", "teaching", "take", "takes", "taking", "come", "comes", "belong", "belongs", "belonging",
            "available", "present", "exist", "exists", "existing", "list", "show", "give", "get", "find", "tell", "display",
            "there", "out", "up", "one", "ones", "person", "people", "persons", "everyone", "everybody", "total",
            "number", "value", "amount", "level", "student's", "s", "them", "together", "overall", "made", "placed",
            "done", "happened", "got", "get", "gets", "scored", "score", "scoring", "costing", "cost", "priced",
            "under", "inside", "within", "whole", "entire", "college", "store", "shop", "database", "table", "we",
            "currently", "joined", "paid", "pay", "make", "makes", "making", "via", "using", "through", "by", "per", "each"));

    // ================================================================== the parse state
    private static final class Span {
        final int start, end;           // word positions, end exclusive
        final CueInfo cue;              // a cue word, or
        final List<Entry> entries;      // schema meanings, or
        final Word word;                // a single number / quoted / unknown word
        boolean used;                   // explained by the final query
        boolean absorbed;               // e.g. "department" in "Computer Science department"
        boolean negated;
        Entry chosen;

        Span(int s, int e, CueInfo c, List<Entry> en, Word w) { start = s; end = e; cue = c; entries = en; word = w; }
        boolean isCue(Cue c) { return cue != null && cue.cue() == c; }
        boolean isSchema() { return entries != null && !entries.isEmpty(); }
        boolean isNumber() { return word != null && word.isNumber(); }
        boolean has(Lexicon.Kind k) {
            if (!isSchema()) return false;
            for (Entry e : entries) if (e.kind() == k) return true;
            return false;
        }
        List<Entry> of(Lexicon.Kind k) {
            List<Entry> l = new ArrayList<>();
            if (entries != null) for (Entry e : entries) if (e.kind() == k) l.add(e);
            return l;
        }
    }

    /** A condition placed in WHERE. */
    private record Cond(TableSchema table, Column column, String op, List<Object> values, String sqlOverride, String why) {}

    private final Lexicon lex;
    private final Database db;
    private List<Word> words;
    private List<Span> spans;
    private Result out;
    private double penalty;
    private Span spansSumCue;
    private LocalDate today;

    private NlToSql(Lexicon lex) {
        this.lex = lex;
        this.db = lex.db;
    }

    public static Result translate(Lexicon lex, String question) {
        return new NlToSql(lex).run(question);
    }

    /** The question with its values and numbers taken out: used by the learning memory. */
    public record Masked(List<String> tokens, List<String> kinds, List<Object> values) {}

    /** "students from Ranchi with cgpa above 8" -> tokens [student, <students.city>, cgpa, above, <num>], values [Ranchi, 8]. */
    public static Masked mask(Lexicon lex, String question) {
        NlToSql n = new NlToSql(lex);
        n.out = new Result();
        n.words = NlTokenizer.tokenize(question);
        n.link();
        List<String> tokens = new ArrayList<>(), kinds = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Span sp : n.spans) {
            List<Entry> vals = sp.of(Lexicon.Kind.VALUE);
            if (!vals.isEmpty() && !sp.has(Lexicon.Kind.TABLE)) {
                Entry e = vals.get(0);
                String k = "<" + e.table().name + "." + e.column().name + ">";
                tokens.add(k); kinds.add(k); values.add(e.value());
            } else if (sp.isNumber()) {
                tokens.add("<num>"); kinds.add("<num>"); values.add(n.num(sp.word));
            } else if (sp.word != null && sp.word.kind == NlTokenizer.Kind.QUOTED) {
                tokens.add("<text>"); kinds.add("<text>"); values.add(sp.word.raw);
            } else if (sp.word != null && sp.word.kind == NlTokenizer.Kind.WORD && STOP.contains(sp.word.norm)) {
                // skip small words
            } else {
                StringBuilder sb = new StringBuilder();
                for (int k = sp.start; k < sp.end; k++) sb.append(k > sp.start ? " " : "").append(n.words.get(k).stem);
                tokens.add(sb.toString());
            }
        }
        return new Masked(tokens, kinds, values);
    }

    // ================================================================== main flow
    private Result run(String question) {
        out = new Result();
        today = Clock.today();
        words = NlTokenizer.tokenize(question);
        if (words.isEmpty()) return fail("Please type a question.");
        link();

        // ---------- 1. what kind of answer
        boolean count = any(Cue.COUNT), sum = any(Cue.SUM), avg = any(Cue.AVG), max = any(Cue.MAX), min = any(Cue.MIN);
        Span most = first(Cue.MOST), least = first(Cue.LEAST);
        Span superl = most != null && (least == null || most.start < least.start) ? most : least;
        if (superl == null) {   // "top 5 students", "bottom 3 products"
            Span top = first(Cue.TOP), bot = first(Cue.BOTTOM);
            if (top != null && !text(top).equals("first")) { superl = top; most = top; }
            else if (bot != null && !text(bot).equals("last")) { superl = bot; least = bot; }
        }
        boolean superDesc = superl != null && superl == most;
        boolean who = any(Cue.WHO) || startsWith("who");
        // "which student ..." / "what city ..." ask for rows; "what is the highest cgpa" asks for one value
        boolean which = startsWith("which") || (startsWith("what") && spans.size() > 1 && spans.get(1).isSchema());
        boolean listy = any(Cue.LIST) || who || which;
        boolean details = any(Cue.DETAILS);
        if (any(Cue.HOW_MUCH) && !count) sum = true;
        // "how many units", "how many credits": counting a number column means adding it up
        for (int k = 0; k + 1 < spans.size(); k++) {
            Span s = spans.get(k), t = spans.get(k + 1);
            if (s.isCue(Cue.COUNT) && t.has(Lexicon.Kind.COLUMN) && !t.has(Lexicon.Kind.TABLE)
                    && t.of(Lexicon.Kind.COLUMN).stream().allMatch(e -> e.column().type.numeric() && !Lexicon.isKey(e.table(), e.column()))) {
                count = false; sum = true; s.used = true;
                out.assumptions.add("\"" + text(s) + " " + text(t) + "\" was read as the total of " + t.of(Lexicon.Kind.COLUMN).get(0).column().label + ".");
                spansSumCue = s;
            }
        }

        // "total number of" is COUNT, "total" alone may be SUM or COUNT (decided by the measure below)
        // ---------- 2. absorb "department" in "Computer Science department", "semester" in "semester 3"
        absorbQualifiers();
        List<TableSchema> context = contextTables();

        // ---------- 3. explicit LIMIT ("top 5", "5 students")
        Long limit = findLimit();
        boolean bottom = any(Cue.BOTTOM) && (superl == null || !superl.isCue(Cue.BOTTOM));

        // ---------- 4. GROUP BY target
        Span groupSpan = findGroupTarget();
        // ---------- 5. measure column (what SUM / AVG / MAX / "highest" works on)
        Span aggCue = firstOf(Cue.SUM, Cue.AVG, Cue.MAX, Cue.MIN, Cue.MOST, Cue.LEAST, Cue.HOW_MUCH, Cue.ORDER, Cue.BY);
        Entry measure = findMeasure(context, groupSpan);

        // ---------- 6. "which department has the most students" / "which city do most students come from"
        Span superGroup = null;
        TableSchema countedTable = null;
        if (superl != null && groupSpan == null && (which || startsWith("which") || startsWith("what"))) {
            Span x = firstSchemaAfterQuestionWord();
            if (x != null && x.start < superl.start && (x.has(Lexicon.Kind.TABLE) || x.has(Lexicon.Kind.COLUMN))) {
                Span y = nextSchema(superl.end, Lexicon.Kind.TABLE, 4);
                TableSchema xTable = tableOf(x, context);
                if (y != null && y != x && measure == null) {
                    TableSchema yt = y.of(Lexicon.Kind.TABLE).get(0).table();
                    if (xTable != yt || x.has(Lexicon.Kind.COLUMN) && !x.has(Lexicon.Kind.TABLE)) {
                        superGroup = x;
                        countedTable = yt;
                        y.used = true;
                    }
                } else if (measure != null && xTable != null && (measure.table() != xTable || (!x.has(Lexicon.Kind.TABLE)))) {
                    superGroup = x;
                    if (!avg && !sum && !count) {
                        if ("rupees".equals(measure.column().unit) && measure.table() != xTable) {
                            sum = true;
                            out.assumptions.add("Compared the total " + measure.column().label + " of each " + labelOf(x) + ".");
                        } else {
                            avg = true;
                            out.assumptions.add("Compared the average " + measure.column().label + " of each " + labelOf(x) + ".");
                        }
                    }
                }
            }
        }
        if (superGroup == null && superl != null && groupSpan == null && measure == null) {
            Span x = null, y = null;
            for (Span s : spans) {
                if (s.absorbed || !s.has(Lexicon.Kind.TABLE) || s.has(Lexicon.Kind.VALUE)) continue;
                if (x == null && s.start < superl.start + 4) { x = s; continue; }
                if (x != null && s.start > superl.start && y == null) y = s;
            }
            if (x != null && y != null && x.start < y.start) {
                TableSchema xt = x.of(Lexicon.Kind.TABLE).get(0).table(), yt = y.of(Lexicon.Kind.TABLE).get(0).table();
                if (xt != yt && xt.nameColumn != null) {
                    superGroup = x;
                    countedTable = yt;
                    y.used = true;
                    markUsed(Cue.COUNT);
                    markUsed(Cue.BY);
                }
            }
        }
        if (superGroup != null) groupSpan = superGroup;

        // ---------- 7. main table
        TableSchema main = null;
        if (countedTable != null) main = countedTable;
        else if (measure != null && (sum || avg || max || min || superl != null || hasOrderColumn())) main = measure.table();
        if (main == null) main = firstTableMention(groupSpan);
        if (main == null && measure != null) main = measure.table();
        if (main == null) main = firstColumnOrValueTable(groupSpan, context);
        if (main == null && groupSpan != null) main = tableOf(groupSpan, context);
        if (main == null) return fail("I could not tell which table you mean. Try naming it, e.g. \"students\", \"courses\" or \"payments\".");
        out.mainTable = main.name;
        final TableSchema mainT = main;
        if (!context.contains(main)) context.add(0, main);
        else { context.remove(main); context.add(0, main); }

        // implicit measure from the cue ("cheapest" -> price, "latest" -> date, "best" -> cgpa)
        boolean wantsMeasure = sum || avg || max || min || superl != null;
        if (measure == null && wantsMeasure) {
            String hint = superl != null && superl.cue.hint() != null ? superl.cue.hint() : null;
            Column c = null;
            if ("date".equals(hint)) c = Lexicon.dateColumn(main);
            else if ("rupees".equals(hint)) c = unitColumn(main, "rupees");
            if (c == null && (sum && !count) && !avg) c = unitColumn(main, "rupees");
            if (c == null && countedTable == null && (avg || max || min || superl != null || sum)) {
                if (!(sum && !avg && !max && !min && superl == null) || unitColumn(main, "rupees") != null) c = Lexicon.defaultMeasure(main);
            }
            if (c == null && sum && !count && unitColumn(main, "rupees") == null) { count = true; sum = false; }
            if (c != null) {
                measure = new Entry(Lexicon.Kind.COLUMN, main, c, null, List.of(), false, c.label);
                if (superl != null && superl.cue.hint() == null && !(sum || avg || max || min))
                    out.assumptions.add("\"" + text(superl) + "\" was taken to mean the highest/lowest " + c.label + ".");
                else if (superl != null && "date".equals(superl.cue.hint()))
                    out.assumptions.add("\"" + text(superl) + "\" was taken from " + c.label + ".");
                penalty += 0.02;
            } else if (superl != null && countedTable == null && groupSpan == null) {
                penalty += 0.15;
                out.assumptions.add("I did not know what to rank by for \"" + text(superl) + "\".");
            }
        }
        if (sum && measure == null) { count = true; sum = false; }
        if (count && measure != null && !sum && !avg && !max && !min && superl == null) measure = null;   // "how many students with cgpa > 8"

        // ---------- 8. conditions
        List<Cond> conds = new ArrayList<>();
        conditionsFromValues(conds, context, groupSpan);
        conditionsFromNumbers(conds, context, main, measure, limit);
        conditionsFromTime(conds, main, context);
        conditionsFromText(conds, main);
        if (any(Cue.OUT_OF_STOCK)) {
            Column st = main.column("stock");
            if (st != null) { conds.add(new Cond(main, st, "=", List.of(0L), null, "out of stock")); markUsed(Cue.OUT_OF_STOCK); }
        }

        // ---------- 9. select list, grouping, ordering
        Builder b = new Builder(main);
        Entry groupCol = groupSpan == null ? null : groupColumn(groupSpan, context, main);
        if (groupSpan != null && groupCol == null) groupSpan = null;
        if (groupSpan != null) groupSpan.used = true;

        String aggFn = avg ? "AVG" : sum ? "SUM" : max ? "MAX" : min ? "MIN" : null;
        boolean ranking = superl != null && aggFn == null || superl != null && groupCol != null;
        if (superl != null && aggFn == null && measure != null && groupCol == null) {
            // "what is the highest cgpa" -> MAX value ; "which student has the highest cgpa" -> rows sorted
            boolean entityAsked = who || which || listy || limit != null || mentionsTable(main) || details;
            if (!entityAsked) { aggFn = superDesc ? "MAX" : "MIN"; ranking = false; }
        }
        for (Span s : spans) if (s == superl || s.isCue(Cue.MAX) || s.isCue(Cue.MIN) || s.isCue(Cue.AVG) || s.isCue(Cue.SUM) || s.isCue(Cue.COUNT) || s.isCue(Cue.HOW_MUCH)) s.used = true;

        for (Cond c : conds) b.where(c);

        if (groupCol != null) {
            // ---- grouped answer
            boolean nameCol = groupCol.column().name.equalsIgnoreCase(groupCol.table().nameColumn);
            b.select(groupCol.table(), groupCol.column(), nameCol ? groupCol.table().singular.replace(' ', '_') : null);
            b.group(groupCol.table(), groupCol.column());
            String agg;
            String alias;
            if (aggFn != null && measure != null) {
                agg = round(aggFn, b.ref(measure.table(), measure.column()));
                alias = aggWord(aggFn) + "_" + measure.column().name;
            } else {
                agg = b.countExpr(main, groupCol.table());
                alias = "number_of_" + main.name;
            }
            b.selectRaw(agg, alias);
            if (ranking) {
                b.order(alias, superDesc ? !bottom : bottom);
                b.limit = limit != null ? limit : 1L;
            } else if (groupCol.column().type.numeric() || groupCol.column().type == ColumnType.DATE) {
                b.order(b.ref(groupCol.table(), groupCol.column()), false);
            } else {
                b.order(alias, true);
                if (limit != null) b.limit = limit;
            }
        } else if (count) {
            b.selectRaw(b.countExpr(main, null), "number_of_" + main.name);
        } else if (aggFn != null && measure != null) {
            b.selectRaw(round(aggFn, b.ref(measure.table(), measure.column())), aggWord(aggFn) + "_" + measure.column().name);
        } else {
            // ---- list of rows
            List<Entry> shown = explicitColumns(context, groupSpan, measure);
            boolean distinct = false;
            if (details) b.star = true;
            else if (!shown.isEmpty()) {
                Span firstCol = firstSchemaAfterQuestionWord();
                distinct = shown.size() == 1 && shown.get(0).column().type == ColumnType.TEXT
                        && !shown.get(0).column().name.equalsIgnoreCase(shown.get(0).table().nameColumn)
                        && (any(Cue.DISTINCT) || firstCol != null && firstCol.has(Lexicon.Kind.COLUMN) && !firstCol.has(Lexicon.Kind.TABLE)
                            && firstTableMentionBefore(firstCol.start) == null);
                if (!distinct && !shown.get(0).column().name.equalsIgnoreCase(main.nameColumn) && main.nameColumn != null
                        && shown.stream().noneMatch(e -> e.table() == mainT && e.column().name.equalsIgnoreCase(mainT.nameColumn))
                        && shown.stream().allMatch(e -> e.table() != mainT || !Lexicon.isKey(mainT, e.column())))
                    b.select(main, main.column(main.nameColumn), null);
                if (main.nameColumn == null && !distinct && main.primaryKey != null) b.select(main, main.column(main.primaryKey), null);
                for (Entry e : shown) b.select(e.table(), e.column(), null);
            } else if (who && main.nameColumn != null) {
                b.select(main, main.column(main.nameColumn), null);
            } else {
                for (String c : main.displayColumns) b.select(main, main.column(c), null);
            }
            // show the other tables that were named without a condition ("courses with their teacher")
            for (Span s : spans) {
                if (!s.has(Lexicon.Kind.TABLE) || s.absorbed || s.used) continue;
                TableSchema t = s.of(Lexicon.Kind.TABLE).get(0).table();
                if (t == main || t.nameColumn == null) { s.used = true; continue; }
                boolean hasCond = conds.stream().anyMatch(c -> c.table() == t);
                if (!hasCond && !b.star) b.select(t, t.column(t.nameColumn), t.singular.replace(' ', '_'));
                s.used = true;
            }
            if (!b.star) for (Cond c : conds)
                if (c.table() == main && c.column() != null && c.values().size() == 1 && !c.op().equals("=") && !c.op().equals("LIKE"))
                    b.select(c.table(), c.column(), null);
            if (distinct) b.distinct = true;

            // ordering
            if (ranking && measure != null) {
                if (!b.star) b.select(measure.table(), measure.column(), null);
                boolean desc = superDesc != bottom;
                b.order(b.ref(measure.table(), measure.column()), desc);
                b.limit = limit != null ? limit : listPlural(main) ? 10L : 1L;
                if (limit == null) out.assumptions.add(b.limit == 1 ? "Showing only the top row." : "Showing the top " + b.limit + " rows.");
            } else {
                Entry orderCol = findOrderColumn(context);
                boolean desc = any(Cue.DESC);
                if (orderCol != null) {
                    if (!b.star) b.select(orderCol.table(), orderCol.column(), null);
                    b.order(b.ref(orderCol.table(), orderCol.column()), desc);
                } else if (any(Cue.ALPHA) && main.nameColumn != null) {
                    b.order(b.ref(main, main.column(main.nameColumn)), desc);
                    markUsed(Cue.ALPHA);
                } else if (distinct) {
                    b.order(b.ref(shown.get(0).table(), shown.get(0).column()), false);
                } else if (bottom && limit != null && Lexicon.dateColumn(main) != null) {
                    Column dc = Lexicon.dateColumn(main);
                    b.order(b.ref(main, dc), true);
                    out.assumptions.add("\"last\" was taken as the most recent by " + dc.label + ".");
                }
                if (limit != null) b.limit = limit;
            }
        }

        // ---------- 10. write SQL
        String sql = b.build();
        out.sql = sql;

        // ---------- 11. confidence
        score(main);
        return out;
    }

    // ================================================================== step 2: link words
    private void link() {
        spans = new ArrayList<>();
        int i = 0;
        while (i < words.size()) {
            Span best = null;
            for (int len = Math.min(Lexicon.MAX_WORDS, words.size() - i); len >= 1 && best == null; len--) {
                StringBuilder key = new StringBuilder();
                boolean ok = true;
                for (int k = i; k < i + len; k++) {
                    Word w = words.get(k);
                    if (w.kind == NlTokenizer.Kind.QUOTED) { ok = false; break; }
                    if (k > i) key.append(' ');
                    key.append(w.kind == NlTokenizer.Kind.NUMBER ? w.norm : w.stem);
                }
                if (!ok) continue;
                String k = key.toString();
                List<Entry> found = new ArrayList<>();
                for (Entry e : lex.lookup(k)) {
                    if (e.requireUpper() && !matchesUpper(i, len, e)) continue;
                    found.add(e);
                }
                CueInfo c = CUES.get(k);
                boolean hasValue = found.stream().anyMatch(e -> e.kind() == Lexicon.Kind.VALUE);
                boolean onlyNameStart = !found.isEmpty() && found.stream().allMatch(e -> e.kind() == Lexicon.Kind.NAME_START);
                if (len == 1 && words.get(i).isNumber() && found.isEmpty()) break;   // numbers handled below
                if (!found.isEmpty() && (hasValue || c == null) && !(onlyNameStart && c != null)) best = new Span(i, i + len, null, found, null);
                else if (c != null) best = new Span(i, i + len, c, null, null);
                else if (!found.isEmpty()) best = new Span(i, i + len, null, found, null);
            }
            if (best == null) best = new Span(i, i + 1, null, null, words.get(i));
            spans.add(best);
            i = best.end;
        }
        // NOT before a value or comparison
        for (int k = 0; k < spans.size(); k++) {
            Span s = spans.get(k);
            if (!s.isCue(Cue.NOT) && !s.isCue(Cue.NE)) continue;
            for (int j = k + 1; j < Math.min(spans.size(), k + 4); j++) {
                Span t = spans.get(j);
                if (t.has(Lexicon.Kind.VALUE)) { t.negated = true; s.used = true; break; }
                if (t.isCue(Cue.GT) || t.isCue(Cue.LT) || t.isCue(Cue.GE) || t.isCue(Cue.LE)) break;
            }
        }
    }

    private boolean matchesUpper(int start, int len, Entry e) {
        // short codes like "IT", "ECE", "O": the question must use capitals, so "it" the word is not a department
        StringBuilder raw = new StringBuilder();
        for (int k = start; k < start + len; k++) raw.append(words.get(k).raw);
        String r = raw.toString();
        if (e.kind() == Lexicon.Kind.NAME_START) return Character.isUpperCase(r.charAt(0));
        return r.equals(r.toUpperCase()) && !r.equals(r.toLowerCase());
    }

    // ================================================================== step 3 helpers
    private void absorbQualifiers() {
        for (int k = 0; k < spans.size(); k++) {
            Span s = spans.get(k);
            if (!s.has(Lexicon.Kind.VALUE)) continue;
            for (int d : new int[]{-1, 1, 2}) {
                int j = k + d;
                if (j < 0 || j >= spans.size()) continue;
                Span t = spans.get(j);
                if (d == 2 && !isStop(spans.get(k + 1))) continue;
                if (!t.isSchema() || t.has(Lexicon.Kind.VALUE)) continue;
                for (Entry v : s.of(Lexicon.Kind.VALUE)) {
                    boolean sameTable = t.of(Lexicon.Kind.TABLE).stream().anyMatch(e -> e.table() == v.table());
                    boolean sameCol = t.of(Lexicon.Kind.COLUMN).stream().anyMatch(e -> e.column() == v.column());
                    if (sameCol || (sameTable && v.table() != null && !isPersonTable(v.table()) && d != -1)
                            || (sameTable && d == 1 && v.table().name.equals("departments"))) {
                        t.absorbed = true;
                        t.used = true;
                    }
                }
            }
        }
    }

    private static boolean isPersonTable(TableSchema t) {
        return t.name.equals("students") || t.name.equals("faculty") || t.name.equals("customers");
    }

    /** Tables clearly named in the question (or owning a clearly named value). */
    private List<TableSchema> contextTables() {
        List<TableSchema> l = new ArrayList<>();
        for (Span s : spans) {
            if (s.absorbed) continue;
            for (Entry e : s.of(Lexicon.Kind.TABLE)) if (!l.contains(e.table())) l.add(e.table());
        }
        for (Span s : spans) {
            List<Entry> v = s.of(Lexicon.Kind.VALUE);
            if (v.size() == 1 && !l.contains(v.get(0).table())) l.add(v.get(0).table());
        }
        for (Span s : spans) {
            List<Entry> c = s.of(Lexicon.Kind.COLUMN);
            Set<TableSchema> ts = new HashSet<>();
            for (Entry e : c) ts.add(e.table());
            if (ts.size() == 1 && !l.contains(c.get(0).table())) l.add(c.get(0).table());
        }
        return l;
    }

    private Long findLimit() {
        for (int k = 0; k < spans.size(); k++) {
            Span s = spans.get(k);
            if (s.isCue(Cue.TOP) || s.isCue(Cue.BOTTOM) || s.isCue(Cue.LIMIT) || s.isCue(Cue.MOST) && text(s).equals("top")) {
                for (int j = k + 1; j < Math.min(spans.size(), k + 3); j++) {
                    Span t = spans.get(j);
                    if (t.isNumber() && t.word.isWholeNumber() && !t.word.isYear() && t.word.number > 0) {
                        t.used = true; s.used = true;
                        return t.word.number.longValue();
                    }
                    if (!isStop(t)) break;
                }
                if (s.isCue(Cue.TOP) && text(s).equals("first")) s.used = true;
            }
        }
        // "show 5 students", "3 best students", "5 most expensive products"
        for (int k = 0; k + 1 < spans.size(); k++) {
            Span s = spans.get(k);
            if (!s.isNumber() || !s.word.isWholeNumber() || s.word.ordinal || s.word.isYear() || s.word.unit != null || s.used) continue;
            Span n = spans.get(k + 1);
            if (n.isCue(Cue.MOST) || n.isCue(Cue.LEAST)) n = k + 2 < spans.size() ? spans.get(k + 2) : n;
            if (n.has(Lexicon.Kind.TABLE) && !n.absorbed) {
                boolean afterCompare = k > 0 && isCompare(spans.get(k - 1));
                if (!afterCompare) { s.used = true; return s.word.number.longValue(); }
            }
        }
        return null;
    }

    private Span findGroupTarget() {
        for (int k = 0; k < spans.size(); k++) {
            Span s = spans.get(k);
            if (s.isCue(Cue.GROUP)) {
                Span t = nextSchemaSpan(k + 1, 3);
                if (t != null && (t.has(Lexicon.Kind.TABLE) || t.has(Lexicon.Kind.COLUMN))) { s.used = true; t.used = true; return t; }
            }
            if (s.isCue(Cue.GROUP_AFTER) && k > 0) {
                Span t = spans.get(k - 1);
                if (t.has(Lexicon.Kind.TABLE) || t.has(Lexicon.Kind.COLUMN)) { s.used = true; t.used = true; return t; }
            }
            if (s.isCue(Cue.BY) && (any(Cue.COUNT) || any(Cue.SUM) || any(Cue.AVG) || any(Cue.MAX) || any(Cue.MIN) || any(Cue.HOW_MUCH))
                    && !any(Cue.MOST) && !any(Cue.LEAST)) {
                Span t = nextSchemaSpan(k + 1, 2);
                if (t != null && !t.has(Lexicon.Kind.VALUE) && (t.has(Lexicon.Kind.TABLE) || t.has(Lexicon.Kind.COLUMN))) {
                    s.used = true; t.used = true; return t;
                }
            }
        }
        return null;
    }

    private Entry findMeasure(List<TableSchema> context, Span groupSpan) {
        // the numeric column right after (or before) an aggregate / superlative / "by" / "sorted by" word
        for (int k = 0; k < spans.size(); k++) {
            Span s = spans.get(k);
            boolean agg = s == spansSumCue || s.isCue(Cue.SUM) || s.isCue(Cue.AVG) || s.isCue(Cue.MAX) || s.isCue(Cue.MIN) || s.isCue(Cue.MOST)
                    || s.isCue(Cue.LEAST) || s.isCue(Cue.HOW_MUCH) || (s.isCue(Cue.BY) && (any(Cue.MOST) || any(Cue.LEAST) || any(Cue.TOP)))
                    || s.isCue(Cue.TOP);
            if (!agg) continue;
            for (int j = k + 1; j < Math.min(spans.size(), k + 5); j++) {
                Span t = spans.get(j);
                if (t == groupSpan) break;
                if (isCompare(t)) break;
                Entry c = numericColumn(t, context);
                if (c != null && !t.absorbed) { t.used = true; return c; }
                if (t.isCue(Cue.SUM) || t.isCue(Cue.AVG) || t.isCue(Cue.MOST) || t.isCue(Cue.LEAST)) continue;
            }
        }
        // "salary average", "cgpa highest"
        for (int k = 1; k < spans.size(); k++) {
            Span s = spans.get(k);
            if (!(s.isCue(Cue.SUM) || s.isCue(Cue.AVG) || s.isCue(Cue.MAX) || s.isCue(Cue.MIN) || s.isCue(Cue.MOST) || s.isCue(Cue.LEAST))) continue;
            int j = k - 1;
            if (j > 0 && isStop(spans.get(j))) j--;
            Entry c = numericColumn(spans.get(j), context);
            if (c != null) { spans.get(j).used = true; return c; }
        }
        return null;
    }

    private boolean hasOrderColumn() { return any(Cue.ORDER); }

    private Entry findOrderColumn(List<TableSchema> context) {
        for (int k = 0; k < spans.size(); k++) {
            Span s = spans.get(k);
            if (!(s.isCue(Cue.ORDER) || s.isCue(Cue.BY))) continue;
            Span t = nextSchemaSpan(k + 1, 3);
            if (t == null || t.has(Lexicon.Kind.VALUE)) continue;
            Entry c = column(t, context);
            if (c != null) {
                s.used = true; t.used = true;
                markUsed(Cue.ASC); markUsed(Cue.DESC);
                return c;
            }
            if (t.has(Lexicon.Kind.TABLE)) {
                TableSchema tb = t.of(Lexicon.Kind.TABLE).get(0).table();
                if (tb.nameColumn != null) { s.used = true; t.used = true; markUsed(Cue.ASC); markUsed(Cue.DESC);
                    return new Entry(Lexicon.Kind.COLUMN, tb, tb.column(tb.nameColumn), null, List.of(), false, ""); }
            }
        }
        return null;
    }

    // ================================================================== conditions
    private void conditionsFromValues(List<Cond> conds, List<TableSchema> context, Span groupSpan) {
        Map<String, List<Span>> byColumn = new LinkedHashMap<>();
        Map<String, Entry> pick = new HashMap<>();
        for (Span s : spans) {
            if (s == groupSpan) continue;
            List<Entry> vals = s.of(Lexicon.Kind.VALUE);
            List<Entry> starts = s.of(Lexicon.Kind.NAME_START);
            if (vals.isEmpty() && starts.isEmpty()) continue;
            if (vals.isEmpty()) {
                // a first name only: "Priya" -> name LIKE 'Priya %'
                Entry e = best(starts, context);
                if (s.has(Lexicon.Kind.TABLE) || s.has(Lexicon.Kind.COLUMN)) continue;
                conds.add(new Cond(e.table(), e.column(), "LIKE", List.of(e.value() + " %"), null, "first name"));
                s.used = true; s.chosen = e;
                penalty += 0.05;
                out.assumptions.add("\"" + text(s) + "\" was matched as a first name.");
                continue;
            }
            if ((s.has(Lexicon.Kind.TABLE) || s.has(Lexicon.Kind.COLUMN)) && !preferValue(s, vals, context)) continue;
            Entry e = best(vals, context);
            if (vals.size() > 1) {
                long distinctCols = vals.stream().map(v -> v.table().name + "." + v.column().name).distinct().count();
                if (distinctCols > 1 && !context.contains(e.table())) penalty += 0.05;
            }
            s.used = true; s.chosen = e;
            String key = e.table().name + "." + e.column().name + (s.negated ? "!" : "");
            byColumn.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            pick.put(key, e);
        }
        for (Map.Entry<String, List<Span>> g : byColumn.entrySet()) {
            Entry e = pick.get(g.getKey());
            List<Object> vals = new ArrayList<>();
            for (Span s : g.getValue()) if (!vals.contains(s.chosen.value())) vals.add(s.chosen.value());
            boolean neg = g.getKey().endsWith("!");
            String op = vals.size() > 1 ? (neg ? "NOT IN" : "IN") : (neg ? "!=" : "=");
            conds.add(new Cond(e.table(), e.column(), op, vals, null, "value"));
        }
        // "or" between two values of different columns is not supported: say so
        for (int k = 1; k + 1 < spans.size(); k++) {
            if (!spans.get(k).isCue(Cue.OR)) continue;
            Span a = spans.get(k - 1), c = spans.get(k + 1);
            if (a.chosen != null && c.chosen != null && a.chosen.column() == c.chosen.column()) spans.get(k).used = true;
        }
        for (Span s : spans) if (s.isCue(Cue.AND)) s.used = true;
    }

    private boolean preferValue(Span s, List<Entry> vals, List<TableSchema> context) {
        // "professors", "girls": a value of the same table it names -> the value (a condition) is meant
        for (Entry v : vals) if (s.of(Lexicon.Kind.TABLE).stream().anyMatch(t -> t.table() == v.table())) return true;
        // "hostel" can be the column hosteller or the fee purpose Hostel: choose by the tables in the question
        for (Entry v : vals) if (context.contains(v.table()) && !s.of(Lexicon.Kind.TABLE).stream().anyMatch(t -> t.table() == v.table())) return true;
        return !s.has(Lexicon.Kind.TABLE) && s.of(Lexicon.Kind.COLUMN).stream().noneMatch(c -> context.contains(c.table()));
    }

    private void conditionsFromNumbers(List<Cond> conds, List<TableSchema> context, TableSchema main, Entry measure, Long limit) {
        for (int k = 0; k < spans.size(); k++) {
            Span s = spans.get(k);
            // ---- BETWEEN a AND b
            if (s.isCue(Cue.BETWEEN)) {
                int a = nextNumber(k + 1, 2), and = a < 0 ? -1 : a + 1;
                if (a >= 0 && and < spans.size() && (spans.get(and).isCue(Cue.AND) || text(spans.get(and)).equals("to"))) {
                    int b = nextNumber(and + 1, 1);
                    if (b >= 0) {
                        Word wa = spans.get(a).word, wb = spans.get(b).word;
                        if (wa.isYear() && wb.isYear()) {
                            Entry yc = yearTarget(k, context, main);
                            if (yc != null) {
                                addYearRange(conds, yc, wa.number.longValue(), wb.number.longValue());
                                markSpans(s, spans.get(a), spans.get(and), spans.get(b));
                                continue;
                            }
                        }
                        Entry col = columnNear(k, b, context, main, wa.unit, measure);
                        if (col != null) {
                            conds.add(new Cond(col.table(), col.column(), "BETWEEN", List.of(num(wa), num(wb)), null, "between"));
                            markSpans(s, spans.get(a), spans.get(and), spans.get(b));
                        }
                    }
                }
                continue;
            }
            // ---- comparison followed by a number: "cgpa above 8", "more than 50000 rupees"
            String op = compareOp(s);
            if (op != null) {
                int n = nextNumber(k + 1, 2);
                if (n < 0) continue;
                Word w = spans.get(n).word;
                boolean neg = k > 0 && spans.get(k - 1).isCue(Cue.NOT);
                if (neg) op = flip(op);
                if (w.isYear()) {
                    Entry yc = yearTarget(k, context, main);
                    if (yc != null) {
                        long y = w.number.longValue();
                        addYearCompare(conds, yc, op, y);
                        markSpans(s, spans.get(n));
                        if (neg) spans.get(k - 1).used = true;
                        continue;
                    }
                }
                Entry col = columnNear(k, n, context, main, w.unit, measure);
                if (col == null) continue;
                conds.add(new Cond(col.table(), col.column(), op, List.of(num(w)), null, "compare"));
                markSpans(s, spans.get(n));
                if (neg) spans.get(k - 1).used = true;
                continue;
            }
            // ---- "2 or more", "8 and above" (number first, then the comparison)
            if (s.isNumber() && !s.used && k + 1 < spans.size()) {
                Span nx = spans.get(k + 1);
                if (nx.isCue(Cue.GE) || nx.isCue(Cue.LE)) {
                    String o = nx.isCue(Cue.GE) ? ">=" : "<=";
                    String t = text(nx);
                    if (t.startsWith("or") || t.startsWith("and")) {
                        Entry col = columnNear(k, k + 1, context, main, s.word.unit, measure);
                        if (col != null) { conds.add(new Cond(col.table(), col.column(), o, List.of(num(s.word)), null, "compare")); markSpans(s, nx); }
                    }
                }
            }
        }
        // ---- time comparisons with a year: "after 2024", "since 2025", "before 2020"
        for (int k = 0; k < spans.size(); k++) {
            Span s = spans.get(k);
            if (!(s.isCue(Cue.AFTER) || s.isCue(Cue.BEFORE) || s.isCue(Cue.SINCE))) continue;
            int n = nextNumber(k + 1, 2);
            if (n < 0 || spans.get(n).used || !spans.get(n).word.isYear()) continue;
            Entry yc = yearTarget(k, context, main);
            if (yc == null) continue;
            String op = s.isCue(Cue.AFTER) ? ">" : s.isCue(Cue.BEFORE) ? "<" : ">=";
            addYearCompare(conds, yc, op, spans.get(n).word.number.longValue());
            markSpans(s, spans.get(n));
        }
        // ---- plain numbers next to a column: "semester 3", "3rd semester", "4 credits", "admitted in 2024", "2025 batch"
        for (int k = 0; k < spans.size(); k++) {
            Span s = spans.get(k);
            if (!s.isNumber() || s.used) continue;
            Word w = s.word;
            Entry col = null;
            Span colSpan = null;
            for (int d : new int[]{-1, 1, -2, 2}) {
                int j = k + d;
                if (j < 0 || j >= spans.size()) continue;
                if (Math.abs(d) == 2 && !isStop(spans.get(k + d / 2))) continue;
                Span t = spans.get(j);
                Entry c = w.isYear() ? yearColumn(t, context) : numericColumn(t, context);
                if (c == null && t.has(Lexicon.Kind.TABLE) && !t.has(Lexicon.Kind.COLUMN)) continue;
                if (c != null && (!t.used || t.absorbed)) { col = c; colSpan = t; break; }
            }
            if (col == null && w.isYear()) {
                Entry yc = yearTarget(k, context, main);
                if (yc != null) { addYearRange(conds, yc, w.number.longValue(), w.number.longValue()); s.used = true; continue; }
            }
            if (col == null) continue;
            if (col.column().type == ColumnType.DATE && w.isYear()) addYearRange(conds, col, w.number.longValue(), w.number.longValue());
            else conds.add(new Cond(col.table(), col.column(), "=", List.of(num(w)), null, "equals"));
            s.used = true;
            colSpan.used = true;
            int prev = k - 1;
            if (prev >= 0 && spans.get(prev).isCue(Cue.EQ)) spans.get(prev).used = true;
        }
    }

    private void conditionsFromTime(List<Cond> conds, TableSchema main, List<TableSchema> context) {
        Column dc = Lexicon.dateColumn(main);
        TableSchema dt = main;
        if (dc == null) {
            for (TableSchema t : context) if (Lexicon.dateColumn(t) != null) { dc = Lexicon.dateColumn(t); dt = t; break; }
        }
        // a date column named in the question wins ("joined", "paid on")
        for (Span s : spans) {
            for (Entry e : s.of(Lexicon.Kind.COLUMN))
                if (e.column().type == ColumnType.DATE && context.contains(e.table())) { dc = e.column(); dt = e.table(); }
        }
        if (dc == null) return;
        for (int k = 0; k < spans.size(); k++) {
            Span s = spans.get(k);
            LocalDate from = null, to = null;
            if (s.isCue(Cue.THIS_YEAR)) { from = today.withDayOfYear(1); to = today; }
            else if (s.isCue(Cue.LAST_YEAR)) { from = today.minusYears(1).withDayOfYear(1); to = today.withDayOfYear(1).minusDays(1); }
            else if (s.isCue(Cue.THIS_MONTH)) { from = today.withDayOfMonth(1); to = today; }
            else if (s.isCue(Cue.LAST_MONTH)) { from = today.minusMonths(1).withDayOfMonth(1); to = today.withDayOfMonth(1).minusDays(1); }
            else if (s.isCue(Cue.TODAY)) { from = today; to = today; }
            else if (s.isCue(Cue.YESTERDAY)) { from = today.minusDays(1); to = from; }
            else if (s.isCue(Cue.LAST_N) || s.isCue(Cue.BOTTOM) && text(s).equals("last")) {
                int n = k + 1 < spans.size() && spans.get(k + 1).isNumber() ? k + 1 : -1;
                Span unit = n >= 0 && n + 1 < spans.size() ? spans.get(n + 1) : (k + 1 < spans.size() ? spans.get(k + 1) : null);
                long amount = n >= 0 ? spans.get(n).word.number.longValue() : 1;
                if (unit == null) continue;
                String u = text(unit);
                if (u.startsWith("day")) from = today.minusDays(amount);
                else if (u.startsWith("week")) from = today.minusWeeks(amount);
                else if (u.startsWith("month")) from = today.minusMonths(amount);
                else if (u.startsWith("year")) from = today.minusYears(amount);
                else continue;
                to = today;
                unit.used = true;
                if (n >= 0) spans.get(n).used = true;
            } else if (s.isCue(Cue.MONTH)) {
                int m = Arrays.asList(MONTHS).indexOf(s.cue.hint()) + 1;
                int year = today.getMonthValue() >= m ? today.getYear() : today.getYear() - 1;
                if (k + 1 < spans.size() && spans.get(k + 1).isNumber() && spans.get(k + 1).word.isYear()) {
                    year = spans.get(k + 1).word.number.intValue();
                    spans.get(k + 1).used = true;
                }
                from = LocalDate.of(year, m, 1);
                to = from.plusMonths(1).minusDays(1);
            }
            if (from == null) continue;
            s.used = true;
            for (Span t : spans) for (Entry e : t.of(Lexicon.Kind.COLUMN)) if (e.column() == dc) t.used = true;
            String ref = dt.name + "." + dc.name;
            conds.add(new Cond(dt, dc, "BETWEEN", List.of(from.toString(), to.toString()), null, "time"));
            if (!ref.isEmpty()) out.assumptions.add("Dates were checked on " + dc.label + " (" + from + " to " + to + ").");
        }
    }

    private void conditionsFromText(List<Cond> conds, TableSchema main) {
        for (int k = 0; k < spans.size(); k++) {
            Span s = spans.get(k);
            String pat = null;
            if (s.isCue(Cue.STARTS)) pat = "%s%%";
            else if (s.isCue(Cue.ENDS)) pat = "%%%s";
            else if (s.isCue(Cue.CONTAINS)) pat = "%%%s%%";
            else if (s.isCue(Cue.NAMED)) pat = "%%%s%%";
            if (pat == null || k + 1 >= spans.size()) continue;
            Span t = spans.get(k + 1);
            if (t.isSchema() && t.has(Lexicon.Kind.COLUMN) && k + 2 < spans.size()) t = spans.get(k + 2);
            String txt;
            if (t.word != null && (t.word.kind == NlTokenizer.Kind.QUOTED || t.word.kind == NlTokenizer.Kind.WORD)) txt = t.word.raw;
            else if (t.isSchema() || t.cue != null) txt = text(t);
            else continue;
            if (txt.isBlank()) continue;
            // "letter A" -> A
            if (txt.equalsIgnoreCase("letter") && k + 2 < spans.size() && spans.get(k + 2).word != null) { t.used = true; t = spans.get(k + 2); txt = t.word.raw; }
            Column nc = main.nameColumn == null ? null : main.column(main.nameColumn);
            if (nc == null) continue;
            if (t.chosen != null) {   // it was already taken as a value: undo that and use LIKE instead
                Span tt = t;
                conds.removeIf(c -> c.values().size() == 1 && tt.chosen != null && Objects.equals(c.values().get(0), tt.chosen.value()));
            }
            conds.add(new Cond(main, nc, "LIKE", List.of(String.format(pat, txt.replace("%", "").replace("_", ""))), null, "text"));
            s.used = true;
            t.used = true;
        }
        // a quoted text that nothing else used: search the name column
        for (Span s : spans) {
            if (s.word == null || s.word.kind != NlTokenizer.Kind.QUOTED || s.used) continue;
            Column nc = main.nameColumn == null ? null : main.column(main.nameColumn);
            if (nc == null) continue;
            conds.add(new Cond(main, nc, "LIKE", List.of("%" + s.word.raw.replace("%", "") + "%"), null, "text"));
            s.used = true;
        }
    }

    private void addYearRange(List<Cond> conds, Entry col, long fromYear, long toYear) {
        if (col.column().type == ColumnType.DATE)
            conds.add(new Cond(col.table(), col.column(), "BETWEEN", List.of(fromYear + "-01-01", toYear + "-12-31"), null, "year"));
        else if (fromYear == toYear) conds.add(new Cond(col.table(), col.column(), "=", List.of(fromYear), null, "year"));
        else conds.add(new Cond(col.table(), col.column(), "BETWEEN", List.of(fromYear, toYear), null, "year"));
    }

    private void addYearCompare(List<Cond> conds, Entry col, String op, long y) {
        if (col.column().type == ColumnType.DATE) {
            String v = switch (op) {
                case ">" -> y + "-12-31";
                case ">=" -> y + "-01-01";
                case "<" -> y + "-01-01";
                case "<=" -> y + "-12-31";
                default -> y + "-01-01";
            };
            conds.add(new Cond(col.table(), col.column(), op, List.of(v), null, "year"));
        } else conds.add(new Cond(col.table(), col.column(), op, List.of(y), null, "year"));
    }

    // ================================================================== finding columns
    private Entry numericColumn(Span s, List<TableSchema> context) {
        if (s == null || !s.isSchema()) return null;
        List<Entry> cols = new ArrayList<>();
        for (Entry e : s.of(Lexicon.Kind.COLUMN))
            if (e.column().type.numeric() && !Lexicon.isKey(e.table(), e.column())) cols.add(e);
        if (cols.isEmpty()) return null;
        return best(cols, context);
    }

    private Entry yearColumn(Span s, List<TableSchema> context) {
        if (s == null || !s.isSchema()) return null;
        List<Entry> cols = new ArrayList<>();
        for (Entry e : s.of(Lexicon.Kind.COLUMN))
            if (e.column().type == ColumnType.DATE || e.column().type == ColumnType.INT && e.column().name.contains("year")) cols.add(e);
        if (cols.isEmpty()) return null;
        return best(cols, context);
    }

    private Entry column(Span s, List<TableSchema> context) {
        if (s == null || !s.isSchema()) return null;
        List<Entry> cols = s.of(Lexicon.Kind.COLUMN);
        if (cols.isEmpty()) return null;
        return best(cols, context);
    }

    /** The column a number is compared with: before the comparison word, after the number, by unit, or the default. */
    private Entry columnNear(int cmp, int numberPos, List<TableSchema> context, TableSchema main, String unit, Entry measure) {
        for (int j = cmp - 1; j >= Math.max(0, cmp - 4); j--) {
            Span t = spans.get(j);
            Entry c = numericColumn(t, context);
            if (c != null) { t.used = true; return c; }
            if (isCompare(t) || t.has(Lexicon.Kind.VALUE)) break;
        }
        for (int j = numberPos + 1; j < Math.min(spans.size(), numberPos + 3); j++) {
            Span t = spans.get(j);
            Entry c = numericColumn(t, context);
            if (c != null) { t.used = true; return c; }
        }
        if (unit != null) {
            for (TableSchema t : context) {
                Column c = unitColumn(t, unit);
                if (c != null) return new Entry(Lexicon.Kind.COLUMN, t, c, null, List.of(), false, c.label);
            }
        }
        if (measure != null) return measure;
        Column d = Lexicon.defaultMeasure(main);
        if (d != null) {
            penalty += 0.08;
            out.assumptions.add("The number was compared with " + d.label + ".");
            return new Entry(Lexicon.Kind.COLUMN, main, d, null, List.of(), false, d.label);
        }
        return null;
    }

    private Entry yearTarget(int near, List<TableSchema> context, TableSchema main) {
        for (int j = Math.max(0, near - 3); j < Math.min(spans.size(), near + 4); j++) {
            Entry c = yearColumn(spans.get(j), context);
            if (c != null) { spans.get(j).used = true; return c; }
        }
        List<TableSchema> order = new ArrayList<>();
        if (main != null) order.add(main);
        order.addAll(context);
        for (TableSchema t : order) {
            if (t == null) continue;
            Column y = Lexicon.yearColumn(t);
            if (y != null) return new Entry(Lexicon.Kind.COLUMN, t, y, null, List.of(), false, y.label);
            Column d = Lexicon.dateColumn(t);
            if (d != null) return new Entry(Lexicon.Kind.COLUMN, t, d, null, List.of(), false, d.label);
        }
        return null;
    }

    private static Column unitColumn(TableSchema t, String unit) {
        for (Column c : t.columns) if (unit.equals(c.unit) && !c.sensitive) return c;
        for (Column c : t.columns) if (unit.equals(c.unit)) return c;
        return null;
    }

    private Entry groupColumn(Span g, List<TableSchema> context, TableSchema main) {
        Entry c = column(g, context);
        if (c != null && !(g.has(Lexicon.Kind.TABLE) && c.column().name.equalsIgnoreCase(c.table().primaryKey))) {
            // "department" is both a table and departments.name: use the name column
            return c;
        }
        List<Entry> ts = g.of(Lexicon.Kind.TABLE);
        if (!ts.isEmpty()) {
            TableSchema t = ts.get(0).table();
            if (t == main && main.nameColumn != null) return new Entry(Lexicon.Kind.COLUMN, t, t.column(t.nameColumn), null, List.of(), false, "");
            if (t.nameColumn != null) return new Entry(Lexicon.Kind.COLUMN, t, t.column(t.nameColumn), null, List.of(), false, "");
        }
        return c;
    }

    private List<Entry> explicitColumns(List<TableSchema> context, Span groupSpan, Entry measure) {
        List<Entry> l = new ArrayList<>();
        for (Span s : spans) {
            if (s == groupSpan || s.absorbed || s.has(Lexicon.Kind.VALUE)) continue;
            if (s.used && !(measure != null && s.of(Lexicon.Kind.COLUMN).stream().anyMatch(e -> e.column() == measure.column()))) continue;
            Entry c = column(s, context);
            if (c == null) continue;
            if (s.has(Lexicon.Kind.TABLE) && c.column().name.equalsIgnoreCase(c.table().nameColumn) == false
                    && s.of(Lexicon.Kind.TABLE).get(0).table() != c.table()) continue;
            if (s.has(Lexicon.Kind.TABLE)) continue;   // "department" as a word means the table, not a column
            boolean dup = l.stream().anyMatch(e -> e.column() == c.column());
            if (!dup) l.add(c);
            s.used = true;
        }
        return l;
    }

    /** Picks the meaning whose table is most connected to the question. */
    private Entry best(List<Entry> options, List<TableSchema> context) {
        Entry best = options.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (Entry e : options) {
            int score = 0;
            int idx = context.indexOf(e.table());
            if (idx >= 0) score += 100 - idx * 5;
            else {
                int dist = context.isEmpty() ? 3 : distance(context.get(0), e.table());
                score -= dist * 10;
            }
            if (score > bestScore) { bestScore = score; best = e; }
        }
        return best;
    }

    private TableSchema tableOf(Span s, List<TableSchema> context) {
        if (s == null) return null;
        List<Entry> t = s.of(Lexicon.Kind.TABLE);
        if (!t.isEmpty()) return t.get(0).table();
        Entry c = column(s, context);
        return c == null ? null : c.table();
    }

    private TableSchema firstTableMention(Span skip) {
        for (Span s : spans) {
            if (s == skip || s.absorbed || s.has(Lexicon.Kind.VALUE)) continue;
            List<Entry> t = s.of(Lexicon.Kind.TABLE);
            if (!t.isEmpty() && !s.has(Lexicon.Kind.COLUMN)) { s.used = true; return t.get(0).table(); }
        }
        for (Span s : spans) {   // a word that is both table and column ("department")
            if (s == skip || s.absorbed || s.has(Lexicon.Kind.VALUE)) continue;
            List<Entry> t = s.of(Lexicon.Kind.TABLE);
            if (!t.isEmpty()) { s.used = true; return t.get(0).table(); }
        }
        return null;
    }

    private TableSchema firstTableMentionBefore(int pos) {
        for (Span s : spans) {
            if (s.start >= pos) break;
            if (!s.absorbed && s.has(Lexicon.Kind.TABLE)) return s.of(Lexicon.Kind.TABLE).get(0).table();
        }
        return null;
    }

    private TableSchema firstColumnOrValueTable(Span skip, List<TableSchema> context) {
        for (Span s : spans) {
            if (s == skip) continue;
            if (s.has(Lexicon.Kind.COLUMN)) return column(s, context).table();
            if (s.has(Lexicon.Kind.VALUE)) return best(s.of(Lexicon.Kind.VALUE), context).table();
        }
        return null;
    }

    private boolean mentionsTable(TableSchema t) {
        for (Span s : spans) for (Entry e : s.of(Lexicon.Kind.TABLE)) if (e.table() == t && !s.absorbed) return true;
        return false;
    }

    private boolean listPlural(TableSchema t) {
        for (Span s : spans) {
            if (s.absorbed) continue;
            for (Entry e : s.of(Lexicon.Kind.TABLE))
                if (e.table() == t) {
                    String raw = text(s);
                    if (raw.endsWith("s") || raw.equals("faculty") || raw.equals("staff")) return true;
                }
        }
        return false;
    }

    private Span firstSchemaAfterQuestionWord() {
        for (Span s : spans) if (s.isSchema() && !s.absorbed) return s;
        return null;
    }

    private Span nextSchema(int fromWord, Lexicon.Kind kind, int within) {
        for (Span s : spans) {
            if (s.start < fromWord) continue;
            if (s.start > fromWord + within) break;
            if (s.has(kind) && !s.absorbed) return s;
        }
        return null;
    }

    private Span nextSchemaSpan(int fromSpan, int within) {
        for (int j = fromSpan; j < Math.min(spans.size(), fromSpan + within); j++) {
            Span t = spans.get(j);
            if (t.isSchema()) return t;
            if (!isStop(t)) return null;
        }
        return null;
    }

    private int nextNumber(int fromSpan, int within) {
        for (int j = fromSpan; j < Math.min(spans.size(), fromSpan + within + 1); j++) {
            Span t = spans.get(j);
            if (t.isNumber()) return t.used ? -1 : j;
            if (!isStop(t) && !(t.word != null && CURRENCY_WORDS.contains(t.word.norm))) return -1;
        }
        return -1;
    }
    private static final Set<String> CURRENCY_WORDS = Set.of("rs", "inr", "₹");

    private String compareOp(Span s) {
        if (s.isCue(Cue.GT)) return ">";
        if (s.isCue(Cue.GE)) { String t = text(s); return t.startsWith("or ") || t.startsWith("and ") ? null : ">="; }
        if (s.isCue(Cue.LT)) return "<";
        if (s.isCue(Cue.LE)) { String t = text(s); return t.startsWith("or ") || t.startsWith("and ") ? null : "<="; }
        if (s.isCue(Cue.EQ)) return "=";
        if (s.isCue(Cue.NE)) return "!=";
        return null;
    }

    private static String flip(String op) {
        return switch (op) {
            case ">" -> "<=";
            case ">=" -> "<";
            case "<" -> ">=";
            case "<=" -> ">";
            case "=" -> "!=";
            default -> "=";
        };
    }

    private boolean isCompare(Span s) {
        return s.isCue(Cue.GT) || s.isCue(Cue.GE) || s.isCue(Cue.LT) || s.isCue(Cue.LE) || s.isCue(Cue.EQ) || s.isCue(Cue.BETWEEN);
    }

    private boolean isStop(Span s) {
        if (s.word != null && s.word.kind == NlTokenizer.Kind.WORD && STOP.contains(s.word.norm)) return true;
        return s.isCue(Cue.WHICH);
    }

    private Object num(Word w) {
        return w.isWholeNumber() ? (Object) w.number.longValue() : (Object) w.number;
    }

    // ================================================================== small helpers
    private boolean any(Cue c) {
        for (Span s : spans) if (s.isCue(c)) return true;
        return false;
    }
    private Span first(Cue c) {
        for (Span s : spans) if (s.isCue(c)) return s;
        return null;
    }
    private Span firstOf(Cue... cs) {
        for (Span s : spans) for (Cue c : cs) if (s.isCue(c)) return s;
        return null;
    }
    private void markUsed(Cue c) { for (Span s : spans) if (s.isCue(c)) s.used = true; }
    private static void markSpans(Span... ss) { for (Span s : ss) s.used = true; }
    private boolean startsWith(String w) { return !words.isEmpty() && words.get(0).norm.equals(w); }

    private String text(Span s) {
        StringBuilder sb = new StringBuilder();
        for (int k = s.start; k < s.end; k++) {
            if (k > s.start) sb.append(' ');
            sb.append(words.get(k).norm);
        }
        return sb.toString();
    }
    private String rawText(Span s) {
        StringBuilder sb = new StringBuilder();
        for (int k = s.start; k < s.end; k++) {
            if (k > s.start) sb.append(' ');
            sb.append(words.get(k).raw);
        }
        return sb.toString();
    }

    private String labelOf(Span s) {
        List<Entry> t = s.of(Lexicon.Kind.TABLE);
        if (!t.isEmpty()) return t.get(0).table().singular;
        List<Entry> c = s.of(Lexicon.Kind.COLUMN);
        return c.isEmpty() ? text(s) : c.get(0).column().label.toLowerCase();
    }

    private static String aggWord(String fn) {
        return switch (fn) {
            case "AVG" -> "average";
            case "SUM" -> "total";
            case "MAX" -> "highest";
            case "MIN" -> "lowest";
            default -> fn.toLowerCase();
        };
    }

    private static String round(String fn, String ref) {
        return fn.equals("AVG") ? "ROUND(AVG(" + ref + "), 2)" : fn + "(" + ref + ")";
    }

    private int distance(TableSchema a, TableSchema b) {
        List<TableSchema> path = JoinPlanner.path(db, a, b);
        return path == null ? 9 : path.size() - 1;
    }

    private Result fail(String msg) {
        out.error = msg;
        out.sql = null;
        out.confidence = 0;
        out.notSure = true;
        if (spans != null) makeChips();
        return out;
    }

    // ================================================================== confidence + chips
    private void score(TableSchema main) {
        int understood = 0, unknown = 0;
        for (Span s : spans) {
            if (s.word != null && s.word.kind == NlTokenizer.Kind.WORD && STOP.contains(s.word.norm)) continue;
            if (s.word != null && s.word.kind == NlTokenizer.Kind.SYMBOL) continue;
            if (s.isCue(Cue.LIST) || s.isCue(Cue.WHO) || s.isCue(Cue.WHICH) || s.isCue(Cue.AND) || s.isCue(Cue.DETAILS)
                    || s.isCue(Cue.LIMIT) || s.isCue(Cue.TOP) && text(s).equals("first") || s.isCue(Cue.DISTINCT)) { s.used = true; continue; }
            if (s.isSchema() && s.has(Lexicon.Kind.TABLE) && s.of(Lexicon.Kind.TABLE).get(0).table() == main) s.used = true;
            if (s.isSchema() && !s.used && out.sql != null && inSql(s)) s.used = true;
            if (s.isCue(Cue.BY)) { s.used = true; continue; }
            if (s.used || s.absorbed) understood += (s.end - s.start);
            else {
                unknown += 1;
                out.unknown.add(rawText(s));
            }
        }
        double coverage = understood + unknown == 0 ? 0 : understood / (double) (understood + unknown);
        double conf = 0.40 + 0.58 * coverage - penalty - 0.15 * unknown;
        if (unknown == 0) conf = Math.max(conf, 0.9 - penalty);
        out.confidence = Math.max(0.05, Math.min(0.98, conf));
        out.notSure = out.confidence < NOT_SURE_BELOW;
        makeChips();
    }

    /** True if the word's table (or column) is part of the final SQL. */
    private boolean inSql(Span s) {
        String sql = out.sql.toLowerCase();
        for (Entry e : s.entries) {
            if (e.kind() == Lexicon.Kind.TABLE && (sql.contains("from " + e.table().name + "\n") || sql.contains("from " + e.table().name + " ")
                    || sql.contains("from " + e.table().name + ";") || sql.contains("join " + e.table().name + " "))) return true;
            if (e.kind() == Lexicon.Kind.COLUMN && sql.matches("(?s).*\\b" + e.column().name.toLowerCase() + "\\b.*")
                    && (sql.contains(" " + e.table().name) )) return true;
        }
        return false;
    }

    private void makeChips() {
        for (Span s : spans) {
            String kind, meaning;
            if (s.absorbed) { kind = "table"; meaning = "part of the value next to it"; }
            else if (s.chosen != null) { kind = "value"; meaning = s.chosen.describe() + (s.negated ? " (not)" : ""); }
            else if (s.isSchema() && s.used) {
                Entry e = s.entries.get(0);
                kind = e.kind() == Lexicon.Kind.TABLE ? "table" : e.kind() == Lexicon.Kind.COLUMN ? "column" : "value";
                meaning = e.describe();
            } else if (s.cue != null && s.used) { kind = "cue"; meaning = cueMeaning(s.cue.cue()); }
            else if (s.isNumber() && s.used) { kind = "number"; meaning = "number " + SqlExecutor.formatNumber(s.word.number); }
            else if (s.word != null && s.word.kind == NlTokenizer.Kind.WORD && STOP.contains(s.word.norm)) { kind = "stop"; meaning = ""; }
            else if (s.cue != null) { kind = "stop"; meaning = ""; }
            else { kind = s.used ? "cue" : "unknown"; meaning = s.used ? "" : "not understood"; }
            out.chips.add(new Chip(s.start, s.end, rawText(s), kind, meaning));
        }
    }

    private static String cueMeaning(Cue c) {
        return switch (c) {
            case COUNT -> "count rows";
            case SUM -> "add up (SUM)";
            case AVG -> "average (AVG)";
            case MAX -> "highest value (MAX)";
            case MIN -> "lowest value (MIN)";
            case MOST -> "highest first";
            case LEAST -> "lowest first";
            case GT -> "greater than (>)";
            case GE -> "at least (>=)";
            case LT -> "less than (<)";
            case LE -> "at most (<=)";
            case EQ -> "equals (=)";
            case NE, NOT -> "not";
            case BETWEEN -> "between";
            case GROUP, GROUP_AFTER -> "group by";
            case BY, ORDER -> "sort by";
            case ASC -> "ascending";
            case DESC -> "descending";
            case ALPHA -> "A to Z";
            case TOP -> "top rows";
            case BOTTOM -> "bottom rows";
            case DISTINCT -> "no repeats (DISTINCT)";
            case AFTER, SINCE -> "after";
            case BEFORE -> "before";
            case THIS_YEAR, LAST_YEAR, THIS_MONTH, LAST_MONTH, TODAY, YESTERDAY, LAST_N, MONTH -> "date range";
            case STARTS -> "starts with (LIKE)";
            case ENDS -> "ends with (LIKE)";
            case CONTAINS, NAMED -> "contains (LIKE)";
            case OUT_OF_STOCK -> "stock = 0";
            case OR -> "either value (IN)";
            default -> "";
        };
    }

    // ================================================================== SQL writer
    /** Collects the parts of the query and prints them, one clause per line. */
    private final class Builder {
        final TableSchema main;
        final List<String[]> select = new ArrayList<>();      // {expr, alias}
        final List<String> where = new ArrayList<>();
        final List<String> group = new ArrayList<>();
        final List<String[]> order = new ArrayList<>();       // {expr, ASC|DESC}
        final LinkedHashSet<TableSchema> tables = new LinkedHashSet<>();
        Long limit;
        boolean distinct, star;

        Builder(TableSchema main) { this.main = main; tables.add(main); }

        String ref(TableSchema t, Column c) {
            tables.add(t);
            return "\u0000" + t.name + "\u0000" + c.name;   // placeholder: alias decided at the end
        }
        void select(TableSchema t, Column c, String alias) {
            if (c == null) return;
            String r = ref(t, c);
            for (String[] s : select) if (s[0].equals(r)) return;
            select.add(new String[]{r, alias});
        }
        void selectRaw(String expr, String alias) { select.add(new String[]{expr, alias}); }
        void group(TableSchema t, Column c) { group.add(ref(t, c)); }
        void order(String expr, boolean desc) { order.add(new String[]{expr, desc ? "DESC" : "ASC"}); }

        String countExpr(TableSchema counted, TableSchema groupTable) {
            // count each row of the main table once, even when a JOIN repeats it
            return "\u0001COUNT\u0001" + counted.name;
        }

        void where(Cond c) {
            tables.add(c.table());
            String col = ref(c.table(), c.column());
            List<String> vs = new ArrayList<>();
            for (Object v : c.values()) vs.add(literal(v, c.column()));
            String w;
            switch (c.op()) {
                case "IN", "NOT IN" -> w = col + " " + c.op() + " (" + String.join(", ", vs) + ")";
                case "BETWEEN" -> w = col + " BETWEEN " + vs.get(0) + " AND " + vs.get(1);
                default -> w = col + " " + c.op() + " " + vs.get(0);
            }
            if (!where.contains(w)) where.add(w);
        }

        String build() {
            // 1. joins: shortest paths from the main table to every other table used
            List<JoinPlanner.Edge> joins = JoinPlanner.plan(db, main, new ArrayList<>(tables));
            boolean qualify = !joins.isEmpty();
            Map<String, String> alias = new HashMap<>();
            Set<String> usedAliases = new HashSet<>();
            alias.put(main.name, uniqueAlias(main, usedAliases));
            for (JoinPlanner.Edge e : joins) alias.putIfAbsent(e.to().name, uniqueAlias(e.to(), usedAliases));

            StringBuilder sb = new StringBuilder("SELECT ");
            if (distinct) sb.append("DISTINCT ");
            List<String> items = new ArrayList<>();
            if (star) items.add(qualify ? alias.get(main.name) + ".*" : "*");
            for (String[] s : select) {
                String e = resolve(s[0], alias, qualify, !joins.isEmpty());
                items.add(s[1] == null ? e : e + " AS " + s[1]);
            }
            sb.append(String.join(", ", items));
            sb.append("\nFROM ").append(main.name).append(qualify ? " " + alias.get(main.name) : "");
            for (JoinPlanner.Edge e : joins) {
                sb.append("\nJOIN ").append(e.to().name).append(' ').append(alias.get(e.to().name))
                        .append(" ON ").append(alias.get(e.to().name)).append('.').append(e.toColumn())
                        .append(" = ").append(alias.get(e.from().name)).append('.').append(e.fromColumn());
            }
            for (int i = 0; i < where.size(); i++)
                sb.append(i == 0 ? "\nWHERE " : "\n  AND ").append(resolve(where.get(i), alias, qualify, !joins.isEmpty()));
            if (!group.isEmpty()) {
                List<String> g = new ArrayList<>();
                for (String x : group) g.add(resolve(x, alias, qualify, !joins.isEmpty()));
                sb.append("\nGROUP BY ").append(String.join(", ", g));
            }
            if (!order.isEmpty()) {
                List<String> o = new ArrayList<>();
                for (String[] x : order) o.add(resolve(x[0], alias, qualify, !joins.isEmpty()) + " " + x[1]);
                sb.append("\nORDER BY ").append(String.join(", ", o));
            }
            if (limit != null) sb.append("\nLIMIT ").append(limit);
            return sb.append(';').toString();
        }

        private String resolve(String s, Map<String, String> alias, boolean qualify, boolean joined) {
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\u0000') {
                    int a = s.indexOf('\u0000', i + 1);
                    String table = s.substring(i + 1, a);
                    int end = a + 1;
                    while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_')) end++;
                    String col = s.substring(a + 1, end);
                    out.append(qualify ? alias.get(table) + "." + col : col);
                    i = end;
                } else if (c == '\u0001') {
                    int a = s.indexOf('\u0001', i + 1);
                    String table = s.substring(a + 1);
                    TableSchema t = db.table(table).schema;
                    out.append(joined ? "COUNT(DISTINCT " + alias.get(t.name) + "." + t.primaryKey + ")" : "COUNT(*)");
                    i = s.length();
                } else { out.append(c); i++; }
            }
            return out.toString();
        }

        private String uniqueAlias(TableSchema t, Set<String> used) {
            String a = t.alias == null || t.alias.isBlank() ? t.name.substring(0, 1) : t.alias;
            String x = a;
            int n = 2;
            while (!used.add(x)) x = a + n++;
            return x;
        }

        private String literal(Object v, Column c) {
            if (v instanceof Number) return SqlExecutor.formatNumber(v);
            return "'" + String.valueOf(v).replace("'", "''") + "'";
        }
    }
}
