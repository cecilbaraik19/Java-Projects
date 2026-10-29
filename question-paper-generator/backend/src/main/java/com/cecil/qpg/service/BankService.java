package com.cecil.qpg.service;

import com.cecil.qpg.model.Blueprint;
import com.cecil.qpg.model.Enums;
import com.cecil.qpg.model.Enums.Bloom;
import com.cecil.qpg.model.Enums.Difficulty;
import com.cecil.qpg.model.Enums.QType;
import com.cecil.qpg.model.Enums.Role;
import com.cecil.qpg.model.Paper;
import com.cecil.qpg.model.Question;
import com.cecil.qpg.model.Subject;
import com.cecil.qpg.model.User;
import com.cecil.qpg.store.DataStore;
import com.cecil.qpg.util.Csv;
import com.cecil.qpg.util.Json;
import com.cecil.qpg.util.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Subjects (with their units) and the question bank: add, edit, search, find near-duplicates, import and export CSV. */
public class BankService {
    private final DataStore store;

    public BankService(DataStore store) { this.store = store; }

    // ================================================================== subjects

    public List<Object> subjects() {
        synchronized (store) {
            List<Object> out = new ArrayList<>();
            for (Subject s : store.subjects) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) s.toJson();
                int n = 0;
                for (Question q : store.questions) if (!q.archived && q.subjectCode.equals(s.code)) n++;
                m.put("questionCount", n);
                out.add(m);
            }
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> saveSubject(User me, String code, Map<String, Object> b) {
        AuthService.need(me, Role.HOD, "add or change subjects");
        synchronized (store) {
            boolean isNew = code == null;
            Subject s = isNew ? new Subject() : store.subject(code);
            if (s == null) throw ApiError.notFound("No such subject");
            if (isNew) {
                String c = Text.line(Json.str(b, "code"), "Subject code", 12, true).toUpperCase(Locale.ROOT);
                if (!c.matches("[A-Z0-9-]{2,12}")) throw ApiError.bad("Subject code may only have letters, numbers and dashes");
                if (store.subject(c) != null) throw ApiError.conflict("Subject " + c + " already exists");
                s.code = c;
            }
            s.name = Text.line(Json.str(b, "name"), "Subject name", 80, true);
            s.course = Text.line(Json.str(b, "course"), "Course", 40, false);
            s.semester = Text.range(Json.intOrNull(b, "semester"), "Semester", 1, 12);
            List<Object> us = Json.list(b, "units");
            if (us == null || us.isEmpty()) throw ApiError.bad("Add at least one unit");
            if (us.size() > 12) throw ApiError.bad("At most 12 units");
            List<Subject.Unit> units = new ArrayList<>();
            int no = 1;
            for (Object o : us) {
                String t = o instanceof Map ? Json.str((Map<String, Object>) o, "title") : String.valueOf(o);
                units.add(new Subject.Unit(no++, Text.line(t, "Unit " + (no - 1) + " title", 80, true)));
            }
            if (!isNew) {
                for (Question q : store.questions)
                    if (q.subjectCode.equals(s.code) && q.unit > units.size())
                        throw ApiError.bad("Unit " + q.unit + " still has questions. Move or delete them first.");
            }
            s.units = units;
            if (isNew) store.subjects.add(s);
            store.saveSubjects();
            store.log(me.username, isNew ? "subject added" : "subject changed", (isNew ? "Added " : "Updated ") + s.code + " " + s.name, "subject:" + s.code);
            return (Map<String, Object>) s.toJson();
        }
    }

    public void deleteSubject(User me, String code) {
        AuthService.need(me, Role.HOD, "delete subjects");
        synchronized (store) {
            Subject s = store.subject(code);
            if (s == null) throw ApiError.notFound("No such subject");
            for (Question q : store.questions) if (q.subjectCode.equals(s.code)) throw ApiError.bad("This subject has questions. Delete or move them first.");
            for (Blueprint bp : store.blueprints) if (bp.subjectCode.equals(s.code)) throw ApiError.bad("This subject has blueprints. Delete them first.");
            for (Paper p : store.papers) if (p.subjectCode.equals(s.code)) throw ApiError.bad("This subject has papers. It cannot be deleted.");
            store.subjects.remove(s);
            store.saveSubjects();
            store.log(me.username, "subject deleted", "Deleted subject " + s.code, null);
        }
    }

    // ================================================================== questions: reading

