package com.cecil.dainandini;

import com.cecil.dainandini.ai.ClaudeClient;
import com.cecil.dainandini.service.AdminService;
import com.cecil.dainandini.service.AssistantService;
import com.cecil.dainandini.service.AuthService;
import com.cecil.dainandini.service.Brain;
import com.cecil.dainandini.service.CalendarService;
import com.cecil.dainandini.service.FocusService;
import com.cecil.dainandini.service.NoteService;
import com.cecil.dainandini.service.TaskService;
import com.cecil.dainandini.store.DataStore;
import com.cecil.dainandini.store.SeedData;
import com.cecil.dainandini.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dainandini (दैनंदिनी, "the daily diary") – AI Productivity Assistant. Starts everything:
 *
 *   1. find the frontend folder (website files) and the data folder
 *   2. load the saved data, or build the demo office on the very first start
 *   3. start the background job (every minute): stops focus sessions that were left running
 *   4. start the web server on http://localhost:8080
 *
 * Settings (optional environment variables): PORT (default 8080), DATA_DIR (default: the project's data folder),
 * ANTHROPIC_API_KEY and ANTHROPIC_MODEL (only for the optional Claude answers).
 */
public final class App {
    private App() { }

    /** Builds all the parts on top of one data store (also used by the tests). */
    public static ApiServer.Parts parts(DataStore store, ClaudeClient claude) {
        AuthService auth = new AuthService(store);
        Brain brain = new Brain(store);
        TaskService tasks = new TaskService(store, brain);
        CalendarService calendar = new CalendarService(store, brain);
        FocusService focus = new FocusService(store, tasks, brain);
        NoteService notes = new NoteService(store, tasks);
        AssistantService assistant = new AssistantService(store, brain, tasks, claude);
        AdminService admin = new AdminService(store, claude);
        return new ApiServer.Parts(store, auth, brain, tasks, calendar, focus, notes, assistant, admin);
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
        ClaudeClient claude = ClaudeClient.fromEnv();
        ApiServer.Parts p = parts(store, claude);
        if (firstStart) {
            System.out.println("First start: building the demo office (Tarang Tech, 8 people, 4 weeks of history - all made up)...");
            SeedData.load(p);
        }

        ApiServer server = new ApiServer(frontend, p);
        server.start(port);

        ScheduledExecutorService jobs = Executors.newScheduledThreadPool(1);
        jobs.scheduleAtFixedRate(() -> {
            try { p.focus().stopForgotten(); } catch (Throwable t) { System.err.println("Background job failed: " + t); }
        }, 1, 1, TimeUnit.MINUTES);

        System.out.println();
        System.out.println("  Dainandini - AI Productivity Assistant is running.");
        System.out.println("  Open  http://localhost:" + port + "  in your browser.");
        System.out.println("  Data folder: " + data);
        System.out.println("  Demo logins: neha/member123, ravi/member123, arjun/lead123 (team lead), admin/admin123");
        System.out.println("  Claude: " + (claude == null ? "off (no ANTHROPIC_API_KEY) - everything works offline" : "key found - an admin can switch it on in Settings"));
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
