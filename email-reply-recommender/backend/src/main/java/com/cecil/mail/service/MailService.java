package com.cecil.mail.service;

import com.cecil.mail.ai.ClaudeWriter;
import com.cecil.mail.ai.EmailAiEngine;
import com.cecil.mail.ai.EmailIntent;
import com.cecil.mail.ai.ModelEvaluator;
import com.cecil.mail.ai.ToneChecker;
import com.cecil.mail.model.Email;
import com.cecil.mail.model.Role;
import com.cecil.mail.model.SavedReply;
import com.cecil.mail.model.Settings;
import com.cecil.mail.model.User;
import com.cecil.mail.store.DataStore;
import com.cecil.mail.util.Clock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the app does with email. The web layer (ApiServer) calls these methods.
 * Every method takes the signed-in User, and only ever touches THAT user's emails and replies.
 */
public class MailService {

    public static final int MAX_BODY = 5000;

    private final DataStore store;
    private final EmailAiEngine engine;
    private final ClaudeWriter claude;

    public MailService(DataStore store, EmailAiEngine engine, ClaudeWriter claude) {
        this.store = store;
        this.engine = engine;
        this.claude = claude;
        // examples learned in earlier runs are taught to the model again
        for (DataStore.Learned l : store.learned()) {
            try { engine.learn(null, l.text(), l.intent()); } catch (IllegalArgumentException ignored) { }
        }
    }

    // ------------------------------------------------------------------ inbox

    public List<Email> inbox(User u) { return store.emailsOf(u.username); }

    public Email addEmail(User u, String senderName, String senderEmail, String subject, String body) {
        if (body == null || body.isBlank()) throw new IllegalArgumentException("Paste the message text first.");
        if (body.length() > MAX_BODY) throw new IllegalArgumentException("The message is too long (max " + MAX_BODY + " characters).");
        Email e = new Email();
        e.owner = u.username;
        e.senderName = clip(senderName, 120);
        e.senderEmail = clip(senderEmail, 160);
        e.subject = subject == null || subject.isBlank() ? "(no subject)" : clip(subject, 200);
        e.body = body;
        e.receivedAt = Clock.localNow().truncatedTo(ChronoUnit.SECONDS).toString();
        var a = engine.analyze(e.subject, e.body);
        e.intent = a.intent();
        e.sentiment = a.sentiment().label();
        e.urgency = a.urgency().level();
        if (e.senderName == null) e.senderName = a.entities().senderName();
        return store.addEmail(e);
    }

    public Email email(User u, long id) {
        return store.email(id, u.username).orElseThrow(() -> new NoSuchElementException("That email is not in your inbox."));
    }

    public void deleteEmail(User u, long id) {
        if (!store.deleteEmail(id, u.username)) throw new NoSuchElementException("That email is not in your inbox.");
    }

    // ------------------------------------------------------------------ AI

    public EmailAiEngine.Analysis analyze(User u, long emailId) {
        Email e = email(u, emailId);
        return engine.analyze(e.subject, e.body);
    }

    public Map<String, Object> suggest(User u, long emailId, String intent, String stance) {
        Email e = email(u, emailId);
        if (intent != null && !intent.isBlank()) EmailIntent.valueOf(intent);
        if (stance != null && !stance.isBlank() && !List.of("POSITIVE", "NEED_INFO", "DECLINE").contains(stance)) {
            throw new IllegalArgumentException("Unknown answer type: " + stance);
        }
        List<EmailAiEngine.Suggestion> list = engine.suggest(e.senderName, e.subject, e.body, intent, stance, u.displayName, u.title);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suggestions", list);
        out.put("claudeAvailable", claudeReady());
        return out;
    }

    /** Polishes one draft with Claude. Falls back to the original if Claude is off, fails, or changes the facts. */
    public Map<String, Object> polish(User u, long emailId, String draft, String tone) {
        Email e = email(u, emailId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!claudeReady()) {
            out.put("ok", false);
            out.put("message", "Claude is not switched on. An admin can turn it on in Settings once an API key is set.");
            return out;
        }
        Optional<String> polished = claude.polish(e.subject, e.body, draft, tone == null ? "FORMAL" : tone);
        if (polished.isEmpty()) {
            out.put("ok", false);
            out.put("message", "Claude could not be reached, so your draft is unchanged.");
            return out;
        }
        List<String> lost = missingFacts(draft, polished.get());
        if (!lost.isEmpty()) {
            out.put("ok", false);
            out.put("message", "Claude's version dropped or changed " + String.join(", ", lost) + ", so your draft is unchanged.");
            return out;
        }
        out.put("ok", true);
        out.put("body", polished.get());
        out.put("check", engine.checkTone(polished.get()));
        return out;
    }

