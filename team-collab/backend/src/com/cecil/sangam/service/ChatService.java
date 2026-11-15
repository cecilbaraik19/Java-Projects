package com.cecil.sangam.service;

import com.cecil.sangam.core.EventBus;
import com.cecil.sangam.core.Mentions;
import com.cecil.sangam.core.WorkHours;
import com.cecil.sangam.model.Channel;
import com.cecil.sangam.model.Message;
import com.cecil.sangam.model.Note;
import com.cecil.sangam.model.User;
import com.cecil.sangam.store.DataStore;
import com.cecil.sangam.util.Clock;
import com.cecil.sangam.util.Json;
import com.cecil.sangam.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Channels, messages, threads, mentions and reactions.
 *
 * Remote-team rules:
 *  - Quiet hours: an @mention of somebody who is asleep (or in focus time) is delivered when their day starts.
 *    The sender is told so at once ("Lena will see this at 09:00 her time – 02:14 now in Berlin").
 *  - Urgent is for real emergencies: it rings through quiet hours, so each person may send at most 3 a day.
 *  - @here = the people in the channel who are working right now; @team = everybody in the channel.
 */
public class ChatService {
    public static final int MAX_TEXT = 4000;
    public static final int URGENT_PER_DAY = 3;
    public static final long EDIT_WINDOW = 24 * WorkHours.HOUR;
    public static final List<String> REACTIONS = List.of("👍", "✅", "👀", "🎉", "🙏", "❤️");

    private final DataStore store;
    private final EventBus events;

    public ChatService(DataStore store, EventBus events) {
        this.store = store;
        this.events = events;
    }

    public Channel create(User u, Map<String, Object> b) {
        if (u.guest()) throw Problem.forbidden("Guests cannot make channels.");
        synchronized (store) {
            String name = Text.clean(Json.str(b, "name"), 30);
            name = name == null ? "" : name.toLowerCase().replace(' ', '-');
            if (!name.matches("[a-z0-9][a-z0-9-]{1,29}")) throw Problem.bad("Channel names: 2 to 30 small letters, digits or dashes.");
            for (Channel c : store.channels.values())
                if (c.kind != Channel.Kind.DIRECT && c.name.equals(name)) throw Problem.conflict("#" + name + " already exists.");
            Channel c = new Channel();
            c.id = store.nextId("C", 1);
            c.name = name;
            c.topic = Text.clean(Json.str(b, "topic", ""), 140);
            c.kind = Json.bool(b, "private", false) ? Channel.Kind.PRIVATE : Channel.Kind.PUBLIC;
            c.createdBy = u.id;
            c.createdAt = Clock.millis();
            if (c.kind == Channel.Kind.PRIVATE) {
                c.memberIds.add(u.id);
                for (String id : Json.strings(b, "memberIds")) {
                    User m = store.users.get(id);
                    if (m != null && m.active && !c.memberIds.contains(id)) c.memberIds.add(id);
                }
            }
            store.channels.put(c.id, c);
            store.log(u.id, "channel.create", "#" + name, c.kind.name());
            store.save("channels", "audit");
            events.publish("channel", c.kind == Channel.Kind.PRIVATE ? c.memberIds : null, null, null, Json.obj("id", c.id));
            return c;
        }
    }

    /** The direct-message channel between two people (made the first time). */
    public Channel direct(User u, String otherId) {
        synchronized (store) {
            User o = store.users.get(otherId);
            if (o == null || !o.active || o.id.equals(u.id)) throw Problem.notFound("Person");
            if (u.guest() || o.guest()) {
                // guests may only write to people who share a project with them
                boolean share = store.projects.values().stream().anyMatch(p -> p.has(u.id) && p.has(o.id));
                if (!share) throw Problem.forbidden("You can only message people who work on a project with you.");
            }
            for (Channel c : store.channels.values())
                if (c.kind == Channel.Kind.DIRECT && c.memberIds.contains(u.id) && c.memberIds.contains(o.id)) return c;
            Channel c = new Channel();
            c.id = store.nextId("C", 1);
            c.name = "dm";
            c.kind = Channel.Kind.DIRECT;
            c.memberIds.add(u.id);
            c.memberIds.add(o.id);
            c.createdBy = u.id;
            c.createdAt = Clock.millis();
            store.channels.put(c.id, c);
            store.save("channels");
            return c;
        }
    }

