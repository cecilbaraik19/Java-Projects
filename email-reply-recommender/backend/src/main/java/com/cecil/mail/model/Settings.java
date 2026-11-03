package com.cecil.mail.model;

import com.cecil.mail.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** App-wide settings an admin can change. */
public class Settings implements Json.JsonWritable {

    public String organisation = "Koel Valley Institute of Technology";
    public boolean claudeEnabled = false;      // only works if ANTHROPIC_API_KEY is set
    public boolean learnFromFeedback = true;   // "useful" replies become new training examples

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organisation", organisation);
        m.put("claudeEnabled", claudeEnabled);
        m.put("learnFromFeedback", learnFromFeedback);
        return m;
    }

    public static Settings fromJson(Map<String, Object> m) {
        Settings s = new Settings();
        if (m.get("organisation") != null) s.organisation = Json.str(m, "organisation");
        s.claudeEnabled = Json.bool(m, "claudeEnabled", false);
        s.learnFromFeedback = Json.bool(m, "learnFromFeedback", true);
        return s;
    }
}
