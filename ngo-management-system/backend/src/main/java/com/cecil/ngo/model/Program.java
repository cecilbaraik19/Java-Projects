package com.cecil.ngo.model;

import com.cecil.ngo.model.Enums.Cause;
import com.cecil.ngo.model.Enums.ProgramStatus;
import com.cecil.ngo.util.Json;

import java.util.Map;

/** A long-running piece of NGO work, like "Evening learning centres" or "Village health camps". */
public class Program implements Json.JsonAble {
    public String id;
    public String name;
    public Cause cause;
    public String location;
    public String description;
    public String startDate;
    public String endDate;
    /** Money set aside for this program, in whole rupees. */
    public long budget;
    /** How many people the program hopes to reach. */
    public int targetBeneficiaries;
    public ProgramStatus status = ProgramStatus.PLANNED;
    /** Username of the program manager in charge. */
    public String lead;
    public String createdAt;

    @Override
    public Object toJson() {
        return Json.map("id", id, "name", name, "cause", cause, "location", location, "description", description,
                "startDate", startDate, "endDate", endDate, "budget", budget, "targetBeneficiaries", targetBeneficiaries,
                "status", status, "lead", lead, "createdAt", createdAt);
    }

    public static Program fromJson(Map<String, Object> m) {
        Program p = new Program();
        p.id = Json.str(m, "id");
        p.name = Json.str(m, "name");
        p.cause = Cause.valueOf(Json.str(m, "cause", "EDUCATION"));
        p.location = Json.str(m, "location", "");
        p.description = Json.str(m, "description", "");
        p.startDate = Json.str(m, "startDate");
        p.endDate = Json.str(m, "endDate");
        Long b = Json.longOrNull(m, "budget");
        p.budget = b == null ? 0 : b;
        p.targetBeneficiaries = Json.integer(m, "targetBeneficiaries", 0);
        p.status = ProgramStatus.valueOf(Json.str(m, "status", "PLANNED"));
        p.lead = Json.str(m, "lead");
        p.createdAt = Json.str(m, "createdAt");
        return p;
    }
}
