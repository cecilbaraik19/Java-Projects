package com.cecil.complaints.service;

import com.cecil.complaints.ai.AiEngine;
import com.cecil.complaints.ai.PriorityEngine;
import com.cecil.complaints.model.Complaint;
import com.cecil.complaints.model.Complaint.Note;
import com.cecil.complaints.model.Complaint.Recommendation;
import com.cecil.complaints.model.Enums;
import com.cecil.complaints.model.Enums.Category;
import com.cecil.complaints.model.Enums.Channel;
import com.cecil.complaints.model.Enums.CustomerTier;
import com.cecil.complaints.model.Enums.Priority;
import com.cecil.complaints.model.Enums.Role;
import com.cecil.complaints.model.Enums.Status;
import com.cecil.complaints.model.Solution;
import com.cecil.complaints.model.User;
import com.cecil.complaints.store.DataStore;
import com.cecil.complaints.util.Json;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * All business rules for complaints: create, analyse, assign, resolve, escalate.
 * Every method locks the store so concurrent requests stay consistent.
 */
public class ComplaintService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    public static final String SYSTEM = "system";
    public static final String AI = "AI assistant";

    private final DataStore store;
    private final AiEngine ai;

    public ComplaintService(DataStore store, AiEngine ai) {
        this.store = store;
        this.ai = ai;
    }

    // ================================================================== create

    /**
     * Create a complaint from form input and run the AI pipeline on it.
     *
     * @param actor username of the agent creating it, or null for the customer portal
     */
    public Complaint create(Map<String, Object> in, String actor) {
        Complaint c = new Complaint();
        c.customerName = required(in, "customerName", "Customer name", 2, 80);
        c.customerEmail = required(in, "customerEmail", "Email", 5, 120).toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(c.customerEmail).matches()) {
            throw ServiceException.badRequest("Please enter a valid email address");
        }
        c.orderId = optional(in, "orderId", 40);
        c.subject = required(in, "subject", "Subject", 3, 150);
        c.description = required(in, "description", "Description", 10, 5000);
        c.channel = Enums.parse(Channel.class, Json.str(in, "channel"), actor == null ? Channel.WEB : Channel.EMAIL);
        // customers cannot pick their own tier on the public portal
        c.customerTier = actor == null ? CustomerTier.STANDARD
                : Enums.parse(CustomerTier.class, Json.str(in, "customerTier"), CustomerTier.STANDARD);

        synchronized (store) {
            c.id = store.nextComplaintId();
            Instant now = Instant.now();
            c.createdAt = now;
            c.updatedAt = now;
            c.addHistory(actor == null ? "customer" : actor,
                    actor == null ? "Complaint submitted via customer portal" : "Complaint logged by agent");
            runAi(c, actor);
            c.dueAt = c.createdAt.plus(Duration.ofHours(c.priority.slaHours));
            autoAssign(c);
            store.complaints().put(c.id, c);
            store.save();
            return c;
        }
    }

    /** Runs the AI pipeline and records what it decided in the history. */
    private void runAi(Complaint c, String actor) {
        List<Complaint> others = new ArrayList<>(store.complaints().values());
        String agentName = c.assignedTo == null ? null : displayName(c.assignedTo);
        PriorityEngine.Result pr = ai.analyze(c, activeSolutions(), others, store.solutions(), agentName);
        for (Recommendation r : c.recommendations) {
            Solution s = store.solutions().get(r.solutionId);
            if (s != null) {
                s.timesRecommended++;
            }
        }
        c.addHistory(AI, "Classified as " + c.category.label + " (" + Math.round(c.categoryConfidence * 100)
                + "%), sentiment " + c.sentiment + ", priority " + c.priority);
        if (c.duplicateOf != null) {
            c.addHistory(AI, "Possible duplicate of " + c.duplicateOf);
        }
        if (pr.autoEscalate() && c.status != Status.ESCALATED) {
            c.status = Status.ESCALATED;
            c.escalationReason = pr.escalationReason();
            c.addHistory(AI, "Auto-escalated: " + pr.escalationReason());
        }
    }

    /** Give the complaint to the agent with the fewest active complaints. */
    private void autoAssign(Complaint c) {
        List<User> agents = store.users().values().stream()
                .filter(u -> u.active && u.role == Role.AGENT)
                .toList();
        if (agents.isEmpty()) {
            return;
        }
        User best = null;
        long bestLoad = Long.MAX_VALUE;
        for (User u : agents) {
            long load = store.complaints().values().stream()
                    .filter(x -> x.status.isActive() && u.username.equals(x.assignedTo))
                    .count();
            if (load < bestLoad) {
                bestLoad = load;
                best = u;
            }
        }
        c.assignedTo = best.username;
        c.addHistory(SYSTEM, "Auto-assigned to " + best.displayName + " (lowest workload)");
        // re-draft the reply now that we know the agent's name
        Solution top = c.recommendations.isEmpty() ? null : store.solutions().get(c.recommendations.get(0).solutionId);
        c.draftResponse = ai.responseGenerator().generate(c, top, best.displayName);
    }

    // ================================================================== read

    public Complaint get(String id) {
        synchronized (store) {
            Complaint c = store.complaints().get(id == null ? "" : id.toUpperCase(Locale.ROOT));
            if (c == null) {
                throw ServiceException.notFound("Complaint " + id + " not found");
            }
            return c;
        }
    }

    /** Filter and search. Any filter can be null. Newest first. */
    public List<Complaint> list(String status, String priority, String category, String assignedTo,
                                String query, boolean overdueOnly) {
        Status st = Enums.parse(Status.class, status, null);
        Priority pr = Enums.parse(Priority.class, priority, null);
        Category cat = Enums.parse(Category.class, category, null);
        String q = query == null ? null : query.trim().toLowerCase(Locale.ROOT);
        Instant now = Instant.now();
        synchronized (store) {
            return store.complaints().values().stream()
                    .filter(c -> st == null || c.status == st)
                    .filter(c -> pr == null || c.priority == pr)
                    .filter(c -> cat == null || c.category == cat)
                    .filter(c -> assignedTo == null || assignedTo.isBlank()
                            || ("unassigned".equals(assignedTo) ? c.assignedTo == null : assignedTo.equals(c.assignedTo)))
                    .filter(c -> !overdueOnly || c.isOverdue(now))
                    .filter(c -> q == null || q.isEmpty() || matches(c, q))
                    .sorted(Comparator.comparing((Complaint c) -> c.createdAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        }
    }

    private static boolean matches(Complaint c, String q) {
        return contains(c.id, q) || contains(c.customerName, q) || contains(c.customerEmail, q)
                || contains(c.orderId, q) || contains(c.subject, q) || contains(c.description, q)
                || c.keywords.stream().anyMatch(k -> k.contains(q));
    }

    private static boolean contains(String field, String q) {
        return field != null && field.toLowerCase(Locale.ROOT).contains(q);
    }

    // ================================================================== workflow

    public Complaint updateStatus(String id, String statusText, String note, User actor) {
        Status target = Enums.parse(Status.class, statusText, null);
        if (target == null) {
            throw ServiceException.badRequest("Unknown status: " + statusText);
        }
        synchronized (store) {
            Complaint c = get(id);
            Status from = c.status;
            if (from == target) {
                return c;
            }
            if (from == Status.CLOSED && target != Status.OPEN) {
                throw ServiceException.badRequest("A closed complaint can only be re-opened");
            }
            if (target == Status.ESCALATED && (note == null || note.isBlank())) {
                throw ServiceException.badRequest("Please give a reason for escalating");
            }
            c.status = target;
            Instant now = Instant.now();
            switch (target) {
                case RESOLVED -> {
                    c.resolvedAt = now;
                    // the AI learns from every resolved complaint
                    ai.classifier().train(c.fullText(), c.category);
                }
                case OPEN, IN_PROGRESS -> {
                    if (from == Status.RESOLVED || from == Status.CLOSED) {
                        c.resolvedAt = null;
                        c.dueAt = now.plus(Duration.ofHours(c.priority.slaHours));
                        c.addHistory(actor.username, "Re-opened - new deadline set");
                    }
                }
                case ESCALATED -> c.escalationReason = note.trim();
                default -> {
                }
            }
            c.addHistory(actor.username, "Status " + from + " -> " + target);
            if (note != null && !note.isBlank()) {
                c.notes.add(new Note(now, actor.username, limit(note.trim(), 2000)));
            }
            c.updatedAt = now;
            store.save();
            return c;
        }
    }

    public Complaint assign(String id, String username, User actor) {
        synchronized (store) {
            Complaint c = get(id);
            if (username == null || username.isBlank()) {
                c.assignedTo = null;
                c.addHistory(actor.username, "Unassigned");
            } else {
                User u = store.users().get(username);
                if (u == null || !u.active) {
                    throw ServiceException.badRequest("Unknown agent: " + username);
                }
                c.assignedTo = u.username;
                c.addHistory(actor.username, "Assigned to " + u.displayName);
            }
            c.updatedAt = Instant.now();
            store.save();
            return c;
        }
    }

    public Complaint changePriority(String id, String priorityText, User actor) {
        Priority p = Enums.parse(Priority.class, priorityText, null);
        if (p == null) {
            throw ServiceException.badRequest("Unknown priority: " + priorityText);
        }
        synchronized (store) {
            Complaint c = get(id);
            Priority old = c.priority;
            c.priority = p;
            c.dueAt = c.createdAt.plus(Duration.ofHours(p.slaHours));
            c.addHistory(actor.username, "Priority changed " + old + " -> " + p + " (AI suggested " + old + ")");
            c.updatedAt = Instant.now();
            store.save();
            return c;
        }
    }

    public Complaint changeCategory(String id, String categoryText, User actor) {
        Category cat = Enums.parse(Category.class, categoryText, null);
        if (cat == null) {
            throw ServiceException.badRequest("Unknown category: " + categoryText);
        }
        synchronized (store) {
            Complaint c = get(id);
            Category old = c.category;
            c.category = cat;
            c.categoryConfidence = 1.0; // a human confirmed it
            // learn from the correction straight away
            ai.classifier().train(c.fullText(), cat);
            c.recommendations = new com.cecil.complaints.ai.SolutionRecommender()
                    .recommend(c, activeSolutions(), resolvedCases(), AiEngine.TOP_N);
            c.addHistory(actor.username, "Category corrected " + old.label + " -> " + cat.label + " (AI re-trained)");
            c.updatedAt = Instant.now();
            store.save();
            return c;
        }
    }

    public Complaint addNote(String id, String text, User actor) {
        if (text == null || text.isBlank()) {
            throw ServiceException.badRequest("Note cannot be empty");
        }
        synchronized (store) {
            Complaint c = get(id);
            c.notes.add(new Note(Instant.now(), actor.username, limit(text.trim(), 2000)));
            c.updatedAt = Instant.now();
            store.save();
            return c;
        }
    }

    /** Agent chooses a solution: the draft reply is rewritten for that solution. */
    public Complaint applySolution(String id, String solutionId, boolean useLlm, User actor) {
        synchronized (store) {
            Complaint c = get(id);
            Solution s = store.solutions().get(solutionId);
            if (s == null) {
                throw ServiceException.notFound("Solution " + solutionId + " not found");
            }
            if (!s.id.equals(c.appliedSolutionId)) {
                s.timesApplied++;
            }
            c.appliedSolutionId = s.id;
            c.draftResponse = ai.draftFor(c, s, actor.displayName, useLlm);
            if (c.status == Status.OPEN) {
                c.status = Status.IN_PROGRESS;
            }
            c.addHistory(actor.username, "Applied solution " + s.id + ": " + s.title);
            c.updatedAt = Instant.now();
            store.save();
            return c;
        }
    }

    /**
     * Agent says whether a recommendation was useful. This updates the article's
     * success rate, which the recommender uses next time (learning from feedback).
     */
    public Complaint feedback(String id, String solutionId, boolean helpful, User actor) {
        synchronized (store) {
            Complaint c = get(id);
            Recommendation rec = c.recommendations.stream()
                    .filter(r -> r.solutionId.equals(solutionId))
                    .findFirst()
                    .orElseThrow(() -> ServiceException.badRequest("That solution was not recommended for this complaint"));
            Solution s = store.solutions().get(solutionId);
            if (s != null) {
                // undo an earlier vote, so changing your mind does not double count
                if (Boolean.TRUE.equals(rec.helpful)) {
                    s.helpfulCount = Math.max(0, s.helpfulCount - 1);
                } else if (Boolean.FALSE.equals(rec.helpful)) {
                    s.notHelpfulCount = Math.max(0, s.notHelpfulCount - 1);
                }
                if (helpful) {
                    s.helpfulCount++;
                } else {
                    s.notHelpfulCount++;
                }
            }
            rec.helpful = helpful;
            c.addHistory(actor.username, "Marked " + solutionId + (helpful ? " helpful" : " not helpful"));
            c.updatedAt = Instant.now();
            store.save();
            return c;
        }
    }

    /** Save the final reply as sent. Optionally resolve the complaint at the same time. */
    public Complaint sendResponse(String id, String text, boolean resolve, User actor) {
        if (text == null || text.trim().length() < 10) {
            throw ServiceException.badRequest("Response is too short");
        }
        synchronized (store) {
            Complaint c = get(id);
            c.finalResponse = limit(text.trim(), 8000);
            c.responseSent = true;
            c.addHistory(actor.username, "Response sent to customer");
            c.updatedAt = Instant.now();
            store.save();
        }
        if (resolve) {
            return updateStatus(id, "RESOLVED", null, actor);
        }
        return get(id);
    }

    /** Run the AI again (for example after the knowledge base changed). */
    public Complaint reanalyze(String id, User actor) {
        synchronized (store) {
            Complaint c = get(id);
            store.complaints().remove(c.id);
            try {
                runAi(c, actor.username);
                c.dueAt = c.createdAt.plus(Duration.ofHours(c.priority.slaHours));
                Solution top = c.recommendations.isEmpty() ? null : store.solutions().get(c.recommendations.get(0).solutionId);
                c.draftResponse = ai.responseGenerator().generate(c, top, displayName(c.assignedTo));
                c.appliedSolutionId = null;
            } finally {
                store.complaints().put(c.id, c);
            }
            c.addHistory(actor.username, "Re-ran AI analysis");
            c.updatedAt = Instant.now();
            store.save();
            return c;
        }
    }

    public void delete(String id, User actor) {
        if (actor.role != Role.ADMIN) {
            throw ServiceException.forbidden("Only admins can delete complaints");
        }
        synchronized (store) {
            Complaint c = get(id);
            store.complaints().remove(c.id);
            store.save();
        }
    }

    // ================================================================== customer portal

    /** Customer checks their complaint; the email must match so others cannot peek. */
    public Map<String, Object> track(String id, String email) {
        synchronized (store) {
            Complaint c = store.complaints().get(id == null ? "" : id.trim().toUpperCase(Locale.ROOT));
            if (c == null || email == null || !email.trim().equalsIgnoreCase(c.customerEmail)) {
                throw ServiceException.notFound("No complaint found with that reference and email");
            }
            return customerView(c);
        }
    }

    public Map<String, Object> rate(String id, String email, int rating) {
        if (rating < 1 || rating > 5) {
            throw ServiceException.badRequest("Rating must be between 1 and 5");
        }
        synchronized (store) {
            Complaint c = store.complaints().get(id == null ? "" : id.trim().toUpperCase(Locale.ROOT));
            if (c == null || email == null || !email.trim().equalsIgnoreCase(c.customerEmail)) {
                throw ServiceException.notFound("No complaint found with that reference and email");
            }
            if (c.status != Status.RESOLVED && c.status != Status.CLOSED) {
                throw ServiceException.badRequest("You can rate once the complaint is resolved");
            }
            c.satisfactionRating = rating;
            c.addHistory("customer", "Customer rated the resolution " + rating + "/5");
            c.updatedAt = Instant.now();
            store.save();
            return customerView(c);
        }
    }

    public Map<String, Object> customerView(Complaint c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("subject", c.subject);
        m.put("status", c.status);
        m.put("category", c.category.label);
        m.put("createdAt", c.createdAt == null ? null : c.createdAt.toString());
        m.put("expectedBy", c.dueAt == null ? null : c.dueAt.toString());
        m.put("resolvedAt", c.resolvedAt == null ? null : c.resolvedAt.toString());
        m.put("response", c.responseSent ? c.finalResponse : null);
        m.put("satisfactionRating", c.satisfactionRating);
        return m;
    }

    // ================================================================== SLA monitor

    /** Escalates every active complaint that missed its deadline. Returns how many changed. */
    public int escalateOverdue() {
        synchronized (store) {
            Instant now = Instant.now();
            int n = 0;
            for (Complaint c : store.complaints().values()) {
                if (c.isOverdue(now) && c.status != Status.ESCALATED) {
                    c.status = Status.ESCALATED;
                    c.escalationReason = "SLA breached (" + c.priority + " = " + c.priority.slaHours + "h)";
                    c.addHistory(SYSTEM, "Auto-escalated: " + c.escalationReason);
                    c.updatedAt = now;
                    n++;
                }
            }
            if (n > 0) {
                store.save();
            }
            return n;
        }
    }

    // ================================================================== export

    public String exportCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("id,created_at,customer_name,customer_email,order_id,channel,tier,subject,category,"
                + "category_confidence,sentiment,priority,status,assigned_to,due_at,resolved_at,overdue,"
                + "applied_solution,top_recommendation,satisfaction\n");
        Instant now = Instant.now();
        for (Complaint c : list(null, null, null, null, null, false)) {
            sb.append(String.join(",",
                    csv(c.id), csv(str(c.createdAt)), csv(c.customerName), csv(c.customerEmail), csv(c.orderId),
                    csv(c.channel.name()), csv(c.customerTier.name()), csv(c.subject), csv(c.category.label),
                    csv(String.format(Locale.ROOT, "%.2f", c.categoryConfidence)), csv(c.sentiment.name()),
                    csv(c.priority.name()), csv(c.status.name()), csv(c.assignedTo), csv(str(c.dueAt)),
                    csv(str(c.resolvedAt)), csv(String.valueOf(c.isOverdue(now))), csv(c.appliedSolutionId),
                    csv(c.recommendations.isEmpty() ? null : c.recommendations.get(0).solutionId),
                    csv(c.satisfactionRating == null ? null : String.valueOf(c.satisfactionRating))));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String str(Instant i) {
        return i == null ? null : i.toString();
    }

    /** CSV-escape a value. Also blocks spreadsheet formula injection (=, +, -, @). */
    static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ================================================================== demo data

    /** Loads the demo complaints through the real pipeline, back-dating them. */
    public void seedDemoComplaints(List<com.cecil.complaints.store.SeedData.DemoComplaint> demos, AuthService auth) {
        User admin;
        synchronized (store) {
            if (!store.complaints().isEmpty()) {
                return;
            }
            admin = store.users().get("admin");
        }
        for (var d : demos) {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("customerName", d.name());
            in.put("customerEmail", d.email());
            in.put("orderId", d.orderId());
            in.put("channel", d.channel());
            in.put("customerTier", d.tier());
            in.put("subject", d.subject());
            in.put("description", d.description());
            Complaint c = create(in, "admin");
            synchronized (store) {
                Duration back = Duration.ofDays(d.daysAgo()).plusHours(2);
                c.createdAt = c.createdAt.minus(back);
                c.updatedAt = c.createdAt;
                c.dueAt = c.createdAt.plus(Duration.ofHours(c.priority.slaHours));
                c.history.forEach(h -> h.at = h.at.minus(back));
            }
            User agent = c.assignedTo == null ? admin : findUser(c.assignedTo);
            if (d.applySolution() != null) {
                if (c.recommendations.stream().anyMatch(r -> r.solutionId.equals(d.applySolution()))) {
                    feedback(c.id, d.applySolution(), true, agent);
                }
                applySolution(c.id, d.applySolution(), false, agent);
            }
            if ("RESOLVED".equals(d.finalStatus())) {
                sendResponse(c.id, get(c.id).draftResponse, true, agent);
                synchronized (store) {
                    // resolved a few hours after it was created
                    Instant resolved = c.createdAt.plus(Duration.ofHours(Math.max(2, c.priority.slaHours / 2)));
                    c.resolvedAt = resolved;
                    c.updatedAt = resolved;
                }
                if (d.rating() != null) {
                    rate(c.id, c.customerEmail, d.rating());
                }
            } else if ("IN_PROGRESS".equals(d.finalStatus()) && c.status == Status.OPEN) {
                updateStatus(c.id, "IN_PROGRESS", null, agent);
            }
        }
        synchronized (store) {
            store.save();
        }
    }

    // ================================================================== helpers

    private User findUser(String username) {
        synchronized (store) {
            return store.users().get(username);
        }
    }

    private String displayName(String username) {
        if (username == null) {
            return null;
        }
        User u = store.users().get(username);
        return u == null ? username : u.displayName;
    }

    private List<Solution> activeSolutions() {
        return store.solutions().values().stream().filter(s -> s.active).toList();
    }

    private List<Complaint> resolvedCases() {
        return store.complaints().values().stream()
                .filter(c -> c.status == Status.RESOLVED || c.status == Status.CLOSED)
                .toList();
    }

    private static String required(Map<String, Object> in, String key, String label, int min, int max) {
        String v = Json.str(in, key);
        if (v == null || v.trim().length() < min) {
            throw ServiceException.badRequest(label + " is required (at least " + min + " characters)");
        }
        v = v.trim();
        if (v.length() > max) {
            throw ServiceException.badRequest(label + " is too long (max " + max + " characters)");
        }
        return v;
    }

    private static String optional(Map<String, Object> in, String key, int max) {
        String v = Json.str(in, key);
        if (v == null || v.isBlank()) {
            return null;
        }
        return limit(v.trim(), max);
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
