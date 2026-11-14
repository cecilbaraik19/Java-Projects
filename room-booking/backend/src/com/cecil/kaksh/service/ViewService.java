package com.cecil.kaksh.service;

import com.cecil.kaksh.core.RoomCalendar;
import com.cecil.kaksh.core.Rules;
import com.cecil.kaksh.core.SlotFinder;
import com.cecil.kaksh.model.AuditEntry;
import com.cecil.kaksh.model.Block;
import com.cecil.kaksh.model.Booking;
import com.cecil.kaksh.model.Note;
import com.cecil.kaksh.model.Room;
import com.cecil.kaksh.model.Series;
import com.cecil.kaksh.model.Status;
import com.cecil.kaksh.model.User;
import com.cecil.kaksh.model.WaitEntry;
import com.cecil.kaksh.store.DataStore;
import com.cecil.kaksh.util.Clock;
import com.cecil.kaksh.util.Json;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds what the website shows. This is also where PRIVACY is decided: a private meeting shows "Private meeting"
 * and no names of the people in it, unless you are in it or you are the facility manager.
 */
public class ViewService {
    private final DataStore store;
    private final RoomCalendar cal;
    private final SlotFinder finder;

    public ViewService(DataStore store, RoomCalendar cal, SlotFinder finder) {
        this.store = store;
        this.cal = cal;
        this.finder = finder;
    }

    // ------------------------------------------------------------------ one booking

    public boolean canSee(Booking b, User viewer) {
        return !b.privateTitle || b.involves(viewer.id) || viewer.admin();
    }

    public Map<String, Object> booking(Booking b, User viewer, boolean detail) {
        synchronized (store) {
            Room r = store.rooms.get(b.roomId);
            boolean see = canSee(b, viewer);
            boolean mine = viewer.id.equals(b.organiserId);
            boolean in = b.involves(viewer.id);
            long now = Clock.millis();
            boolean notStarted = (b.status == Status.CONFIRMED || b.status == Status.PENDING) && b.start > now;
            Map<String, Object> m = Json.obj("id", b.id, "roomId", b.roomId, "roomName", r == null ? "—" : r.name,
                    "roomCode", r == null ? "" : r.code, "floor", r == null ? 0 : r.floor,
                    "title", see ? b.title : "Private meeting", "private", b.privateTitle,
                    "organiser", Json.obj("id", b.organiserId, "name", store.userName(b.organiserId)),
                    "start", b.start, "end", b.end, "status", b.status, "headcount", b.headcount(), "guests", b.guests,
                    "seriesId", b.seriesId, "mine", mine, "invited", in && !mine, "version", b.version,
                    "checkedInAt", b.checkedInAt, "reason", see ? b.reason : "",
                    "checkinFrom", b.start - Rules.CHECKIN_EARLY_MIN * Rules.MINUTE,
                    "checkinUntil", b.start + Rules.NO_SHOW_MIN * Rules.MINUTE);
            List<Map<String, Object>> people = new ArrayList<>();
            if (see) for (String p : b.people) {
                User u = store.users.get(p);
                if (u != null) people.add(u.pub());
            }
            m.put("people", people);
            m.put("needs", see ? b.needs : List.of());
            m.put("can", Json.obj(
                    "edit", (mine || viewer.admin()) && notStarted,
                    "cancel", (mine || viewer.admin()) && notStarted,
                    "checkin", (in || viewer.admin()) && b.status == Status.CONFIRMED
                            && now >= b.start - Rules.CHECKIN_EARLY_MIN * Rules.MINUTE && now <= b.start + Rules.NO_SHOW_MIN * Rules.MINUTE,
                    "end", (in || viewer.admin()) && b.status == Status.CHECKED_IN,
                    "extend", (mine || viewer.admin()) && (b.status == Status.CHECKED_IN || (b.status == Status.CONFIRMED && b.end > now)),
                    "approve", viewer.admin() && b.status == Status.PENDING));
            if (detail) {
                List<Map<String, Object>> hist = new ArrayList<>();
                if (see) for (Map<String, Object> h : b.history)
                    hist.add(Json.obj("at", h.get("at"), "by", h.get("by") == null ? "Kaksh" : store.userName((String) h.get("by")), "text", h.get("text")));
                m.put("history", hist);
                Series s = b.seriesId == null ? null : store.series.get(b.seriesId);
                if (s != null) {
                    List<Map<String, Object>> dates = new ArrayList<>();
                    for (String id : s.bookingIds) {
                        Booking x = store.bookings.get(id);
                        if (x != null) dates.add(Json.obj("id", x.id, "start", x.start, "end", x.end, "status", x.status));
                    }
                    m.put("series", Json.obj("id", s.id, "repeat", s.repeat, "count", s.count, "dates", dates, "skipped", s.skipped));
                }
                m.put("room", r == null ? null : room(r));
            }
            return m;
        }
    }

