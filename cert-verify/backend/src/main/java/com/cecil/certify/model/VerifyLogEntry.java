package com.cecil.certify.model;

import com.cecil.certify.util.Json;

import java.util.Map;

/** One verification attempt (kept so institutions and holders can see when their certificates were checked). */
public final class VerifyLogEntry implements Json.JsonWritable {
    public long time;
    public String certId;      // as typed (cleaned), may be unknown
    public String issuerId = "";
    public String method;      // LINK, FILE, TYPED
    public String verdict;     // GENUINE, REVOKED, TAMPERED, NOT_FOUND, UNTRUSTED_ISSUER, NETWORK_UNSAFE, BAD_INPUT
    public String changedFields = "";

    @Override
    public Object toJson() {
        return Json.map("time", time, "certId", certId, "issuerId", issuerId, "method", method, "verdict", verdict,
                "changedFields", changedFields);
    }

    public static VerifyLogEntry fromJson(Map<String, Object> m) {
        VerifyLogEntry e = new VerifyLogEntry();
        e.time = Json.num(m, "time", 0);
        e.certId = Json.str(m, "certId", "");
        e.issuerId = Json.str(m, "issuerId", "");
        e.method = Json.str(m, "method", "");
        e.verdict = Json.str(m, "verdict", "");
        e.changedFields = Json.str(m, "changedFields", "");
        return e;
    }
}
