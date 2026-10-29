package com.cecil.qpg.service;

import com.cecil.qpg.generator.PaperGenerator;
import com.cecil.qpg.model.Blueprint;
import com.cecil.qpg.model.Enums.PaperStatus;
import com.cecil.qpg.model.Enums.QType;
import com.cecil.qpg.model.Enums.Role;
import com.cecil.qpg.model.Paper;
import com.cecil.qpg.model.Question;
import com.cecil.qpg.model.Subject;
import com.cecil.qpg.model.User;
import com.cecil.qpg.store.DataStore;
import com.cecil.qpg.util.Json;
import com.cecil.qpg.util.Text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Papers: generate from a blueprint, change (swap / move questions, edit the header),
 * and move through review: Draft -> Waiting for review -> Approved -> Released.
 */
public class PaperService {
    private final DataStore store;
    private final Random seeds = new Random();

    public PaperService(DataStore store) { this.store = store; }

    // ================================================================== reading

    public List<Object> list(Map<String, String> f) {
        synchronized (store) {
            String subject = f.get("subject"), status = f.get("status"), q = f.get("q"), group = f.get("group");
            List<Paper> hits = new ArrayList<>();
            for (Paper p : store.papers) {
                if (notBlank(subject) && !p.subjectCode.equalsIgnoreCase(subject)) continue;
                if (notBlank(status) && !p.status.name().equals(status)) continue;
                if (notBlank(group) && !group.equals(p.groupId)) continue;
                if (notBlank(q) && !(p.title + " " + p.id).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT).trim())) continue;
                hits.add(p);
            }
            hits.sort(Comparator.comparing((Paper p) -> p.createdAt == null ? "" : p.createdAt).reversed().thenComparing(p -> p.id, Comparator.reverseOrder()));
            List<Object> out = new ArrayList<>();
            for (Paper p : hits) out.add(p.summary());
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String id) {
        synchronized (store) {
            Paper p = find(id);
            Map<String, Object> m = (Map<String, Object>) p.toJson();
            Subject s = store.subject(p.subjectCode);
            m.put("subject", s == null ? null : s.toJson());
            m.put("analysis", PaperGenerator.analyse(p, s));
            Blueprint bp = p.blueprintId == null ? null : store.blueprint(p.blueprintId);
            m.put("blueprintName", bp == null ? null : bp.name);
            m.put("department", store.settings.department);
            // the other sets made together with this one, and how many questions they share
            List<Object> sets = new ArrayList<>();
            Set<String> mine = new HashSet<>(p.questionIds());
            for (Paper o : store.papers) {
                if (o == p || p.groupId == null || !p.groupId.equals(o.groupId)) continue;
                int shared = 0;
                for (String qid : o.questionIds()) if (mine.contains(qid)) shared++;
                sets.add(Json.map("id", o.id, "setLabel", o.setLabel, "status", o.status, "statusLabel", o.status.label, "shared", shared));
            }
            m.put("otherSets", sets);
            return m;
        }
    }

    // ================================================================== generate

    public List<Object> generate(User me, Map<String, Object> b) {
        synchronized (store) {
            Blueprint bp = store.blueprint(Json.str(b, "blueprintId"));
            if (bp == null) throw ApiError.bad("Choose a blueprint");
            PaperGenerator.Options o = new PaperGenerator.Options();
            try {
                o.sets = Text.range(Json.integer(b, "sets", 1), "Number of sets", 1, 4);
                LocalDate d = Text.date(Json.str(b, "examDate"), "Exam date", false);
                o.examDate = d == null ? null : d.toString();
                o.title = Text.line(Json.str(b, "title"), "Title", 100, false);
                Long seed = Json.longOrNull(b, "seed");
                o.seed = seed == null ? Math.abs(seeds.nextLong() % 1_000_000_000L) : seed;
            } catch (IllegalArgumentException e) {
                throw ApiError.bad(e.getMessage());
            }
            o.institute = store.settings.institute;
            o.today = store.today();
            o.duplicateThreshold = store.settings.duplicateThreshold;
            List<Paper> made = PaperGenerator.generate(bp, store.subject(bp.subjectCode), store.questions, o);
            if (made.isEmpty() || made.get(0).questionCount() == 0)
                throw ApiError.bad("No questions in the bank match this blueprint. Add questions first.");
            String group = store.newGroupId();
            List<Object> out = new ArrayList<>();
            for (Paper p : made) {
                p.id = store.newPaperId();
                p.groupId = group;
                p.createdBy = me.username;
                p.createdAt = store.now();
                p.updatedAt = p.createdAt;
                store.papers.add(p);
                out.add(p.summary());
            }
            store.savePapers();
            store.log(me.username, "generated", "Generated " + (made.size() == 1 ? made.get(0).title : made.size() + " sets of " + bp.name)
                    + " (seed " + o.seed + ")", "paper:" + made.get(0).id);
            return out;
        }
    }

    /** Makes a fresh draft from the same blueprint with a new random seed, keeping the id and header. */
    public Map<String, Object> regenerate(User me, String id) {
        synchronized (store) {
            Paper p = find(id);
            canEdit(me, p);
            Blueprint bp = p.blueprintId == null ? null : store.blueprint(p.blueprintId);
            if (bp == null) throw ApiError.bad("The blueprint of this paper was deleted, so it cannot be made again");
            PaperGenerator.Options o = new PaperGenerator.Options();
            o.seed = Math.abs(seeds.nextLong() % 1_000_000_000L);
            o.title = p.title;
            o.examDate = p.examDate;
            o.institute = p.institute;
            o.today = store.today();
            o.duplicateThreshold = store.settings.duplicateThreshold;
            for (Paper other : store.papers) if (other != p && p.groupId != null && p.groupId.equals(other.groupId)) o.avoidIds.addAll(other.questionIds());
            Paper fresh = PaperGenerator.generate(bp, store.subject(bp.subjectCode), store.questions, o).get(0);
            p.sections = fresh.sections;
            p.warnings = fresh.warnings;
            p.seed = o.seed;
            p.targetMix = fresh.targetMix;
            p.updatedAt = store.now();
            store.savePapers();
            store.log(me.username, "regenerated", "Made " + p.title + " again (seed " + o.seed + ")", "paper:" + p.id);
            return get(p.id);
        }
    }

    // ================================================================== editing a draft

    public Map<String, Object> updateHeader(User me, String id, Map<String, Object> b) {
        synchronized (store) {
            Paper p = find(id);
            canEdit(me, p);
            try {
                if (b.containsKey("title")) p.title = Text.line(Json.str(b, "title"), "Title", 100, true);
                if (b.containsKey("examDate")) {
                    LocalDate d = Text.date(Json.str(b, "examDate"), "Exam date", false);
                    p.examDate = d == null ? null : d.toString();
                }
                if (b.containsKey("durationMin")) p.durationMin = Text.range(Json.intOrNull(b, "durationMin"), "Duration (minutes)", 10, 360);
                if (b.containsKey("institute")) p.institute = Text.line(Json.str(b, "institute"), "Institute", 100, false);
                if (b.containsKey("instructions")) {
                    List<String> ins = new ArrayList<>();
                    for (String s : Json.strings(b, "instructions")) {
                        String t = Text.line(s, "Instruction", 200, false);
                        if (!t.isEmpty()) ins.add(t);
                    }
                    if (ins.size() > 12) throw new IllegalArgumentException("At most 12 instructions");
                    p.instructions = ins;
                }
                List<Object> secs = Json.list(b, "sectionInstructions");
                if (secs != null) {
                    for (int i = 0; i < Math.min(secs.size(), p.sections.size()); i++)
                        p.sections.get(i).instructions = Text.line(String.valueOf(secs.get(i) == null ? "" : secs.get(i)), "Section instructions", 200, false);
                }
            } catch (IllegalArgumentException e) {
                throw ApiError.bad(e.getMessage());
            }
            p.updatedAt = store.now();
            store.savePapers();
            store.log(me.username, "paper changed", "Edited the header of " + p.title, "paper:" + p.id);
            return get(p.id);
        }
    }

    /** Questions that could replace one question (same kind and marks, not already in the paper). */
    public List<Object> candidates(String id, int section, int item) {
        synchronized (store) {
            Paper p = find(id);
            Paper.Item cur = item(p, section, item);
            Paper.PSection sec = p.sections.get(section);
            Set<String> in = new HashSet<>(p.questionIds());
            Set<String> other = otherSetIds(p);
            List<Question> list = new ArrayList<>();
            for (Question q : store.questions) {
                if (q.archived || !q.subjectCode.equals(p.subjectCode) || q.type != sec.type || q.marks != sec.marksEach || in.contains(q.id)) continue;
                if (!unitAllowed(p, q.unit)) continue;
                list.add(q);
            }
            list.sort(Comparator.comparingInt((Question q) -> q.difficulty == cur.difficulty ? 0 : 1)
                    .thenComparingInt(q -> q.unit == cur.unit ? 0 : 1).thenComparing(q -> q.id));
            List<Object> out = new ArrayList<>();
            for (Question q : list) out.add(Json.map("id", q.id, "text", q.text, "unit", q.unit, "difficulty", q.difficulty,
                    "bloom", q.bloom, "timesUsed", q.timesUsed, "inOtherSet", other.contains(q.id)));
            return out;
        }
    }

    /** Replaces one question: with the chosen questionId, or with the best match the generator finds. */
    public Map<String, Object> swap(User me, String id, Map<String, Object> b) {
        synchronized (store) {
            Paper p = find(id);
            canEdit(me, p);
            int si = Json.integer(b, "section", -1), ii = Json.integer(b, "item", -1);
            Paper.Item cur = item(p, si, ii);
            Paper.PSection sec = p.sections.get(si);
            String wanted = Json.str(b, "questionId");
            Question q;
            if (wanted != null && !wanted.isBlank()) {
                q = store.question(wanted);
                if (q == null || q.archived) throw ApiError.bad("That question is not available");
                if (!q.subjectCode.equals(p.subjectCode) || q.type != sec.type || q.marks != sec.marksEach)
                    throw ApiError.bad("The new question must be the same kind and marks (" + sec.type.label + ", " + sec.marksEach + " marks)");
                if (p.questionIds().contains(q.id)) throw ApiError.bad("That question is already in this paper");
                if (!unitAllowed(p, q.unit)) throw ApiError.bad("Unit " + q.unit + " is not part of this paper's blueprint");
            } else {
                q = PaperGenerator.replacement(p, si, ii, store.questions, otherSetIds(p), store.settings.duplicateThreshold, new Random(seeds.nextLong()), blueprintUnits(p));
                if (q == null) throw ApiError.bad("No other " + sec.type.label.toLowerCase(Locale.ROOT) + " questions of " + sec.marksEach + " marks are left in the bank");
            }
            Paper.Item fresh = Paper.Item.of(q, null);
            if (q.type == QType.MCQ) java.util.Collections.shuffle(fresh.options, new Random(seeds.nextLong()));
            sec.items.set(ii, fresh);
            p.updatedAt = store.now();
            store.savePapers();
            store.log(me.username, "swapped", "Swapped " + cur.questionId + " for " + q.id + " in " + p.title, "paper:" + p.id);
            return get(p.id);
        }
    }

    public Map<String, Object> move(User me, String id, Map<String, Object> b) {
        synchronized (store) {
            Paper p = find(id);
            canEdit(me, p);
            int si = Json.integer(b, "section", -1), ii = Json.integer(b, "item", -1), dir = Json.integer(b, "dir", 0);
            item(p, si, ii);
            List<Paper.Item> items = p.sections.get(si).items;
            int to = ii + (dir < 0 ? -1 : 1);
            if (dir == 0 || to < 0 || to >= items.size()) throw ApiError.bad("Cannot move further");
            Paper.Item x = items.get(ii);
            items.set(ii, items.get(to));
            items.set(to, x);
            p.updatedAt = store.now();
            store.savePapers();
            return get(p.id);
        }
    }

    // ================================================================== review steps

    public Map<String, Object> submit(User me, String id) {
        synchronized (store) {
            Paper p = find(id);
            canEdit(me, p);
            if (p.questionCount() == 0) throw ApiError.bad("The paper has no questions");
            for (Paper.PSection s : p.sections)
                if (s.items.isEmpty()) throw ApiError.bad(s.title + " has no questions. Add questions to the bank and make the paper again.");
            p.status = PaperStatus.SUBMITTED;
            p.reviewNote = null;
            p.updatedAt = store.now();
            store.savePapers();
            store.log(me.username, "submitted", "Sent " + p.title + " for review", "paper:" + p.id);
            return get(p.id);
        }
    }

    public Map<String, Object> approve(User me, String id) {
        AuthService.need(me, Role.HOD, "approve papers");
        synchronized (store) {
            Paper p = find(id);
            if (p.status != PaperStatus.SUBMITTED) throw ApiError.bad("Only papers waiting for review can be approved");
            p.status = PaperStatus.APPROVED;
            p.approvedBy = me.username;
            p.approvedAt = store.now();
            p.reviewedBy = me.username;
            p.updatedAt = p.approvedAt;
            store.savePapers();
            store.log(me.username, "approved", "Approved " + p.title, "paper:" + p.id);
            return get(p.id);
        }
    }

    public Map<String, Object> sendBack(User me, String id, Map<String, Object> b) {
        AuthService.need(me, Role.HOD, "send papers back");
        synchronized (store) {
            Paper p = find(id);
            if (p.status != PaperStatus.SUBMITTED && p.status != PaperStatus.APPROVED)
                throw ApiError.bad("Only papers waiting for review or approved can be sent back");
            String note;
            try { note = Text.clean(Json.str(b, "note"), "Note for the teacher", 500, true); }
            catch (IllegalArgumentException e) { throw ApiError.bad(e.getMessage()); }
            p.status = PaperStatus.DRAFT;
            p.reviewNote = note;
            p.reviewedBy = me.username;
            p.approvedBy = null;
            p.approvedAt = null;
            p.updatedAt = store.now();
            store.savePapers();
            store.log(me.username, "sent back", "Sent " + p.title + " back: " + note, "paper:" + p.id);
            return get(p.id);
        }
    }

    /** Locks the paper for the exam and records that its questions were used today. */
    public Map<String, Object> release(User me, String id) {
        AuthService.need(me, Role.HOD, "release papers");
        synchronized (store) {
            Paper p = find(id);
            if (p.status != PaperStatus.APPROVED) throw ApiError.bad("Only approved papers can be released");
            p.status = PaperStatus.RELEASED;
            p.releasedAt = store.now();
            p.updatedAt = p.releasedAt;
            String day = store.today().toString();
            for (String qid : p.questionIds()) {
                Question q = store.question(qid);
                if (q != null) { q.timesUsed++; q.lastUsedOn = day; }
            }
            store.saveQuestions();
            store.savePapers();
            store.log(me.username, "released", "Released " + p.title + " for the exam", "paper:" + p.id);
            return get(p.id);
        }
    }

    public void delete(User me, String id) {
        synchronized (store) {
            Paper p = find(id);
            if (p.status == PaperStatus.RELEASED) throw ApiError.bad("A released paper is kept as a record and cannot be deleted");
            if (p.status != PaperStatus.DRAFT) AuthService.need(me, Role.HOD, "delete papers under review");
            else canEdit(me, p);
            store.papers.remove(p);
            store.savePapers();
            store.log(me.username, "paper deleted", "Deleted " + p.title, null);
        }
    }

    // ================================================================== text download

    /** The paper (or its answer key) as plain text, for saving or pasting into Word. */
    public String asText(String id, boolean key) {
        synchronized (store) {
            Paper p = find(id);
            Subject s = store.subject(p.subjectCode);
            StringBuilder sb = new StringBuilder();
            String line = "=".repeat(72) + "\r\n";
            sb.append(center(nz(p.institute))).append(center(nz(store.settings.department)));
            sb.append(center(nz(p.examType) + (key ? " - ANSWER KEY" : "")));
            sb.append(center(p.subjectCode + " : " + (s == null ? "" : s.name) + (p.setLabel != null ? "   (Set " + p.setLabel + ")" : "")));
            sb.append("Time: ").append(duration(p.durationMin)).append(" ".repeat(20)).append("Maximum Marks: ").append(p.totalMarks()).append("\r\n");
            if (p.examDate != null) sb.append("Date: ").append(p.examDate).append("\r\n");
            sb.append(line);
            if (!key && !p.instructions.isEmpty()) {
                sb.append("General instructions:\r\n");
                for (int i = 0; i < p.instructions.size(); i++) sb.append("  ").append(i + 1).append(". ").append(p.instructions.get(i)).append("\r\n");
                sb.append(line);
            }
            int n = 1;
            for (Paper.PSection sec : p.sections) {
                sb.append("\r\n").append(sec.title.toUpperCase(Locale.ROOT));
                sb.append("   (").append(sec.attempt).append(" x ").append(sec.marksEach).append(" = ").append(sec.marks()).append(" marks)\r\n");
                if (!key && sec.instructions != null && !sec.instructions.isBlank()) sb.append(sec.instructions).append("\r\n");
                sb.append("\r\n");
                for (Paper.Item it : sec.items) {
                    sb.append("Q").append(n++).append(". ").append(it.text.replace("\n", "\r\n     ")).append("   [").append(it.marks).append("]\r\n");
                    if (!key && it.type == QType.MCQ)
                        for (int k = 0; k < it.options.size(); k++) sb.append("     (").append((char) ('a' + k)).append(") ").append(it.options.get(k)).append("\r\n");
                    if (key) {
                        String a = it.type == QType.MCQ ? "(" + it.answerLetter() + ") " + it.answer : nz(it.answer);
                        sb.append("     Answer: ").append(a.isBlank() ? "(no model answer given)" : a.replace("\n", "\r\n     ")).append("\r\n");
                        sb.append("     [Unit ").append(it.unit).append(", ").append(it.difficulty.label).append(", ").append(it.bloom.label).append("]\r\n");
                    }
                    sb.append("\r\n");
                }
            }
            sb.append(line).append(center("*** End of paper ***"));
            return sb.toString();
        }
    }

    private static String center(String s) {
        int pad = Math.max(0, (72 - s.length()) / 2);
        return " ".repeat(pad) + s + "\r\n";
    }

    public static String duration(int min) {
        int h = min / 60, m = min % 60;
        if (h == 0) return m + " minutes";
        return h + " hour" + (h == 1 ? "" : "s") + (m == 0 ? "" : " " + m + " minutes");
    }

    // ================================================================== helpers

    /** The units the paper's blueprint allows (empty = all units, or the blueprint was deleted). */
    private List<Integer> blueprintUnits(Paper p) {
        Blueprint bp = p.blueprintId == null ? null : store.blueprint(p.blueprintId);
        return bp == null ? List.of() : bp.units;
    }

    private boolean unitAllowed(Paper p, int unit) {
        List<Integer> u = blueprintUnits(p);
        return u.isEmpty() || u.contains(unit);
    }

    private Set<String> otherSetIds(Paper p) {
        Set<String> s = new HashSet<>();
        for (Paper o : store.papers) if (o != p && p.groupId != null && p.groupId.equals(o.groupId)) s.addAll(o.questionIds());
        return s;
    }

    private Paper.Item item(Paper p, int si, int ii) {
        if (si < 0 || si >= p.sections.size()) throw ApiError.bad("No such section");
        List<Paper.Item> items = p.sections.get(si).items;
        if (ii < 0 || ii >= items.size()) throw ApiError.bad("No such question in that section");
        return items.get(ii);
    }

    private void canEdit(User me, Paper p) {
        if (!p.editable()) throw ApiError.bad("Only draft papers can be changed. This one is " + p.status.label.toLowerCase(Locale.ROOT) + ".");
        if (!me.role.atLeast(Role.HOD) && !me.username.equals(p.createdBy))
            throw ApiError.forbidden("Only the person who made this paper (or the HOD) can change it");
    }

    private Paper find(String id) {
        Paper p = store.paper(id);
        if (p == null) throw ApiError.notFound("No such paper");
        return p;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
