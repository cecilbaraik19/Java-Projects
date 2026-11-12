package com.cecil.sikho.model;

import com.cecil.sikho.util.Json;

import java.util.Map;

/**
 * A card on the community board: "I can teach X" (OFFER) or "I want to learn X" (WANT).
 */
public class Listing implements Json.Writable {
    public enum Type { OFFER, WANT }
    public enum Level { BEGINNER, INTERMEDIATE, EXPERT }
    public enum Mode { IN_PERSON, ONLINE, EITHER }
    public enum Place { MY_PLACE, YOUR_PLACE, PUBLIC }

    public String id;
    public String userId;
    public Type type;
    public String skillId;
    public String title;
    public String details;
    /** For an offer: how well the person knows it. For a want: the level they are at now. */
    public Level level = Level.BEGINNER;
    public Mode mode = Mode.IN_PERSON;
    public Place place = Place.PUBLIC;
    public boolean active = true;
    public long createdAt;

    public static <E extends Enum<E>> E parse(Class<E> type, String s, E fallback) {
        if (s == null) return fallback;
        for (E e : type.getEnumConstants()) if (e.name().equalsIgnoreCase(s.trim())) return e;
        return fallback;
    }

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "userId", userId, "type", type, "skillId", skillId, "title", title, "details", details,
                "level", level, "mode", mode, "place", place, "active", active, "createdAt", createdAt);
    }

    public static Listing fromJson(Map<String, Object> m) {
        Listing l = new Listing();
        l.id = Json.str(m, "id");
        l.userId = Json.str(m, "userId");
        l.type = parse(Type.class, Json.str(m, "type"), Type.OFFER);
        l.skillId = Json.str(m, "skillId");
        l.title = Json.str(m, "title");
        l.details = Json.str(m, "details");
        l.level = parse(Level.class, Json.str(m, "level"), Level.BEGINNER);
        l.mode = parse(Mode.class, Json.str(m, "mode"), Mode.IN_PERSON);
        l.place = parse(Place.class, Json.str(m, "place"), Place.PUBLIC);
        l.active = Json.bool(m, "active", true);
        l.createdAt = Json.num(m, "createdAt", 0);
        return l;
    }
}
