package com.cecil.mail.store;

import com.cecil.mail.ai.EmailAiEngine;
import com.cecil.mail.model.Email;
import com.cecil.mail.model.Role;
import com.cecil.mail.model.SavedReply;
import com.cecil.mail.model.Settings;
import com.cecil.mail.model.User;
import com.cecil.mail.util.Clock;
import com.cecil.mail.util.Passwords;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Random;

/**
 * Fills an empty data folder with demo people, inbox emails and some reply history,
 * so every page has real content the first time the app starts.
 * The history replies are produced by the real AI engine (nothing is typed in by hand).
 */
public final class SeedData {

    private SeedData() { }

    public static void seedIfEmpty(DataStore store, EmailAiEngine engine) {
        if (!store.isEmpty()) return;

        store.saveUser(new User("aditi", "Aditi Kumari", "Student Help Desk", Role.AGENT, Passwords.hash("agent123")));
        store.saveUser(new User("rohan", "Rohan Tirkey", "Office of the HoD, IT", Role.AGENT, Passwords.hash("agent123")));
        store.saveUser(new User("admin", "Dr. Meera Lal", "Principal's Office", Role.ADMIN, Passwords.hash("admin123")));
        store.saveSettings(new Settings());

        LocalDateTime now = Clock.localNow().truncatedTo(ChronoUnit.MINUTES);
        Random rnd = new Random(42);           // fixed seed: the same demo data every time

        // Old, already answered emails -> realistic history for the Insights page
        int k = 0;
        for (String[] s : SampleEmails.HISTORY) {
            LocalDateTime when = now.minusDays(3 + (k * 17L) % 18).minusHours(rnd.nextInt(8)).minusMinutes(rnd.nextInt(60));
            Email e = email(s, when, engine);
            e.replied = true;
            store.addEmail(e);

            User u = store.user(e.owner).orElseThrow();
            var best = engine.suggest(e.senderName, e.subject, e.body, null, null, u.displayName, u.title).get(0);
            SavedReply r = new SavedReply();
            r.owner = e.owner;
            r.emailId = e.id;
            r.toName = e.senderName;
            r.toEmail = e.senderEmail;
            r.subject = best.subject();
            r.intent = e.intent;
            r.tone = best.tone();
            r.stance = best.stance();
            r.body = best.body();
            r.professionalism = best.professionalism();
            r.rating = k % 7 == 3 ? -1 : (k % 3 == 0 ? 0 : 1);
            r.source = "OFFLINE";
            r.savedAt = when.plusMinutes(20 + rnd.nextInt(200)).toString();
            store.addReply(r);
            k++;
        }

        // New emails waiting in the inboxes (received over the last ~30 hours)
        int minutes = 0;
        for (String[] s : SampleEmails.ALL) {
            minutes += 35 + rnd.nextInt(90);
            store.addEmail(email(s, now.minusMinutes(minutes), engine));
        }
    }

    private static Email email(String[] s, LocalDateTime when, EmailAiEngine engine) {
        Email e = new Email();
        e.owner = s[0];
        e.senderName = s[1];
        e.senderEmail = s[2];
        e.subject = s[3];
        e.body = s[4];
        e.receivedAt = when.toString();
        var a = engine.analyze(e.subject, e.body);
        e.intent = a.intent();
        e.sentiment = a.sentiment().label();
        e.urgency = a.urgency().level();
        return e;
    }
}
