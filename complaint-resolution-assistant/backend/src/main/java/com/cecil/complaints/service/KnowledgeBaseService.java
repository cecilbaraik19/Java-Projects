package com.cecil.complaints.service;

import com.cecil.complaints.model.Complaint;
import com.cecil.complaints.model.Enums;
import com.cecil.complaints.model.Enums.Category;
import com.cecil.complaints.model.Enums.Role;
import com.cecil.complaints.model.Solution;
import com.cecil.complaints.model.User;
import com.cecil.complaints.store.DataStore;
import com.cecil.complaints.util.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Create, edit and remove knowledge-base solutions.
 * Agents can read; only admins can change the knowledge base.
 */
public class KnowledgeBaseService {

    private final DataStore store;

    public KnowledgeBaseService(DataStore store) {
        this.store = store;
    }

    public List<Solution> list(String category, String query, boolean includeInactive) {
        Category cat = Enums.parse(Category.class, category, null);
        String q = query == null ? null : query.trim().toLowerCase(Locale.ROOT);
        synchronized (store) {
            return store.solutions().values().stream()
                    .filter(s -> includeInactive || s.active)
                    .filter(s -> cat == null || s.category == cat)
                    .filter(s -> q == null || q.isEmpty()
                            || s.title.toLowerCase(Locale.ROOT).contains(q)
                            || s.keywords.stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).contains(q)))
                    .sorted(Comparator.comparing((Solution s) -> s.id))
                    .toList();
        }
    }

    public Solution get(String id) {
        synchronized (store) {
            Solution s = store.solutions().get(id);
            if (s == null) {
                throw ServiceException.notFound("Solution " + id + " not found");
            }
            return s;
        }
    }

    public Solution create(Map<String, Object> in, User actor) {
        requireAdmin(actor);
        Solution s = new Solution();
        apply(s, in);
        synchronized (store) {
            s.id = store.nextSolutionId();
            s.createdAt = Instant.now();
            s.updatedAt = s.createdAt;
            store.solutions().put(s.id, s);
            store.save();
        }
        return s;
    }

    public Solution update(String id, Map<String, Object> in, User actor) {
        requireAdmin(actor);
        synchronized (store) {
            Solution s = get(id);
            apply(s, in);
            if (in.containsKey("active")) {
                s.active = Json.bool(in, "active", true);
            }
            s.updatedAt = Instant.now();
            store.save();
            return s;
        }
    }

    /**
     * Deletes the article. If complaints already used it, it is only deactivated,
     * so their history still makes sense.
     */
    public String delete(String id, User actor) {
        requireAdmin(actor);
        synchronized (store) {
            Solution s = get(id);
            boolean used = false;
            for (Complaint c : store.complaints().values()) {
                if (id.equals(c.appliedSolutionId)) {
                    used = true;
                    break;
                }
            }
            if (used) {
                s.active = false;
                s.updatedAt = Instant.now();
                store.save();
                return "deactivated";
            }
            store.solutions().remove(id);
            store.save();
            return "deleted";
        }
    }

    private void apply(Solution s, Map<String, Object> in) {
        String title = Json.str(in, "title");
        if (title == null || title.trim().length() < 5 || title.length() > 120) {
            throw ServiceException.badRequest("Title must be 5-120 characters");
        }
        s.title = title.trim();
        s.category = Enums.parse(Category.class, Json.str(in, "category"), Category.OTHER);
        s.keywords = cleanList(in.get("keywords"), "[,\\n]", 20, 60);
        s.steps = cleanList(in.get("steps"), "\\n", 15, 400);
        if (s.steps.isEmpty()) {
            throw ServiceException.badRequest("Add at least one resolution step");
        }
        String tpl = Json.str(in, "responseTemplate");
        if (tpl == null || tpl.trim().length() < 20) {
            throw ServiceException.badRequest("Customer response template must be at least 20 characters");
        }
        if (tpl.length() > 3000) {
            throw ServiceException.badRequest("Customer response template is too long");
        }
        s.responseTemplate = tpl.trim();
    }

    /** Accepts a JSON list or a separated string (keywords: comma/newline, steps: newline). */
    private static List<String> cleanList(Object raw, String splitRegex, int maxItems, int maxLen) {
        List<String> items = new ArrayList<>();
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                if (o != null) {
                    items.add(String.valueOf(o));
                }
            }
        } else if (raw instanceof String s) {
            for (String part : s.split(splitRegex)) {
                items.add(part);
            }
        }
        List<String> out = new ArrayList<>();
        for (String i : items) {
            String t = i.trim();
            if (!t.isEmpty() && out.size() < maxItems) {
                out.add(t.length() > maxLen ? t.substring(0, maxLen) : t);
            }
        }
        return out;
    }

    private static void requireAdmin(User u) {
        if (u == null || u.role != Role.ADMIN) {
            throw ServiceException.forbidden("Only admins can change the knowledge base");
        }
    }
}
