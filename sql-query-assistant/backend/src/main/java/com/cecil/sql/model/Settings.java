package com.cecil.sql.model;

import com.cecil.sql.util.Json;

import java.util.Map;

/** App-wide settings an admin can change. */
public final class Settings implements Json.JsonWritable {
    /** OFF = offline engine only; WHEN_NOT_SURE = ask Claude only when the offline engine is not sure; ALWAYS = Claude first. */
    public enum ClaudeMode { OFF, WHEN_NOT_SURE, ALWAYS }

    public String organisation = "Koel Valley Institute of Technology";
    public ClaudeMode claudeMode = ClaudeMode.OFF;
    public boolean viewersSeeSql = true;

    @Override public Object toJson() {
        return Json.map("organisation", organisation, "claudeMode", claudeMode, "viewersSeeSql", viewersSeeSql);
    }

    public static Settings fromJson(Map<String, Object> m) {
        Settings s = new Settings();
        s.organisation = Json.str(m, "organisation", s.organisation);
        try { s.claudeMode = ClaudeMode.valueOf(Json.str(m, "claudeMode", "OFF")); } catch (IllegalArgumentException ignored) { }
        s.viewersSeeSql = Json.bool(m, "viewersSeeSql", true);
        return s;
    }
}
