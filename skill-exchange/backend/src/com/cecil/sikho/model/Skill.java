package com.cecil.sikho.model;

import com.cecil.sikho.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One skill in the community catalogue, for example "Guitar" in the category "Music". */
public class Skill implements Json.Writable {
    public String id;
    public String name;
    public String category;
    /** Other words people use for it (helps search), for example "gitar", "acoustic". */
    public List<String> tags = new ArrayList<>();

    public Skill() { }

    public Skill(String id, String name, String category, List<String> tags) {
        this.id = id; this.name = name; this.category = category; this.tags = new ArrayList<>(tags);
    }

    @Override
    public Map<String, Object> toJson() { return Json.obj("id", id, "name", name, "category", category, "tags", tags); }

    public static Skill fromJson(Map<String, Object> m) {
        return new Skill(Json.str(m, "id"), Json.str(m, "name"), Json.str(m, "category"), Json.strings(m, "tags"));
    }
}
