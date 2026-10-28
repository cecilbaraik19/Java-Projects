package com.cecil.chatbot.service;

import com.cecil.chatbot.model.Chat;
import com.cecil.chatbot.model.Persona;
import com.cecil.chatbot.model.User;
import com.cecil.chatbot.store.DataStore;
import com.cecil.chatbot.util.Json;
import com.cecil.chatbot.util.Passwords;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Assistants (personas): list for everyone; add / change / remove for AI trainers and the admin. */
public class PersonaService {

    public static final String DEFAULT_ID = "general";
    static final Set<String> COLORS = Set.of("teal", "blue", "violet", "amber", "rose", "green", "slate");

    private final DataStore store;

    public PersonaService(DataStore store) {
        this.store = store;
    }

    public List<Map<String, Object>> list(User me) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            for (Persona p : store.personas) {
                if (!p.active && !me.role.canTrain()) continue;
                Map<String, Object> m = p.toMap();
                int chats = 0;
                for (Chat c : store.chats) if (p.id.equals(c.personaId)) chats++;
                m.put("chatCount", chats);
                out.add(m);
            }
        }
        return out;
    }

    /** The assistant to use for a chat (falls back to the default one if it was removed). Caller holds the lock. */
    public Persona resolve(String id) {
        Persona p = store.persona(id);
        if (p != null) return p;
        p = store.persona(DEFAULT_ID);
        if (p != null) return p;
        return store.personas.isEmpty() ? null : store.personas.get(0);
    }

    public Map<String, Object> save(User me, String id, Map<String, Object> body) {
        AuthService.requireTrainer(me);
        synchronized (store.lock) {
            Persona p;
            if (id == null) {
                if (store.personas.size() >= 60) throw ServiceException.bad("There are already 60 assistants. Remove some first.");
                p = new Persona();
                p.id = "p" + Passwords.randomId();
                p.createdBy = me.username;
            } else {
                p = store.persona(id);
                if (p == null) throw ServiceException.notFound("No such assistant.");
            }
            String name = AuthService.clean(Json.str(body, "name", p.name), 40);
            if (name == null || name.length() < 2) throw ServiceException.bad("Give the assistant a name (2-40 characters).");
            for (Persona o : store.personas) {
                if (o != p && o.name.equalsIgnoreCase(name)) throw ServiceException.conflict("Another assistant already has that name.");
            }
            String prompt = Json.str(body, "systemPrompt", p.systemPrompt);
            prompt = prompt == null ? "" : prompt.replace("\r", "").trim();
            if (prompt.length() < 10) throw ServiceException.bad("Write the instructions (system prompt), at least 10 characters.");
            if (prompt.length() > 4000) throw ServiceException.bad("Instructions can be at most 4000 characters.");
            double temp = Json.num(body, "temperature", p.temperature);
            if (temp < 0 || temp > 1.5) throw ServiceException.bad("Creativity (temperature) must be between 0 and 1.5.");
            List<String> starters = new ArrayList<>();
            for (Object o : body.containsKey("starters") ? Json.list(body, "starters") : new ArrayList<Object>(p.starters)) {
                String s = AuthService.clean(String.valueOf(o), 150);
                if (!s.isEmpty() && starters.size() < 6) starters.add(s);
            }
            String color = Json.str(body, "color", p.color);
            p.name = name;
            p.description = AuthService.clean(Json.str(body, "description", p.description), 160);
            p.systemPrompt = prompt;
            p.temperature = Math.round(temp * 100) / 100.0;
            p.useKnowledge = Json.bool(body, "useKnowledge", p.useKnowledge);
            p.starters = starters;
            p.color = COLORS.contains(color) ? color : "teal";
            boolean active = Json.bool(body, "active", p.active);
            if (!active && DEFAULT_ID.equals(p.id)) throw ServiceException.bad("The default assistant cannot be turned off.");
            p.active = active;
            p.updatedAt = store.nowText();
            if (id == null) store.personas.add(p);
            store.savePersonas();
            return p.toMap();
        }
    }

    public void delete(User me, String id) {
        AuthService.requireTrainer(me);
        synchronized (store.lock) {
            Persona p = store.persona(id);
            if (p == null) throw ServiceException.notFound("No such assistant.");
            if (DEFAULT_ID.equals(id)) throw ServiceException.bad("The default assistant cannot be removed.");
            store.personas.remove(p);
            // chats that used it move to the default assistant
            for (Chat c : store.chats) if (id.equals(c.personaId)) c.personaId = DEFAULT_ID;
            store.savePersonas();
            store.saveChats();
        }
    }
}
