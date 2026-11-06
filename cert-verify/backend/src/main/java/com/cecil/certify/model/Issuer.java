package com.cecil.certify.model;

import com.cecil.certify.util.Json;

import java.util.Map;

/**
 * An institution's own record (kept by the app, off the chain). The PUBLIC key is also on the chain;
 * the PRIVATE key is kept in data/keys/&lt;code&gt;.key and never leaves the server.
 */
public final class Issuer implements Json.JsonWritable {
    public String id;          // short code, e.g. KVIT
    public String name;
    public String city;
    public String publicKey;
    public long createdAt;

    @Override
    public Object toJson() {
        return Json.map("id", id, "name", name, "city", city, "publicKey", publicKey, "createdAt", createdAt);
    }

    public static Issuer fromJson(Map<String, Object> m) {
        Issuer i = new Issuer();
        i.id = Json.str(m, "id");
        i.name = Json.str(m, "name");
        i.city = Json.str(m, "city", "");
        i.publicKey = Json.str(m, "publicKey");
        i.createdAt = Json.num(m, "createdAt", 0);
        return i;
    }
}
