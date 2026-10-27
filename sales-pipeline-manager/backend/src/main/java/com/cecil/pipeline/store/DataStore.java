package com.cecil.pipeline.store;

import com.cecil.pipeline.model.Account;
import com.cecil.pipeline.model.Activity;
import com.cecil.pipeline.model.Contact;
import com.cecil.pipeline.model.Deal;
import com.cecil.pipeline.model.User;
import com.cecil.pipeline.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps all data in memory and saves it as JSON files in the data folder.
 * Every read or change goes through the services, which lock on this object, so it is thread safe.
 * Pass a null folder to keep everything in memory only (used by the tests).
 */
public class DataStore {

    public final List<User> users = new ArrayList<>();
    public final List<Account> accounts = new ArrayList<>();
    public final List<Contact> contacts = new ArrayList<>();
    public final List<Deal> deals = new ArrayList<>();
    public final List<Activity> activities = new ArrayList<>();

    private final Path dir;
    private final Map<String, Integer> counters = new HashMap<>();

    public DataStore(Path dir) {
        this.dir = dir;
    }

    /** Loads the data files. If there are none yet, fills the store with demo data (when seed is true). */
    public synchronized void load(boolean seed) {
        users.clear(); accounts.clear(); contacts.clear(); deals.clear(); activities.clear();
        boolean found = false;
        if (dir != null && Files.exists(dir.resolve("deals.json"))) {
            readInto("users.json", users, User::fromMap);
            readInto("accounts.json", accounts, Account::fromMap);
            readInto("contacts.json", contacts, Contact::fromMap);
            readInto("deals.json", deals, Deal::fromMap);
            readInto("activities.json", activities, Activity::fromMap);
            found = true;
        }
        rebuildCounters();
        if (!found && seed) {
            SeedData.fill(this);
            save();
        }
    }

    // ------------------------------------------------------------------ ids

    /** New unique id like D-1001. Prefixes: U users, A accounts, C contacts, D deals, T activities, N notes. */
    public synchronized String nextId(String prefix) {
        int n = counters.getOrDefault(prefix, startFor(prefix)) + 1;
        counters.put(prefix, n);
        return prefix + "-" + n;
    }

    private static int startFor(String prefix) {
        return switch (prefix) {
            case "D" -> 1000;
            case "A" -> 100;
            case "C" -> 200;
            case "T" -> 5000;
            default -> 0;
        };
    }

    private void rebuildCounters() {
        counters.clear();
        for (Account a : accounts) bump(a.id);
        for (Contact c : contacts) bump(c.id);
        for (Deal d : deals) {
            bump(d.id);
            for (Deal.Note n : d.notes) bump(n.id);
        }
        for (Activity a : activities) bump(a.id);
    }

    private void bump(String id) {
        if (id == null) return;
        int dash = id.indexOf('-');
        if (dash < 1) return;
        try {
            String p = id.substring(0, dash);
            int n = Integer.parseInt(id.substring(dash + 1));
            counters.merge(p, Math.max(n, startFor(p)), Math::max);
        } catch (NumberFormatException ignored) {
            // not one of our ids
        }
    }

    // ------------------------------------------------------------------ look-ups

    public synchronized User user(String username) {
        if (username == null) return null;
        for (User u : users) if (u.username.equalsIgnoreCase(username)) return u;
        return null;
    }

    public synchronized Account account(String id) {
        for (Account a : accounts) if (a.id.equals(id)) return a;
        return null;
    }

    public synchronized Contact contact(String id) {
        for (Contact c : contacts) if (c.id.equals(id)) return c;
        return null;
    }

    public synchronized Deal deal(String id) {
        for (Deal d : deals) if (d.id.equals(id)) return d;
        return null;
    }

    public synchronized Activity activity(String id) {
        for (Activity a : activities) if (a.id.equals(id)) return a;
        return null;
    }

    // ------------------------------------------------------------------ saving

    /** Writes every collection to disk. Each file is written to a temp file first, then moved, so a crash never leaves half a file. */
    public synchronized void save() {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            write("users.json", users.stream().map(User::toStore).toList());
            write("accounts.json", accounts.stream().map(Account::toMap).toList());
            write("contacts.json", contacts.stream().map(Contact::toMap).toList());
            write("deals.json", deals.stream().map(Deal::toMap).toList());
            write("activities.json", activities.stream().map(Activity::toMap).toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save data to " + dir, e);
        }
    }

    private void write(String file, List<?> items) throws IOException {
        Path target = dir.resolve(file);
        Path tmp = dir.resolve(file + ".tmp");
        Files.writeString(tmp, Json.pretty(items), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void readInto(String file, List<T> list, Function<Map<String, Object>, T> fn) {
        Path p = dir.resolve(file);
        if (!Files.exists(p)) return;
        try {
            Object parsed = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
            if (parsed instanceof List<?> l) {
                for (Object o : l) if (o instanceof Map) list.add(fn.apply((Map<String, Object>) o));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + p, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("The data file " + p + " is damaged: " + e.getMessage()
                + ". Fix it or delete the data folder to start again with demo data.", e);
        }
    }

    public Path dir() { return dir; }
}
