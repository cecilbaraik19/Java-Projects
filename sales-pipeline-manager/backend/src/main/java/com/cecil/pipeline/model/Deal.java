package com.cecil.pipeline.model;

import com.cecil.pipeline.model.Enums.LeadSource;
import com.cecil.pipeline.model.Enums.Priority;
import com.cecil.pipeline.model.Enums.Stage;
import com.cecil.pipeline.util.Json;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A potential sale (also called an opportunity) moving through the pipeline. */
public class Deal {

    public String id;
    public String title;
    public String accountId;
    public String contactId;
    public String owner;                 // username of the sales rep
    public long value;                   // deal size in rupees
    public Stage stage = Stage.LEAD;
    /** Chance of winning in %. null means "use the stage default". */
    public Integer probability;
    public LocalDate expectedClose;
    public LeadSource source = LeadSource.OTHER;
    public Priority priority = Priority.MEDIUM;
    public String description;
    public String lostReason;
    public String createdAt;
    public String updatedAt;
    public String closedAt;
    public String lastActivityAt;        // last time anyone did something on this deal
    public List<StageChange> history = new ArrayList<>();
    public List<Note> notes = new ArrayList<>();

    /** One move from a stage to another. */
    public static class StageChange {
        public Stage from;
        public Stage to;
        public String at;
        public String by;

        public Map<String, Object> toMap() { return Json.obj("from", from, "to", to, "at", at, "by", by); }

        static StageChange fromMap(Map<String, Object> m) {
            StageChange c = new StageChange();
            c.from = Enums.parse(Stage.class, Json.str(m, "from"), null);
            c.to = Enums.parse(Stage.class, Json.str(m, "to"), Stage.LEAD);
            c.at = Json.str(m, "at");
            c.by = Json.str(m, "by");
            return c;
        }
    }

    /** A free-text note on the deal. */
    public static class Note {
        public String id;
        public String text;
        public String at;
        public String by;

        public Map<String, Object> toMap() { return Json.obj("id", id, "text", text, "at", at, "by", by); }

        static Note fromMap(Map<String, Object> m) {
            Note n = new Note();
            n.id = Json.str(m, "id");
            n.text = Json.str(m, "text", "");
            n.at = Json.str(m, "at");
            n.by = Json.str(m, "by");
            return n;
        }
    }

    // ------------------------------------------------------------------ calculated values

    /** Probability actually used: closed deals are 100 / 0, otherwise the override or the stage default. */
    public int effectiveProbability() {
        if (stage == Stage.WON) return 100;
        if (stage == Stage.LOST) return 0;
        return probability != null ? probability : stage.probability;
    }

    /** Value x probability, the "expected" amount used in forecasts. */
    public long weightedValue() {
        return Math.round(value * effectiveProbability() / 100.0);
    }

    public boolean isOpen() { return stage.isOpen(); }

    /** When the deal entered its current stage. */
    public Instant stageEnteredAt() {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).to == stage && history.get(i).at != null) return Instant.parse(history.get(i).at);
        }
        return createdAt == null ? Instant.now() : Instant.parse(createdAt);
    }

    /** True if this deal ever reached the given stage (used for the funnel). */
    public boolean reached(Stage s) {
        if (s == Stage.WON || s == Stage.LOST) return stage == s;
        // highest open stage this deal ever got to; a won deal passed through all of them
        int max = Stage.LEAD.ordinal();
        for (StageChange c : history) if (c.to.isOpen()) max = Math.max(max, c.to.ordinal());
        if (stage.isOpen()) max = Math.max(max, stage.ordinal());
        if (stage == Stage.WON) max = Stage.NEGOTIATION.ordinal();
        return s.ordinal() <= max;
    }

    // ------------------------------------------------------------------ conversion

    public Map<String, Object> toMap() {
        List<Object> h = new ArrayList<>();
        for (StageChange c : history) h.add(c.toMap());
        List<Object> n = new ArrayList<>();
        for (Note x : notes) n.add(x.toMap());
        return Json.obj(
            "id", id,
            "title", title,
            "accountId", accountId,
            "contactId", contactId,
            "owner", owner,
            "value", value,
            "stage", stage,
            "probability", probability,
            "expectedClose", expectedClose == null ? null : expectedClose.toString(),
            "source", source,
            "priority", priority,
            "description", description,
            "lostReason", lostReason,
            "createdAt", createdAt,
            "updatedAt", updatedAt,
            "closedAt", closedAt,
            "lastActivityAt", lastActivityAt,
            "history", h,
            "notes", n);
    }

    public static Deal fromMap(Map<String, Object> m) {
        Deal d = new Deal();
        d.id = Json.str(m, "id");
        d.title = Json.str(m, "title", "");
        d.accountId = Json.str(m, "accountId");
        d.contactId = Json.str(m, "contactId");
        d.owner = Json.str(m, "owner");
        d.value = Json.lng(m, "value", 0);
        d.stage = Enums.parse(Stage.class, Json.str(m, "stage"), Stage.LEAD);
        d.probability = Json.intOrNull(m, "probability");
        String ec = Json.str(m, "expectedClose");
        d.expectedClose = ec == null || ec.isBlank() ? null : LocalDate.parse(ec);
        d.source = Enums.parse(LeadSource.class, Json.str(m, "source"), LeadSource.OTHER);
        d.priority = Enums.parse(Priority.class, Json.str(m, "priority"), Priority.MEDIUM);
        d.description = Json.str(m, "description", "");
        d.lostReason = Json.str(m, "lostReason");
        d.createdAt = Json.str(m, "createdAt");
        d.updatedAt = Json.str(m, "updatedAt");
        d.closedAt = Json.str(m, "closedAt");
        d.lastActivityAt = Json.str(m, "lastActivityAt");
        for (Map<String, Object> x : Json.objects(m, "history")) d.history.add(StageChange.fromMap(x));
        for (Map<String, Object> x : Json.objects(m, "notes")) d.notes.add(Note.fromMap(x));
        return d;
    }
}
