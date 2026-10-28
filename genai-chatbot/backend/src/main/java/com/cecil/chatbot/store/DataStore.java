package com.cecil.chatbot.store;

import com.cecil.chatbot.model.Chat;
import com.cecil.chatbot.model.KnowledgeDoc;
import com.cecil.chatbot.model.Persona;
import com.cecil.chatbot.model.Provider;
import com.cecil.chatbot.model.Settings;
import com.cecil.chatbot.model.UsageRecord;
import com.cecil.chatbot.model.User;
import com.cecil.chatbot.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps all data in memory and saves it to JSON files in the data folder.
 *
 * Why JSON files and not a database? The project should run with only Java installed.
 * The files are small and easy to open and read.
 *
 * Thread safety: the web server handles requests on several threads, so every service
 * does its reading and changing inside synchronized (store.lock).
 * The lock is NOT held while the AI is writing an answer (that can take many seconds).
 *
 * Safe saving: each file is first written to "name.json.tmp" and then moved over the real file,
 * so a crash in the middle never leaves a half-written file.
 */
public class DataStore {

    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    static final int MAX_USAGE = 20000;

    public final Object lock = new Object();
    private final Path dir;
    private final Clock clock;

    public final List<User> users = new ArrayList<>();
    public final List<Persona> personas = new ArrayList<>();
    public final List<Chat> chats = new ArrayList<>();
    public final List<KnowledgeDoc> knowledge = new ArrayList<>();
    public final List<Provider> providers = new ArrayList<>();
    public final List<UsageRecord> usage = new ArrayList<>();
    public Settings settings = new Settings();

    public DataStore(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    public Clock clock() { return clock; }
    public LocalDate today() { return LocalDate.now(clock); }
    public LocalDateTime now() { return LocalDateTime.now(clock).withNano(0); }
    public String nowText() { return now().format(TIME); }
    public Path dir() { return dir; }

    /** Loads every file. Returns false when there is no data yet (first run). */
    public boolean load() throws IOException {
        Files.createDirectories(dir);
        if (!Files.exists(dir.resolve("users.json"))) return false;
        synchronized (lock) {
            readList("users.json", User::fromMap, users);
            readList("personas.json", Persona::fromMap, personas);
            readList("chats.json", Chat::fromMap, chats);
            readList("knowledge.json", KnowledgeDoc::fromMap, knowledge);
            readList("providers.json", Provider::fromMap, providers);
            readList("usage.json", UsageRecord::fromMap, usage);
            Path s = dir.resolve("settings.json");
            if (Files.exists(s)) settings = Settings.fromMap(Json.parseObject(Files.readString(s, StandardCharsets.UTF_8)));
        }
        return !users.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private <T> void readList(String file, Function<Map<String, Object>, T> maker, List<T> into) throws IOException {
        into.clear();
        Path p = dir.resolve(file);
        if (!Files.exists(p)) return;
        Object data = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
        if (!(data instanceof List)) throw new IOException(file + " is not a JSON list");
        for (Object o : (List<Object>) data) {
            if (o instanceof Map) into.add(maker.apply((Map<String, Object>) o));
        }
    }

    // ------------------------------------------------------------------ saving

    public void saveAll() {
        saveUsers(); savePersonas(); saveChats(); saveKnowledge(); saveProviders(); saveUsage(); saveSettings();
    }

    public void saveUsers() { writeList("users.json", users, User::toMap); }
    public void savePersonas() { writeList("personas.json", personas, Persona::toMap); }
    public void saveChats() { writeList("chats.json", chats, Chat::toMap); }
    public void saveKnowledge() { writeList("knowledge.json", knowledge, KnowledgeDoc::toMap); }
    public void saveProviders() { writeList("providers.json", providers, Provider::toMap); }
    public void saveUsage() { writeList("usage.json", usage, UsageRecord::toMap); }
    public void saveSettings() { writeText("settings.json", Json.pretty(settings.toMap())); }

    private <T> void writeList(String file, List<T> list, Function<T, Map<String, Object>> conv) {
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        synchronized (lock) {
            for (T t : list) out.add(conv.apply(t));
        }
        // usage.json can have thousands of rows, so it is written compact; the rest is easy to read
        writeText(file, file.equals("usage.json") ? Json.write(out) : Json.pretty(out));
    }

    private void writeText(String file, String text) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(file);
            Path tmp = dir.resolve(file + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + file + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ lookups (caller holds the lock)

    public User user(String username) {
        if (username == null) return null;
        for (User u : users) if (u.username.equalsIgnoreCase(username)) return u;
        return null;
    }

    public Persona persona(String id) {
        if (id == null) return null;
        for (Persona p : personas) if (p.id.equals(id)) return p;
        return null;
    }

    public Chat chat(String id) {
        if (id == null) return null;
        for (Chat c : chats) if (c.id.equals(id)) return c;
        return null;
    }

    public KnowledgeDoc doc(String id) {
        if (id == null) return null;
        for (KnowledgeDoc d : knowledge) if (d.id.equals(id)) return d;
        return null;
    }

    public Provider provider(String id) {
        if (id == null) return null;
        for (Provider p : providers) if (p.id.equals(id)) return p;
        return null;
    }

    public String displayName(String username) {
        User u = user(username);
        return u == null ? (username == null ? "System" : username) : u.displayName;
    }

    /** Adds a usage line (keeps the newest 20000). Caller holds the lock and saves. */
    public void addUsage(UsageRecord r) {
        usage.add(r);
        while (usage.size() > MAX_USAGE) usage.remove(0);
    }
}
