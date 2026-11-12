package com.cecil.sikho.service;

import com.cecil.sikho.model.Listing;
import com.cecil.sikho.model.Skill;
import com.cecil.sikho.model.User;
import com.cecil.sikho.store.DataStore;
import com.cecil.sikho.util.Clock;
import com.cecil.sikho.util.Json;
import com.cecil.sikho.util.Text;

import java.util.Map;

/** "I can teach" and "I want to learn" cards: add, change, pause and remove. */
public class ListingService {
    public static final int MAX_ACTIVE = 8;

    private final DataStore store;

    public ListingService(DataStore store) { this.store = store; }

    public Listing create(User me, Map<String, Object> body) {
        synchronized (store) {
            if (!store.canAct(me)) throw Problem.forbidden("Your account cannot add cards right now.");
            Listing.Type type = Listing.parse(Listing.Type.class, Json.str(body, "type"), null);
            if (type == null) throw Problem.bad("Choose \"I can teach\" or \"I want to learn\".");
            long active = store.listings.values().stream().filter(l -> l.active && l.userId.equals(me.id)).count();
            if (active >= MAX_ACTIVE) throw Problem.bad("You can have " + MAX_ACTIVE + " active cards. Pause one first.");
            Skill skill = store.skills.get(Json.str(body, "skillId"));
            if (skill == null) throw Problem.bad("Choose a skill from the list (ask a moderator to add a missing one).");
            for (Listing l : store.listings.values())
                if (l.active && l.userId.equals(me.id) && l.type == type && l.skillId.equals(skill.id))
                    throw Problem.conflict("You already have an active card for " + skill.name + ".");
            Listing l = new Listing();
            l.id = store.nextId("L", 1001);
            l.userId = me.id;
            l.type = type;
            l.skillId = skill.id;
            fill(l, body, skill);
            l.createdAt = Clock.millis();
            store.listings.put(l.id, l);
            store.log(me.id, "listing.create", l.id, type + " " + skill.name);
            store.save("listings", "audit");
            return l;
        }
    }

    private void fill(Listing l, Map<String, Object> body, Skill skill) {
        String title = Text.clean(Json.str(body, "title"), 80);
        l.title = title != null ? title : (l.type == Listing.Type.OFFER ? "I can teach " : "I want to learn ") + skill.name.toLowerCase();
        l.details = Text.clean(Json.str(body, "details"), 600);
        l.level = Listing.parse(Listing.Level.class, Json.str(body, "level"), l.level);
        l.mode = Listing.parse(Listing.Mode.class, Json.str(body, "mode"), l.mode);
        l.place = Listing.parse(Listing.Place.class, Json.str(body, "place"), l.place);
    }

    private Listing own(User me, String id) {
        Listing l = store.listings.get(id);
        if (l == null || !l.userId.equals(me.id)) throw Problem.notFound("Card");
        return l;
    }

    public Listing update(User me, String id, Map<String, Object> body) {
        synchronized (store) {
            Listing l = own(me, id);
            fill(l, body, store.skills.get(l.skillId));
            if (body.containsKey("active")) {
                boolean a = Json.bool(body, "active", true);
                if (a && !l.active) {
                    long active = store.listings.values().stream().filter(x -> x.active && x.userId.equals(me.id)).count();
                    if (active >= MAX_ACTIVE) throw Problem.bad("You can have " + MAX_ACTIVE + " active cards. Pause one first.");
                }
                l.active = a;
            }
            store.log(me.id, "listing.update", l.id, l.active ? "active" : "paused");
            store.save("listings", "audit");
            return l;
        }
    }

    /** Removes a card that was never used. A card with exchanges is paused instead (the history needs it). */
    public String delete(User me, String id) {
        synchronized (store) {
            Listing l = own(me, id);
            boolean used = store.exchanges.values().stream().anyMatch(e -> l.id.equals(e.listingId));
            if (used) {
                l.active = false;
                store.log(me.id, "listing.pause", l.id, "has exchanges, paused instead of removed");
                store.save("listings", "audit");
                return "paused";
            }
            store.listings.remove(l.id);
            store.log(me.id, "listing.delete", l.id, null);
            store.save("listings", "audit");
            return "removed";
        }
    }

    public Listing get(String id) {
        synchronized (store) {
            Listing l = store.listings.get(id);
            if (l == null) throw Problem.notFound("Card");
            User owner = store.users.get(l.userId);
            if (!store.canAct(owner)) throw Problem.notFound("Card");
            return l;
        }
    }

    /** Count of open exchanges on one card. */
    public long openOn(String listingId) {
        synchronized (store) {
            return store.exchanges.values().stream().filter(e -> listingId.equals(e.listingId) && e.isOpen()).count();
        }
    }
}
