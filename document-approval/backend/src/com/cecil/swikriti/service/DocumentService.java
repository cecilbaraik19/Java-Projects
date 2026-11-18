package com.cecil.swikriti.service;

import com.cecil.swikriti.core.DocNumber;
import com.cecil.swikriti.core.Ledger;
import com.cecil.swikriti.model.Attachment;
import com.cecil.swikriti.model.Comment;
import com.cecil.swikriti.model.DocStatus;
import com.cecil.swikriti.model.Document;
import com.cecil.swikriti.model.Revision;
import com.cecil.swikriti.model.Task;
import com.cecil.swikriti.model.Template;
import com.cecil.swikriti.model.User;
import com.cecil.swikriti.store.DataStore;
import com.cecil.swikriti.util.Clock;
import com.cecil.swikriti.util.Json;
import com.cecil.swikriti.util.Text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writing documents: create a draft, edit it, attach a file, submit it (this freezes a revision and starts the
 * workflow), withdraw it, and redline comments.
 */
public class DocumentService {
    public static final int MAX_BODY = 20_000;
    public static final int MAX_FILE = 1_000_000;
    public static final long MAX_AMOUNT = 100_000_000_000L;   // Rs. 10,000 crore - anything above is a typing mistake
    public static final int MAX_CC = 10;
    public static final int MAX_COMMENTS = 200;

    private final DataStore store;
    private final WorkflowService flow;

    public DocumentService(DataStore store, WorkflowService flow) {
        this.store = store;
        this.flow = flow;
    }

    // ================================================================== drafts

    public Document create(User u, Map<String, Object> b) {
        synchronized (store) {
            Template t = store.templates.get(Json.str(b, "type"));
            if (t == null || !t.active) throw Problem.bad("Choose a document type.");
            Document d = new Document();
            d.type = t.code;
            d.authorId = u.id;
            d.dept = u.dept;
            d.createdAt = d.updatedAt = Clock.millis();
            fill(d, t, b, u);
            d.id = store.nextId("D", 1001);
            store.docs.put(d.id, d);
            store.log(u.id, "doc.create", d.id, t.code + " " + d.title);
            store.save("docs", "audit");
            return d;
        }
    }

    public Document update(User u, String id, Map<String, Object> b) {
        synchronized (store) {
            Document d = mine(u, id);
            Template t = store.templates.get(d.type);
            fill(d, t, b, u);
            d.updatedAt = Clock.millis();
            store.save("docs");
            return d;
        }
    }

    /** Copies the form fields into the working copy, with all the checks. */
    private void fill(Document d, Template t, Map<String, Object> b, User u) {
        String title = Text.clean(Json.str(b, "title", d.title), 140);
        if (title == null || title.length() < 3) throw Problem.bad("Give the document a title (at least 3 characters).");
        d.title = title;
        if (b.containsKey("amount") || !t.hasAmount) {
            long amount = t.hasAmount ? Json.num(b, "amount", 0) : 0;
            if (amount < 0) throw Problem.bad("The amount cannot be negative.");
            if (amount > MAX_AMOUNT) throw Problem.bad("That amount is too large - check the number of zeros.");
            d.amount = amount;
        }
        if (b.containsKey("vendor")) d.vendor = Text.clean(Json.str(b, "vendor"), 80);
        if (b.containsKey("urgent")) d.urgent = Json.bool(b, "urgent", false);
        if (b.containsKey("needBy")) {
            String nb = Json.str(b, "needBy");
            if (Text.blank(nb)) d.needBy = null;
            else {
                LocalDate nd;
                try { nd = LocalDate.parse(nb.trim()); } catch (Exception e) { throw Problem.bad("'Needed by' is not a valid date."); }
                if (nd.isBefore(Clock.today())) throw Problem.bad("'Needed by' is in the past.");
                d.needBy = nd.toString();
            }
        }
        if (b.containsKey("body")) {
            String body = Json.str(b, "body", "");
            if (body.length() > MAX_BODY) throw Problem.bad("The text is too long (at most " + MAX_BODY + " characters).");
            d.body = Text.block(body, MAX_BODY);
        }
        if (b.containsKey("dept")) {
            String dept = Text.clean(Json.str(b, "dept"), 40);
            if (!Text.blank(dept)) d.dept = dept;
        }
        if (b.containsKey("cc")) {
            List<String> cc = new ArrayList<>();
            for (String id : Json.strings(b, "cc")) {
                User c = store.users.get(id);
                if (c == null || !c.active) throw Problem.bad("Unknown person on the cc list.");
                if (!c.id.equals(u.id) && !cc.contains(c.id)) cc.add(c.id);
            }
            if (cc.size() > MAX_CC) throw Problem.bad("At most " + MAX_CC + " people on the cc list.");
            d.cc = cc;
        }
    }

