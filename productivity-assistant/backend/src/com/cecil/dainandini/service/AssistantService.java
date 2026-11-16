package com.cecil.dainandini.service;

import com.cecil.dainandini.ai.ClaudeClient;
import com.cecil.dainandini.ai.EstimateCoach;
import com.cecil.dainandini.ai.IntentClassifier;
import com.cecil.dainandini.ai.Planner;
import com.cecil.dainandini.ai.QuickCapture;
import com.cecil.dainandini.ai.WorkTime;
import com.cecil.dainandini.model.CalEvent;
import com.cecil.dainandini.model.ChatMessage;
import com.cecil.dainandini.model.Task;
import com.cecil.dainandini.model.User;
import com.cecil.dainandini.store.DataStore;
import com.cecil.dainandini.util.Clock;
import com.cecil.dainandini.util.Json;
import com.cecil.dainandini.util.Text;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "ASK DAINANDINI" – the chat assistant.
 *
 *   question ─► a few clear command words? (add …, move …, mark … done)  ─► that intent
 *            └► otherwise the naive Bayes classifier guesses the intent (with a confidence)
 *   intent   ─► the matching skill answers from the person's own data (plan, tasks, calendar, focus history)
 *   not sure ─► Claude (only if the admin switched it on and an API key is set) – else a friendly "try asking …"
 *
 * Every answer says which intent was understood and how sure the classifier was.
 */
