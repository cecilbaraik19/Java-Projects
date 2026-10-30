package com.cecil.ngo.service;

import com.cecil.ngo.model.Beneficiary;
import com.cecil.ngo.model.Enums;
import com.cecil.ngo.model.Enums.AgeGroup;
import com.cecil.ngo.model.Enums.Gender;
import com.cecil.ngo.model.Program;
import com.cecil.ngo.model.User;
import com.cecil.ngo.store.DataStore;
import com.cecil.ngo.util.Csv;
import com.cecil.ngo.util.Json;
import com.cecil.ngo.util.Text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * People the NGO helps. This is personal data, so only program managers and the admin can see it
 * (finance and volunteers cannot).
 */
public class BeneficiaryService {
    private final DataStore store;

    public BeneficiaryService(DataStore store) { this.store = store; }

    private Map<String, Object> view(Beneficiary b) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) b.toJson();
        List<String> names = new ArrayList<>();
        for (String pid : b.programIds) { Program p = store.program(pid); names.add(p == null ? pid : p.name); }
        m.put("programNames", names);
        m.put("genderLabel", b.gender.label);
        m.put("ageLabel", b.ageGroup.label);
        return m;
    }

    /** Filters: program, village, gender, age, q. Also returns counts for the small charts. */
    public Map<String, Object> list(User me, Map<String, String> q) {
        AuthService.needPrograms(me, "see beneficiaries");
        String program = q.getOrDefault("program", ""), gender = q.getOrDefault("gender", ""), age = q.getOrDefault("age", "");
        String village = q.getOrDefault("village", "").trim().toLowerCase(Locale.ROOT);
        String text = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> byGender = new LinkedHashMap<>(), byAge = new LinkedHashMap<>(), byVillage = new LinkedHashMap<>();
        for (Gender g : Gender.values()) byGender.put(g.name(), 0);
        for (AgeGroup a : AgeGroup.values()) byAge.put(a.name(), 0);
        synchronized (store) {
            List<Beneficiary> list = new ArrayList<>(store.beneficiaries);
            list.sort(Comparator.comparing((Beneficiary b) -> b.enrolledOn).reversed().thenComparing(b -> b.name));
            for (Beneficiary b : list) {
                if (!program.isEmpty() && !b.programIds.contains(program)) continue;
                if (!gender.isEmpty() && !b.gender.name().equals(gender)) continue;
                if (!age.isEmpty() && !b.ageGroup.name().equals(age)) continue;
                if (!village.isEmpty() && !b.village.toLowerCase(Locale.ROOT).contains(village)) continue;
                if (!text.isEmpty() && !(b.name + " " + b.id + " " + b.village).toLowerCase(Locale.ROOT).contains(text)) continue;
                rows.add(view(b));
                byGender.merge(b.gender.name(), 1, Integer::sum);
                byAge.merge(b.ageGroup.name(), 1, Integer::sum);
                byVillage.merge(b.village, 1, Integer::sum);
            }
        }
        return Json.map("rows", rows, "total", rows.size(), "byGender", byGender, "byAge", byAge, "byVillage", byVillage);
    }

    /**
     * Adds or edits a person. If someone with the same name already lives in the same village,
     * the save stops with a warning (409) unless the form says "yes, this is a different person"
     * (allowDuplicate = true). This avoids counting the same person twice.
     */
    public Map<String, Object> save(User me, String id, Map<String, Object> body) {
        AuthService.needPrograms(me, "add or edit beneficiaries");
        synchronized (store) {
            Beneficiary b = id == null ? new Beneficiary() : must(id);
            String name = Text.line(Json.str(body, "name"), "Name", 80, true);
            String village = Text.line(Json.str(body, "village"), "Village / area", 60, true);
            Gender g = Enums.parse(Gender.class, Json.str(body, "gender"), "Gender");
            AgeGroup a = Enums.parse(AgeGroup.class, Json.str(body, "ageGroup"), "Age group");
            List<String> progs = new ArrayList<>();
            for (String pid : Json.strings(body, "programIds")) {
                if (store.program(pid) == null) throw ApiError.bad("Program " + pid + " not found");
                if (!progs.contains(pid)) progs.add(pid);
            }
            if (progs.isEmpty()) throw ApiError.bad("Enrol the person in at least one program");
            LocalDate on = Text.date(Json.str(body, "enrolledOn"), "Enrolled on", false);
            if (on == null) on = store.today();
            if (on.isAfter(store.today())) throw ApiError.bad("Enrolled on cannot be in the future");
            if (!Json.bool(body, "allowDuplicate", false)) {
                for (Beneficiary o : store.beneficiaries)
                    if (o != b && Text.key(o.name).equals(Text.key(name)) && Text.key(o.village).equals(Text.key(village)))
                        throw ApiError.conflict(o.name + " from " + o.village + " is already registered (" + o.id + "). If this is a different person, tick \"Different person\" and save again.");
            }
            b.name = name; b.village = village; b.gender = g; b.ageGroup = a; b.programIds = progs;
            b.enrolledOn = on.toString();
            b.notes = Text.clean(Json.str(body, "notes"), "Notes", 500, false);
            if (id == null) {
                b.id = store.newId("B", 4);
                store.beneficiaries.add(b);
            }
            store.saveBeneficiaries();
            store.log(me.username, id == null ? "enrolled" : "edited beneficiary", b.name + " (" + b.village + ")", "beneficiary:" + b.id);
            return view(b);
        }
    }

    public void delete(User me, String id) {
        AuthService.needPrograms(me, "remove beneficiaries");
        synchronized (store) {
            Beneficiary b = must(id);
            store.beneficiaries.remove(b);
            store.saveBeneficiaries();
            store.log(me.username, "removed beneficiary", b.id, null);
        }
    }

    Beneficiary must(String id) {
        Beneficiary b = store.beneficiary(id);
        if (b == null) throw ApiError.notFound("Beneficiary not found");
        return b;
    }

    @SuppressWarnings("unchecked")
    public String exportCsv(User me, Map<String, String> q) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(Csv.row("ID", "Name", "Gender", "Age group", "Village", "Programs", "Enrolled on", "Notes"));
        for (Object o : (List<Object>) list(me, q).get("rows")) {
            Map<String, Object> m = (Map<String, Object>) o;
            sb.append(Csv.row(m.get("id"), m.get("name"), m.get("genderLabel"), m.get("ageLabel"), m.get("village"),
                    String.join("; ", (List<String>) m.get("programNames")), m.get("enrolledOn"), m.get("notes")));
        }
        return sb.toString();
    }
}
