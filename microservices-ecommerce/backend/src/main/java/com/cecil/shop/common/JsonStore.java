package com.cecil.shop.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The private "database" of ONE service: a folder of JSON files, one file per collection.
 * Example: data/order-service/orders.json
 *
 * Database-per-service rule: no service ever opens another service's folder.
 * If the order service needs a product's price it must ASK the catalog service over HTTP.
 *
 * Documents are plain maps with a numeric "id". Every change is written to disk straight away
 * (write to a temp file, then rename, so a crash never leaves half a file).
 */
public class JsonStore {
    private final Path dir;
    private final Map<String, List<Map<String, Object>>> collections = new LinkedHashMap<>();
    private final Map<String, Object> meta; // id counters
    private final boolean persist;

    public JsonStore(Path dir) {
        this.dir = dir;
        this.persist = dir != null;
        Map<String, Object> m = new LinkedHashMap<>();
        if (persist) {
            try {
                Files.createDirectories(dir);
                Path f = dir.resolve("_meta.json");
                if (Files.exists(f)) m = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot open data folder " + dir + ": " + e.getMessage(), e);
            }
        }
        this.meta = m;
    }

    /** An in-memory store (used by tests). */
    public static JsonStore memory() { return new JsonStore(null); }

    public synchronized boolean isEmpty(String coll) { return list(coll).isEmpty(); }

    private List<Map<String, Object>> list(String coll) {
        return collections.computeIfAbsent(coll, c -> {
            List<Map<String, Object>> l = new ArrayList<>();
            if (persist) {
                Path f = dir.resolve(c + ".json");
                if (Files.exists(f)) {
                    try {
                        for (Object o : Json.list(Json.parse(Files.readString(f, StandardCharsets.UTF_8)))) l.add(Json.map(o));
                    } catch (IOException | IllegalArgumentException e) {
                        throw new IllegalStateException("Cannot read " + f + ": " + e.getMessage(), e);
                    }
                }
            }
            return l;
        });
    }

    /** Copies of all documents in a collection. */
    public synchronized List<Map<String, Object>> all(String coll) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : list(coll)) out.add(Json.copy(d));
        return out;
    }

    public synchronized List<Map<String, Object>> where(String coll, Predicate<Map<String, Object>> test) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : list(coll)) if (test.test(d)) out.add(Json.copy(d));
        return out;
    }

    public synchronized Map<String, Object> get(String coll, long id) {
        for (Map<String, Object> d : list(coll)) if (Json.lng(d, "id") == id) return Json.copy(d);
        return null;
    }

    public synchronized Map<String, Object> findFirst(String coll, Predicate<Map<String, Object>> test) {
        for (Map<String, Object> d : list(coll)) if (test.test(d)) return Json.copy(d);
        return null;
    }

    public synchronized long nextId(String coll) {
        long next = Json.lng(meta, coll, 0) + 1;
        meta.put(coll, next);
        saveMeta();
        return next;
    }

    /** Adds a document. Gives it the next id when it has none. Returns the stored copy. */
    public synchronized Map<String, Object> insert(String coll, Map<String, Object> doc) {
        Map<String, Object> d = Json.copy(doc);
        if (Json.lng(d, "id") <= 0) {
            Map<String, Object> withId = new LinkedHashMap<>();
            withId.put("id", nextId(coll));
            withId.putAll(d);
            d = withId;
        } else if (Json.lng(d, "id") > Json.lng(meta, coll, 0)) {
            meta.put(coll, Json.lng(d, "id"));
            saveMeta();
        }
        list(coll).add(d);
        save(coll);
        return Json.copy(d);
    }

    /** Replaces the document with the same id. */
    public synchronized Map<String, Object> update(String coll, Map<String, Object> doc) {
        List<Map<String, Object>> l = list(coll);
        long id = Json.lng(doc, "id");
        for (int i = 0; i < l.size(); i++) {
            if (Json.lng(l.get(i), "id") == id) {
                l.set(i, Json.copy(doc));
                save(coll);
                return Json.copy(doc);
            }
        }
        throw ApiError.notFound("Record " + id + " not found");
    }

    public synchronized boolean delete(String coll, long id) {
        boolean removed = list(coll).removeIf(d -> Json.lng(d, "id") == id);
        if (removed) save(coll);
        return removed;
    }

    public synchronized int deleteWhere(String coll, Predicate<Map<String, Object>> test) {
        List<Map<String, Object>> l = list(coll);
        int before = l.size();
        l.removeIf(test);
        if (l.size() != before) save(coll);
        return before - l.size();
    }

    /** Version of the demo data this folder was filled with (0 = none / very old). */
    public synchronized long dataVersion() { return Json.lng(meta, "_dataVersion", 0); }

    /**
     * Empties this service's whole database and marks it with a new data version.
     * Used when the demo data changes between versions of the project (old files would not match).
     */
    public synchronized void resetTo(long version) {
        if (persist) {
            try (var files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (f.getFileName().toString().endsWith(".json")) Files.deleteIfExists(f);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot reset " + dir + ": " + e.getMessage(), e);
            }
        }
        collections.clear();
        meta.clear();
        meta.put("_dataVersion", version);
        saveMeta();
    }

    private void save(String coll) {
        if (!persist) return;
        writeAtomic(dir.resolve(coll + ".json"), Json.write(list(coll)));
    }

    private void saveMeta() {
        if (!persist) return;
        writeAtomic(dir.resolve("_meta.json"), Json.write(meta));
    }

    private static void writeAtomic(Path f, String text) {
        try {
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save " + f + ": " + e.getMessage(), e);
        }
    }
}