    public Map<String, Object> room(Room r) {
        return Json.obj("id", r.id, "code", r.code, "name", r.name, "floor", r.floor, "capacity", r.capacity,
                "equipment", r.equipment, "bufferMin", r.bufferMin, "needsApproval", r.needsApproval, "active", r.active, "note", r.note);
    }

    // ------------------------------------------------------------------ home: the key rack

    /** What a room is doing right now: free (until when) or busy (until when, with what). */
    private Map<String, Object> nowStatus(Room r, User viewer, long now) {
        LocalDate today = Rules.dayOf(now);
        long close = Rules.closes(today);
        RoomCalendar.Busy current = null, next = null;
        for (RoomCalendar.Busy b : cal.between(r.id, now - 12 * 3_600_000L, now + 7 * 24 * 3_600_000L)) {
            if (b.start() <= now && b.end() > now) current = b;
            else if (b.start() > now && next == null) next = b;
        }
        Map<String, Object> m = Json.obj("room", room(r));
        boolean open = Rules.openDay(today) && now >= Rules.opens(today) && now < close;
        if (!r.active) {
            m.put("state", "OFF");
        } else if (current != null) {
            m.put("state", current.kind().equals("BLOCK") ? "CLOSED" : "BUSY");
            m.put("until", current.end());
            if (current.kind().equals("BOOKING")) {
                Booking b = store.bookings.get(current.id());
                m.put("booking", b == null ? null : booking(b, viewer, false));
            } else {
                Block bl = store.blocks.get(current.id());
                m.put("reason", bl == null ? "" : bl.reason);
            }
        } else {
            m.put("state", open ? "FREE" : "AFTER_HOURS");
            if (open) m.put("until", next == null ? close : Math.min(next.start(), close));
            if (next != null && next.kind().equals("BOOKING")) {
                Booking b = store.bookings.get(next.id());
                m.put("next", b == null ? null : booking(b, viewer, false));
            }
        }
        return m;
    }

