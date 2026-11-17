package com.cecil.kaushal.model;

import com.cecil.kaushal.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One skill on one person's profile: what they claim, what was verified, and when they last used it.
 * One claim per person per skill.
 */
public class Claim implements Json.Writable {
    public String id;
    public String userId;
    public String skillId;
    public int claimed;             // 1..5, what the person says
    public int verified;            // 0 = never verified, else 1..5 (kept while a new claim waits)
    public ClaimStatus status = ClaimStatus.CLAIMED;
    public String lastUsed;         // yyyy-mm (month the person last used the skill)
    public String evidence;         // "Built the payment API in Java 21" or a link
    public long claimedAt;
    public long verifiedAt;
    public String verifiedBy;
    public String note;             // the verifier's note (private: person, their managers, HR)
    public List<Map<String, Object>> history = new ArrayList<>();

    public void event(long at, String by, String action, int level, String note) {
        history.add(Json.obj("at", at, "by", by, "action", action, "level", level, "note", note));
        while (history.size() > 30) history.remove(0);
    }

    @Override public Map<String, Object> toJson() {
        return Json.obj("id", id, "userId", userId, "skillId", skillId, "claimed", claimed, "verified", verified,
                "status", status, "lastUsed", lastUsed, "evidence", evidence, "claimedAt", claimedAt, "verifiedAt", verifiedAt,
                "verifiedBy", verifiedBy, "note", note, "history", history);
    }

    @SuppressWarnings("unchecked")
    public static Claim fromJson(Map<String, Object> m) {
        Claim c = new Claim();
        c.id = Json.str(m, "id");
        c.userId = Json.str(m, "userId");
        c.skillId = Json.str(m, "skillId");
        c.claimed = (int) Json.num(m, "claimed", 1);
        c.verified = (int) Json.num(m, "verified", 0);
        c.status = ClaimStatus.valueOf(Json.str(m, "status", "CLAIMED"));
        c.lastUsed = Json.str(m, "lastUsed");
        c.evidence = Json.str(m, "evidence");
        c.claimedAt = Json.num(m, "claimedAt", 0);
        c.verifiedAt = Json.num(m, "verifiedAt", 0);
        c.verifiedBy = Json.str(m, "verifiedBy");
        c.note = Json.str(m, "note");
        for (Object o : Json.list(m, "history")) if (o instanceof Map) c.history.add((Map<String, Object>) o);
        return c;
    }
}