    /** A document the author may still change: DRAFT or RETURNED. */
    private Document mine(User u, String id) {
        Document d = Access.see(store, u, id);
        if (!d.authorId.equals(u.id)) throw Problem.forbidden("Only the author can change the document.");
        if (d.status != DocStatus.DRAFT && d.status != DocStatus.RETURNED)
            throw Problem.conflict("A document that is " + d.status.name().toLowerCase().replace('_', ' ') + " cannot be changed.");
        return d;
    }

    // ================================================================== attachment

    /** Adds a PDF / PNG / JPG (Base64 in JSON). The type is checked from the first bytes, not from the name. */
    public Document attach(User u, String id, Map<String, Object> b) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(Json.str(b, "data", "")); } catch (IllegalArgumentException e) { throw Problem.bad("The file could not be read."); }
        if (bytes.length == 0) throw Problem.bad("The file is empty.");
        if (bytes.length > MAX_FILE) throw Problem.bad("The file is larger than 1 MB.");
        String mime = sniff(bytes);
        if (mime == null) throw Problem.bad("Only PDF, PNG and JPG files can be attached.");
        String name = Text.clean(Json.str(b, "name", "file"), 80).replaceAll("[\\\\/:*?\"<>|]", "_");
        synchronized (store) {
            Document d = mine(u, id);
            Attachment a = new Attachment();
            a.name = name.isEmpty() ? "file" : name;
            a.mime = mime;
            a.size = bytes.length;
            a.sha = Ledger.sha256(bytes);
            store.putFile(a.sha, bytes);
            d.attachment = a;
            d.updatedAt = Clock.millis();
            store.save("docs");
            return d;
        }
    }

    public Document detach(User u, String id) {
        synchronized (store) {
            Document d = mine(u, id);
            d.attachment = null;
            d.updatedAt = Clock.millis();
            store.save("docs");
            return d;
        }
    }

    /** Recognises a file by its "magic bytes". */
    static String sniff(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "application/pdf";
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "image/jpeg";
        return null;
    }

    // ================================================================== submit and withdraw

    /** Freezes the working copy as the next revision and starts the workflow. */
    public Document submit(User u, String id, String note) {
        synchronized (store) {
            Document d = mine(u, id);
            Template t = store.templates.get(d.type);
            if (t == null || !t.active) throw Problem.conflict("This document type is switched off. Ask the Document Controller.");
            if (t.hasAmount && d.amount <= 0) throw Problem.bad("Enter the amount (Rs.).");
            if (Text.blank(d.body) && d.attachment == null) throw Problem.bad("Write the details or attach a file before submitting.");
            Revision last = d.latest();
            String n = Text.clean(note, 300);
            if (last != null) {
                if (n == null || n.length() < 5) throw Problem.bad("Say in a few words what you changed (at least 5 characters).");
                if (Objects.equals(contentKey(last), contentKey(d))) throw Problem.bad("Nothing changed since revision " + last.letter() + ". Make the changes that were asked for first.");
            }
            if (d.number == null) {
                String fy = DocNumber.fy(Clock.today());
                d.number = DocNumber.format(t.code, fy, store.nextNumber(t.code + "/" + fy));
                d.submittedAt = Clock.millis();
            }
            Revision r = new Revision();
            r.no = d.rev() + 1;
            r.at = Clock.millis();
            r.title = d.title; r.amount = d.amount; r.vendor = d.vendor; r.urgent = d.urgent; r.needBy = d.needBy;
            r.body = d.body; r.attachment = d.attachment; r.note = n;
            r.hash = hash(d, r);
            d.revisions.add(r);
            for (Comment c : store.comments.values())       // old redlines are closed by the new revision
                if (c.docId.equals(d.id) && !c.resolved) { c.resolved = true; c.resolvedBy = u.id; }
            store.chain(d.id, r.no, "SUBMIT", u.id, null, r.hash);
            flow.start(d, t);
            d.updatedAt = Clock.millis();
            store.log(u.id, "doc.submit", d.number, "rev " + r.letter());
            store.save("docs", "tasks", "comments", "notes", "ledger", "audit");
            return d;
        }
    }

    public Document withdraw(User u, String id, String reason) {
        synchronized (store) {
            Document d = Access.see(store, u, id);
            if (!d.authorId.equals(u.id)) throw Problem.forbidden("Only the author can withdraw the document.");
            if (d.status.closed()) throw Problem.conflict("The document is already closed.");
            String r = Text.clean(reason, 300);
            if (d.status != DocStatus.DRAFT && (r == null || r.length() < 5)) throw Problem.bad("Give a short reason (at least 5 characters).");
            List<Task> open = new ArrayList<>();
            for (Task k : store.tasksOf(d.id)) if (k.state.open()) open.add(k);
            flow.closeOpen(d, Task.State.CANCELLED);
            if (d.stageIndex >= 0) d.route.get(d.stageIndex).state = com.cecil.swikriti.model.RouteStage.State.STOPPED;
            d.stageIndex = -1;
            d.status = DocStatus.WITHDRAWN;
            d.closedAt = Clock.millis();
            if (d.rev() > 0) store.chain(d.id, d.rev(), "WITHDRAW", u.id, null, d.latest().hash);
            for (Task k : open)
                for (String a : k.assignees) store.notify(a, (d.number == null ? d.title : d.number) + " was withdrawn: " + r, "#/doc/" + d.id);
            store.log(u.id, "doc.withdraw", d.number == null ? d.id : d.number, r);
            store.save("docs", "tasks", "notes", "ledger", "audit");
            return d;
        }
    }

    // ================================================================== hashing

    /** The fingerprint of a revision: SHA-256 of every field that the approvers see, in a fixed order. */
    public static String hash(Document d, Revision r) {
        String text = "SWIKRITI-1\n" + d.number + "\n" + d.type + "\n" + r.no + "\n" + r.title + "\n" + r.amount + "\n"
                + nz(r.vendor) + "\n" + r.urgent + "\n" + nz(r.needBy) + "\n" + (r.attachment == null ? "" : r.attachment.sha) + "\n" + nz(r.body);
        return Ledger.sha256(text);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String contentKey(Revision r) {
        return r.title + "|" + r.amount + "|" + nz(r.vendor) + "|" + r.urgent + "|" + nz(r.needBy) + "|" + (r.attachment == null ? "" : r.attachment.sha) + "|" + nz(r.body);
    }

    private static String contentKey(Document d) {
        return d.title + "|" + d.amount + "|" + nz(d.vendor) + "|" + d.urgent + "|" + nz(d.needBy) + "|" + (d.attachment == null ? "" : d.attachment.sha) + "|" + nz(d.body);
    }

    // ================================================================== redlines

    /** A note on one line of the latest revision. Only the author and the people with a task on it may write. */
    public Comment comment(User u, String id, int line, String text) {
        synchronized (store) {
            Document d = Access.see(store, u, id);
            Revision r = d.latest();
            if (r == null) throw Problem.bad("Comments are for submitted documents.");
            if (d.status.closed()) throw Problem.conflict("The document is closed.");
            boolean part = d.authorId.equals(u.id) || u.admin();
            for (Task k : store.tasksOf(d.id)) if (k.assignees.contains(u.id)) part = true;
            if (!part) throw Problem.forbidden("Only the author and the reviewers can write redlines.");
            int lines = r.body == null || r.body.isEmpty() ? 0 : r.body.split("\n", -1).length;
            if (line < 0 || line > lines) throw Problem.bad("There is no line " + line + ".");
            String t = Text.block(text, 600);
            if (t == null || t.length() < 2) throw Problem.bad("Write the comment.");
            long count = store.comments.values().stream().filter(c -> c.docId.equals(d.id)).count();
            if (count >= MAX_COMMENTS) throw Problem.conflict("This document has too many comments.");
            Comment c = new Comment();
            c.id = store.nextId("C", 1);
            c.docId = d.id;
            c.rev = r.no;
            c.line = line;
            c.userId = u.id;
            c.text = t;
            c.at = Clock.millis();
            store.comments.put(c.id, c);
            if (!d.authorId.equals(u.id)) store.notify(d.authorId, store.users.get(u.id).name + " marked up " + d.number
                    + (line > 0 ? " (line " + line + ")" : ""), "#/doc/" + d.id);
            store.save("comments", "notes");
            return c;
        }
    }

    public Comment resolve(User u, String commentId) {
        synchronized (store) {
            Comment c = store.comments.get(commentId);
            if (c == null) throw Problem.notFound("Comment");
            Document d = Access.see(store, u, c.docId);
            if (!(u.id.equals(c.userId) || u.id.equals(d.authorId) || u.admin())) throw Problem.forbidden("Only the writer or the author can close a comment.");
            c.resolved = true;
            c.resolvedBy = u.id;
            store.save("comments");
            return c;
        }
    }

    // ================================================================== route preview

    /** "If I submit this now, who will approve it?" - for the form. Works on unsaved values too. */
    public List<Map<String, Object>> preview(User u, Map<String, Object> b) {
        synchronized (store) {
            Template t = store.templates.get(Json.str(b, "type"));
            if (t == null) throw Problem.bad("Choose a document type.");
            Document d = new Document();
            d.type = t.code;
            d.authorId = u.id;
            d.dept = Text.blank(Json.str(b, "dept")) ? u.dept : Json.str(b, "dept");
            d.title = Json.str(b, "title", "");
            d.amount = t.hasAmount ? Math.max(0, Json.num(b, "amount", 0)) : 0;
            d.vendor = Json.str(b, "vendor", "");
            d.urgent = Json.bool(b, "urgent", false);
            return flow.plan(d, t);
        }
    }
}