    public Map<String, Object> home(User viewer) {
        synchronized (store) {
            long now = Clock.millis();
            List<Map<String, Object>> rack = new ArrayList<>();
            for (Room r : store.rooms.values()) rack.add(nowStatus(r, viewer, now));
            List<Map<String, Object>> mine = new ArrayList<>();
            List<Booking> up = new ArrayList<>();
            for (Booking b : store.bookings.values())
                if (b.involves(viewer.id) && b.status.open() && b.end > now && b.start < now + 14L * 24 * 3_600_000L) up.add(b);
            up.sort(Comparator.comparingLong(b -> b.start));
            for (Booking b : up) if (mine.size() < 8) mine.add(booking(b, viewer, false));
            List<Map<String, Object>> waits = new ArrayList<>();
            for (WaitEntry w : store.waitlist.values()) if (w.userId.equals(viewer.id) && w.status.equals("WAITING")) waits.add(wait(w));
            int unread = 0;
            for (Note n : store.notes.values()) if (viewer.id.equals(n.userId) && !n.read) unread++;
            Map<String, Object> out = Json.obj("now", now, "rack", rack, "upcoming", mine, "upcomingTotal", up.size(),
                    "waiting", waits, "unread", unread, "rules", Rules.asJson());
            LocalDate d = Rules.dayOf(now);
            LocalDate mon = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            long wFrom = Rules.at(mon, LocalTime.MIDNIGHT), wTo = Rules.at(mon.plusDays(7), LocalTime.MIDNIGHT);
            long used = 0;
            for (Booking b : store.bookings.values())
                if (viewer.id.equals(b.organiserId) && b.start >= wFrom && b.start < wTo && (b.status.open() || b.status == Status.COMPLETED)) used += b.minutes();
            out.put("quota", Json.obj("usedMin", used, "limitMin", Rules.WEEK_QUOTA_MIN, "exempt", viewer.admin()));
            if (viewer.admin()) {
                int pending = 0, noShowToday = 0, closed = 0;
                long dayStart = Rules.at(d, LocalTime.MIDNIGHT);
                for (Booking b : store.bookings.values()) {
                    if (b.status == Status.PENDING) pending++;
                    if (b.status == Status.RELEASED && b.start >= dayStart && b.start < dayStart + 86_400_000L) noShowToday++;
                }
                for (Block bl : store.blocks.values()) if (bl.active && bl.end > now) closed++;
                out.put("admin", Json.obj("pending", pending, "noShowToday", noShowToday, "closings", closed));
            }
            out.put("stats", Json.obj("clashesStopped", Json.num(store.meta, "stats.clashesStopped", 0),
                    "minutesGivenBack", Json.num(store.meta, "stats.minutesGivenBack", 0),
                    "waitlistWins", Json.num(store.meta, "stats.waitlistWins", 0)));
            return out;
        }
    }

    public Map<String, Object> wait(WaitEntry w) {
        int place = 1;
        for (WaitEntry x : store.waitlist.values())
            if (x != w && x.status.equals("WAITING") && x.roomId.equals(w.roomId) && x.start < w.end && w.start < x.end
                    && (x.createdAt < w.createdAt || (x.createdAt == w.createdAt && x.id.compareTo(w.id) < 0))) place++;
        return Json.obj("id", w.id, "roomId", w.roomId, "roomName", store.roomName(w.roomId), "title", w.title,
                "start", w.start, "end", w.end, "status", w.status, "bookingId", w.bookingId, "place", place, "createdAt", w.createdAt);
    }

    // ------------------------------------------------------------------ the day board

