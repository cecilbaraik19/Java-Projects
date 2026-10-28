package com.cecil.chatbot.service;

import com.cecil.chatbot.ai.AiException;
import com.cecil.chatbot.ai.ChatModel;
import com.cecil.chatbot.ai.ChatResult;
import com.cecil.chatbot.ai.ModelRegistry;
import com.cecil.chatbot.ai.PromptBuilder;
import com.cecil.chatbot.ai.Tokens;
import com.cecil.chatbot.knowledge.Retriever;
import com.cecil.chatbot.model.Chat;
import com.cecil.chatbot.model.Enums.InjectionMode;
import com.cecil.chatbot.model.Enums.ProviderKind;
import com.cecil.chatbot.model.Enums.Role;
import com.cecil.chatbot.model.Message;
import com.cecil.chatbot.model.Persona;
import com.cecil.chatbot.model.Provider;
import com.cecil.chatbot.model.Settings;
import com.cecil.chatbot.model.UsageRecord;
import com.cecil.chatbot.model.User;
import com.cecil.chatbot.safety.Guard;
import com.cecil.chatbot.safety.RateLimiter;
import com.cecil.chatbot.store.DataStore;
import com.cecil.chatbot.util.Json;
import com.cecil.chatbot.util.Passwords;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chats and messages, and the most important job: getting an answer from the AI.
 *
 * Sending a message (generate):
 *   1. checks: owner, not already answering, rate limit, daily token limit, blocked words, prompt injection
 *   2. saves the person's message
 *   3. looks up the knowledge base (if on for this chat)
 *   4. builds the prompt (system prompt + recent history within the token budget, personal details hidden)
 *   5. streams the answer from the chosen AI provider, piece by piece, to the browser
 *   6. if the online model fails before writing anything, the built-in demo answers (when allowed)
 *   7. saves the answer with model name, tokens and time taken, and a usage record
 */
public class ChatService {

    public static final int MAX_MESSAGE = 8000;
    public static final int MAX_CHATS = 500;

    /** Receives the answer while it is being written (the web server turns this into SSE events). */
    public interface Sink {
        void start(Map<String, Object> info) throws IOException;
        void delta(String text) throws IOException;
    }

    private final DataStore store;
    private final ModelRegistry models;
    private final PersonaService personas;
    private final KnowledgeService knowledge;
    private final RateLimiter limiter;
    /** Chats that are answering right now -> their Stop flag. */
    private final Map<String, AtomicBoolean> active = new ConcurrentHashMap<>();

    public ChatService(DataStore store, ModelRegistry models, PersonaService personas, KnowledgeService knowledge, RateLimiter limiter) {
        this.store = store;
        this.models = models;
        this.personas = personas;
        this.knowledge = knowledge;
        this.limiter = limiter;
    }

    // ================================================================== chats

