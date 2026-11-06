package com.cecil.certify.store;

import com.cecil.certify.model.CertificateRecord;
import com.cecil.certify.model.Issuer;
import com.cecil.certify.model.User;
import com.cecil.certify.model.VerifyLogEntry;
import com.cecil.certify.util.Crypto;
import com.cecil.certify.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves the app's own (off-chain) data as readable JSON files in the data folder:
 *   users.json, issuers.json, certificates.json, verifications.json, keys/&lt;code&gt;.key, keys/server.secret
 * The chain itself is saved by {@link com.cecil.certify.chain.Network} in data/nodes/.
 * With dir == null everything stays in memory (used by the tests).
 */
public final class DataStore {
    private final Path dir;

    public final Map<String, User> users = new LinkedHashMap<>();
    public final Map<String, Issuer> issuers = new LinkedHashMap<>();
    public final Map<String, CertificateRecord> certificates = new LinkedHashMap<>();
    public final List<VerifyLogEntry> verifications = new ArrayList<>();
    private final Map<String, String> privateKeys = new LinkedHashMap<>();
    private String serverSecret;

    public static final int MAX_LOG = 3000;

    public DataStore(Path dir) {
        this.dir = dir;
    }

    public boolean isNew() {
        return dir == null || !Files.exists(dir.resolve("users.json"));
    }

    @SuppressWarnings("unchecked")
    public synchronized void load() throws IOException {
        if (dir == null) return;
        Files.createDirectories(dir.resolve("keys"));
        for (Object o : readList("users.json")) { User u = User.fromJson((Map<String, Object>) o); users.put(u.username, u); }
        for (Object o : readList("issuers.json")) { Issuer i = Issuer.fromJson((Map<String, Object>) o); issuers.put(i.id, i); }
        for (Object o : readList("certificates.json")) {
            CertificateRecord c = CertificateRecord.fromJson((Map<String, Object>) o);
            certificates.put(c.certId, c);
        }
        for (Object o : readList("verifications.json")) verifications.add(VerifyLogEntry.fromJson((Map<String, Object>) o));
        for (Issuer i : issuers.values()) {
            Path k = dir.resolve("keys/" + i.id + ".key");
            if (Files.exists(k)) privateKeys.put(i.id, Files.readString(k).trim());
        }
        Path s = dir.resolve("keys/server.secret");
        if (Files.exists(s)) serverSecret = Files.readString(s).trim();
    }

    private List<Object> readList(String name) throws IOException {
        Path f = dir.resolve(name);
        if (!Files.exists(f)) return new ArrayList<>();
        Object v = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
        if (v instanceof List<?> l) return new ArrayList<>(l);
        return new ArrayList<>();
    }

    // ------------------------------------------------------------------ saving

    public synchronized void saveUsers() { writeList("users.json", users.values()); }
    public synchronized void saveIssuers() { writeList("issuers.json", issuers.values()); }
    public synchronized void saveCertificates() { writeList("certificates.json", certificates.values()); }

    public synchronized void addVerification(VerifyLogEntry e) {
        verifications.add(e);
        while (verifications.size() > MAX_LOG) verifications.remove(0);
        writeList("verifications.json", verifications);
    }

    private void writeList(String name, Iterable<? extends Json.JsonWritable> items) {
        if (dir == null) return;
        List<Object> out = new ArrayList<>();
        for (Json.JsonWritable w : items) out.add(w.toJson());
        write(dir.resolve(name), Json.pretty(out));
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ keys

    public synchronized void putPrivateKey(String issuerId, PrivateKey key) {
        String enc = Crypto.encodePrivate(key);
        privateKeys.put(issuerId, enc);
        if (dir != null) write(dir.resolve("keys/" + issuerId + ".key"), enc);
    }

    public synchronized PrivateKey privateKey(String issuerId) {
        String enc = privateKeys.get(issuerId);
        return enc == null ? null : Crypto.decodePrivate(enc);
    }

    /** Secret used to make the permanent QR token of each certificate (HMAC). Created on first use. */
    public synchronized String serverSecret() {
        if (serverSecret == null) {
            serverSecret = Crypto.randomHex(32);
            if (dir != null) write(dir.resolve("keys/server.secret"), serverSecret);
        }
        return serverSecret;
    }
}
