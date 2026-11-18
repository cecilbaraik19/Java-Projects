package com.cecil.swikriti.model;

import com.cecil.swikriti.util.Json;

import java.util.Map;

/** A stage as copied into one document for one revision, with what happened to it. */
public class RouteStage implements Json.Writable {
    public enum State { WAITING, ACTIVE, DONE, SKIPPED, FAILED, STOPPED;
        static State of(String s) { try { return valueOf(s); } catch (Exception e) { return WAITING; } }
    }

    public Stage stage;
    public State state = State.WAITING;
    public String why;          // for SKIPPED: e.g. "condition not met: amount > 50000"
    public long startedAt;
    public long doneAt;

    @Override public Map<String, Object> toJson() {
        Map<String, Object> m = stage.toJson();
        m.put("state", state);
        m.put("why", why);
        m.put("startedAt", startedAt);
        m.put("doneAt", doneAt);
        return m;
    }

    public static RouteStage fromJson(Map<String, Object> m) {
        RouteStage r = new RouteStage();
        r.stage = Stage.fromJson(m);
        r.state = State.of(Json.str(m, "state"));
        r.why = Json.str(m, "why");
        r.startedAt = Json.num(m, "startedAt", 0);
        r.doneAt = Json.num(m, "doneAt", 0);
        return r;
    }
}