    public ToneChecker.Report checkTone(String draft) {
        if (draft != null && draft.length() > 6000) throw new IllegalArgumentException("The reply is too long.");
        return engine.checkTone(draft);
    }

    /**
     * Fact guard for Claude: every number, amount, date-like token and reference in the draft
     * (for example 45821, Rs 18,500, 11:30) must still be in the polished version.
     */
    public static List<String> missingFacts(String draft, String polished) {
        List<String> lost = new ArrayList<>();
        Matcher m = Pattern.compile("[#₹$]?\\d[\\d,:./-]*\\d|\\b\\d\\b").matcher(draft == null ? "" : draft);
        String p = polished == null ? "" : polished.replace(" ", "");
        while (m.find()) {
            String fact = m.group().replaceAll("[.,]$", "");
            if (!p.contains(fact.replace(" ", "")) && !lost.contains(fact)) lost.add(fact);
        }
        return lost;
    }

    // ------------------------------------------------------------------ saved replies

    public List<SavedReply> replies(User u) { return store.repliesOf(u.username); }

    public SavedReply saveReply(User u, long emailId, String subject, String intent, String tone, String stance,
                                String body, int rating, String source) {
        Email e = email(u, emailId);
        if (body == null || body.isBlank()) throw new IllegalArgumentException("The reply is empty.");
        if (body.length() > 6000) throw new IllegalArgumentException("The reply is too long.");
        if (intent != null) EmailIntent.valueOf(intent);
        SavedReply r = new SavedReply();
        r.owner = u.username;
        r.emailId = e.id;
        r.toName = e.senderName;
        r.toEmail = e.senderEmail;
        r.subject = clip(subject, 220);
        r.intent = intent == null ? e.intent : intent;
        r.tone = List.of("FORMAL", "FRIENDLY", "BRIEF").contains(tone) ? tone : "FORMAL";
        r.stance = stance;
        r.body = body;
        r.professionalism = engine.checkTone(body).score();
        r.rating = Math.max(-1, Math.min(1, rating));
        r.source = "CLAUDE".equals(source) ? "CLAUDE" : "OFFLINE";
        r.savedAt = Clock.localNow().truncatedTo(ChronoUnit.SECONDS).toString();
        store.addReply(r);

        e.replied = true;
        if (r.intent != null && !r.intent.equals(e.intent)) e.intent = r.intent;   // the user corrected the type
        store.updateEmail(e);
        if (r.rating == 1) learnFrom(e, r.intent);
        return r;
    }

    public SavedReply rate(User u, long replyId, int rating) {
        SavedReply r = store.reply(replyId, u.username).orElseThrow(() -> new NoSuchElementException("Reply not found."));
        int old = r.rating;
        r.rating = Math.max(-1, Math.min(1, rating));
        store.updateReply(r);
        if (r.rating == 1 && old != 1) store.email(r.emailId, u.username).ifPresent(e -> learnFrom(e, r.intent));
        return r;
    }

    public void deleteReply(User u, long id) {
        if (!store.deleteReply(id, u.username)) throw new NoSuchElementException("Reply not found.");
    }

    /** A reply marked useful = the user confirms this email's type. Teach it to the model. */
    private void learnFrom(Email e, String intent) {
        if (!store.settings().learnFromFeedback || intent == null) return;
        String text = (e.subject == null ? "" : e.subject + ". ") + e.body;
        engine.learn(null, text, intent);
        store.addLearned(new DataStore.Learned(text, intent, Clock.localNow().truncatedTo(ChronoUnit.SECONDS).toString()));
    }

    // ------------------------------------------------------------------ insights