    /** My chats, pinned first, newest first. q searches titles and messages. */
    public List<Map<String, Object>> list(User me, String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Chat> mine = new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            for (Chat c : store.chats) {
                if (!c.owner.equals(me.username)) continue;
                if (!needle.isEmpty() && !matches(c, needle)) continue;
                mine.add(c);
            }
            mine.sort(Comparator.comparing((Chat c) -> !c.pinned).thenComparing((Chat c) -> c.updatedAt, Comparator.reverseOrder()));
            for (Chat c : mine) {
                Map<String, Object> m = c.summary();
                m.put("busy", active.containsKey(c.id));
                out.add(m);
            }
        }
        return out;
    }

    private static boolean matches(Chat c, String needle) {
        if (c.title.toLowerCase(Locale.ROOT).contains(needle)) return true;
        for (Message m : c.messages) if (m.content.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return false;
    }

    /** Finds my chat. Someone else's chat gives "not found" (we do not even say it exists). Caller holds the lock. */
    private Chat mine(User me, String id) {
        Chat c = store.chat(id);
        if (c == null || !c.owner.equals(me.username)) throw ServiceException.notFound("Chat not found.");
        return c;
    }

    public Map<String, Object> get(User me, String id) {
        synchronized (store.lock) {
            Chat c = mine(me, id);
            Map<String, Object> m = c.toMap();
            m.put("busy", active.containsKey(c.id));
            return m;
        }
    }

    public Map<String, Object> create(User me, Map<String, Object> body) {
        synchronized (store.lock) {
            long count = store.chats.stream().filter(c -> c.owner.equals(me.username)).count();
            if (count >= MAX_CHATS) throw ServiceException.bad("You have " + MAX_CHATS + " chats. Delete some old ones first.");
            Persona p = personas.resolve(Json.str(body, "personaId"));
            if (p != null && !p.active) p = personas.resolve(PersonaService.DEFAULT_ID);
            Chat c = new Chat();
            c.id = "c" + Passwords.randomId();
            c.owner = me.username;
            c.personaId = p == null ? PersonaService.DEFAULT_ID : p.id;
            c.useKnowledge = p != null && p.useKnowledge;
            String prov = Json.str(body, "providerId", "");
            c.providerId = store.provider(prov) != null ? prov : "";
            c.model = "";
            c.createdAt = store.nowText();
            c.updatedAt = c.createdAt;
            store.chats.add(c);
            store.saveChats();
            return c.toMap();
        }
    }

    public Map<String, Object> update(User me, String id, Map<String, Object> b) {
        synchronized (store.lock) {
            Chat c = mine(me, id);
            if (b.containsKey("title")) {
                String t = AuthService.clean(Json.str(b, "title", ""), 80);
                if (t.isEmpty()) throw ServiceException.bad("The title cannot be empty.");
                c.title = t;
            }
            if (b.containsKey("personaId")) {
                Persona p = store.persona(Json.str(b, "personaId"));
                if (p == null || !p.active) throw ServiceException.bad("Unknown assistant.");
                if (!p.id.equals(c.personaId)) c.useKnowledge = p.useKnowledge;
                c.personaId = p.id;
            }
            if (b.containsKey("providerId")) {
                String prov = Json.str(b, "providerId", "");
                if (!prov.isEmpty() && store.provider(prov) == null) throw ServiceException.bad("Unknown AI model provider.");
                if (!prov.equals(c.providerId == null ? "" : c.providerId)) c.model = "";
                c.providerId = prov;
            }
            if (b.containsKey("model")) {
                String m = AuthService.clean(Json.str(b, "model", ""), 100);
                if (!m.isEmpty() && !m.matches("[A-Za-z0-9._:/@-]+")) throw ServiceException.bad("The model name has characters that are not allowed.");
                c.model = m;
            }
            if (b.containsKey("temperature")) {
                Object t = b.get("temperature");
                if (t == null || "".equals(t)) {
                    c.temperature = null;
                } else {
                    double v = Json.num(b, "temperature", Double.NaN);
                    if (Double.isNaN(v) || v < 0 || v > 1.5) throw ServiceException.bad("Creativity (temperature) must be between 0 and 1.5.");
                    c.temperature = Math.round(v * 100) / 100.0;
                }
            }
            if (b.containsKey("useKnowledge")) c.useKnowledge = Json.bool(b, "useKnowledge", c.useKnowledge);
            if (b.containsKey("pinned")) c.pinned = Json.bool(b, "pinned", c.pinned);
            store.saveChats();
            return c.summary();
        }
    }

    public void delete(User me, String id) {
        synchronized (store.lock) {
            Chat c = mine(me, id);
            AtomicBoolean flag = active.get(c.id);
            if (flag != null) flag.set(true);
            store.chats.remove(c);
            store.saveChats();
        }
    }

    /** Deletes all my chats. */
    public int deleteAll(User me) {
        synchronized (store.lock) {
            int before = store.chats.size();
            store.chats.removeIf(c -> c.owner.equals(me.username) && !c.pinned);
            store.saveChats();
            return before - store.chats.size();
        }
    }

    public Map<String, Object> feedback(User me, String chatId, String msgId, Map<String, Object> b) {
        synchronized (store.lock) {
            Chat c = mine(me, chatId);
            Message m = c.message(msgId);
            if (m == null || m.isUser()) throw ServiceException.notFound("Answer not found.");
            int v = Json.integer(b, "value", 0);
            m.feedback = v > 0 ? 1 : v < 0 ? -1 : 0;
            m.feedbackNote = m.feedback == 0 ? null : AuthService.clean(Json.str(b, "note", ""), 300);
            store.saveChats();
            return m.toMap();
        }
    }

    /** Asks the running answer in this chat to stop. */
    public boolean stop(User me, String id) {
        synchronized (store.lock) {
            mine(me, id);
        }
        AtomicBoolean flag = active.get(id);
        if (flag == null) return false;
        flag.set(true);
        return true;
    }

    // ================================================================== export

    public String exportMarkdown(User me, String id) {
        synchronized (store.lock) {
            Chat c = mine(me, id);
            Persona p = personas.resolve(c.personaId);
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(c.title).append("\n\n");
            sb.append("- Assistant: ").append(p == null ? "-" : p.name).append('\n');
            sb.append("- Started: ").append(c.createdAt.replace('T', ' ')).append('\n');
            sb.append("- Exported: ").append(store.nowText().replace('T', ' ')).append("\n\n---\n\n");
            for (Message m : c.messages) {
                if (m.isUser()) {
                    sb.append("**").append(store.displayName(c.owner)).append("** (").append(m.at.replace('T', ' ')).append(")\n\n");
                } else {
                    sb.append("**").append(store.settings.botName).append("** · ").append(m.providerName == null ? "" : m.providerName)
                      .append(m.model == null || m.model.isEmpty() ? "" : " / " + m.model).append(" (").append(m.at.replace('T', ' ')).append(")\n\n");
                }
                sb.append(m.content.isEmpty() ? "_(no text)_" : m.content).append("\n\n");
                if (!m.sources.isEmpty()) {
                    sb.append("Sources: ");
                    for (Map<String, Object> s : m.sources) sb.append('[').append(s.get("n")).append("] ").append(s.get("title")).append("  ");
                    sb.append("\n\n");
                }
                sb.append("---\n\n");
            }
            return sb.toString();
        }
    }

    public String exportJson(User me, String id) {
        synchronized (store.lock) {
            return Json.pretty(mine(me, id).toMap());
        }
    }

    // ================================================================== the AI answer

    /** mode: "new" (send text), "regenerate" (answer the last message again), "edit" (change the last message and answer again). */
    public Map<String, Object> generate(User me, String chatId, String mode, String text, Sink sink) {
        String md = mode == null || mode.isBlank() ? "new" : mode.trim().toLowerCase(Locale.ROOT);
        if (!md.equals("new") && !md.equals("regenerate") && !md.equals("edit")) throw ServiceException.bad("Unknown mode.");
        String input = text == null ? "" : text.replace("\r", "").replaceAll("[\\p{Cntrl}&&[^\n\t]]", "").strip();
        if (!md.equals("regenerate")) {
            if (input.isEmpty()) throw ServiceException.bad("Type a message first.");
            if (input.length() > MAX_MESSAGE) throw ServiceException.bad("The message is too long (at most " + MAX_MESSAGE + " characters).");
        }

        Settings s;
        synchronized (store.lock) {
            mine(me, chatId);
            s = store.settings;
        }
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        if (active.putIfAbsent(chatId, stopFlag) != null) throw ServiceException.conflict("The bot is still answering in this chat. Wait or press Stop.");
        try {
            return generateLocked(me, chatId, md, input, s, stopFlag, sink);
        } finally {
            active.remove(chatId, stopFlag);
        }
    }

    private Map<String, Object> generateLocked(User me, String chatId, String md, String input, Settings s,
                                                AtomicBoolean stopFlag, Sink sink) {
        boolean admin = me.role == Role.ADMIN;
        // ---- 1. limits
        if (!admin) {
            int quota = s.dailyTokenQuota;
            if (quota > 0) {
                int used = tokensToday(me.username);
                if (used >= quota) throw ServiceException.tooMany("You have used today's limit of " + quota + " tokens. It resets at midnight.");
            }
            long wait = limiter.tryAcquire(me.username, s.ratePerMinute);
            if (wait > 0) throw ServiceException.tooMany("You are sending messages too fast. Try again in " + wait + " seconds.");
        }
        // ---- 2. safety checks on the new text
        List<String> userFlags = new ArrayList<>();
        String userNote = null;
        if (!md.equals("regenerate")) {
            String bad = Guard.blockedWord(input, s.blockedWords);
            if (bad != null) throw ServiceException.bad("Your message has a blocked word (\"" + bad + "\"). Please rephrase it.");
            if (s.injectionMode != InjectionMode.OFF && Guard.looksLikeInjection(input)) {
                if (s.injectionMode == InjectionMode.BLOCK) {
                    throw ServiceException.bad("This message looks like an attempt to make the bot ignore its rules (prompt injection), so it was blocked.");
                }
                userFlags.add("INJECTION");
                userNote = "Looks like a prompt-injection attempt (marked for the admin).";
            }
        }

        // ---- 3. change the chat and take a copy of what we need (under the lock)
        Chat chat;
        Message userMsg;
        List<Message> history;
        Persona persona;
        Provider provider;
        String model;
        double temperature;
        boolean useKb;
        synchronized (store.lock) {
            chat = mine(me, chatId);
            int lastUser = -1;
            for (int i = chat.messages.size() - 1; i >= 0; i--) if (chat.messages.get(i).isUser()) { lastUser = i; break; }
            if (md.equals("new")) {
                userMsg = new Message();
                userMsg.id = "m" + Passwords.randomId();
                userMsg.role = Message.USER;
                userMsg.content = input;
                userMsg.at = store.nowText();
                chat.messages.add(userMsg);
            } else {
                if (lastUser < 0) throw ServiceException.bad("There is no message to answer again.");
                userMsg = chat.messages.get(lastUser);
                if (md.equals("edit")) {
                    userMsg.content = input;
                    userMsg.editedAt = store.nowText();
                    userMsg.flags.remove("INJECTION");
                    userMsg.note = null;
                }
                while (chat.messages.size() > lastUser + 1) chat.messages.remove(chat.messages.size() - 1);
            }
            for (String f : userFlags) if (!userMsg.flags.contains(f)) userMsg.flags.add(f);
            if (userNote != null) userMsg.note = userNote;
            if (chat.title.equals("New chat")) chat.title = autoTitle(userMsg.content);
            chat.updatedAt = store.nowText();
            history = new ArrayList<>(chat.messages);

            persona = personas.resolve(chat.personaId);
            provider = store.provider(chat.providerId == null || chat.providerId.isEmpty() ? s.defaultProviderId : chat.providerId);
            if (provider == null) provider = store.provider("demo");
            if (provider == null) throw new IllegalStateException("No demo provider");
            model = chat.model == null || chat.model.isEmpty() ? provider.model : chat.model;
            temperature = chat.temperature != null ? chat.temperature : persona == null ? 0.7 : persona.temperature;
            useKb = chat.useKnowledge;
            store.saveChats();
        }

        // ---- 4. which model actually answers
        List<String> flags = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Provider answering = provider;
        if (!provider.ready()) {
            if (s.fallbackToDemo) {
                answering = demoProvider();
                flags.add("FALLBACK");
                notes.add(provider.name + " is not ready (" + provider.status().toLowerCase(Locale.ROOT) + "), so the built-in demo answered.");
            } else {
                throw ServiceException.bad(provider.name + " is not ready: " + provider.status() + ". Ask the admin to set it up.");
            }
        }

        // ---- 5. knowledge base + prompt
        List<Retriever.Hit> hits = useKb ? knowledge.retriever.search(userMsg.content, 3) : List.of();
        boolean external = answering.kind != ProviderKind.DEMO;
        PromptBuilder.Built built = PromptBuilder.build(s.botName, persona, history, hits, s.maxContextTokens,
                external && s.redactPii, store.now());
        built.request.model = answering == provider ? model : answering.model;
        built.request.temperature = temperature;
        built.request.maxTokens = s.maxReplyTokens;
        built.request.timeoutSec = s.timeoutSec;
        if (built.redactions > 0) {
            synchronized (store.lock) {
                if (!userMsg.flags.contains("REDACTED")) userMsg.flags.add("REDACTED");
                if (userMsg.note == null) userMsg.note = "Personal details were hidden before sending to " + answering.name + ".";
                store.saveChats();
            }
        }

        Message reply = new Message();
        reply.id = "m" + Passwords.randomId();
        reply.role = Message.ASSISTANT;
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Retriever.Hit h : hits) {
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("n", h.n());
            src.put("docId", h.docId());
            src.put("title", h.title());
            src.put("snippet", h.text().length() > 240 ? h.text().substring(0, 240) + "…" : h.text());
            sources.add(src);
        }
        reply.sources = sources;

        // ---- 6. stream the answer
        boolean[] clientGone = {false};
        StringBuilder written = new StringBuilder();
        java.util.function.Consumer<String> onDelta = piece -> {
            written.append(piece);
            if (clientGone[0]) return;
            try {
                sink.delta(piece);
            } catch (IOException e) {
                clientGone[0] = true;       // the browser closed the page or pressed Stop
                stopFlag.set(true);
            }
        };
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("userMessage", userMsg.toMap());
        info.put("assistantId", reply.id);
        info.put("providerName", answering.name);
        info.put("model", built.request.model);
        info.put("sources", sources);
        info.put("keptMessages", built.keptMessages);
        info.put("droppedMessages", built.droppedMessages);
        info.put("redactions", built.redactions);
        info.put("notes", notes);
        info.put("chatTitle", chat.title);
        try {
            sink.start(info);
        } catch (IOException e) {
            clientGone[0] = true;
            stopFlag.set(true);
        }

        long t0 = System.currentTimeMillis();
        ChatResult result = null;
        String error = null;
        UsageRecord failed = null;
        try {
            result = models.forProvider(answering).stream(built.request, onDelta, stopFlag::get);
        } catch (AiException e) {
            error = e.getMessage();
            failed = usage(me, chatId, answering, built.request.model, built.estimatedTokens, 0, System.currentTimeMillis() - t0, false, false, error);
            if (written.length() == 0 && s.fallbackToDemo && answering.kind != ProviderKind.DEMO && !stopFlag.get()) {
                Provider demo = demoProvider();
                flags.add("FALLBACK");
                notes.add(answering.name + " failed: " + error + " The built-in demo answered instead.");
                answering = demo;
                PromptBuilder.Built again = PromptBuilder.build(s.botName, persona, history, hits, s.maxContextTokens, false, store.now());
                again.request.model = demo.model;
                try {
                    result = models.demo().stream(again.request, onDelta, stopFlag::get);
                    error = null;
                } catch (RuntimeException ex) {
                    error = "The demo model failed too.";
                }
            }
        } catch (RuntimeException e) {
            error = "Unexpected problem: " + e.getClass().getSimpleName();
            System.err.println("Model error: " + e);
        }
        long ms = System.currentTimeMillis() - t0;

        // ---- 7. save the answer
        reply.content = result != null ? result.text : written.toString();
        reply.at = store.nowText();
        reply.providerId = answering.id;
        reply.providerName = answering.name;
        reply.model = answering.kind == ProviderKind.DEMO ? answering.model : built.request.model;
        reply.ms = ms;
        reply.promptTokens = result != null && result.promptTokens > 0 ? result.promptTokens : built.estimatedTokens;
        reply.outputTokens = result != null && result.outputTokens > 0 ? result.outputTokens : Tokens.estimate(reply.content);
        if (error != null) {
            flags.add("ERROR");
            notes.add(error);
        }
        if (stopFlag.get() && error == null) {
            flags.add("STOPPED");
            notes.add("Stopped before the end.");
        }
        if (result != null && "length".equals(result.finish)) notes.add("The answer was cut short by the reply length limit (" + s.maxReplyTokens + " tokens).");
        if (built.droppedMessages > 0) notes.add(built.droppedMessages + " older message(s) were left out to fit the model's memory (context window).");
        reply.flags = flags;
        reply.note = notes.isEmpty() ? null : String.join(" ", notes);

        synchronized (store.lock) {
            if (failed != null) store.addUsage(failed);
            store.addUsage(usage(me, chatId, answering, reply.model, reply.promptTokens, reply.outputTokens, ms,
                    error == null, flags.contains("FALLBACK"), error));
            Chat still = store.chat(chatId);
            if (still != null) {
                still.messages.add(reply);
                still.updatedAt = store.nowText();
                store.saveChats();
            }
            store.saveUsage();
        }
        Map<String, Object> out = reply.toMap();
        out.put("chatTitle", chat.title);
        return out;
    }

    private Provider demoProvider() {
        synchronized (store.lock) {
            Provider d = store.provider("demo");
            if (d == null) {
                d = new Provider();
                d.id = "demo";
                d.name = "Demo (offline)";
                d.kind = ProviderKind.DEMO;
                d.model = "demo-rules-1";
            }
            return d;
        }
    }

    private UsageRecord usage(User me, String chatId, Provider p, String model, int in, int out, long ms, boolean ok, boolean fallback, String error) {
        UsageRecord u = new UsageRecord();
        u.at = store.nowText();
        u.username = me.username;
        u.chatId = chatId;
        u.providerId = p.id;
        u.model = model;
        u.promptTokens = in;
        u.outputTokens = out;
        u.ms = ms;
        u.ok = ok;
        u.fallback = fallback;
        u.error = error;
        return u;
    }

    /** Tokens this person used today (for the daily limit). */
    public int tokensToday(String username) {
        String day = store.today().toString();
        int sum = 0;
        synchronized (store.lock) {
            for (UsageRecord u : store.usage) {
                if (username.equals(u.username) && u.at != null && u.at.startsWith(day)) sum += u.totalTokens();
            }
        }
        return sum;
    }

    /** First few words of the first message become the chat title. */
    static String autoTitle(String text) {
        String t = text.replaceAll("```[\\s\\S]*?```", " code ").replaceAll("[#*_`>\\[\\]]", "").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return "New chat";
        String[] w = t.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.length && i < 7; i++) {
            if (sb.length() + w[i].length() > 48) break;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w[i]);
        }
        String title = sb.length() == 0 ? t.substring(0, Math.min(48, t.length())) : sb.toString();
        if (title.length() < t.length()) title += "…";
        return Character.toUpperCase(title.charAt(0)) + title.substring(1);
    }

    public boolean isBusy(String chatId) { return active.containsKey(chatId); }
}
