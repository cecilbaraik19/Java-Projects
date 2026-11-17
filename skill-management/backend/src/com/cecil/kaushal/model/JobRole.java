package com.cecil.kaushal.model;

import com.cecil.kaushal.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A job role and the skills it needs, e.g. "Senior Backend Engineer" on the "Backend" ladder, step 2.
 * Roles on the same ladder form a career path: step 1 -> step 2 -> step 3.
 */
public class JobRole implements Json.Writable {
    public String id;
    public String name;
    public String ladder;
    public int step;
    public String description;
    public List<Req> reqs = new ArrayList<>();

    @Override public Map<String, Object> toJson() {
        return Json.obj("id", id, "name", name, "ladder", ladder, "step", step, "description", description, "reqs", reqs);
    }

    public static JobRole fromJson(Map<String, Object> m) {
        JobRole r = new JobRole();
        r.id = Json.str(m, "id");
        r.name = Json.str(m, "name");
        r.ladder = Json.str(m, "ladder");
        r.step = (int) Json.num(m, "step", 1);
        r.description = Json.str(m, "description", "");
        r.reqs = Req.list(Json.list(m, "reqs"));
        return r;
    }
}
