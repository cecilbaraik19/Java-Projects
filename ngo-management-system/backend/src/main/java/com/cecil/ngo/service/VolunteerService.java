package com.cecil.ngo.service;

import com.cecil.ngo.model.Activity;
import com.cecil.ngo.model.Enums.ActivityStatus;
import com.cecil.ngo.model.Enums.Review;
import com.cecil.ngo.model.Enums.Role;
import com.cecil.ngo.model.HoursLog;
import com.cecil.ngo.model.User;
import com.cecil.ngo.model.Volunteer;
import com.cecil.ngo.store.DataStore;
import com.cecil.ngo.util.Csv;
import com.cecil.ngo.util.Json;
import com.cecil.ngo.util.Text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Volunteers and the hours they give. */
public class VolunteerService {
    public static final Set<String> AVAILABILITY = Set.of("WEEKDAYS", "WEEKENDS", "ANY");
    public static final double MIN_HOURS = 0.5, MAX_HOURS = 12;

    private final DataStore store;

    public VolunteerService(DataStore store) { this.store = store; }

    // ================================================================== volunteers

    private Map<String, Object> view(Volunteer v) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v.toJson();
        m.put("hours", Calc.round1(Calc.volunteerHours(store, v.id, Review.APPROVED, null, null)));
        m.put("pendingHours", Calc.round1(Calc.volunteerHours(store, v.id, Review.PENDING, null, null)));
        int done = 0, upcoming = 0;
        String last = null;
        for (Activity a : store.activities) {
            if (!a.volunteerIds.contains(v.id)) continue;
            if (a.status == ActivityStatus.DONE) { done++; if (last == null || a.date.compareTo(last) > 0) last = a.date; }
            if (a.status == ActivityStatus.PLANNED) upcoming++;
        }
        m.put("activitiesDone", done);
        m.put("upcoming", upcoming);
        m.put("lastActive", last);
        User login = null;
        for (User u : store.users) if (v.id.equals(u.volunteerId)) login = u;
        m.put("login", login == null ? null : login.username);
        return m;
    }

    public List<Map<String, Object>> list(User me, Map<String, String> q) {
        AuthService.needStaff(me, "see the volunteer list");
        String text = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        String skill = q.getOrDefault("skill", "").trim().toLowerCase(Locale.ROOT);
        String state = q.getOrDefault("state", "active");
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) {
            for (Volunteer v : store.volunteers) {
                if ("active".equals(state) && !v.active) continue;
                if ("inactive".equals(state) && v.active) continue;
                if (!text.isEmpty() && !(v.name + " " + v.city + " " + v.id + " " + v.email).toLowerCase(Locale.ROOT).contains(text)) continue;
                if (!skill.isEmpty() && v.skills.stream().noneMatch(s -> s.toLowerCase(Locale.ROOT).contains(skill))) continue;
                out.add(view(v));
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> -((Number) m.get("hours")).doubleValue()).thenComparing(m -> (String) m.get("name")));
        return out;
    }

    public Map<String, Object> get(User me, String id) {
        synchronized (store) {
            if (!AuthService.isStaff(me) && !id.equals(me.volunteerId)) throw ApiError.forbidden("You can only see your own profile");
            Volunteer v = must(id);
            Map<String, Object> m = view(v);
            List<Object> acts = new ArrayList<>();
            store.activities.stream().filter(a -> a.volunteerIds.contains(id))
                    .sorted(Comparator.comparing((Activity a) -> a.date).reversed())
                    .forEach(a -> acts.add(Json.map("id", a.id, "title", a.title, "date", a.date, "status", a.status,
                            "programName", store.program(a.programId) == null ? "" : store.program(a.programId).name)));
            m.put("activities", acts);
            List<Object> hrs = new ArrayList<>();
            store.hours.stream().filter(h -> h.volunteerId.equals(id)).sorted(Comparator.comparing((HoursLog h) -> h.date).reversed())
                    .forEach(h -> hrs.add(hoursView(h)));
            m.put("hoursLog", hrs);
            return m;
        }
    }

    public Map<String, Object> create(User me, Map<String, Object> b) {
        AuthService.needPrograms(me, "add volunteers");
        synchronized (store) {
            Volunteer v = new Volunteer();
            fill(v, b, true);
            v.id = store.newId("V", 3);
            store.volunteers.add(v);
            store.saveVolunteers();
            store.log(me.username, "added volunteer", v.name, "volunteer:" + v.id);
            return view(v);
        }
    }

    /** Staff edit anyone; a volunteer may edit their own phone, email, city, skills and availability. */
    public Map<String, Object> update(User me, String id, Map<String, Object> b) {
        synchronized (store) {
            Volunteer v = must(id);
            boolean self = id.equals(me.volunteerId);
            if (!AuthService.canPrograms(me) && !self) throw ApiError.forbidden("Only a program manager or the admin can edit volunteers");
            if (!AuthService.canPrograms(me)) { b.remove("name"); b.remove("active"); b.remove("joinedOn"); }
            Volunteer copy = Volunteer.fromJson(asMap(v));
            fill(copy, b, false);
            fill(v, b, false);
            if (b.containsKey("active")) v.active = Json.bool(b, "active", true);
            store.saveVolunteers();
            store.log(me.username, "edited volunteer", v.name, "volunteer:" + v.id);
            return view(v);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Volunteer v) { return (Map<String, Object>) v.toJson(); }

    public void delete(User me, String id) {
        AuthService.needPrograms(me, "delete volunteers");
        synchronized (store) {
            Volunteer v = must(id);
            if (store.hours.stream().anyMatch(h -> h.volunteerId.equals(id)) || store.activities.stream().anyMatch(a -> a.volunteerIds.contains(id)))
                throw ApiError.conflict(v.name + " has activity history. Turn the volunteer off instead of deleting.");
            if (store.users.stream().anyMatch(u -> id.equals(u.volunteerId)))
                throw ApiError.conflict(v.name + " has a login. Turn the login off first.");
            store.volunteers.remove(v);
            store.saveVolunteers();
            store.log(me.username, "deleted volunteer", v.name, null);
        }
    }

    private void fill(Volunteer v, Map<String, Object> b, boolean isNew) {
        if (isNew || b.containsKey("name")) v.name = Text.line(Json.str(b, "name"), "Name", 80, true);
        if (isNew || b.containsKey("phone")) v.phone = Text.phone(Json.str(b, "phone"));
        if (isNew || b.containsKey("email")) v.email = Text.email(Json.str(b, "email"));
        if (v.phone.isEmpty() && v.email.isEmpty()) throw ApiError.bad("Give a phone number or an email so we can reach the volunteer");
        for (Volunteer o : store.volunteers) {
            if (o.id.equals(v.id)) continue;
            if (!v.phone.isEmpty() && v.phone.equals(o.phone)) throw ApiError.conflict("Another volunteer (" + o.name + ") has this phone number");
            if (!v.email.isEmpty() && v.email.equals(o.email)) throw ApiError.conflict("Another volunteer (" + o.name + ") has this email");
        }
        if (isNew || b.containsKey("city")) v.city = Text.line(Json.str(b, "city"), "City", 60, false);
        if (isNew || b.containsKey("skills")) {
            List<String> skills = new ArrayList<>();
            for (String s : Json.strings(b, "skills")) {
                String t = Text.line(s, "Skill", 30, false);
                if (!t.isEmpty() && skills.stream().noneMatch(x -> x.equalsIgnoreCase(t))) skills.add(t);
            }
            if (skills.size() > 10) throw ApiError.bad("At most 10 skills");
            v.skills = skills;
        }
        if (isNew || b.containsKey("availability")) {
            String a = Json.str(b, "availability", "ANY").toUpperCase(Locale.ROOT);
            if (!AVAILABILITY.contains(a)) throw ApiError.bad("Availability must be WEEKDAYS, WEEKENDS or ANY");
            v.availability = a;
        }
        if (isNew || b.containsKey("joinedOn")) {
            LocalDate d = Text.date(Json.str(b, "joinedOn"), "Joined on", false);
            if (d == null) d = store.today();
            if (d.isAfter(store.today())) throw ApiError.bad("Joined on cannot be in the future");
            v.joinedOn = d.toString();
        }
    }

    Volunteer must(String id) {
        Volunteer v = store.volunteer(id);
        if (v == null) throw ApiError.notFound("Volunteer not found");
        return v;
    }

    public String exportCsv(User me) {
        AuthService.needStaff(me, "export volunteers");
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(Csv.row("ID", "Name", "Phone", "Email", "City", "Skills", "Availability", "Joined", "Active", "Approved hours", "Activities done"));
        synchronized (store) {
            for (Volunteer v : store.volunteers) {
                Map<String, Object> m = view(v);
                sb.append(Csv.row(v.id, v.name, v.phone, v.email, v.city, String.join("; ", v.skills), v.availability, v.joinedOn,
                        v.active ? "Yes" : "No", m.get("hours"), m.get("activitiesDone")));
            }
        }
        return sb.toString();
    }

    // ================================================================== hours

    private Map<String, Object> hoursView(HoursLog h) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) h.toJson();
        Volunteer v = store.volunteer(h.volunteerId);
        Activity a = store.activity(h.activityId);
        m.put("volunteerName", v == null ? h.volunteerId : v.name);
        m.put("activityTitle", a == null ? h.activityId : a.title);
        return m;
    }

    /** Filters: status, volunteer. A volunteer only ever sees their own hours. */
    public List<Map<String, Object>> hours(User me, Map<String, String> q) {
        String status = q.getOrDefault("status", ""), vol = q.getOrDefault("volunteer", "");
        if (!AuthService.isStaff(me)) vol = me.volunteerId == null ? "-" : me.volunteerId;
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store) {
            List<HoursLog> list = new ArrayList<>(store.hours);
            list.sort(Comparator.comparing((HoursLog h) -> h.date).reversed().thenComparing(h -> h.id, Comparator.reverseOrder()));
            for (HoursLog h : list) {
                if (!status.isEmpty() && !h.status.name().equals(status)) continue;
                if (!vol.isEmpty() && !h.volunteerId.equals(vol)) continue;
                out.add(hoursView(h));
            }
        }
        return out;
    }

    /**
     * Logs hours for one done activity. Rules: the volunteer was signed up, the activity is done,
     * 0.5 to 12 hours, and only one entry per volunteer per activity.
     * A volunteer's own entry waits for approval; an entry made by a manager is approved at once.
     */
    public Map<String, Object> logHours(User me, Map<String, Object> b) {
        synchronized (store) {
            String vid;
            if (me.role == Role.VOLUNTEER) {
                if (me.volunteerId == null) throw ApiError.forbidden("Your login is not linked to a volunteer record");
                vid = me.volunteerId;
            } else {
                AuthService.needPrograms(me, "log hours for others");
                vid = Json.str(b, "volunteerId");
            }
            Volunteer v = must(vid);
            Activity a = store.activity(Json.str(b, "activityId"));
            if (a == null) throw ApiError.bad("Choose an activity");
            if (a.status != ActivityStatus.DONE) throw ApiError.conflict("Hours can only be logged after the activity is marked done");
            if (!a.volunteerIds.contains(vid)) throw ApiError.conflict(v.name + " was not signed up for this activity");
            for (HoursLog h : store.hours)
                if (h.volunteerId.equals(vid) && h.activityId.equals(a.id) && h.status != Review.REJECTED)
                    throw ApiError.conflict("Hours for this activity are already logged");
            Object hv = b.get("hours");
            double hrs;
            try { hrs = hv instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(hv)); }
            catch (NumberFormatException e) { throw ApiError.bad("Hours must be a number"); }
            if (hrs < MIN_HOURS || hrs > MAX_HOURS) throw ApiError.bad("Hours must be between 0.5 and 12");
            hrs = Math.round(hrs * 2) / 2.0; // half-hour steps
            HoursLog h = new HoursLog();
            h.id = store.newId("H", 4);
            h.volunteerId = vid;
            h.activityId = a.id;
            h.date = a.date;
            h.hours = hrs;
            h.note = Text.line(Json.str(b, "note"), "Note", 300, false);
            h.loggedBy = me.username;
            h.loggedAt = store.now();
            if (AuthService.canPrograms(me)) { h.status = Review.APPROVED; h.reviewedBy = me.username; }
            store.hours.add(h);
            store.saveHours();
            store.log(me.username, "logged hours", hrs + " h - " + v.name + " at " + a.title, "volunteer:" + vid);
            return hoursView(h);
        }
    }

    public Map<String, Object> review(User me, String id, boolean approve) {
        AuthService.needPrograms(me, "approve hours");
        synchronized (store) {
            HoursLog h = store.hoursLog(id);
            if (h == null) throw ApiError.notFound("Hours entry not found");
            if (h.status != Review.PENDING) throw ApiError.conflict("Already " + h.status.label.toLowerCase());
            h.status = approve ? Review.APPROVED : Review.REJECTED;
            h.reviewedBy = me.username;
            store.saveHours();
            Volunteer v = store.volunteer(h.volunteerId);
            store.log(me.username, approve ? "approved hours" : "rejected hours", h.hours + " h - " + (v == null ? "" : v.name), "volunteer:" + h.volunteerId);
            return hoursView(h);
        }
    }

    /** Done activities this volunteer was on and has not logged hours for yet (for the "log hours" form). */
    public List<Map<String, Object>> unlogged(User me, String volunteerId) {
        String vid = AuthService.isStaff(me) ? volunteerId : me.volunteerId;
        List<Map<String, Object>> out = new ArrayList<>();
        if (vid == null) return out;
        synchronized (store) {
            for (Activity a : store.activities) {
                if (a.status != ActivityStatus.DONE || !a.volunteerIds.contains(vid)) continue;
                boolean logged = store.hours.stream().anyMatch(h -> h.volunteerId.equals(vid) && h.activityId.equals(a.id) && h.status != Review.REJECTED);
                if (!logged) out.add(Json.map("id", a.id, "title", a.title, "date", a.date));
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("date")).reversed());
        return out;
    }
}
