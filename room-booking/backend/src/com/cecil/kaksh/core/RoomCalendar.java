package com.cecil.kaksh.core;

import com.cecil.kaksh.model.Block;
import com.cecil.kaksh.model.Booking;
import com.cecil.kaksh.model.Room;
import com.cecil.kaksh.store.DataStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * THE CONFLICT DETECTOR. For every room it keeps the busy times sorted by start time (a TreeMap = a red-black tree).
 *
 * Two meetings in the same room clash when they overlap, counting the room's buffer (cleaning time) after each:
 *
 *     new [s, e)  and  old [s2, e2)  clash  when   s < e2 + buffer   AND   s2 < e + buffer
 *
 * (Half-open times: a meeting that ends at 10:00 and one that starts at 10:00 + buffer do NOT clash.)
 *
 * Instead of checking every booking (slow: O(n)), we only look at busy times that start before e + buffer
 * (headMap) and walk backwards. We can stop as soon as an entry starts so early that even the LONGEST busy time
 * in this room could not reach s. So a check costs O(log n + k) where k is the number of clashes (normally 0 or 1).
 *
 * The calendar is only a fast index: the real records stay in the DataStore. It is changed in the same locked
 * block as the records (see BookingService), so it can never disagree with them.
 */
public class RoomCalendar {

    /** One busy time in a room: a booking or a maintenance block. */
    public record Busy(long start, long end, String kind, String id) { }

    private static final class Lane {
        final TreeMap<Long, List<Busy>> byStart = new TreeMap<>();
        long longest = 0;   // the longest busy time ever added to this room (used to stop the search early)
    }

    private final Map<String, Lane> lanes = new HashMap<>();

    /** Builds the index from all bookings that hold a room and all active blocks. */
    public static RoomCalendar build(DataStore store) {
        RoomCalendar c = new RoomCalendar();
        synchronized (store) {
            for (Booking b : store.bookings.values()) if (b.status.holdsRoom()) c.add(b);
            for (Block b : store.blocks.values()) if (b.active) c.add(b);
        }
        return c;
    }

    public synchronized void add(Booking b) { put(b.roomId, new Busy(b.start, b.end, "BOOKING", b.id)); }

    public synchronized void add(Block b) { put(b.roomId, new Busy(b.start, b.end, "BLOCK", b.id)); }

    private void put(String roomId, Busy busy) {
        Lane lane = lanes.computeIfAbsent(roomId, k -> new Lane());
        lane.byStart.computeIfAbsent(busy.start(), k -> new ArrayList<>(1)).add(busy);
        lane.longest = Math.max(lane.longest, busy.end() - busy.start());
    }

    /** Removes a booking or block by its id (call it BEFORE changing the record's times). */
    public synchronized void remove(String roomId, long start, String id) {
        Lane lane = lanes.get(roomId);
        if (lane == null) return;
        List<Busy> at = lane.byStart.get(start);
        if (at == null) return;
        at.removeIf(x -> x.id().equals(id));
        if (at.isEmpty()) lane.byStart.remove(start);
    }

    /**
     * Every busy time in this room that clashes with [s, e), counting the buffer. ignoreId lets an edit skip
     * the booking that is being moved. An empty list means: free.
     */
    public synchronized List<Busy> clashes(String roomId, long s, long e, long bufferMs, String ignoreId) {
        List<Busy> out = new ArrayList<>();
        Lane lane = lanes.get(roomId);
        if (lane == null) return out;
        // only entries that start before e + buffer can clash; walk them from the latest backwards
        NavigableMap<Long, List<Busy>> before = lane.byStart.headMap(e + bufferMs, false).descendingMap();
        for (Map.Entry<Long, List<Busy>> entry : before.entrySet()) {
            long s2 = entry.getKey();
            if (s2 + lane.longest + bufferMs <= s) break;   // nothing this early can reach s: stop
            for (Busy b : entry.getValue()) {
                if (b.id().equals(ignoreId)) continue;
                if (s < b.end() + bufferMs && b.start() < e + bufferMs) out.add(0, b);
            }
        }
        return out;
    }

    public boolean free(Room room, long s, long e, String ignoreId) {
        return clashes(room.id, s, e, room.bufferMin * Rules.MINUTE, ignoreId).isEmpty();
    }

    /** All busy times in a room that touch [from, to), in time order (for drawing the day board). */
    public synchronized List<Busy> between(String roomId, long from, long to) {
        List<Busy> out = new ArrayList<>();
        Lane lane = lanes.get(roomId);
        if (lane == null) return out;
        for (List<Busy> list : lane.byStart.subMap(from - lane.longest, true, to, false).values())
            for (Busy b : list) if (b.end() > from) out.add(b);
        return out;
    }

    public synchronized int size() {
        int n = 0;
        for (Lane l : lanes.values()) for (List<Busy> x : l.byStart.values()) n += x.size();
        return n;
    }
}
