package com.cecil.dainandini.ai;

import com.cecil.dainandini.model.Energy;
import com.cecil.dainandini.model.Importance;
import com.cecil.dainandini.model.User;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QUICK CAPTURE – understands one line of plain English and turns it into a task.
 *
 *   "Budget report for Q3 @meera by Friday 5pm ~45m #finance !!"
 *      title      = Budget report for Q3
 *      due        = this Friday 17:00          person   = meera (a team lead hands the task to Meera)
 *      estimate   = 45 min                     tags     = finance
 *      importance = HIGH                       energy   = SHALLOW ("send" is quick work)
 *
 * How: a list of patterns (regular expressions) is tried one after another. Each piece of text that is understood
 * is cut out of the line, so it is not read twice, and whatever is left over becomes the title.
 * "@name" after "to / with / for / from / cc" ("send it to @meera") only names the person inside the sentence;
 * on its own ("fix the crash @ravi") it means "this task is for Ravi".
 * This is a rule-based "named entity" finder – it works offline, is fast, and every result can be explained.
 */
public final class QuickCapture {
    private QuickCapture() { }

    /** One thing that was understood, shown to the person as a small chip ("Due: Fri 17:00"). */
    public record Chip(String kind, String label) {
        public Map<String, Object> toJson() { return Map.of("kind", kind, "label", label); }
    }

    /** A date and/or time found in a sentence. */
    public static final class When {
        public LocalDate date;
        public int minute = -1;          // -1 = no time of day
        public String dateText, timeText;
        public boolean hasAny() { return date != null || minute >= 0; }
    }

    public static final class Result {
        public String title = "";
        /** The title with "@meera" kept as the plain word "Meera" (used when the task is not handed over). */
        public String titleWithMention = "";
        public long due;
        public boolean dueHasTime;
        public Importance importance;     // null = not said
        public int estimateMin;           // 0 = not said
        public final List<String> tags = new ArrayList<>();
        public String mention;            // "meera" from "@meera"
        /** true for "send it to @meera" (Meera is part of the sentence, the task is not for her). */
        public boolean mentionInSentence;
        public String recurrence = "";
        public Energy energy = Energy.SHALLOW;
        public String energyWord;         // the verb that decided it, or null
        public final List<Chip> chips = new ArrayList<>();
    }

    static final int F = Pattern.CASE_INSENSITIVE;
    static final String MONTHS = "(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?";
    static final String DAYS = "(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun)";
    static final String NUMWORD = "(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten)";
    static final String PRE = "(?:\\b(?:by|on|due|before|until|till|for)\\s+)?";

    static final Pattern TAG = Pattern.compile("(?<![\\w#])#([\\p{L}\\d_-]{1,30})");
    static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([A-Za-z][\\w.]{0,30})");
    static final Pattern[] ESTIMATE = {
            Pattern.compile("~?\\s*\\b(\\d{1,2})\\s*h\\s*(\\d{1,2})\\s*m(?:in)?\\b", F),
            Pattern.compile("~\\s*(\\d+(?:\\.\\d+)?)\\s*(h|hr|hrs|hours?|m|min|mins|minutes?)\\b", F),
            Pattern.compile("\\b(?:for|takes?|about|approx\\.?|around)\\s+(\\d+(?:\\.\\d+)?)\\s*(h|hr|hrs|hours?|m|min|mins|minutes?)\\b", F),
            Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*(hrs?|hours?|mins?|minutes?)\\b", F),
            Pattern.compile("\\b(?:for\\s+|takes?\\s+|about\\s+)?(half an hour|an hour|a couple of hours)\\b", F),
    };
    static final Pattern[] REPEAT = {
            Pattern.compile("\\b(every\\s*day|daily|everyday)\\b", F),
            Pattern.compile("\\b(every\\s+weekday|every\\s+working\\s+day|on\\s+weekdays)\\b", F),
            Pattern.compile("\\bevery\\s+" + DAYS + "\\b", F),
            Pattern.compile("\\b(every\\s+week|weekly)\\b", F),
            Pattern.compile("\\b(every\\s+month|monthly)\\b", F),
    };
    static final Pattern BANGS = Pattern.compile("(?:^|(?<=\\s))(!{1,3})(?=\\s|$)");
    static final Pattern BANG_WORD = Pattern.compile("(?<!\\w)!(high|hi|med|medium|low|lo)\\b", F);
    static final Pattern P_LEVEL = Pattern.compile("\\bp([1-4])\\b", F);
    static final Pattern HIGH_CUT = Pattern.compile("\\b(high priority|top priority|asap|a\\.s\\.a\\.p\\.?|urgently|urgent)\\b", F);
    static final Pattern HIGH_KEEP = Pattern.compile("\\b(important|critical)\\b", F);
    static final Pattern LOW_CUT = Pattern.compile("\\b(low priority|no rush|not urgent|someday|whenever)\\b", F);

