package com.cecil.certify.model;

import com.cecil.certify.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full certificate as the institution keeps it (OFF the chain): the readable fields and their secret salts.
 * Only the salted fingerprints of these fields are written on the chain.
 */
public final class CertificateRecord implements Json.JsonWritable {
    public String certId;
    public String issuerId;
    public Map<String, String> fields = new LinkedHashMap<>();
    public Map<String, String> salts = new LinkedHashMap<>();
    /** The student account this certificate belongs to ("" if none yet). */
    public String holderUsername = "";
    public String issuedBy = "";
    public long issuedAt;
    public String txId = "";
    public int block = -1;
    public String batchId = "";
    public boolean revoked;
    public String revokeReason = "";
    public long revokedAt;
    public List<ShareLink> shareLinks = new ArrayList<>();

    @Override
    public Object toJson() {
        List<Object> links = new ArrayList<>();
        for (ShareLink s : shareLinks) links.add(s.toJson());
        return Json.map("certId", certId, "issuerId", issuerId, "fields", fields, "salts", salts,
                "holderUsername", holderUsername, "issuedBy", issuedBy, "issuedAt", issuedAt, "txId", txId,
                "block", block, "batchId", batchId, "revoked", revoked, "revokeReason", revokeReason,
                "revokedAt", revokedAt, "shareLinks", links);
    }

    @SuppressWarnings("unchecked")
    public static CertificateRecord fromJson(Map<String, Object> m) {
        CertificateRecord c = new CertificateRecord();
        c.certId = Json.str(m, "certId");
        c.issuerId = Json.str(m, "issuerId");
        Map<String, Object> f = Json.obj(m, "fields");
        if (f != null) f.forEach((k, v) -> c.fields.put(k, v == null ? "" : String.valueOf(v)));
        Map<String, Object> s = Json.obj(m, "salts");
        if (s != null) s.forEach((k, v) -> c.salts.put(k, v == null ? "" : String.valueOf(v)));
        c.holderUsername = Json.str(m, "holderUsername", "");
        c.issuedBy = Json.str(m, "issuedBy", "");
        c.issuedAt = Json.num(m, "issuedAt", 0);
        c.txId = Json.str(m, "txId", "");
        c.block = (int) Json.num(m, "block", -1);
        c.batchId = Json.str(m, "batchId", "");
        c.revoked = Json.bool(m, "revoked", false);
        c.revokeReason = Json.str(m, "revokeReason", "");
        c.revokedAt = Json.num(m, "revokedAt", 0);
        for (Object o : Json.arr(m, "shareLinks")) c.shareLinks.add(ShareLink.fromJson((Map<String, Object>) o));
        return c;
    }

    /** A share link made by the holder. Only the HASH of the secret token is stored. */
    public static final class ShareLink implements Json.JsonWritable {
        public String id;          // short public id, used to switch the link off
        public String tokenHash;
        public String label = "";
        public long createdAt;
        public long expiresAt;
        public int views;
        public boolean disabled;

        @Override
        public Object toJson() {
            return Json.map("id", id, "tokenHash", tokenHash, "label", label, "createdAt", createdAt,
                    "expiresAt", expiresAt, "views", views, "disabled", disabled);
        }

        public static ShareLink fromJson(Map<String, Object> m) {
            ShareLink s = new ShareLink();
            s.id = Json.str(m, "id");
            s.tokenHash = Json.str(m, "tokenHash");
            s.label = Json.str(m, "label", "");
            s.createdAt = Json.num(m, "createdAt", 0);
            s.expiresAt = Json.num(m, "expiresAt", 0);
            s.views = (int) Json.num(m, "views", 0);
            s.disabled = Json.bool(m, "disabled", false);
            return s;
        }
    }
}