    public Map<String, Object> list(Map<String, String> f) {
        synchronized (store) {
            String subject = f.get("subject"), q = lower(f.get("q")), by = f.get("by");
            Integer unit = intOrNull(f.get("unit"));
            String type = f.get("type"), diff = f.get("difficulty"), bloom = f.get("bloom");
            String archived = f.getOrDefault("archived", "no");
            List<Question> hits = new ArrayList<>();
            for (Question x : store.questions) {
                if (archived.equals("no") && x.archived) continue;
                if (archived.equals("only") && !x.archived) continue;
                if (notBlank(subject) && !x.subjectCode.equalsIgnoreCase(subject)) continue;
                if (unit != null && x.unit != unit) continue;
                if (notBlank(type) && !x.type.name().equals(type)) continue;
                if (notBlank(diff) && !x.difficulty.name().equals(diff)) continue;
                if (notBlank(bloom) && !x.bloom.name().equals(bloom)) continue;
                if (notBlank(by) && !by.equalsIgnoreCase(x.createdBy)) continue;
                if (notBlank(q) && !(lower(x.text).contains(q) || lower(x.id).contains(q) || lower(String.join(" ", x.tags)).contains(q))) continue;
                hits.add(x);
            }
            String sort = f.getOrDefault("sort", "id");
            Comparator<Question> c = switch (sort) {
                case "new" -> Comparator.comparing((Question x) -> x.updatedAt == null ? "" : x.updatedAt).reversed();
                case "used" -> Comparator.comparingInt((Question x) -> x.timesUsed).reversed().thenComparing(x -> x.id);
                case "unused" -> Comparator.comparingInt((Question x) -> x.timesUsed).thenComparing(x -> x.id);
                case "marks" -> Comparator.comparingInt((Question x) -> x.marks).reversed().thenComparing(x -> x.id);
                case "unit" -> Comparator.comparing((Question x) -> x.subjectCode).thenComparingInt(x -> x.unit).thenComparing(x -> x.id);
                default -> Comparator.comparing((Question x) -> x.id);
            };
            hits.sort(c);
            int limit = clamp(intOrNull(f.get("limit")), 1, 500, 50);
            int offset = clamp(intOrNull(f.get("offset")), 0, 1_000_000, 0);
            Set<String> inPapers = idsInPapers();
            List<Object> items = new ArrayList<>();
            for (int i = offset; i < Math.min(hits.size(), offset + limit); i++) items.add(view(hits.get(i), inPapers));
            return Json.map("total", hits.size(), "offset", offset, "limit", limit, "items", items);
        }
    }

    public Map<String, Object> get(String id) {
        synchronized (store) {
            Question q = find(id);
            Map<String, Object> m = view(q, idsInPapers());
            List<Object> papers = new ArrayList<>();
            for (Paper p : store.papers) if (p.questionIds().contains(q.id)) papers.add(p.summary());
            m.put("papers", papers);
            m.put("similar", similar(q.subjectCode, q.text, q.id));
            return m;
        }
    }

