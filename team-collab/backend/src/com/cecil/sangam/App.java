package com.cecil.sangam;

import com.cecil.sangam.core.EventBus;
import com.cecil.sangam.service.AuthService;
import com.cecil.sangam.service.ChatService;
import com.cecil.sangam.service.DocService;
import com.cecil.sangam.service.MeetingService;
import com.cecil.sangam.service.PeopleService;
import com.cecil.sangam.service.RhythmService;
import com.cecil.sangam.service.ViewService;
import com.cecil.sangam.service.WorkService;
import com.cecil.sangam.store.DataStore;
import com.cecil.sangam.store.SeedData;
import com.cecil.sangam.util.Clock;
import com.cecil.sangam.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sangam (संगम, "where rivers meet") – Remote Team Collaboration Platform. Starts everything:
 *
 *   1. find the frontend folder (website files) and the data folder
 *   2. load the saved data, or build the demo workspace on the very first start
 *   3. start the background job (every 30 seconds): due-date reminders, call reminders, notifications that come
 *      out of quiet hours, and a "tick" so every open page updates its clocks
 *   4. start the web server on http://localhost:8080
 *
 * Settings (optional environment variables): PORT (default 8080), DATA_DIR (default: the project's data folder).
 */
public final class App {
    private App() { }

    /** Builds all the parts on top of one data store (also used by the tests). */
    public static ApiServer.Parts parts(DataStore store) {
        EventBus events = new EventBus();
        AuthService auth = new AuthService(store);
        PeopleService people = new PeopleService(store, events);
        WorkService work = new WorkService(store, events);
        ChatService chat = new ChatService(store, events);
        DocService docs = new DocService(store, events);
        RhythmService rhythm = new RhythmService(store, events, chat);
        MeetingService meetings = new MeetingService(store, events);
        ViewService views = new ViewService(store, docs, rhythm);
        return new ApiServer.Parts(store, events, auth, people, work, chat, docs, rhythm, meetings, views);
    }

    public static void main(String[] args) throws Exception {
        Path project = findProjectFolder();
        Path frontend = project.resolve("frontend").toAbsolutePath().normalize();
        String dataEnv = System.getenv("DATA_DIR");
        Path data = (dataEnv != null && !dataEnv.isBlank() ? Path.of(dataEnv) : project.resolve("data")).toAbsolutePath().normalize();
        int port = 8080;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && portEnv.matches("\\d{2,5}")) port = Integer.parseInt(portEnv);

        DataStore store = new DataStore(data);
        boolean firstStart = store.load();
        ApiServer.Parts p = parts(store);
        if (firstStart) {
            System.out.println("First start: building the demo workspace (Lumen Labs, 12 people in 11 cities - all made up)...");
            SeedData.load(p);
        }

        ApiServer server = new ApiServer(frontend, p);
        server.start(port);

        AtomicLong last = new AtomicLong(Clock.millis());
        ScheduledExecutorService jobs = Executors.newScheduledThreadPool(1);
        jobs.scheduleAtFixedRate(() -> {
            try {
                long now = Clock.millis();
                p.work().dueReminders();
                p.meetings().reminders();
                ApiServer.releaseHeld(store, p.events(), last.getAndSet(now), now);
            } catch (Throwable t) {
                System.err.println("Background job failed: " + t);
            }
        }, 2, 30, TimeUnit.SECONDS);

        System.out.println();
        System.out.println("  Sangam - Remote Team Collaboration is running.");
        System.out.println("  Open  http://localhost:" + port + "  in your browser.");
        System.out.println("  Data folder: " + data);
        System.out.println("  Demo logins: asha/member123, lena/member123, diego/member123, kwame/member123, admin/admin123, client/guest1234");
        System.out.println("  Tip: sign in as two people in two different browsers to see live updates.");
        System.out.println("  Press Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            jobs.shutdownNow();
            server.stop();
            store.saveAll();
        }));
    }

    /** The folder that has both "frontend" and "backend" in it: the current folder or one above it. */
    static Path findProjectFolder() {
        Path here = Path.of("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("frontend")) && Files.isDirectory(p.resolve("backend"))) return p;
        }
        return here;
    }
}
