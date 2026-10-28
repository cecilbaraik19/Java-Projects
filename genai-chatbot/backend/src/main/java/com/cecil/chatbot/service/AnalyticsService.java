package com.cecil.chatbot.service;

import com.cecil.chatbot.model.Chat;
import com.cecil.chatbot.model.Enums.Role;
import com.cecil.chatbot.model.Message;
import com.cecil.chatbot.model.Persona;
import com.cecil.chatbot.model.Provider;
import com.cecil.chatbot.model.UsageRecord;
import com.cecil.chatbot.model.User;
import com.cecil.chatbot.store.DataStore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Numbers for the dashboard: my usage, and for the admin the whole team's usage, models, feedback and safety. */
public class AnalyticsService {

    private final DataStore store;

    public AnalyticsService(DataStore store) {
        this.store = store;
    }

    public Map<String, Object> dashboard(User me) {
        boolean admin = me.role == Role.ADMIN;
        boolean trainer = me.role.canTrain();
        LocalDate today = store.today();
        String todayText = today.toString();
        Map<String, Object> out = new LinkedHashMap<>();
        synchronized (store.lock) {
            // ---- me
            int myChats = 0, myQuestions = 0, myAnswers = 0, questionsToday = 0;
            for (Chat c : store.chats) {
                if (!c.owner.equals(me.username)) continue;
                myChats++;
                for (Message m : c.messages) {
                    if (m.isUser()) {
                        myQuestions++;
                        if (m.at != null && m.at.startsWith(todayText)) questionsToday++;
                    } else {
                        myAnswers++;
                    }
                }
            }
            int tokensToday = 0;
            long myMs = 0;
            int myCalls = 0;
            for (UsageRecord u : store.usage) {
                if (!me.username.equals(u.username)) continue;
                if (u.at != null && u.at.startsWith(todayText)) tokensToday += u.totalTokens();
                if (u.ok) { myMs += u.ms; myCalls++; }
            }
            Map<String, Object> mine = new LinkedHashMap<>();
            mine.put("chats", myChats);
            mine.put("questions", myQuestions);
            mine.put("answers", myAnswers);
            mine.put("questionsToday", questionsToday);
            mine.put("tokensToday", tokensToday);
            mine.put("quota", admin ? 0 : store.settings.dailyTokenQuota);
            mine.put("ratePerMinute", admin ? 0 : store.settings.ratePerMinute);
            mine.put("avgMs", myCalls == 0 ? 0 : myMs / myCalls);
            out.put("me", mine);

            // ---- 14-day chart (team for admin, mine for others)
            List<Map<String, Object>> days = new ArrayList<>();
            Map<String, int[]> perDay = new LinkedHashMap<>();
            for (int i = 13; i >= 0; i--) perDay.put(today.minusDays(i).toString(), new int[3]);
            for (UsageRecord u : store.usage) {
                if (!admin && !me.username.equals(u.username)) continue;
                if (u.at == null || u.at.length() < 10) continue;
                int[] d = perDay.get(u.at.substring(0, 10));
                if (d == null) continue;
                d[0]++;
                d[1] += u.totalTokens();
                if (!u.ok) d[2]++;
            }
            perDay.forEach((k, v) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", k);
                m.put("calls", v[0]);
                m.put("tokens", v[1]);
                m.put("errors", v[2]);
                days.add(m);
            });
            out.put("days", days);
            out.put("scope", admin ? "team" : "me");

            // ---- provider status (everyone sees which models are ready)
            List<Map<String, Object>> status = new ArrayList<>();
            for (Provider p : store.providers) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.id);
                m.put("name", p.name);
                m.put("model", p.model);
                m.put("ready", p.ready());
                m.put("status", p.status());
                m.put("isDefault", p.id.equals(store.settings.defaultProviderId));
                status.add(m);
            }
            out.put("providers", status);

            if (trainer) out.put("feedback", feedback());
            if (admin) out.put("team", team(today));
        }
        return out;
    }

    /** Thumbs up / down counts and the latest thumbs-down answers (to improve the assistants). Caller holds the lock. */
    private Map<String, Object> feedback() {
        int up = 0, down = 0;
        List<Map<String, Object>> recent = new ArrayList<>();
        Map<String, int[]> byPersona = new LinkedHashMap<>();
        for (Chat c : store.chats) {
            Persona p = store.persona(c.personaId);
            String pname = p == null ? "General" : p.name;
            String question = "";
            for (Message m : c.messages) {
                if (m.isUser()) { question = m.content; continue; }
                if (m.feedback == 0) continue;
                int[] pc = byPersona.computeIfAbsent(pname, k -> new int[2]);
                if (m.feedback > 0) { up++; pc[0]++; } else { down++; pc[1]++; }
                if (m.feedback < 0) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("at", m.at);
                    r.put("user", store.displayName(c.owner));
                    r.put("assistant", pname);
                    r.put("model", (m.providerName == null ? "" : m.providerName) + (m.model == null ? "" : " / " + m.model));
                    r.put("question", cut(question, 140));
                    r.put("answer", cut(m.content, 180));
                    r.put("note", m.feedbackNote);
                    recent.add(r);
                }
            }
        }
        recent.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("at"))).reversed());
        List<Map<String, Object>> persona = new ArrayList<>();
        byPersona.forEach((k, v) -> persona.add(Map.of("name", k, "up", v[0], "down", v[1])));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("up", up);
        f.put("down", down);
        f.put("byAssistant", persona);
        f.put("recentDown", recent.size() > 8 ? recent.subList(0, 8) : recent);
        return f;
    }

    /** Whole team (admin). Caller holds the lock. */
    private Map<String, Object> team(LocalDate today) {
        Map<String, Object> t = new LinkedHashMap<>();
        int messages = 0, redacted = 0, injection = 0, fallback = 0, stopped = 0;
        for (Chat c : store.chats) {
            for (Message m : c.messages) {
                messages++;
                if (m.flags.contains("REDACTED")) redacted++;
                if (m.flags.contains("INJECTION")) injection++;
                if (m.flags.contains("FALLBACK")) fallback++;
                if (m.flags.contains("STOPPED")) stopped++;
            }
        }
        long tokens = 0;
        int calls = 0, errors = 0;
        String since = today.minusDays(29).toString();
        Map<String, long[]> byProvider = new LinkedHashMap<>();
        Map<String, long[]> byUser = new LinkedHashMap<>();
        for (UsageRecord u : store.usage) {
            tokens += u.totalTokens();
            calls++;
            if (!u.ok) errors++;
            if (u.at == null || u.at.compareTo(since) < 0) continue;
            Provider p = store.provider(u.providerId);
            String pn = p == null ? u.providerId : p.name;
            long[] a = byProvider.computeIfAbsent(pn, k -> new long[5]);
            a[0]++;
            a[1] += u.totalTokens();
            if (u.ok) { a[2] += u.ms; a[3]++; } else a[4]++;
            long[] b = byUser.computeIfAbsent(u.username, k -> new long[2]);
            b[0]++;
            b[1] += u.totalTokens();
        }
        List<Map<String, Object>> prov = new ArrayList<>();
        byProvider.forEach((k, a) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", k);
            m.put("calls", a[0]);
            m.put("tokens", a[1]);
            m.put("avgMs", a[3] == 0 ? 0 : a[2] / a[3]);
            m.put("errors", a[4]);
            prov.add(m);
        });
        prov.sort(Comparator.comparing((Map<String, Object> m) -> (Long) m.get("calls")).reversed());
        List<Map<String, Object>> users = new ArrayList<>();
        byUser.forEach((k, b) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", k);
            m.put("name", store.displayName(k));
            m.put("calls", b[0]);
            m.put("tokens", b[1]);
            users.add(m);
        });
        users.sort(Comparator.comparing((Map<String, Object> m) -> (Long) m.get("tokens")).reversed());
        Map<String, Integer> personaChats = new LinkedHashMap<>();
        for (Persona p : store.personas) personaChats.put(p.name, 0);
        for (Chat c : store.chats) {
            Persona p = store.persona(c.personaId);
            if (p != null) personaChats.merge(p.name, 1, Integer::sum);
        }
        List<Map<String, Object>> personas = new ArrayList<>();
        personaChats.forEach((k, v) -> personas.add(Map.of("name", k, "chats", v)));
        personas.sort(Comparator.comparing((Map<String, Object> m) -> (Integer) m.get("chats")).reversed());

        t.put("users", store.users.size());
        t.put("chats", store.chats.size());
        t.put("messages", messages);
        t.put("calls", calls);
        t.put("tokens", tokens);
        t.put("errors", errors);
        t.put("redacted", redacted);
        t.put("injection", injection);
        t.put("fallback", fallback);
        t.put("stopped", stopped);
        t.put("byProvider", prov);
        t.put("byUser", users.size() > 8 ? users.subList(0, 8) : users);
        t.put("byAssistant", personas);
        return t;
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        String t = Chat.plain(s);
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }
}
