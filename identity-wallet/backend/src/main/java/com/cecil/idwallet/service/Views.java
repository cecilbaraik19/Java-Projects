package com.cecil.idwallet.service;

import com.cecil.idwallet.crypto.EcKeys;
import com.cecil.idwallet.domain.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Converts entities to the JSON shapes the frontend uses (never exposes keys or hashes). */
public final class Views {
    private Views() {}

    public static Map<String, Object> user(UserAccount u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id);
        m.put("email", u.email);
        m.put("fullName", u.fullName);
        m.put("role", u.role);
        m.put("did", u.did);
        m.put("keyFingerprint", EcKeys.fingerprint(u.publicKey));
        m.put("mfaEnabled", u.mfaEnabled);
        m.put("issuerId", u.issuerId);
        m.put("createdAt", u.createdAt);
        m.put("lastLoginAt", u.lastLoginAt);
        m.put("active", u.active);
        return m;
    }

    public static Map<String, Object> issuer(Issuer i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id);
        m.put("name", i.name);
        m.put("category", i.category);
        m.put("website", i.website);
        m.put("did", i.did);
        m.put("publicKey", i.publicKey);
        m.put("keyFingerprint", EcKeys.fingerprint(i.publicKey));
        m.put("trusted", i.trusted);
        m.put("createdAt", i.createdAt);
        return m;
    }

    public static Map<String, Object> audit(AuditEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("actor", e.actor);
        m.put("action", e.action);
        m.put("detail", e.detail);
        m.put("ip", e.ip);
        m.put("at", e.at);
        m.put("hash", e.hash);
        return m;
    }
}