    /** Result of posting: the message, plus who will only see it later because of quiet hours. */
    public record Posted(Message message, List<Map<String, Object>> later) { }

    public Posted post(User u, String channelId, Map<String, Object> b) {
        return post(u, channelId, Json.str(b, "text"), Json.str(b, "parentId"), Json.bool(b, "urgent", false), "text");
    }

    public Posted post(User u, String channelId, String text, String parentId, boolean urgent, String kind) {
        synchronized (store) {
            Channel c = Access.channel(store, u, channelId);
            String body = WorkService.clip(text, MAX_TEXT);
            if (Text.blank(body)) throw Problem.bad("Write something first.");
            Message parent = null;
            if (!Text.blank(parentId)) {
                parent = store.messages.get(parentId);
                if (parent == null || !parent.channelId.equals(c.id)) throw Problem.notFound("Message");
                if (parent.parentId != null) parent = store.messages.get(parent.parentId);   // replies go to the thread start
            }
            long now = Clock.millis();
            if (urgent && urgentToday(u, now) >= URGENT_PER_DAY)
                throw Problem.conflict("You already sent " + URGENT_PER_DAY + " urgent messages today. Urgent wakes people up – use it only for real emergencies.");
            Message m = new Message();
            m.id = store.nextId("M", 1);
            m.seq = store.nextSeq();
            m.channelId = c.id;
            m.authorId = u.id;
            m.text = body;
            m.at = now;
            m.parentId = parent == null ? null : parent.id;
            m.urgent = urgent;
            m.kind = kind;
            List<User> members = Access.members(store, c);
            Map<String, User> notifyThem = new LinkedHashMap<>();
            for (String name : Mentions.names(body)) {
                if (name.equals("here")) { for (User x : members) if (WorkHours.working(x, now)) notifyThem.put(x.id, x); continue; }
                if (name.equals("team")) { for (User x : members) notifyThem.put(x.id, x); continue; }
                User x = store.userByName(name);
                if (x != null && x.active && members.contains(x)) { notifyThem.put(x.id, x); if (!m.mentions.contains(x.id)) m.mentions.add(x.id); }
            }
            if (c.kind == Channel.Kind.DIRECT) for (User x : members) notifyThem.put(x.id, x);
            if (parent != null) {                                   // the people in the thread hear about replies
                notifyThem.putIfAbsent(parent.authorId, store.users.get(parent.authorId));
                for (Message r : store.messages.values())
                    if (parent.id.equals(r.parentId) && store.users.containsKey(r.authorId)) notifyThem.putIfAbsent(r.authorId, store.users.get(r.authorId));
            }
            notifyThem.remove(u.id);
            store.messages.put(m.id, m);
            u.readUpTo.put(c.id, m.seq);
            List<Map<String, Object>> later = new ArrayList<>();
            String where = c.kind == Channel.Kind.DIRECT ? "a direct message" : "#" + c.name;
            for (User x : notifyThem.values()) {
                if (x == null || !Access.seeChannel(store, x, c)) continue;
                String what = m.mentions.contains(x.id) ? " mentioned you in " : (parent != null && c.kind != Channel.Kind.DIRECT ? " replied in a thread in " : " wrote in ");
                Note n = store.notify(x.id, (urgent ? "URGENT – " : "") + u.name + what + where + ": " + Text.clean(body, 90),
                        "#/channel/" + c.id + (parent != null ? "/" + parent.id : "") + "?m=" + m.id, urgent);
                if (n != null && n.deliverAt > now && (m.mentions.contains(x.id) || c.kind == Channel.Kind.DIRECT)) {
                    var z = WorkHours.local(x, now);
                    var d = WorkHours.local(x, n.deliverAt);
                    later.add(Json.obj("userId", x.id, "name", x.name, "city", x.city,
                            "localNow", String.format("%02d:%02d", z.getHour(), z.getMinute()),
                            "deliverAt", n.deliverAt,
                            "deliverLocal", String.format("%s %02d:%02d", d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH), d.getHour(), d.getMinute()),
                            "why", x.focusUntil > now ? "in focus time" : "outside working hours"));
                }
            }
            if (urgent) store.log(u.id, "message.urgent", where, Text.clean(body, 80));
            store.save("messages", "notes", "users", "audit");
            publish(c, "message", Json.obj("id", m.id, "seq", m.seq, "parentId", m.parentId, "authorId", u.id));
            return new Posted(m, later);
        }
    }

    private int urgentToday(User u, long now) {
        int n = 0;
        for (Message m : store.messages.values()) if (m.urgent && u.id.equals(m.authorId) && now - m.at < WorkHours.DAY) n++;
        return n;
    }

    public Message edit(User u, String id, String text) {
        synchronized (store) {
            Message m = mine(u, id);
            if (Clock.millis() - m.at > EDIT_WINDOW) throw Problem.forbidden("Messages can be edited for 24 hours.");
            String body = WorkService.clip(text, MAX_TEXT);
            if (Text.blank(body)) throw Problem.bad("Write something first (or delete the message).");
            m.text = body;
            m.editedAt = Clock.millis();
            store.save("messages");
            publish(store.channels.get(m.channelId), "message.edit", Json.obj("id", m.id, "parentId", m.parentId));
            return m;
        }
    }

    public Message delete(User u, String id) {
        synchronized (store) {
            Message m = store.messages.get(id);
            if (m == null || !Access.seeChannel(store, u, store.channels.get(m.channelId))) throw Problem.notFound("Message");
            if (!u.id.equals(m.authorId) && !u.admin()) throw Problem.forbidden("You can only delete your own messages.");
            m.deleted = true;
            m.text = "";
            m.reactions.clear();
            store.log(u.id, "message.delete", m.id, u.id.equals(m.authorId) ? "own" : "by admin");
            store.save("messages", "audit");
            publish(store.channels.get(m.channelId), "message.edit", Json.obj("id", m.id, "parentId", m.parentId));
            return m;
        }
    }

    /** Add or take back a reaction. */
    public Message react(User u, String id, String emoji) {
        if (!REACTIONS.contains(emoji)) throw Problem.bad("Unknown reaction.");
        synchronized (store) {
            Message m = store.messages.get(id);
            if (m == null || m.deleted || !Access.seeChannel(store, u, store.channels.get(m.channelId))) throw Problem.notFound("Message");
            List<String> who = m.reactions.computeIfAbsent(emoji, k -> new ArrayList<>());
            if (!who.remove(u.id)) who.add(u.id);
            if (who.isEmpty()) m.reactions.remove(emoji);
            store.save("messages");
            publish(store.channels.get(m.channelId), "message.edit", Json.obj("id", m.id, "parentId", m.parentId));
            return m;
        }
    }

    public void markRead(User u, String channelId) {
        synchronized (store) {
            Channel c = Access.channel(store, u, channelId);
            long max = u.readUpTo.getOrDefault(c.id, 0L);
            for (Message m : store.messages.values()) if (m.channelId.equals(c.id)) max = Math.max(max, m.seq);
            u.readUpTo.put(c.id, max);
            store.save("users");
        }
        events.publish("read", List.of(u.id), null, null, Json.obj("channelId", channelId));
    }

    private Message mine(User u, String id) {
        Message m = store.messages.get(id);
        if (m == null || m.deleted || !Access.seeChannel(store, u, store.channels.get(m.channelId))) throw Problem.notFound("Message");
        if (!u.id.equals(m.authorId)) throw Problem.forbidden("You can only edit your own messages.");
        return m;
    }

    private void publish(Channel c, String type, Map<String, Object> data) {
        data.put("channelId", c.id);
        events.publish(type, null, c.id, null, data);
    }
}
