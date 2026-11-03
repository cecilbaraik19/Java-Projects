package com.cecil.mail.store;

import com.cecil.mail.model.Email;
import com.cecil.mail.model.SavedReply;
import com.cecil.mail.model.Settings;
import com.cecil.mail.model.User;
import com.cecil.mail.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps all data in memory and saves it as JSON files in the "data" folder:
 *
 *   users.json     people who can sign in
 *   emails.json    every inbox email
 *   replies.json   saved replies
 *   learned.json   extra training examples learned from "useful" feedback
 *   settings.json  admin settings
 *
 * Every method is synchronized, so two browser requests at the same time cannot corrupt the data.
 * Files are written to a .tmp file first and then renamed, so a crash never leaves half a file.
 */
public class DataStore {

    public record Learned(String text, String intent, String addedAt) { }

    private final Path dir;
    private final List<User> users = new ArrayList<>();
    private final List<Email> emails = new ArrayList<>();
    private final List<SavedReply> replies = new ArrayList<>();
    private final List<Learned> learned = new ArrayList<>();
    private Settings settings = new Settings();
    private long nextId = 1;

    public DataStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        load();
    }

    public synchronized boolean isEmpty() { return users.isEmpty(); }

    // ------------------------------------------------------------------ users

    public synchronized Optional<User> user(String username) {
        if (username == null) return Optional.empty();
        return users.stream().filter(u -> u.username.equalsIgnoreCase(username.trim())).findFirst();
    }

    public synchronized List<User> users() { return new ArrayList<>(users); }

    public synchronized void saveUser(User u) {
        users.removeIf(x -> x.username.equals(u.username));
        users.add(u);
        write("users.json", users);
    }

    // ------------------------------------------------------------------ emails

    public synchronized List<Email> emailsOf(String owner) {
        List<Email> out = new ArrayList<>();
        for (Email e : emails) if (e.owner.equals(owner)) out.add(e);
        out.sort((a, b) -> b.receivedAt.compareTo(a.receivedAt));
        return out;
    }

    public synchronized List<Email> allEmails() { return new ArrayList<>(emails); }

    /** Returns the email only if it belongs to this owner (stops people opening other inboxes). */
    public synchronized Optional<Email> email(long id, String owner) {
        return emails.stream().filter(e -> e.id == id && e.owner.equals(owner)).findFirst();
    }

    public synchronized Email addEmail(Email e) {
        e.id = nextId++;
        emails.add(e);
        write("emails.json", emails);
        return e;
    }

    public synchronized void updateEmail(Email e) { write("emails.json", emails); }

    public synchronized boolean deleteEmail(long id, String owner) {
        boolean removed = emails.removeIf(e -> e.id == id && e.owner.equals(owner));
        if (removed) write("emails.json", emails);
        return removed;
    }

    // ------------------------------------------------------------------ replies

    public synchronized List<SavedReply> repliesOf(String owner) {
        List<SavedReply> out = new ArrayList<>();
        for (SavedReply r : replies) if (r.owner.equals(owner)) out.add(r);
        out.sort((a, b) -> b.savedAt.compareTo(a.savedAt));
        return out;
    }

    public synchronized List<SavedReply> allReplies() { return new ArrayList<>(replies); }

    public synchronized Optional<SavedReply> reply(long id, String owner) {
        return replies.stream().filter(r -> r.id == id && r.owner.equals(owner)).findFirst();
    }

    public synchronized SavedReply addReply(SavedReply r) {
        r.id = nextId++;
        replies.add(r);
        write("replies.json", replies);
        return r;
    }

    public synchronized void updateReply(SavedReply r) { write("replies.json", replies); }

    public synchronized boolean deleteReply(long id, String owner) {
        boolean removed = replies.removeIf(r -> r.id == id && r.owner.equals(owner));
        if (removed) write("replies.json", replies);
        return removed;
    }

    // ------------------------------------------------------------------ learned examples

    public synchronized List<Learned> learned() { return new ArrayList<>(learned); }

    public synchronized void addLearned(Learned l) {
        learned.add(l);
        write("learned.json", learned);
    }

    // ------------------------------------------------------------------ settings

    public synchronized Settings settings() { return settings; }

    public synchronized void saveSettings(Settings s) {
        settings = s;
        writeObject("settings.json", s.toJson());
    }

    // ------------------------------------------------------------------ files

    private void load() {
        for (Object o : readList("users.json")) users.add(User.fromJson(map(o)));
        for (Object o : readList("emails.json")) emails.add(Email.fromJson(map(o)));
        for (Object o : readList("replies.json")) replies.add(SavedReply.fromJson(map(o)));
        for (Object o : readList("learned.json")) {
            Map<String, Object> m = map(o);
            learned.add(new Learned(Json.str(m, "text"), Json.str(m, "intent"), Json.str(m, "addedAt")));
        }
        Path s = dir.resolve("settings.json");
        if (Files.exists(s)) settings = Settings.fromJson(Json.parseObject(read(s)));
        for (Email e : emails) nextId = Math.max(nextId, e.id + 1);
        for (SavedReply r : replies) nextId = Math.max(nextId, r.id + 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    private List<Object> readList(String name) {
        Path p = dir.resolve(name);
        if (!Files.exists(p)) return List.of();
        return Json.parseList(read(p));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String name, List<?> list) { writeText(name, Json.write(list)); }

    private void writeObject(String name, Map<String, Object> obj) { writeText(name, Json.write(new LinkedHashMap<>(obj))); }

    private void writeText(String name, String text) {
        Path target = dir.resolve(name);
        Path tmp = dir.resolve(name + ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                throw new UncheckedIOException(e2);
            }
        }
    }
}