public class AssistantService {
    public static final int MAX_TEXT = 500;
    static final Pattern ADD = Pattern.compile("^(?:please\\s+)?(?:add(?:\\s+a)?(?:\\s+task)?|remind me(?:\\s+to)?|create(?:\\s+a)?\\s+task|new task|todo|to do|remember to|note down(?:\\s+to)?|put on my list)\\s*:?\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    static final Pattern MOVE = Pattern.compile("^(?:please\\s+)?(?:move|postpone|push|reschedule|delay|shift|snooze)\\b.*", Pattern.CASE_INSENSITIVE);
    static final Pattern DONE = Pattern.compile("^(?:please\\s+)?(?:mark|tick off|complete|close)\\b.*|.*\\b(?:is|as|are) (?:done|finished)[.!]*$|^(?:i\\s+)?(?:have\\s+|'ve\\s+)?(?:just\\s+)?(?:finished|completed|done with)\\b.*", Pattern.CASE_INSENSITIVE);
    static final Set<String> COMMAND_WORDS = Set.of("move", "postpone", "push", "reschedule", "delay", "shift", "snooze", "mark", "tick",
            "off", "complete", "completed", "close", "done", "finished", "finish", "the", "task", "as", "to", "is", "i", "have", "with",
            "my", "please", "it", "later", "next", "week", "tomorrow", "today", "monday", "tuesday", "wednesday", "thursday", "friday",
            "saturday", "sunday", "until", "till", "for");

    public static final List<String> SUGGESTIONS = List.of("What should I do now?", "Plan my tomorrow", "How much free time do I have today?",
            "What is overdue?", "Add review vendor contract by Friday 3pm ~1h #legal", "How was my week?", "When do I focus best?",
            "Do I underestimate?", "How many meetings this week?");

    private final DataStore store;
    private final Brain brain;
    private final TaskService tasks;
    private final IntentClassifier nb = new IntentClassifier();
    private final ClaudeClient claude;

    public AssistantService(DataStore store, Brain brain, TaskService tasks, ClaudeClient claude) {
        this.store = store; this.brain = brain; this.tasks = tasks; this.claude = claude;
    }

    public boolean claudeReady() { return claude != null && "WHEN_UNSURE".equals(store.setting("claude", "OFF")); }

    public Map<String, Object> history(User u) {
        synchronized (store) {
            List<Object> out = new ArrayList<>();
            for (ChatMessage c : store.chat.values()) if (c.userId.equals(u.id)) out.add(c.toJson());
            return Json.obj("messages", out, "suggestions", SUGGESTIONS, "claude", claudeReady());
        }
    }

    public void clear(User u) {
        synchronized (store) {
            store.chat.values().removeIf(c -> c.userId.equals(u.id));
            store.save("chat");
        }
    }

    /** Saves the question and the answer, and returns both. */
    public List<Object> ask(User u, String text) {
        String q = Text.clean(text, MAX_TEXT);
        if (Text.blank(q)) throw Problem.bad("Type a question first.");
        ChatMessage reply = answer(u, q);
        synchronized (store) {
            ChatMessage me = new ChatMessage();
            me.id = store.nextId("C", 1);
            me.userId = u.id; me.fromUser = true; me.text = q; me.at = Clock.millis();
            reply.id = store.nextId("C", 1);
            reply.userId = u.id; reply.at = Clock.millis();
            store.chat.put(me.id, me);
            store.chat.put(reply.id, reply);
            List<String> mine = new ArrayList<>();
            for (ChatMessage c : store.chat.values()) if (c.userId.equals(u.id)) mine.add(c.id);
            for (int i = 0; i < mine.size() - DataStore.MAX_CHAT_PER_USER; i++) store.chat.remove(mine.get(i));
            store.save("chat");
            return List.of(me.toJson(), reply.toJson());
        }
    }

    /** Works out the answer (also used by the tests). */
    public ChatMessage answer(User u, String q) {
        ChatMessage r = new ChatMessage();
        String intent;
        double conf;
        if (ADD.matcher(q).matches()) { intent = "ADD_TASK"; conf = 1.0; }
        else if (MOVE.matcher(q).matches()) { intent = "MOVE"; conf = 1.0; }
        else if (DONE.matcher(q).matches()) { intent = "DONE"; conf = 1.0; }
        else {
            IntentClassifier.Guess g = nb.classify(q);
            intent = g.intent(); conf = g.confidence();
            if (conf < IntentClassifier.SURE || intent.equals("UNKNOWN")) {
                if (!intent.equals("UNKNOWN")) r.card.put("guesses", top3(g.all()));   // only when it was close, not for off-topic words
                intent = "UNKNOWN";
            }
        }
        r.intent = intent;
        r.confidence = conf;
        try {
            switch (intent) {
                case "ADD_TASK" -> addTask(u, q, r);
                case "WHAT_NOW" -> whatNow(u, r);
                case "PLAN_DAY" -> planDay(u, q, r);
                case "FREE_TIME" -> freeTime(u, q, r);
                case "OVERDUE" -> overdue(u, r);
                case "WEEK_SUMMARY" -> week(u, r);
                case "FOCUS" -> focus(u, r);
                case "MEETINGS" -> meetings(u, r);
                case "ESTIMATES" -> estimates(u, r);
                case "MOVE" -> move(u, q, r);
                case "DONE" -> done(u, q, r);
                case "HELP" -> r.text = "I am your productivity assistant. I plan your day around your meetings, pick the most important task, "
                        + "warn you about deadlines, learn how long your work really takes and when you focus best. Try one of the questions below – "
                        + "or add a task in plain English, like “add call the bank tomorrow 11am”.";
                case "GREETING" -> r.text = q.toLowerCase(Locale.ROOT).contains("thank") ? "You're welcome! Ask me anything about your day."
                        : Brain.greeting(Clock.millis()) + ", " + u.firstName() + "! Ask me “What should I do now?” or “Plan my day”.";
                default -> unknown(u, q, r);
            }
        } catch (Problem p) {
            r.text = p.getMessage();
        }
        return r;
    }

    private static List<Object> top3(Map<String, Double> all) {
        List<Map.Entry<String, Double>> e = new ArrayList<>(all.entrySet());
        e.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < Math.min(3, e.size()); i++) out.add(Json.obj("intent", e.get(i).getKey(), "p", e.get(i).getValue()));
        return out;
    }

    // ------------------------------------------------------------------ skills