    public Map<String, Object> board(User viewer, LocalDate day) {
        synchronized (store) {
            long from = Rules.opens(day), to = Rules.closes(day);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Room r : store.rooms.values()) {
                List<Map<String, Object>> items = new ArrayList<>();
                for (RoomCalendar.Busy b : cal.between(r.id, from, to)) {
                    if (b.kind().equals("BLOCK")) {
                        Block bl = store.blocks.get(b.id());
                        items.add(Json.obj("kind", "BLOCK", "id", b.id(), "start", b.start(), "end", b.end(), "reason", bl == null ? "" : bl.reason));
                    } else {
                        Booking bk = store.bookings.get(b.id());
                        if (bk != null) {
                            Map<String, Object> v = booking(bk, viewer, false);
                            v.put("kind", "BOOKING");
                            items.add(v);
                        }
                    }
                }
                // released (no-show) bookings are not in the calendar any more, but showing them as ghosts is useful
                for (Booking bk : store.bookings.values())
                    if (bk.roomId.equals(r.id) && bk.status == Status.RELEASED && bk.start < to && bk.end > from) {
                        Map<String, Object> v = booking(bk, viewer, false);
                        v.put("kind", "GHOST");
                        items.add(v);
                    }
                rows.add(Json.obj("room", room(r), "items", items));
            }
            return Json.obj("date", day.toString(), "open", Rules.openDay(day), "from", from, "to", to,
                    "now", Clock.millis(), "rows", rows, "rules", Rules.asJson());
        }
    }

    // ------------------------------------------------------------------ one room

    public Map<String, Object> roomPage(User viewer, String id, LocalDate day) {
        synchronized (store) {
            Room r = store.rooms.get(id);
            if (r == null) throw Problem.notFound("Room");
            LocalDate mon = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            List<Map<String, Object>> week = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                LocalDate d = mon.plusDays(i);
                List<Map<String, Object>> items = new ArrayList<>();
                for (RoomCalendar.Busy b : cal.between(r.id, Rules.opens(d), Rules.closes(d))) {
                    if (b.kind().equals("BLOCK")) {
                        Block bl = store.blocks.get(b.id());
                        items.add(Json.obj("kind", "BLOCK", "start", b.start(), "end", b.end(), "reason", bl == null ? "" : bl.reason));
                    } else {
                        Booking bk = store.bookings.get(b.id());
                        if (bk != null) { Map<String, Object> v = booking(bk, viewer, false); v.put("kind", "BOOKING"); items.add(v); }
                    }
                }
                week.add(Json.obj("date", d.toString(), "from", Rules.opens(d), "to", Rules.closes(d), "items", items));
            }
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (Block bl : store.blocks.values())
                if (bl.roomId.equals(r.id) && bl.active && bl.end > Clock.millis())
                    blocks.add(Json.obj("id", bl.id, "start", bl.start, "end", bl.end, "reason", bl.reason, "by", store.userName(bl.byId)));
            Map<String, Object> use = utilisation(r, 4);
            return Json.obj("room", room(r), "now", nowStatus(r, viewer, Clock.millis()), "week", week,
                    "freeToday", finder.freeGaps(r, Rules.dayOf(Clock.millis())), "freeOn", finder.freeGaps(r, day),
                    "day", day.toString(), "blocks", blocks, "use", use);
        }
    }

    // ------------------------------------------------------------------ my bookings

    public Map<String, Object> mine(User viewer, String tab) {
        synchronized (store) {
            long now = Clock.millis();
            List<Booking> list = new ArrayList<>();
            for (Booking b : store.bookings.values()) {
                if (!b.involves(viewer.id)) continue;
                boolean upcoming = b.status.open() && b.end > now;
                switch (tab) {
                    case "past" -> { if (!upcoming && (b.status == Status.COMPLETED || b.status == Status.RELEASED)) list.add(b); }
                    case "cancelled" -> { if (b.status == Status.CANCELLED || b.status == Status.DECLINED || b.status == Status.EXPIRED) list.add(b); }
                    default -> { if (upcoming) list.add(b); }
                }
            }
            Comparator<Booking> byStart = Comparator.comparingLong(b -> b.start);
            list.sort(tab.equals("upcoming") || !List.of("past", "cancelled").contains(tab) ? byStart : byStart.reversed());
            List<Map<String, Object>> out = new ArrayList<>();
            for (Booking b : list) if (out.size() < 120) out.add(booking(b, viewer, false));
            List<Map<String, Object>> waits = new ArrayList<>();
            for (WaitEntry w : store.waitlist.values()) if (w.userId.equals(viewer.id) && (w.status.equals("WAITING") || w.createdAt > now - 14L * 86_400_000)) waits.add(wait(w));
            waits.sort(Comparator.comparingLong(m -> -(Long) m.get("createdAt")));
            return Json.obj("tab", tab, "bookings", out, "total", list.size(), "waiting", waits);
        }
    }

    // ------------------------------------------------------------------ the facility manager's numbers

    /** Booked share of the open hours over the last `weeks` weeks (not counting today), plus no-shows and size fit. */
    public Map<String, Object> utilisation(Room r, int weeks) {
        LocalDate today = Clock.today();
        LocalDate first = today.minusDays(7L * weeks);
        long from = Rules.at(first, LocalTime.MIDNIGHT), to = Rules.at(today, LocalTime.MIDNIGHT);
        long openMin = 0;
        for (LocalDate d = first; d.isBefore(today); d = d.plusDays(1)) if (Rules.openDay(d)) openMin += (Rules.closes(d) - Rules.opens(d)) / Rules.MINUTE;
        long usedMin = 0, people = 0;
        int meetings = 0, noShows = 0, checkedIn = 0;
        for (Booking b : store.bookings.values()) {
            if (!b.roomId.equals(r.id) || b.start < from || b.start >= to) continue;
            if (b.status == Status.COMPLETED) {
                usedMin += b.minutes();
                meetings++;
                people += b.headcount();
                if (b.checkedInAt > 0) checkedIn++;
            } else if (b.status == Status.RELEASED) noShows++;
        }
        double avg = meetings == 0 ? 0 : (double) people / meetings;
        String advice;
        double pct = openMin == 0 ? 0 : usedMin * 100.0 / openMin;
        if (meetings + noShows < 3) advice = "Hardly used – tell people about it, or use the space for something else.";
        else if (pct < 8) advice = "Little used (" + Math.round(pct) + "% of open hours). Fine for a special room; otherwise open it to more people.";
        else if (avg <= r.capacity * 0.3 && r.capacity >= 8) advice = "Usually " + Math.round(avg) + " people in " + r.capacity + " seats – small groups take a big room. Nudge them to smaller rooms.";
        else if (noShows * 4 > meetings + noShows) advice = "Many no-shows – remind people to cancel when plans change.";
        else if (openMin > 0 && usedMin * 100 / openMin >= 75) advice = "Very busy – people may struggle to find it free. Think about another room of this size.";
        else advice = "Healthy use.";
        return Json.obj("roomId", r.id, "name", r.name, "code", r.code, "capacity", r.capacity, "openMin", openMin,
                "usedMin", usedMin, "percent", openMin == 0 ? 0 : Math.round(usedMin * 1000.0 / openMin) / 10.0,
                "meetings", meetings, "noShows", noShows, "noShowPercent", meetings + noShows == 0 ? 0 : Math.round(noShows * 100.0 / (meetings + noShows)),
                "avgPeople", Math.round(avg * 10) / 10.0, "checkedIn", checkedIn, "advice", advice);
    }

    public Map<String, Object> insights(User admin, int weeks) {
        if (!admin.admin()) throw Problem.forbidden("Only the facility manager can see this.");
        synchronized (store) {
            List<Map<String, Object>> rooms = new ArrayList<>();
            for (Room r : store.rooms.values()) rooms.add(utilisation(r, weeks));
            LocalDate today = Clock.today();
            long from = Rules.at(today.minusDays(7L * weeks), LocalTime.MIDNIGHT), to = Rules.at(today, LocalTime.MIDNIGHT);
            // heat map: day of week (Mon..Sat) x hour (8..19): how many rooms were in use, on average
            double[][] heat = new double[6][12];
            int[] daysCounted = new int[6];
            for (LocalDate d = today.minusDays(7L * weeks); d.isBefore(today); d = d.plusDays(1))
                if (Rules.openDay(d)) daysCounted[d.getDayOfWeek().getValue() - 1]++;
            int total = 0, noShows = 0, cancelled = 0, completed = 0;
            long minutes = 0;
            Map<String, Integer> byOrganiser = new HashMap<>();
            Map<String, Integer> byTeam = new LinkedHashMap<>();
            int[] lengths = new int[5];   // <=30, <=60, <=90, <=120, longer
            for (Booking b : store.bookings.values()) {
                if (b.start < from || b.start >= to) continue;
                total++;
                if (b.status == Status.RELEASED) noShows++;
                if (b.status == Status.CANCELLED) cancelled++;
                if (b.status != Status.COMPLETED) continue;
                completed++;
                minutes += b.minutes();
                byOrganiser.merge(b.organiserId, 1, Integer::sum);
                User o = store.users.get(b.organiserId);
                byTeam.merge(o == null ? "?" : o.team, (int) b.minutes(), Integer::sum);
                long m = b.minutes();
                lengths[m <= 30 ? 0 : m <= 60 ? 1 : m <= 90 ? 2 : m <= 120 ? 3 : 4]++;
                int dow = Rules.dayOf(b.start).getDayOfWeek().getValue() - 1;
                if (dow > 5) continue;
                for (int h = 0; h < 12; h++) {
                    long hs = Rules.at(Rules.dayOf(b.start), LocalTime.of(8 + h, 0)), he = hs + 3_600_000L;
                    long overlap = Math.min(he, b.end) - Math.max(hs, b.start);
                    if (overlap > 0) heat[dow][h] += overlap / 3_600_000.0;
                }
            }
            List<List<Double>> heatOut = new ArrayList<>();
            for (int d = 0; d < 6; d++) {
                List<Double> row = new ArrayList<>();
                for (int h = 0; h < 12; h++) row.add(daysCounted[d] == 0 ? 0 : Math.round(heat[d][h] / daysCounted[d] * 10) / 10.0);
                heatOut.add(row);
            }
            List<Map<String, Object>> top = new ArrayList<>();
            byOrganiser.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6)
                    .forEach(e -> top.add(Json.obj("name", store.userName(e.getKey()), "count", e.getValue())));
            List<Map<String, Object>> teams = new ArrayList<>();
            byTeam.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> teams.add(Json.obj("team", e.getKey(), "minutes", e.getValue())));
            return Json.obj("weeks", weeks, "rooms", rooms, "heat", heatOut, "roomsCount", store.rooms.size(),
                    "totals", Json.obj("bookings", total, "completed", completed, "noShows", noShows, "cancelled", cancelled,
                            "hours", Math.round(minutes / 60.0), "noShowPercent", total == 0 ? 0 : Math.round(noShows * 100.0 / total),
                            "clashesStopped", Json.num(store.meta, "stats.clashesStopped", 0),
                            "minutesGivenBack", Json.num(store.meta, "stats.minutesGivenBack", 0),
                            "waitlistWins", Json.num(store.meta, "stats.waitlistWins", 0)),
                    "top", top, "teams", teams, "lengths", List.of(lengths[0], lengths[1], lengths[2], lengths[3], lengths[4]));
        }
    }

    public Map<String, Object> approvals(User admin) {
        if (!admin.admin()) throw Problem.forbidden("Only the facility manager can see this.");
        synchronized (store) {
            List<Booking> list = new ArrayList<>();
            for (Booking b : store.bookings.values()) if (b.status == Status.PENDING) list.add(b);
            list.sort(Comparator.comparingLong(b -> b.start));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Booking b : list) out.add(booking(b, admin, false));
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (Block bl : store.blocks.values())
                if (bl.active && bl.end > Clock.millis())
                    blocks.add(Json.obj("id", bl.id, "roomId", bl.roomId, "roomName", store.roomName(bl.roomId), "start", bl.start, "end", bl.end,
                            "reason", bl.reason, "by", store.userName(bl.byId)));
            blocks.sort(Comparator.comparingLong(m -> (Long) m.get("start")));
            return Json.obj("pending", out, "blocks", blocks);
        }
    }

    public List<Map<String, Object>> audit(User admin, int max) {
        if (!admin.admin()) throw Problem.forbidden("Only the facility manager can see this.");
        synchronized (store) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = store.audit.size() - 1; i >= 0 && out.size() < max; i--) {
                AuditEntry a = store.audit.get(i);
                out.add(Json.obj("at", a.at(), "who", a.userId() == null ? "—" : store.userName(a.userId()), "action", a.action(),
                        "target", a.target(), "detail", a.detail()));
            }
            return out;
        }
    }

    public List<Map<String, Object>> notes(User viewer) {
        synchronized (store) {
            List<Map<String, Object>> out = new ArrayList<>();
            List<Note> mine = new ArrayList<>();
            for (Note n : store.notes.values()) if (viewer.id.equals(n.userId)) mine.add(n);
            for (int i = mine.size() - 1; i >= 0; i--) out.add(mine.get(i).toJson());
            return out;
        }
    }

    public List<Map<String, Object>> people() {
        synchronized (store) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (User u : store.users.values()) if (u.active) out.add(u.pub());
            out.sort(Comparator.comparing(m -> (String) m.get("name")));
            return out;
        }
    }
}
