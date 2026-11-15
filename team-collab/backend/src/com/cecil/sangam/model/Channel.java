package com.cecil.sangam.model;

import com.cecil.sangam.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A chat channel.
 *  PUBLIC  – every member of the workspace (not guests).
 *  PROJECT – the members of one project (guests too, if they are in the project).
 *  PRIVATE – only the people in memberIds.
 *  DIRECT  – a one-to-one chat between exactly two people.
 */
public class Channel implements Json.Writable {
    public enum Kind { PUBLIC, PROJECT, PRIVATE, DIRECT }

    public String id;
    public String name;
    public String topic = "";
    public Kind kind = Kind.PUBLIC;
    public String projectId;
    public List<String> memberIds = new ArrayList<>();
    public String createdBy;
    public long createdAt;

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "name", name, "topic", topic, "kind", kind, "projectId", projectId,
                "memberIds", memberIds, "createdBy", createdBy, "createdAt", createdAt);
    }

    public static Channel fromJson(Map<String, Object> m) {
        Channel c = new Channel();
        c.id = Json.str(m, "id");
        c.name = Json.str(m, "name");
        c.topic = Json.str(m, "topic", "");
        c.kind = Kind.valueOf(Json.str(m, "kind", "PUBLIC"));
        c.projectId = Json.str(m, "projectId");
        c.memberIds = new ArrayList<>(Json.strings(m, "memberIds"));
        c.createdBy = Json.str(m, "createdBy");
        c.createdAt = Json.num(m, "createdAt", 0);
        return c;
    }
}