    private void addTask(User u, String q, ChatMessage r) {
        var m = ADD.matcher(q);
        String what = m.matches() ? m.group(1) : q;
        Task t = tasks.create(u, Json.obj("text", what));
        Map<String, Object> j;
        synchronized (store) { j = brain.taskJson(t, Clock.millis(), brain.model(u)); }
        StringBuilder sb = new StringBuilder("Added “" + t.title + "”");
        if (!t.ownerId.equals(u.id)) sb.append(" for ").append(store.userName(t.ownerId));
        if (t.due > 0) sb.append(", due ").append(when(t.due, t.dueHasTime));
        sb.append(", about ").append(QuickCapture.duration(t.estimateMin)).append(t.estimateGuessed ? " (my guess)" : "").append('.');
        r.text = sb.toString();
        r.card = Json.obj("type", "tasks", "tasks", List.of(j));
    }

    private void whatNow(User u, ChatMessage r) {
        synchronized (store) {
            long now = Clock.millis();
            Planner.DayPlan p = brain.plan(u, WorkTime.dateOf(now), now);
            Map<String, Object> d = brain.doNow(u, p, now, brain.model(u));
            String kind = String.valueOf(d.get("kind"));
            r.text = switch (kind) {
                case "TASK" -> "Work on “" + d.get("title") + "”" + (d.get("from") != null ? " from " + hm((long) d.get("from")) : "")
                        + " until " + hm((long) d.get("until")) + " – " + d.get("reason") + ".";
                case "FOCUS" -> "You are in a focus session on “" + d.get("title") + "”. Keep going – it ends at " + hm((long) d.get("until")) + ".";
                case "MEETING", "BLOCK" -> "You are in “" + d.get("title") + "” until " + hm((long) d.get("until")) + ".";
                case "LUNCH" -> "It is lunch time until " + hm((long) d.get("until")) + ". Take a real break!";
                default -> d.get("title") + " – " + d.get("reason") + ".";
            };
            r.card = Json.obj("type", "donow", "doNow", d);
        }
    }

    private void planDay(User u, String q, ChatMessage r) {
        synchronized (store) {
            long now = Clock.millis();
            LocalDate day = dayIn(u, q, now);
            Planner.DayPlan p = brain.plan(u, day, now);
            String name = dayName(day, now);
            if (!p.workday) { r.text = name + ": " + p.note + "."; r.card = Json.obj("type", "plan", "plan", brain.planJson(p)); return; }
            int tasksIn = (int) p.blocks.stream().filter(b -> b.type().equals("TASK")).map(Planner.Block::taskId).distinct().count();
            StringBuilder sb = new StringBuilder(name + ": " + QuickCapture.duration(p.meetingMin) + " of meetings, "
                    + QuickCapture.duration(p.focusMin) + " of planned work on " + tasksIn + " task" + (tasksIn == 1 ? "" : "s"));
            if (p.freeMin > 0) sb.append(" and ").append(QuickCapture.duration(p.freeMin)).append(" still free");
            sb.append('.');
            if (!p.unplaced.isEmpty()) sb.append(" ").append(p.unplaced.size()).append(" task").append(p.unplaced.size() == 1 ? " does" : "s do").append(" not fit – see below.");
            r.text = sb.toString();
            r.card = Json.obj("type", "plan", "plan", brain.planJson(p));
        }
    }

