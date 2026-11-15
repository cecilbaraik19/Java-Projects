package com.cecil.sangam.model;

import com.cecil.sangam.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A project: a task board, a chat channel, documents and stand-ups for a group of people. */
public class Project implements Json.Writable {
    public String id;
    /** Short code used in task numbers, e.g. "ORB" gives ORB-1, ORB-2 ... */
    public String key;
    public String name;
    public String description = "";
    /** One of the stamp colours used by the website. */
    public String color = "red";
    public List<String> memberIds = new ArrayList<>();
    /** Most tasks allowed in "Doing" and in "Review" at the same time (0 = no limit). */
    public int wipDoing = 6;
    public int wipReview = 3;
    /** When true, a task must go through Review, and someone other than the assignee must finish it. */
    public boolean reviewRequired = true;
    public String channelId;
    public long createdAt;
    public int nextNumber = 1;
    public boolean archived;

    public boolean has(String userId) { return memberIds.contains(userId); }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "key", key, "name", name, "description", description, "color", color,
                "memberIds", memberIds, "wipDoing", wipDoing, "wipReview", wipReview, "reviewRequired", reviewRequired,
                "channelId", channelId, "createdAt", createdAt, "nextNumber", nextNumber, "archived", archived);
    }

    public static Project fromJson(Map<String, Object> m) {
        Project p = new Project();
        p.id = Json.str(m, "id");
        p.key = Json.str(m, "key");
        p.name = Json.str(m, "name");
        p.description = Json.str(m, "description", "");
        p.color = Json.str(m, "color", "red");
        p.memberIds = new ArrayList<>(Json.strings(m, "memberIds"));
        p.wipDoing = (int) Json.num(m, "wipDoing", 6);
        p.wipReview = (int) Json.num(m, "wipReview", 3);
        p.reviewRequired = Json.bool(m, "reviewRequired", true);
        p.channelId = Json.str(m, "channelId");
        p.createdAt = Json.num(m, "createdAt", 0);
        p.nextNumber = (int) Json.num(m, "nextNumber", 1);
        p.archived = Json.bool(m, "archived", false);
        return p;
    }
}
