package com.cecil.sikho.model;

import com.cecil.sikho.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One learning session between two neighbours: the teacher gives some hours, the learner pays the same number
 * of time credits. The credits wait in escrow from the request until the session is confirmed.
 *
 * The learner can ask a teacher ("teach me"), or a teacher can offer to a learner who posted a want card.
 * The credits are held when the learner commits: at once for a learner's request, at acceptance for a teacher's offer.
 *
 *   REQUESTED ──accept──► ACCEPTED ──both say "done"──► COMPLETED   (credits go to the teacher)
 *       │ decline / cancel / time passes      │ cancel                ▲
 *       ▼                                     ▼                       │ moderator
 *   DECLINED / CANCELLED / EXPIRED      CANCELLED            DISPUTED ┘ (or refund)
 *   (credits go back to the learner)
 */
public class Exchange implements Json.Writable {
    public enum Status { REQUESTED, ACCEPTED, COMPLETED, DECLINED, CANCELLED, EXPIRED, DISPUTED, NOT_CONFIRMED }

    public String id;
    public String listingId;
    public String skillId;
    public String teacherId;
    public String learnerId;
    /** Who sent the request: the learner ("teach me") or the teacher ("I can teach you"). The other one accepts. */
    public String initiatorId;
    public int hours;
    /** When the session starts (epoch millis). */
    public long at;
    public String place;
    public Status status = Status.REQUESTED;
    public long createdAt;
    public long updatedAt;
    public long acceptedAt;
    public long closedAt;
    public boolean teacherDone;
    public boolean learnerDone;
    /** When the first person said "done" (for the 3-day automatic completion). */
    public long firstDoneAt;
    public String cancelledBy;
    public String reason;
    /** Cancelled by this person less than 24 hours before the session. */
    public boolean lateCancel;
    public String disputeBy;
    /** Moderator's decision on a dispute: TEACHER (paid), LEARNER (refunded) or SPLIT. */
    public String disputeResult;
    /** The person a dispute was decided against (counts as a no-show for reliability). */
    public String faultOf;
    public boolean learnerReviewed;
    public boolean teacherReviewed;
    public List<Message> messages = new ArrayList<>();

    public boolean isOpen() {
        return status == Status.REQUESTED || status == Status.ACCEPTED || status == Status.DISPUTED;
    }

    public boolean involves(String userId) { return userId.equals(teacherId) || userId.equals(learnerId); }

    public String partnerOf(String userId) { return userId.equals(teacherId) ? learnerId : teacherId; }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "listingId", listingId, "skillId", skillId, "teacherId", teacherId, "learnerId", learnerId,
                "initiatorId", initiatorId, "hours", hours, "at", at, "place", place, "status", status, "createdAt", createdAt, "updatedAt", updatedAt,
                "acceptedAt", acceptedAt, "closedAt", closedAt, "teacherDone", teacherDone, "learnerDone", learnerDone,
                "firstDoneAt", firstDoneAt, "cancelledBy", cancelledBy, "reason", reason, "lateCancel", lateCancel,
                "disputeBy", disputeBy, "disputeResult", disputeResult, "faultOf", faultOf,
                "learnerReviewed", learnerReviewed, "teacherReviewed", teacherReviewed, "messages", messages);
    }

    public static Exchange fromJson(Map<String, Object> m) {
        Exchange e = new Exchange();
        e.id = Json.str(m, "id");
        e.listingId = Json.str(m, "listingId");
        e.skillId = Json.str(m, "skillId");
        e.teacherId = Json.str(m, "teacherId");
        e.learnerId = Json.str(m, "learnerId");
        e.initiatorId = Json.str(m, "initiatorId");
        e.hours = (int) Json.num(m, "hours", 1);
        e.at = Json.num(m, "at", 0);
        e.place = Json.str(m, "place");
        e.status = Listing.parse(Status.class, Json.str(m, "status"), Status.REQUESTED);
        e.createdAt = Json.num(m, "createdAt", 0);
        e.updatedAt = Json.num(m, "updatedAt", 0);
        e.acceptedAt = Json.num(m, "acceptedAt", 0);
        e.closedAt = Json.num(m, "closedAt", 0);
        e.teacherDone = Json.bool(m, "teacherDone", false);
        e.learnerDone = Json.bool(m, "learnerDone", false);
        e.firstDoneAt = Json.num(m, "firstDoneAt", 0);
        e.cancelledBy = Json.str(m, "cancelledBy");
        e.reason = Json.str(m, "reason");
        e.lateCancel = Json.bool(m, "lateCancel", false);
        e.disputeBy = Json.str(m, "disputeBy");
        e.disputeResult = Json.str(m, "disputeResult");
        e.faultOf = Json.str(m, "faultOf");
        e.learnerReviewed = Json.bool(m, "learnerReviewed", false);
        e.teacherReviewed = Json.bool(m, "teacherReviewed", false);
        for (Object o : Json.list(m, "messages")) if (o instanceof Map<?, ?> mm) e.messages.add(Message.fromJson(cast(mm)));
        return e;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }
}
