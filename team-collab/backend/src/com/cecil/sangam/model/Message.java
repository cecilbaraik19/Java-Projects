package com.cecil.sangam.model;

import com.cecil.sangam.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One chat message. "seq" is a number that only goes up (across all channels): "unread" means seq is higher than
 * the last one the person read in that channel. A message with a parentId is a reply in a thread.
 */
public class Message implements Json.Writable {
    public String id;
    public long seq;
    public String channelId;
    public String authorId;
    public String text;
    public long at;
    public String parentId;
    public long editedAt;
    public boolean deleted;
    public boolean urgent;
    /** "text", "handoff", "standup" or "system". */
    public String kind = "text";
    /** User ids named with @. */
    public List<String> mentions = new ArrayList<>();
    /** Emoji -> who reacted. */
    public Map<String, List<String>> reactions = new LinkedHashMap<>();

    @Override
    public Map<String, Object> toJson() {
        return Json.obj("id", id, "seq", seq, "channelId", channelId, "authorId", authorId, "text", text, "at", at,
                "parentId", parentId, "editedAt", editedAt, "deleted", deleted, "urgent", urgent, "kind", kind,
                "mentions", mentions, "reactions", reactions);
    }

    public static Message fromJson(Map<String, Object> m) {
        Message x = new Message();
        x.id = Json.str(m, "id");
        x.seq = Json.num(m, "seq", 0);
        x.channelId = Json.str(m, "channelId");
        x.authorId = Json.str(m, "authorId");
        x.text = Json.str(m, "text", "");
        x.at = Json.num(m, "at", 0);
        x.parentId = Json.str(m, "parentId");
        x.editedAt = Json.num(m, "editedAt", 0);
        x.deleted = Json.bool(m, "deleted", false);
        x.urgent = Json.bool(m, "urgent", false);
        x.kind = Json.str(m, "kind", "text");
        x.mentions = new ArrayList<>(Json.strings(m, "mentions"));
        if (m.get("reactions") instanceof Map<?, ?> r)
            for (Map.Entry<?, ?> e : r.entrySet())
                if (e.getValue() instanceof List<?> l) {
                    List<String> who = new ArrayList<>();
                    for (Object o : l) who.add(String.valueOf(o));
                    x.reactions.put(String.valueOf(e.getKey()), who);
                }
        return x;
    }
}
