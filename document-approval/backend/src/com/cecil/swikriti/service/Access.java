package com.cecil.swikriti.service;

import com.cecil.swikriti.model.DocStatus;
import com.cecil.swikriti.model.Document;
import com.cecil.swikriti.model.Task;
import com.cecil.swikriti.model.Template;
import com.cecil.swikriti.model.User;
import com.cecil.swikriti.store.DataStore;

/**
 * Who may read a document. Documents are private by default (a contract or a salary claim is not for everybody):
 *   - the author, the people on its cc list
 *   - everybody who has (or had) a task on it, including delegates and escalation managers
 *   - the author's managers (all the way up the reporting line)
 *   - the Document Controller (admin)
 *   - EVERYBODY, once approved, if its type is published (policies and SOPs go to the library)
 */
public final class Access {
    private Access() { }

    public static boolean canSee(DataStore s, User u, Document d) {
        if (u == null || d == null) return false;
        if (u.admin() || u.id.equals(d.authorId) || d.cc.contains(u.id)) return true;
        Template t = s.templates.get(d.type);
        if (t != null && t.publishOnApproval && d.status == DocStatus.APPROVED) return true;
        for (Task k : s.tasksOf(d.id)) if (k.assignees.contains(u.id) || k.candidates.contains(u.id) || u.id.equals(k.decidedBy)) return true;
        User author = s.users.get(d.authorId);
        return author != null && s.manages(u, author);
    }

    public static Document see(DataStore s, User u, String docId) {
        Document d = s.docs.get(docId);
        if (d == null || !canSee(s, u, d)) throw Problem.notFound("Document");   // same answer: no hint that it exists
        return d;
    }

    public static void admin(User u) {
        if (u == null || !u.admin()) throw Problem.forbidden("Only the Document Controller can do this.");
    }
}