    private Map<String, Object> view(Question q, Set<String> inPapers) {
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) q.toJson();
        m.put("inPapers", inPapers.contains(q.id));
        return m;
    }

    private Set<String> idsInPapers() {
        Set<String> s = new HashSet<>();
        for (Paper p : store.papers) s.addAll(p.questionIds());
        return s;
    }

    /** Questions in the same subject that look almost the same as this text (most alike first, at most 5). */
    public List<Object> similar(String subject, String text, String exceptId) {
        synchronized (store) {
            List<Object[]> hits = new ArrayList<>();
            double limit = Math.max(0.3, store.settings.duplicateThreshold - 0.1);
            for (Question q : store.questions) {
                if (q.id.equals(exceptId) || (subject != null && !q.subjectCode.equalsIgnoreCase(subject))) continue;
                double sim = Text.similarity(text, q.text);
                if (sim >= limit) hits.add(new Object[]{sim, q});
            }
            hits.sort((a, b) -> Double.compare((double) b[0], (double) a[0]));
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < Math.min(5, hits.size()); i++) {
                Question q = (Question) hits.get(i)[1];
                double sim = (double) hits.get(i)[0];
                out.add(Json.map("id", q.id, "text", q.text, "type", q.type, "unit", q.unit, "archived", q.archived,
                        "similarity", Math.round(sim * 100), "duplicate", sim >= store.settings.duplicateThreshold));
            }
            return out;
        }
    }

    // ================================================================== questions: writing

    public Map<String, Object> create(User me, Map<String, Object> b) {
        synchronized (store) {
            Question q = new Question();
            apply(q, b);
            if (!Json.bool(b, "force", false)) {
                for (Question o : store.questions)
                    if (o.subjectCode.equals(q.subjectCode) && Text.similarity(o.text, q.text) >= 0.95)
                        throw ApiError.conflict("This looks the same as " + o.id + ". Save anyway if it is really different.");
            }
            q.id = store.newQuestionId();
            q.createdBy = me.username;
            q.createdAt = store.now();
            q.updatedAt = q.createdAt;
            store.questions.add(q);
            store.saveQuestions();
            store.log(me.username, "question added", "Added " + q.id + " (" + q.subjectCode + ", unit " + q.unit + ")", "question:" + q.id);
            return view(q, Set.of());
        }
    }

    public Map<String, Object> update(User me, String id, Map<String, Object> b) {
        synchronized (store) {
            Question q = find(id);
            canEdit(me, q);
            apply(q, b); // checks everything first and only then changes the question
            q.updatedAt = store.now();
            store.saveQuestions();
            store.log(me.username, "question changed", "Edited " + q.id, "question:" + q.id);
            return view(q, idsInPapers());
        }
    }

    public Map<String, Object> setArchived(User me, String id, boolean archived) {
        synchronized (store) {
            Question q = find(id);
            canEdit(me, q);
            q.archived = archived;
            q.updatedAt = store.now();
            store.saveQuestions();
            store.log(me.username, archived ? "question archived" : "question restored",
                    (archived ? "Archived " : "Restored ") + q.id, "question:" + q.id);
            return view(q, idsInPapers());
        }
    }

    public void delete(User me, String id) {
        synchronized (store) {
            Question q = find(id);
            canEdit(me, q);
            if (idsInPapers().contains(q.id)) throw ApiError.bad("This question is in a paper, so it cannot be deleted. Archive it instead.");
            store.questions.remove(q);
            store.saveQuestions();
            store.log(me.username, "question deleted", "Deleted " + q.id, null);
        }
    }

    private void canEdit(User me, Question q) {
        if (!me.role.atLeast(Role.HOD) && !me.username.equals(q.createdBy))
            throw ApiError.forbidden("You can only change questions you added. Ask the HOD to change this one.");
    }

    private Question find(String id) {
        Question q = store.question(id);
        if (q == null) throw ApiError.notFound("No such question");
        return q;
    }

    /** Checks the form and copies it into the question. Throws a clear 400 error for anything wrong. */
    void apply(Question q, Map<String, Object> b) {
        try {
            String code = Json.str(b, "subjectCode", q.subjectCode);
            Subject s = store.subject(code);
            if (s == null) throw new IllegalArgumentException("Choose a valid subject");
            Integer unit = Json.intOrNull(b, "unit");
            if (unit == null) unit = q.unit;
            if (s.unit(unit) == null) throw new IllegalArgumentException("Unit " + unit + " does not exist in " + s.code);
            QType type = b.containsKey("type") ? Enums.parse(QType.class, Json.str(b, "type"), "Question type") : q.type;
            if (type == null) throw new IllegalArgumentException("Question type is required");
            String text = Text.clean(Json.str(b, "text", q.text), "Question text", 2000, true);
            if (text.length() < 5) throw new IllegalArgumentException("Question text is too short");
            Integer marks = Json.intOrNull(b, "marks");
            if (marks == null) marks = q.marks == 0 ? null : q.marks;
            Text.range(marks, "Marks", 1, 50);
            Difficulty diff = b.containsKey("difficulty") ? Enums.parse(Difficulty.class, Json.str(b, "difficulty"), "Difficulty") : q.difficulty;
            Bloom bloom = b.containsKey("bloom") ? Enums.parse(Bloom.class, Json.str(b, "bloom"), "Bloom level") : q.bloom;
            if (diff == null) throw new IllegalArgumentException("Difficulty is required");
            if (bloom == null) throw new IllegalArgumentException("Bloom level is required");
            List<String> options = b.containsKey("options") ? Json.strings(b, "options") : q.options;
            String answer = Json.str(b, "answer", q.answer);
            List<String> opts = new ArrayList<>();
            switch (type) {
                case MCQ -> {
                    Set<String> seen = new HashSet<>();
                    for (String o : options) {
                        String t = Text.line(o, "Option", 300, false);
                        if (t.isEmpty()) continue;
                        if (!seen.add(t.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Two options are the same: " + t);
                        opts.add(t);
                    }
                    if (opts.size() < 2 || opts.size() > 6) throw new IllegalArgumentException("A multiple-choice question needs 2 to 6 options");
                    answer = Text.line(answer, "Correct answer", 300, true);
                    String a = answer;
                    String match = opts.stream().filter(o -> o.equalsIgnoreCase(a)).findFirst().orElse(null);
                    if (match == null) throw new IllegalArgumentException("The correct answer must be one of the options");
                    answer = match;
                }
                case TRUE_FALSE -> {
                    opts = new ArrayList<>(List.of("True", "False"));
                    String a = answer == null ? "" : answer.trim();
                    if (a.equalsIgnoreCase("true") || a.equalsIgnoreCase("t")) answer = "True";
                    else if (a.equalsIgnoreCase("false") || a.equalsIgnoreCase("f")) answer = "False";
                    else throw new IllegalArgumentException("The answer must be True or False");
                }
                case FILL_BLANK -> {
                    if (!text.contains("___")) throw new IllegalArgumentException("Show the blank in the question with at least three underscores ___");
                    answer = Text.line(answer, "Answer", 300, true);
                }
                default -> answer = Text.clean(answer, "Model answer", 3000, false);
            }
            if (type.objective && marks > 5) throw new IllegalArgumentException(type.label + " questions can carry at most 5 marks");
            List<String> tags = new ArrayList<>();
            if (b.containsKey("tags")) {
                Object t = b.get("tags");
                List<String> raw = t instanceof List ? Json.strings(b, "tags") : List.of(String.valueOf(t == null ? "" : t).split(","));
                for (String x : raw) {
                    String c = Text.line(x, "Tag", 30, false).toLowerCase(Locale.ROOT);
                    if (!c.isEmpty() && !tags.contains(c)) tags.add(c);
                }
                if (tags.size() > 8) throw new IllegalArgumentException("At most 8 tags");
            } else tags = q.tags;
            q.subjectCode = s.code;
            q.unit = unit;
            q.type = type;
            q.text = text;
            q.marks = marks;
            q.difficulty = diff;
            q.bloom = bloom;
            q.options = opts;
            q.answer = answer;
            q.tags = tags;
        } catch (IllegalArgumentException e) {
            throw ApiError.bad(e.getMessage());
        }
    }

    // ================================================================== coverage (how full the bank is)

    /** For each subject and unit: how many questions there are of each difficulty and kind. Finds thin spots. */
    public Map<String, Object> coverage(String subjectCode) {
        synchronized (store) {
            List<Object> subs = new ArrayList<>();
            for (Subject s : store.subjects) {
                if (subjectCode != null && !subjectCode.isBlank() && !s.code.equalsIgnoreCase(subjectCode)) continue;
                List<Object> units = new ArrayList<>();
                Map<String, Integer> byType = new LinkedHashMap<>();
                for (QType t : QType.values()) byType.put(t.name(), 0);
                int total = 0;
                List<String> gaps = new ArrayList<>();
                for (Subject.Unit u : s.units) {
                    Map<String, Integer> byDiff = new LinkedHashMap<>();
                    for (Difficulty d : Difficulty.values()) byDiff.put(d.name(), 0);
                    Map<String, Integer> uType = new LinkedHashMap<>();
                    for (QType t : QType.values()) uType.put(t.name(), 0);
                    int n = 0;
                    for (Question q : store.questions) {
                        if (q.archived || !q.subjectCode.equals(s.code) || q.unit != u.no) continue;
                        byDiff.merge(q.difficulty.name(), 1, Integer::sum);
                        uType.merge(q.type.name(), 1, Integer::sum);
                        byType.merge(q.type.name(), 1, Integer::sum);
                        n++;
                    }
                    total += n;
                    if (n < 5) gaps.add("Unit " + u.no + " has only " + n + " question" + (n == 1 ? "" : "s"));
                    else if (byDiff.get("HARD") == 0) gaps.add("Unit " + u.no + " has no hard questions");
                    units.add(Json.map("no", u.no, "title", u.title, "total", n, "byDifficulty", byDiff, "byType", uType));
                }
                subs.add(Json.map("code", s.code, "name", s.name, "total", total, "units", units, "byType", byType, "gaps", gaps));
            }
            return Json.map("subjects", subs);
        }
    }

    // ================================================================== CSV import / export

    public static final String[] CSV_HEAD = {"subject", "unit", "type", "difficulty", "bloom", "marks", "question", "options", "answer", "tags"};

    public String exportCsv(Map<String, String> filters) {
        Map<String, String> f = new LinkedHashMap<>(filters);
        f.put("limit", "500");
        StringBuilder sb = new StringBuilder("﻿"); // so Excel reads the text as UTF-8
        sb.append(Csv.row((Object[]) CSV_HEAD));
        synchronized (store) {
            int offset = 0;
            while (true) {
                f.put("offset", String.valueOf(offset));
                Map<String, Object> page = list(f);
                @SuppressWarnings("unchecked") List<Object> items = (List<Object>) page.get("items");
                for (Object o : items) {
                    Question q = store.question(String.valueOf(((Map<?, ?>) o).get("id")));
                    sb.append(Csv.row(q.subjectCode, q.unit, q.type.name(), q.difficulty.name(), q.bloom.name(), q.marks,
                            q.text, q.type == QType.MCQ ? String.join(" | ", q.options) : "", q.answer, String.join(", ", q.tags)));
                }
                offset += items.size();
                if (items.isEmpty() || offset >= (int) page.get("total")) break;
            }
        }
        return sb.toString();
    }

    /**
     * Imports questions from CSV text. With dryRun it only checks and reports.
     * Rows that are wrong or are duplicates of a question already in the bank are skipped and listed.
     */
    public Map<String, Object> importCsv(User me, String csv, boolean dryRun) {
        if (csv == null || csv.isBlank()) throw ApiError.bad("The CSV is empty");
        List<List<String>> rows = Csv.parse(csv);
        if (rows.isEmpty()) throw ApiError.bad("The CSV is empty");
        Map<String, Integer> col = new LinkedHashMap<>();
        List<String> head = rows.get(0);
        for (int i = 0; i < head.size(); i++) col.put(head.get(i).trim().toLowerCase(Locale.ROOT), i);
        for (String need : List.of("subject", "unit", "type", "marks", "question"))
            if (!col.containsKey(need)) throw ApiError.bad("The first row must be the header. Missing column: " + need);
        if (rows.size() - 1 > 1000) throw ApiError.bad("At most 1000 questions per import");
        List<Object> errors = new ArrayList<>();
        List<Object> preview = new ArrayList<>();
        int added = 0, duplicates = 0;
        synchronized (store) {
            List<Question> fresh = new ArrayList<>();
            for (int r = 1; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                try {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("subjectCode", cell(row, col, "subject"));
                    b.put("unit", cell(row, col, "unit"));
                    b.put("type", cell(row, col, "type"));
                    b.put("difficulty", orDefault(cell(row, col, "difficulty"), "MEDIUM"));
                    b.put("bloom", orDefault(cell(row, col, "bloom"), "UNDERSTAND"));
                    b.put("marks", cell(row, col, "marks"));
                    b.put("text", Csv.unguard(cell(row, col, "question")));
                    String opts = cell(row, col, "options");
                    b.put("options", opts == null || opts.isBlank() ? List.of() : List.of(opts.split("\\s*\\|\\s*")));
                    b.put("answer", Csv.unguard(cell(row, col, "answer")));
                    b.put("tags", orDefault(cell(row, col, "tags"), ""));
                    Question q = new Question();
                    apply(q, b);
                    boolean dup = false;
                    for (Question o : store.questions) if (o.subjectCode.equals(q.subjectCode) && Text.similarity(o.text, q.text) >= 0.95) { dup = true; break; }
                    for (Question o : fresh) if (o.subjectCode.equals(q.subjectCode) && Text.similarity(o.text, q.text) >= 0.95) { dup = true; break; }
                    if (dup) {
                        duplicates++;
                        errors.add(Json.map("row", r + 1, "error", "Already in the bank (duplicate) - skipped"));
                        continue;
                    }
                    fresh.add(q);
                    if (preview.size() < 10) preview.add(Json.map("row", r + 1, "subjectCode", q.subjectCode, "unit", q.unit,
                            "type", q.type, "marks", q.marks, "text", q.text));
                } catch (ApiError | IllegalArgumentException e) {
                    errors.add(Json.map("row", r + 1, "error", e.getMessage()));
                }
            }
            if (!dryRun && !fresh.isEmpty()) {
                for (Question q : fresh) {
                    q.id = store.newQuestionId();
                    q.createdBy = me.username;
                    q.createdAt = store.now();
                    q.updatedAt = q.createdAt;
                    store.questions.add(q);
                }
                store.saveQuestions();
                store.log(me.username, "questions imported", "Imported " + fresh.size() + " questions from CSV", null);
            }
            added = fresh.size();
        }
        return Json.map("dryRun", dryRun, "rows", rows.size() - 1, "ok", added, "duplicates", duplicates,
                "errors", errors, "preview", preview);
    }

    private static String cell(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= row.size()) return null;
        return row.get(i).trim();
    }

    private static String orDefault(String s, String d) { return s == null || s.isBlank() ? d : s; }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT).trim(); }

    private static Integer intOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { throw ApiError.bad("'" + s + "' is not a number"); }
    }

    private static int clamp(Integer v, int min, int max, int def) {
        if (v == null) return def;
        return Math.max(min, Math.min(max, v));
    }
}