    private void freeTime(User u, String q, ChatMessage r) {
        synchronized (store) {
            long now = Clock.millis();
            if (q.toLowerCase(Locale.ROOT).contains("week")) {
                int free = brain.capacity(u, now, 5);
                r.text = "In the next 5 working days you have about " + QuickCapture.duration(free) + " of real focus time (free time outside meetings and lunch, minus 25% for small things).";
                return;
            }
            LocalDate day = dayIn(u, q, now);
            Planner.DayPlan p = brain.plan(u, day, now);
            long biggest = 0, bs = 0, be = 0;
            int free = 0;
            for (Planner.Block b : p.blocks) if (b.type().equals("TASK") || b.type().equals("FREE")) free += (int) ((b.end() - b.start()) / WorkTime.MIN);
            // biggest gap between meetings (planned work counts as free here)
            List<long[]> busy = new ArrayList<>();
            for (Planner.Block b : p.blocks) if (!b.type().equals("TASK") && !b.type().equals("FREE") && !b.type().equals("BREAK")) busy.add(new long[] { b.start(), b.end() });
            long from = day.equals(WorkTime.dateOf(now)) ? Math.max(now, WorkTime.at(day, u.workStart)) : WorkTime.at(day, u.workStart);
            for (long[] s : WorkTime.free(from, WorkTime.at(day, u.workEnd), busy)) if (s[1] - s[0] > biggest) { biggest = s[1] - s[0]; bs = s[0]; be = s[1]; }
            if (!p.workday) { r.text = dayName(day, now) + " is not a working day (" + p.note + ")."; return; }
            r.text = dayName(day, now) + " you have " + QuickCapture.duration(free) + " outside meetings"
                    + (biggest > 0 ? ". The longest gap is " + hm(bs) + "–" + hm(be) + " (" + QuickCapture.duration((int) (biggest / WorkTime.MIN)) + ")." : ".");
            r.card = Json.obj("type", "plan", "plan", brain.planJson(p));
        }
    }

    private void overdue(User u, ChatMessage r) {
        synchronized (store) {
            long now = Clock.millis();
            EstimateCoach.Model m = brain.model(u);
            long endToday = WorkTime.at(WorkTime.dateOf(now).plusDays(1), 0);
            List<Object> late = new ArrayList<>(), today = new ArrayList<>();
            for (Task t : store.openTasksOf(u.id)) {
                if (t.due > 0 && t.due < now) late.add(brain.taskJson(t, now, m));
                else if (t.due > 0 && t.due < endToday) today.add(brain.taskJson(t, now, m));
            }
            List<Planner.Risk> risks = brain.radar(u, now);
            StringBuilder sb = new StringBuilder();
            sb.append(late.isEmpty() ? "Nothing is overdue." : late.size() + " task" + (late.size() == 1 ? " is" : "s are") + " overdue.");
            sb.append(today.isEmpty() ? " Nothing else is due today." : " " + today.size() + " more " + (today.size() == 1 ? "is" : "are") + " due today.");
            long risky = risks.stream().filter(x -> x.task().due >= now).count();
            if (risky > 0) {
                Planner.Risk first = risks.stream().filter(x -> x.task().due >= now).findFirst().get();
                sb.append(" Deadline radar: “").append(first.task().title).append("” needs ").append(QuickCapture.duration(first.shortfall()))
                        .append(" more time than you have before it is due.");
            }
            List<Object> all = new ArrayList<>(late);
            all.addAll(today);
            r.text = sb.toString();
            List<Object> rj = new ArrayList<>();
            for (Planner.Risk x : risks) rj.add(x.toJson());
            r.card = Json.obj("type", "tasks", "tasks", all, "risks", rj);
        }
    }

    private void week(User u, ChatMessage r) {
        Map<String, Object> in = brain.insights(u, 7);
        Map<?, ?> t = (Map<?, ?>) in.get("totals");
        r.text = "Last 7 days: " + t.get("done") + " tasks finished, " + QuickCapture.duration(((Number) t.get("focusMin")).intValue()) + " of focus, "
                + QuickCapture.duration(((Number) t.get("meetingMin")).intValue()) + " in meetings, " + t.get("onTimeRate") + "% of deadlines met. "
                + "Balance score: " + in.get("balance") + "/100.";
        List<?> tips = (List<?>) in.get("tips");
        if (!tips.isEmpty()) r.text += " Tip: " + tips.get(0);
        r.card = Json.obj("type", "week", "labels", in.get("labels"), "focus", in.get("focus"), "meetings", in.get("meetings"), "done", in.get("done"));
    }