    /** Numbers for the Insights page. Agents see their own inbox; admins can ask for the whole office. */
    public Map<String, Object> insights(User u, boolean wholeOffice) {
        boolean all = wholeOffice && u.role == Role.ADMIN;
        List<Email> emails = all ? store.allEmails() : store.emailsOf(u.username);
        List<SavedReply> replies = all ? store.allReplies() : store.repliesOf(u.username);

        Map<String, Long> byIntent = new LinkedHashMap<>();
        for (EmailIntent i : EmailIntent.values()) byIntent.put(i.getLabel(), 0L);
        Map<String, Long> bySentiment = new LinkedHashMap<>();
        for (String s : List.of("POSITIVE", "NEUTRAL", "NEGATIVE")) bySentiment.put(s, 0L);
        Map<String, Long> byTone = new LinkedHashMap<>();
        for (String s : List.of("FORMAL", "FRIENDLY", "BRIEF")) byTone.put(s, 0L);

        for (Email e : emails) {
            if (e.intent != null) byIntent.merge(EmailIntent.valueOf(e.intent).getLabel(), 1L, Long::sum);
            if (e.sentiment != null) bySentiment.merge(e.sentiment, 1L, Long::sum);
        }
        long helpful = 0, notHelpful = 0, profSum = 0;
        for (SavedReply r : replies) {
            byTone.merge(r.tone, 1L, Long::sum);
            if (r.rating == 1) helpful++;
            if (r.rating == -1) notHelpful++;
            profSum += r.professionalism;
        }

        // replies per day for the last 14 days
        LocalDate today = Clock.localNow().toLocalDate();
        Map<String, Long> perDay = new LinkedHashMap<>();
        for (int d = 13; d >= 0; d--) perDay.put(today.minusDays(d).toString(), 0L);
        for (SavedReply r : replies) {
            String day = r.savedAt.substring(0, 10);
            if (perDay.containsKey(day)) perDay.merge(day, 1L, Long::sum);
        }

        // average time to reply (minutes) for answered emails
        long totalMins = 0, answered = 0;
        for (SavedReply r : replies) {
            Optional<Email> e = emails.stream().filter(x -> x.id == r.emailId).findFirst();
            if (e.isEmpty()) continue;
            long mins = ChronoUnit.MINUTES.between(LocalDateTime.parse(e.get().receivedAt), LocalDateTime.parse(r.savedAt));
            if (mins >= 0) { totalMins += mins; answered++; }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", all ? "office" : "mine");
        m.put("totalEmails", emails.size());
        m.put("waiting", emails.stream().filter(e -> !e.replied).count());
        m.put("urgentWaiting", emails.stream().filter(e -> !e.replied && "HIGH".equals(e.urgency)).count());
        m.put("savedReplies", replies.size());
        m.put("helpful", helpful);
        m.put("notHelpful", notHelpful);
        m.put("avgProfessionalism", replies.isEmpty() ? 0 : Math.round((double) profSum / replies.size()));
        m.put("avgReplyMinutes", answered == 0 ? 0 : totalMins / answered);
        m.put("byIntent", byIntent);
        m.put("bySentiment", bySentiment);
        m.put("byTone", byTone);
        m.put("perDay", perDay);
        m.put("model", engine.modelInfo());
        return m;
    }

    // ------------------------------------------------------------------ admin

    public ModelEvaluator.Report testBench() { return ModelEvaluator.run(engine); }

    public Settings settings() { return store.settings(); }

    public Settings saveSettings(String organisation, Boolean claudeEnabled, Boolean learn) {
        Settings s = store.settings();
        Settings n = new Settings();
        n.organisation = organisation == null || organisation.isBlank() ? s.organisation : clip(organisation, 100);
        n.claudeEnabled = claudeEnabled == null ? s.claudeEnabled : claudeEnabled;
        n.learnFromFeedback = learn == null ? s.learnFromFeedback : learn;
        store.saveSettings(n);
        return n;
    }

    public boolean claudeReady() { return store.settings().claudeEnabled && claude.hasKey(); }

    public boolean claudeHasKey() { return claude.hasKey(); }

    public String claudeModel() { return claude.model(); }

    public List<Map<String, Object>> intents() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EmailIntent i : EmailIntent.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.name());
            m.put("label", i.getLabel());
            m.put("description", i.getDescription());
            out.add(m);
        }
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) return null;
        s = s.trim().replaceAll("[\\r\\n]+", " ");
        return s.length() > max ? s.substring(0, max) : s;
    }
}