    // dates (the order matters: longer, more exact patterns first)
    static final Pattern D_ISO = Pattern.compile(PRE + "\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b", F);
    static final Pattern D_DAY_MONTH = Pattern.compile(PRE + "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + MONTHS + "(?:\\s+(\\d{4}))?(?!\\w)", F);
    static final Pattern D_MONTH_DAY = Pattern.compile(PRE + "\\b" + MONTHS + "\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b", F);
    static final Pattern D_SLASH = Pattern.compile(PRE + "\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b", F);
    static final Pattern D_AFTER_TMRW = Pattern.compile(PRE + "\\b(day after tomorrow)\\b", F);
    static final Pattern D_TODAY = Pattern.compile(PRE + "\\b(today|tonight|eod|end of (?:the )?day)\\b", F);
    static final Pattern D_TMRW = Pattern.compile(PRE + "\\b(tomorrow|tmrw|tmr|tomorow)\\b", F);
    static final Pattern D_IN = Pattern.compile("\\bin\\s+" + NUMWORD + "\\s+(days?|weeks?|months?)\\b", F);
    static final Pattern D_EOW = Pattern.compile(PRE + "\\b(end of (?:the |this )?week|eow|this weekend|the weekend|weekend)\\b", F);
    static final Pattern D_EOM = Pattern.compile(PRE + "\\b(end of (?:the |this )?month|eom)\\b", F);
    static final Pattern D_NEXT_WEEK = Pattern.compile(PRE + "\\b(next week)\\b", F);
    static final Pattern D_NEXT_MONTH = Pattern.compile(PRE + "\\b(next month)\\b", F);
    static final Pattern D_WEEKDAY = Pattern.compile(PRE + "\\b(next\\s+|this\\s+|coming\\s+)?" + DAYS + "\\b", F);

    // times
    static final Pattern T_AMPM = Pattern.compile("(?:\\b(?:at|by|before)\\s+|@\\s*)?\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)", F);
    static final Pattern T_24 = Pattern.compile("(?:\\b(?:at|by|before)\\s+)?\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b", F);
    static final Pattern T_WORD = Pattern.compile("(?:\\b(?:at|by|in the|this)\\s+)?\\b(noon|midday|lunchtime|lunch time|morning|afternoon|evening|midnight)\\b", F);
    static final Pattern T_AT = Pattern.compile("\\b(?:at|by)\\s+(\\d{1,2})\\b(?!\\s*(?:%|/|-|\\.\\d|days?|weeks?|months?))", F);

    static final Set<String> DEEP_VERBS = Set.of("write", "draft", "design", "build", "code", "develop", "analyse", "analyze",
            "research", "study", "prepare", "plan", "create", "refactor", "fix", "debug", "outline", "model", "review",
            "learn", "test", "architect", "implement", "rewrite", "redesign", "brainstorm", "think", "audit");
    static final Set<String> SHALLOW_VERBS = Set.of("email", "mail", "reply", "call", "ring", "phone", "book", "schedule",
            "pay", "send", "submit", "print", "sign", "file", "update", "remind", "check", "order", "share", "forward",
            "ask", "buy", "renew", "upload", "download", "approve", "confirm", "invite", "message", "text", "ping",
            "collect", "post", "follow", "cancel", "register", "attend");
    static final Set<String> DANGLING = Set.of("by", "on", "at", "due", "before", "until", "till", "for", "every", "in", "the", "and", "&", "-", ",", "–", "about");

    /** Reads one line. me = the person (for working hours), now = current time in ms. */
    public static Result parse(String text, User me, long now) {
        Result r = new Result();
        if (text == null) text = "";
        String line = text.replace('\n', ' ').trim();
        if (line.length() > 300) line = line.substring(0, 300);
        StringBuilder work = new StringBuilder(line);

        // tags
        Matcher m = TAG.matcher(work);
        while (m.find()) {
            String t = m.group(1).toLowerCase(Locale.ROOT);
            if (!r.tags.contains(t) && r.tags.size() < 6) { r.tags.add(t); r.chips.add(new Chip("tag", "#" + t)); }
            blank(work, m.start(), m.end());
        }
        // a person
        int mentionAt = -1;
        m = MENTION.matcher(work);
        if (m.find()) {
            r.mention = m.group(1).replaceAll("\\.+$", "").toLowerCase(Locale.ROOT);
            mentionAt = m.start();
            String[] before = work.substring(0, m.start()).trim().toLowerCase(Locale.ROOT).split("\\s+");
            r.mentionInSentence = Set.of("to", "with", "for", "from", "cc", "and", "by", "ask", "tell", "remind", "call", "email", "ping", "meet")
                    .contains(before[before.length - 1]);
            blank(work, m.start(), m.end());
            r.chips.add(new Chip("person", "@" + r.mention));
        }
        // how long it takes
        for (int i = 0; i < ESTIMATE.length && r.estimateMin == 0; i++) {
            m = ESTIMATE[i].matcher(work);
            if (!m.find()) continue;
            int min;
            if (i == 0) min = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
            else if (i == 4) {
                String w = m.group(1).toLowerCase(Locale.ROOT);
                min = w.startsWith("half") ? 30 : w.startsWith("a couple") ? 120 : 60;
            } else {
                double v = Double.parseDouble(m.group(1));
                String unit = m.group(2).toLowerCase(Locale.ROOT);
                min = (int) Math.round(unit.startsWith("h") ? v * 60 : v);
            }
            if (min >= 5 && min <= 16 * 60) {
                r.estimateMin = min;
                r.chips.add(new Chip("estimate", "~" + duration(min)));
                blank(work, m.start(), m.end());
               
            }
        }
        // repeating
        DayOfWeek repeatDay = null;
        for (int i = 0; i < REPEAT.length && r.recurrence.isEmpty(); i++) {
            m = REPEAT[i].matcher(work);
            if (!m.find()) continue;
            switch (i) {
                case 0 -> r.recurrence = "DAILY";
                case 1 -> r.recurrence = "WEEKDAYS";
                case 2 -> { repeatDay = dayOf(m.group(1)); r.recurrence = "WEEKLY:" + repeatDay.getValue(); }
                case 3 -> r.recurrence = "WEEKLY:0";   // filled in below
                default -> r.recurrence = "MONTHLY";
            }
            blank(work, m.start(), m.end());
           
        }
        // importance
        m = BANG_WORD.matcher(work);
        if (m.find()) {
            String w = m.group(1).toLowerCase(Locale.ROOT);
            r.importance = w.startsWith("h") ? Importance.HIGH : w.startsWith("l") ? Importance.LOW : Importance.MEDIUM;
            blank(work, m.start(), m.end());
        }
        if (r.importance == null) {
            m = BANGS.matcher(work);
            if (m.find()) { r.importance = Importance.HIGH; blank(work, m.start(), m.end()); }
        }
        if (r.importance == null) {
            m = P_LEVEL.matcher(work);
            if (m.find()) {
                int p = Integer.parseInt(m.group(1));
                r.importance = p == 1 ? Importance.HIGH : p == 2 ? Importance.MEDIUM : Importance.LOW;
                blank(work, m.start(), m.end());
            }
        }
        if (r.importance == null) {
            m = HIGH_CUT.matcher(work);
            if (m.find()) { r.importance = Importance.HIGH; blank(work, m.start(), m.end()); }
        }
        if (r.importance == null) {
            m = LOW_CUT.matcher(work);
            if (m.find()) { r.importance = Importance.LOW; blank(work, m.start(), m.end()); }
        }
        if (r.importance == null && HIGH_KEEP.matcher(work).find()) r.importance = Importance.HIGH;   // the word stays in the title
        if (r.importance != null) r.chips.add(new Chip("priority", r.importance.name().charAt(0) + r.importance.name().substring(1).toLowerCase(Locale.ROOT)));

        // date and time
        When w = findWhen(work, me, now, true);
        LocalDate today = WorkTime.dateOf(now);
        if (!w.hasAny() && repeatDay != null) w.date = nextOrSame(today, repeatDay);
        if (!w.hasAny() && r.recurrence.equals("DAILY")) w.date = today;
        if (!w.hasAny() && r.recurrence.equals("WEEKDAYS")) w.date = WorkTime.nextWorkday(me, today);
        if (w.hasAny()) {
            if (w.date == null) {
                // only a time: today if it is still ahead, else tomorrow
                w.date = WorkTime.at(today, w.minute) > now ? today : today.plusDays(1);
            }
            r.dueHasTime = w.minute >= 0;
            r.due = WorkTime.at(w.date, r.dueHasTime ? w.minute : me.workEnd);
            r.chips.add(new Chip("due", "Due " + w.date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " "
                    + w.date.getDayOfMonth() + " " + w.date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    + (r.dueHasTime ? " " + hhmm(w.minute) : "")));
        }
        if (r.recurrence.equals("WEEKLY:0")) {
            LocalDate d = w.date != null ? w.date : today;
            r.recurrence = "WEEKLY:" + d.getDayOfWeek().getValue();
        }
        if (!r.recurrence.isEmpty()) r.chips.add(new Chip("repeat", repeatLabel(r.recurrence)));

        r.title = tidy(work.toString());
        StringBuilder with = new StringBuilder(work);
        if (mentionAt >= 0) with.replace(mentionAt, mentionAt + r.mention.length(), capital(r.mention));
        r.titleWithMention = tidy(with.toString());
        if (r.title.isEmpty()) r.title = r.titleWithMention.isEmpty() ? "Untitled task" : r.titleWithMention;
        if (r.titleWithMention.isEmpty()) r.titleWithMention = r.title;

        // deep or shallow work? Look at the words of the title.
        for (String word : r.title.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (DEEP_VERBS.contains(word)) { r.energy = Energy.DEEP; r.energyWord = word; break; }
            if (SHALLOW_VERBS.contains(word)) { r.energy = Energy.SHALLOW; r.energyWord = word; break; }
        }
        if (r.energyWord == null && r.estimateMin >= 45) r.energy = Energy.DEEP;
        r.chips.add(new Chip("energy", r.energy == Energy.DEEP ? "Deep work" : "Quick work"));
        return r;
    }

    /**
     * Finds a date and/or a time in the text (also used by the assistant: "move the report to Friday").
     * When cut = true the words found are blanked out of the text.
     */
    public static When findWhen(StringBuilder work, User me, long now, boolean cut) {
        When w = new When();
        LocalDate today = WorkTime.dateOf(now);
        Matcher m;
        // ---- dates
        if ((m = D_ISO.matcher(work)).find()) {
            w.date = safeDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            done(work, m, w, cut, true);
        } else if ((m = D_DAY_MONTH.matcher(work)).find()) {
            w.date = absolute(today, Integer.parseInt(m.group(1)), month(m.group(2)), m.group(3));
            done(work, m, w, cut, true);
        } else if ((m = D_MONTH_DAY.matcher(work)).find()) {
            w.date = absolute(today, Integer.parseInt(m.group(2)), month(m.group(1)), m.group(3));
            done(work, m, w, cut, true);
        } else if ((m = D_SLASH.matcher(work)).find()) {
            w.date = absolute(today, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3));   // Indian order: day/month
            done(work, m, w, cut, true);
        } else if ((m = D_AFTER_TMRW.matcher(work)).find()) {
            w.date = today.plusDays(2); done(work, m, w, cut, true);
        } else if ((m = D_TODAY.matcher(work)).find()) {
            w.date = today;
            String word = m.group(1).toLowerCase(Locale.ROOT);
            if (word.equals("tonight")) w.minute = 20 * 60;
            if (word.startsWith("eod") || word.startsWith("end")) w.minute = me.workEnd;
            done(work, m, w, cut, true);
        } else if ((m = D_TMRW.matcher(work)).find()) {
            w.date = today.plusDays(1); done(work, m, w, cut, true);
        } else if ((m = D_IN.matcher(work)).find()) {
            int n = numberWord(m.group(1));
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            w.date = unit.startsWith("day") ? today.plusDays(n) : unit.startsWith("week") ? today.plusWeeks(n) : today.plusMonths(n);
            done(work, m, w, cut, true);
        } else if ((m = D_EOW.matcher(work)).find()) {
            String word = m.group(1).toLowerCase(Locale.ROOT);
            w.date = word.contains("weekend") ? nextOrSame(today, DayOfWeek.SATURDAY) : nextOrSame(today, DayOfWeek.FRIDAY);
            done(work, m, w, cut, true);
        } else if ((m = D_EOM.matcher(work)).find()) {
            w.date = today.with(TemporalAdjusters.lastDayOfMonth()); done(work, m, w, cut, true);
        } else if ((m = D_NEXT_WEEK.matcher(work)).find()) {
            w.date = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)); done(work, m, w, cut, true);
        } else if ((m = D_NEXT_MONTH.matcher(work)).find()) {
            w.date = today.plusMonths(1).withDayOfMonth(1); done(work, m, w, cut, true);
        } else if ((m = D_WEEKDAY.matcher(work)).find()) {
            DayOfWeek dow = dayOf(m.group(2));
            LocalDate d = nextOrSame(today, dow);
            boolean next = m.group(1) != null && m.group(1).trim().equalsIgnoreCase("next");
            // "next Friday": the Friday of next week (when this week's Friday is still to come, skip it)
            if (next && !d.isAfter(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)))) d = d.plusWeeks(1);
            w.date = d;
            done(work, m, w, cut, true);
        }
        // ---- times
        if ((m = T_AMPM.matcher(work)).find()) {
            int hh = Integer.parseInt(m.group(1)) % 12, mm = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if (m.group(3).toLowerCase(Locale.ROOT).startsWith("p")) hh += 12;
            if (hh < 24 && mm < 60) { w.minute = hh * 60 + mm; done(work, m, w, cut, false); }
        } else if ((m = T_24.matcher(work)).find()) {
            w.minute = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)); done(work, m, w, cut, false);
        } else if ((m = T_WORD.matcher(work)).find()) {
            String word = m.group(1).toLowerCase(Locale.ROOT);
            w.minute = switch (word) {
                case "noon", "midday" -> 12 * 60;
                case "lunchtime", "lunch time" -> me.lunchStart;
                case "morning" -> 11 * 60;
                case "afternoon" -> 15 * 60;
                case "evening" -> Math.max(17 * 60, me.workEnd);
                default -> 23 * 60 + 59;
            };
            done(work, m, w, cut, false);
        } else if ((m = T_AT.matcher(work)).find()) {
            int hh = Integer.parseInt(m.group(1));
            if (hh >= 1 && hh <= 7) hh += 12;     // "at 3" in an office means 15:00
            if (hh < 24) { w.minute = hh * 60; done(work, m, w, cut, false); }
        }
        return w;
    }

    // ------------------------------------------------------------------ helpers

    private static void done(StringBuilder work, Matcher m, When w, boolean cut, boolean date) {
        if (date) w.dateText = m.group(); else w.timeText = m.group();
        if (cut) blank(work, m.start(), m.end());
    }

    /** Replaces a piece of the text with spaces (so positions stay the same). */
    static void blank(StringBuilder sb, int s, int e) { for (int i = s; i < e; i++) sb.setCharAt(i, ' '); }

    /** Joins spaces, removes words left hanging at the ends ("by", "on", "at"), first letter in capitals. */
    static String tidy(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        t = t.replaceAll("\\s+([,.;:!?])", "$1");
        boolean changed = true;
        while (changed && !t.isEmpty()) {
            changed = false;
            String[] words = t.split(" ");
            if (words.length > 0 && DANGLING.contains(words[words.length - 1].toLowerCase(Locale.ROOT).replaceAll("[,.;:]$", ""))) {
                t = t.substring(0, Math.max(0, t.length() - words[words.length - 1].length())).trim(); changed = true;
            } else if (words.length > 1 && DANGLING.contains(words[0].toLowerCase(Locale.ROOT)) && !words[0].equalsIgnoreCase("the")) {
                t = t.substring(words[0].length()).trim(); changed = true;
            }
            t = t.replaceAll("[,;:\\-–]+$", "").trim();
        }
        // "for" left inside, like "Book tickets for  ," is fine; remove doubled prepositions such as "by on"
        t = t.replaceAll("\\b(by|on|at|due)\\s+(by|on|at|due)\\b", "$1");
        if (t.isEmpty()) return t;
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    static DayOfWeek dayOf(String w) {
        String s = w.toLowerCase(Locale.ROOT).substring(0, 3);
        return switch (s) {
            case "mon" -> DayOfWeek.MONDAY; case "tue" -> DayOfWeek.TUESDAY; case "wed" -> DayOfWeek.WEDNESDAY;
            case "thu" -> DayOfWeek.THURSDAY; case "fri" -> DayOfWeek.FRIDAY; case "sat" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    static LocalDate nextOrSame(LocalDate d, DayOfWeek dow) { return d.with(TemporalAdjusters.nextOrSame(dow)); }

    static int month(String w) {
        String s = w.toLowerCase(Locale.ROOT).substring(0, 3);
        String all = "janfebmaraprmayjunjulaugsepoctnovdec";
        return all.indexOf(s) / 3 + 1;
    }

    static int numberWord(String w) {
        return switch (w.toLowerCase(Locale.ROOT)) {
            case "a", "an", "one" -> 1; case "two" -> 2; case "three" -> 3; case "four" -> 4; case "five" -> 5;
            case "six" -> 6; case "seven" -> 7; case "eight" -> 8; case "nine" -> 9; case "ten" -> 10;
            default -> Math.min(365, Integer.parseInt(w));
        };
    }

    /** A day and month without a year means the next time it comes (3 Jan in November = next January). */
    static LocalDate absolute(LocalDate today, int day, int month, String yearText) {
        if (yearText != null) {
            int y = Integer.parseInt(yearText);
            if (y < 100) y += 2000;
            return safeDate(y, month, day);
        }
        LocalDate d = safeDate(today.getYear(), month, day);
        if (d != null && d.isBefore(today)) d = safeDate(today.getYear() + 1, month, day);
        return d;
    }

    static LocalDate safeDate(int y, int m, int d) {
        try { return LocalDate.of(y, m, d); } catch (RuntimeException e) { return null; }
    }

    public static String hhmm(int minute) { return String.format("%02d:%02d", minute / 60, minute % 60); }

    public static String duration(int min) {
        if (min < 60) return min + " min";
        int h = min / 60, m = min % 60;
        return h + " h" + (m > 0 ? " " + m + " min" : "");
    }

    public static String repeatLabel(String rec) {
        if (rec == null || rec.isEmpty()) return "";
        if (rec.equals("DAILY")) return "Every day";
        if (rec.equals("WEEKDAYS")) return "Every weekday";
        if (rec.equals("MONTHLY")) return "Every month";
        if (rec.startsWith("WEEKLY:")) {
            int d = Integer.parseInt(rec.substring(7));
            return "Every " + DayOfWeek.of(Math.max(1, Math.min(7, d))).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        }
        return rec;
    }

    /** The next date of a repeating task after "from". */
    public static LocalDate nextRepeat(String rec, LocalDate from, User u) {
        if (rec.equals("DAILY")) return from.plusDays(1);
        if (rec.equals("WEEKDAYS")) return WorkTime.nextWorkday(u, from.plusDays(1));
        if (rec.equals("MONTHLY")) return from.plusMonths(1);
        if (rec.startsWith("WEEKLY:")) {
            int d = Integer.parseInt(rec.substring(7));
            return from.with(TemporalAdjusters.next(DayOfWeek.of(Math.max(1, Math.min(7, d)))));
        }
        return null;
    }

    /** Words of a title (lower case, without very common words) – used to compare titles. */
    public static Set<String> words(String title) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : title.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            if (w.length() > 2 && !STOP.contains(w)) out.add(w);
        return out;
    }

    static final Set<String> STOP = Set.of("the", "and", "for", "with", "from", "into", "about", "this", "that", "our", "your",
            "new", "all", "are", "was", "will", "can", "get", "out", "per", "via", "off", "too", "its", "has", "have");
}