    private void focus(User u, ChatMessage r) {
        synchronized (store) {
            long now = Clock.millis();
            var peak = brain.peak(u);
            int[] bw = peak.bestWindow(u);
            int today = brain.focusMinutes(u, WorkTime.at(WorkTime.dateOf(now), 0), now);
            r.text = "Today you focused " + QuickCapture.duration(today) + " of your " + QuickCapture.duration(u.focusGoalMin) + " goal. "
                    + (peak.learned() ? "From your sessions, your best focus time is " : "Until I learn from your sessions, I assume your best time is ")
                    + QuickCapture.hhmm(bw[0]) + "–" + QuickCapture.hhmm(bw[1]) + ". Start a session from any task (the ▶ button).";
            r.card = Json.obj("type", "peak", "peak", peak.hours(), "bestWindow", List.of(bw[0], bw[1]));
        }
    }

    private void meetings(User u, ChatMessage r) {
        synchronized (store) {
            long now = Clock.millis();
            LocalDate today = WorkTime.dateOf(now);
            LocalDate mon = today.minusDays(today.getDayOfWeek().getValue() - 1);
            int total = 0, count = 0, worst = 0;
            LocalDate worstDay = null;
            CalEvent next = null;
            for (int i = 0; i < 7; i++) {
                LocalDate d = mon.plusDays(i);
                int m = 0;
                for (CalEvent e : brain.eventsOn(u, d)) if (e.kind == CalEvent.Kind.MEETING) {
                    m += e.minutes(); count++;
                    if (e.start > now && (next == null || e.start < next.start)) next = e;
                }
                total += m;
                if (m > worst) { worst = m; worstDay = d; }
            }
            StringBuilder sb = new StringBuilder("This week: " + count + " meetings, " + QuickCapture.duration(total) + " in total.");
            if (worstDay != null) sb.append(" The busiest day is ").append(worstDay.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)).append(" (").append(QuickCapture.duration(worst)).append(").");
            if (next != null) sb.append(" Next: “").append(next.title).append("” ").append(when(next.start, true)).append('.');
            r.text = sb.toString();
        }
    }

    private void estimates(User u, ChatMessage r) {
        EstimateCoach.Model m = brain.model(u);
        EstimateCoach.Factor f = m.overall();
        if (f.samples() < 3) { r.text = "I need at least 3 finished tasks with an estimate to learn how long your work takes. So far I have " + f.samples() + "."; return; }
        StringBuilder sb = new StringBuilder();
        long pct = Math.round((f.median() - 1) * 100);
        sb.append("From ").append(f.samples()).append(" finished tasks: ");
        sb.append(pct > 5 ? "your tasks usually take " + pct + "% longer than you estimate" : pct < -5 ? "you usually finish " + (-pct) + "% faster than estimated" : "your estimates are on target");
        sb.append(". I plan with a factor of ×").append(f.value()).append(" (pulled a little towards ×1 to stay careful).");
        m.byTag().forEach((tag, x) -> { if (Math.abs(x.value() - 1) >= 0.15) sb.append(" #").append(tag).append(": ×").append(x.value()).append('.'); });
        r.text = sb.toString();
    }

    private void move(User u, String q, ChatMessage r) {
        Task t = findTask(u, q, r);
        if (t == null) return;
        long now = Clock.millis();
        StringBuilder work = new StringBuilder(q);
        QuickCapture.When w = QuickCapture.findWhen(work, u, now, false);
        LocalDate d = w.date;
        if (d == null) d = q.toLowerCase(Locale.ROOT).contains("next week") ? WorkTime.dateOf(now).plusWeeks(1) : WorkTime.nextWorkday(u, WorkTime.dateOf(now).plusDays(1));
        tasks.update(u, t.id, Json.obj("doDate", d.toString()));
        String text = "Moved “" + t.title + "” to " + dayName(d, now) + ". It will not be planned before then.";
        if (t.due > 0 && WorkTime.dateOf(t.due).isBefore(d)) text += " Careful: its deadline is " + when(t.due, t.dueHasTime) + " – before that day!";
        r.text = text;
        synchronized (store) { r.card = Json.obj("type", "tasks", "tasks", List.of(brain.taskJson(t, now, brain.model(u)))); }
    }

    private void done(User u, String q, ChatMessage r) {
        Task t = findTask(u, q, r);
        if (t == null) return;
        Task next = tasks.done(u, t.id, null);
        r.text = "Well done! “" + t.title + "” is finished" + (t.actualMin > 0 ? " (" + QuickCapture.duration(t.actualMin) + " of real time recorded)" : "") + "."
                + (next != null ? " The next one is due " + when(next.due, next.dueHasTime) + "." : "");
    }

    /** Finds the open task the sentence talks about (word overlap with the title). */
    Task findTask(User u, String q, ChatMessage r) {
        Set<String> words = new java.util.LinkedHashSet<>(QuickCapture.words(q));
        words.removeIf(COMMAND_WORDS::contains);
        synchronized (store) {
            Task best = null; double bestSim = 0;
            for (Task t : store.openTasksOf(u.id)) {
                Set<String> tw = QuickCapture.words(t.title);
                if (tw.isEmpty() || words.isEmpty()) continue;
                int both = 0;
                for (String x : words) if (tw.contains(x) || tw.stream().anyMatch(y -> y.startsWith(x) || x.startsWith(y))) both++;
                double sim = both / (double) Math.max(1, Math.min(words.size(), tw.size()));
                if (sim > bestSim) { bestSim = sim; best = t; }
            }
            if (best == null || bestSim < 0.5) {
                r.text = "Which task do you mean? I could not find an open task with those words.";
                return null;
            }
            return best;
        }
    }

    private void unknown(User u, String q, ChatMessage r) {
        if (claudeReady()) {
            try {
                r.text = claude.ask(q, context(u));
                r.source = "claude";
                r.intent = "CLAUDE";
                return;
            } catch (Exception e) {
                System.err.println("Claude failed: " + e.getMessage());
            }
        }
        r.text = "I am not sure what you mean. I can plan your day, pick your next task, find free time, list deadlines, add tasks and "
                + "explain your focus and meeting patterns. Try one of the questions below.";
    }

    /** What Claude may see: the person's open tasks and the next 3 days of calendar (titles and times only). */
    String context(User u) {
        synchronized (store) {
            long now = Clock.millis();
            StringBuilder sb = new StringBuilder("Now: " + when(now, true) + ". Person: " + u.name + ", works " + QuickCapture.hhmm(u.workStart) + "-" + QuickCapture.hhmm(u.workEnd) + ".\nOpen tasks:\n");
            int n = 0;
            for (Task t : store.openTasksOf(u.id)) {
                if (n++ >= 40) break;
                sb.append("- ").append(t.title).append(" | ").append(t.importance).append(" | est ").append(t.estimateMin).append(" min")
                        .append(t.due > 0 ? " | due " + when(t.due, t.dueHasTime) : "").append('\n');
            }
            sb.append("Calendar (next 3 days):\n");
            for (CalEvent e : store.eventsOf(u.id, now, now + 3 * WorkTime.DAY)) sb.append("- ").append(e.title).append(" ").append(when(e.start, true)).append("-").append(hm(e.end)).append('\n');
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static LocalDate dayIn(User u, String q, long now) {
        StringBuilder sb = new StringBuilder(q);
        QuickCapture.When w = QuickCapture.findWhen(sb, u, now, false);
        return w.date != null ? w.date : WorkTime.dateOf(now);
    }

    static String dayName(LocalDate d, long now) {
        LocalDate t = WorkTime.dateOf(now);
        if (d.equals(t)) return "Today";
        if (d.equals(t.plusDays(1))) return "Tomorrow";
        return d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    static String hm(long ms) { return QuickCapture.hhmm(WorkTime.minuteOf(ms)); }

    static String when(long ms, boolean time) {
        LocalDate d = WorkTime.dateOf(ms);
        String s = d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        return time ? s + " " + hm(ms) : s;
    }

    static Map<String, Object> map() { return new LinkedHashMap<>(); }
}
