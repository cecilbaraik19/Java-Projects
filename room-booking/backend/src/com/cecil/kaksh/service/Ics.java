package com.cecil.kaksh.service;

import com.cecil.kaksh.model.Booking;
import com.cecil.kaksh.model.Room;
import com.cecil.kaksh.store.DataStore;
import com.cecil.kaksh.util.Clock;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes bookings as an iCalendar (.ics) file (RFC 5545), so they can be added to Outlook, Google Calendar or a phone.
 * Times are written in UTC ("Z"), lines end with CRLF, and special characters in text are escaped.
 */
public final class Ics {
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private Ics() { }

    public static String of(DataStore store, List<Booking> list, String viewerId) {
        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//Cecil Baraik//Kaksh Room Booking//EN");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "METHOD:PUBLISH");
        synchronized (store) {
            for (Booking b : list) {
                Room r = store.rooms.get(b.roomId);
                boolean see = !b.privateTitle || b.involves(viewerId);
                line(sb, "BEGIN:VEVENT");
                line(sb, "UID:" + b.id + "@kaksh.local");
                line(sb, "DTSTAMP:" + UTC.format(Instant.ofEpochMilli(Clock.millis())));
                line(sb, "DTSTART:" + UTC.format(Instant.ofEpochMilli(b.start)));
                line(sb, "DTEND:" + UTC.format(Instant.ofEpochMilli(b.end)));
                line(sb, "SUMMARY:" + esc(see ? b.title : "Private meeting"));
                line(sb, "LOCATION:" + esc(r == null ? "" : r.name + " (room " + r.code + ", floor " + r.floor + ")"));
                line(sb, "ORGANIZER;CN=" + esc(store.userName(b.organiserId)) + ":mailto:" + email(store, b.organiserId));
                for (String p : b.people) line(sb, "ATTENDEE;CN=" + esc(store.userName(p)) + ":mailto:" + email(store, p));
                line(sb, "STATUS:" + (b.status.name().equals("PENDING") ? "TENTATIVE" : b.status.open() || b.status.name().equals("COMPLETED") ? "CONFIRMED" : "CANCELLED"));
                line(sb, "DESCRIPTION:" + esc("Booked with Kaksh. Please check in at the room within 15 minutes of the start, or the room is given back."));
                line(sb, "END:VEVENT");
            }
        }
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    private static String email(DataStore store, String id) {
        var u = store.users.get(id);
        return u == null || u.email == null || u.email.isBlank() ? "nobody@kaksh.local" : u.email;
    }

    /** RFC 5545 text escaping: backslash, semicolon, comma and new lines. */
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "").replace("\n", "\\n");
    }

    /** Lines longer than 75 bytes are folded (continued on the next line after a space), as the standard asks. */
    private static void line(StringBuilder sb, String text) {
        int max = 73;
        while (text.length() > max) {
            sb.append(text, 0, max).append("\r\n ");
            text = text.substring(max);
        }
        sb.append(text).append("\r\n");
    }
}
