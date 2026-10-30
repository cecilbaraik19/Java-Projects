package com.cecil.ngo.model;

import com.cecil.ngo.model.Enums.AgeGroup;
import com.cecil.ngo.model.Enums.Gender;
import com.cecil.ngo.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A person the NGO helps, enrolled in one or more programs. */
public class Beneficiary implements Json.JsonAble {
    public String id;
    public String name;
    public Gender gender;
    public AgeGroup ageGroup;
    public String village;
    public List<String> programIds = new ArrayList<>();
    public String enrolledOn;
    public String notes;

    @Override
    public Object toJson() {
        return Json.map("id", id, "name", name, "gender", gender, "ageGroup", ageGroup, "village", village,
                "programIds", programIds, "enrolledOn", enrolledOn, "notes", notes);
    }

    public static Beneficiary fromJson(Map<String, Object> m) {
        Beneficiary b = new Beneficiary();
        b.id = Json.str(m, "id");
        b.name = Json.str(m, "name");
        b.gender = Gender.valueOf(Json.str(m, "gender", "OTHER"));
        b.ageGroup = AgeGroup.valueOf(Json.str(m, "ageGroup", "ADULT"));
        b.village = Json.str(m, "village", "");
        b.programIds = new ArrayList<>(Json.strings(m, "programIds"));
        b.enrolledOn = Json.str(m, "enrolledOn");
        b.notes = Json.str(m, "notes", "");
        return b;
    }
}
