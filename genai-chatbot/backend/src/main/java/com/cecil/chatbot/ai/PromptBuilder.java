package com.cecil.chatbot.ai;

import com.cecil.chatbot.knowledge.Retriever;
import com.cecil.chatbot.model.Message;
import com.cecil.chatbot.model.Persona;
import com.cecil.chatbot.safety.Guard;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the prompt that is sent to the model.
 *
 * 1. System prompt = who the bot is + the assistant's own instructions + today's date
 *    + (if found) knowledge-base notes numbered [1], [2] ...
 * 2. Chat history, newest first, until the token budget (maxContextTokens) is used up.
 *    Models can only read a limited amount ("context window"), and every token costs money,
 *    so very old messages are left out. The newest message is always included.
 * 3. If safety is on, personal details are hidden in everything that leaves the server.
 */
public final class PromptBuilder {

    private PromptBuilder() { }

    /** The finished prompt plus some facts for the screen. */
    public static class Built {
        public final ChatRequest request = new ChatRequest();
        public int keptMessages;
        public int droppedMessages;
        public int redactions;
        public int estimatedTokens;
    }

    public static Built build(String botName, Persona persona, List<Message> history, List<Retriever.Hit> hits,
                              int maxContextTokens, boolean redact, LocalDateTime now) {
        Built b = new Built();
        ChatRequest req = b.request;
        req.botName = botName;
        req.personaId = persona == null ? "" : persona.id;
        req.personaName = persona == null ? "assistant" : persona.name;
        req.sources = hits;

        StringBuilder sys = new StringBuilder();
        sys.append("You are ").append(botName).append(", a helpful AI assistant in a chat app.\n");
        if (persona != null && !persona.systemPrompt.isBlank()) sys.append(persona.systemPrompt.trim()).append('\n');
        sys.append("Today is ").append(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))).append(".\n");
        sys.append("Write in clear, simple English. Use Markdown (short paragraphs, lists, tables, ```code blocks```) when it helps.\n");
        sys.append("Some personal details may appear as [EMAIL], [PHONE], [CARD], [ID_NUMBER] or [SECRET_KEY] - they were hidden on purpose; do not ask for them.\n");
        if (!hits.isEmpty()) {
            sys.append("\nUse these notes from the knowledge base when they help. Cite them like [1] or [2] right after the fact. ")
               .append("If the notes do not contain the answer, say so, then answer from general knowledge.\n");
            for (Retriever.Hit h : hits) {
                sys.append("\n[").append(h.n()).append("] ").append(h.title()).append(":\n").append(h.text()).append('\n');
            }
        }
        String system = sys.toString();
        if (redact) {
            Guard.Redaction r = Guard.redact(system);
            system = r.text();
            b.redactions += r.count();
        }
        req.system = system;

        int budget = Math.max(200, maxContextTokens) - Tokens.estimate(system);
        List<ChatRequest.Turn> picked = new ArrayList<>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m.content == null || m.content.isBlank() || m.flags.contains("ERROR")) continue;
            String text = m.content;
            if (redact) {
                Guard.Redaction r = Guard.redact(text);
                text = r.text();
                b.redactions += r.count();
            }
            int t = Tokens.estimate(text);
            if (!picked.isEmpty() && used + t > budget) {
                b.droppedMessages = countUsable(history, i);
                break;
            }
            picked.add(0, new ChatRequest.Turn(m.isUser() ? "user" : "assistant", text));
            used += t;
        }
        req.turns.addAll(picked);
        b.keptMessages = picked.size();
        b.estimatedTokens = used + Tokens.estimate(system);
        return b;
    }

    private static int countUsable(List<Message> history, int upTo) {
        int n = 0;
        for (int i = 0; i <= upTo; i++) {
            Message m = history.get(i);
            if (m.content != null && !m.content.isBlank() && !m.flags.contains("ERROR")) n++;
        }
        return n;
    }
}
