package com.cecil.ngo;

import com.cecil.ngo.store.DataStore;
import com.cecil.ngo.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NGO Management System - starts everything.
 *   1. finds the frontend folder and the data folder
 *   2. loads the data (or makes the demo data on the first run)
 *   3. starts the web server on http://localhost:8080
 *
 * Settings (optional environment variables): PORT, HOST, DATA_DIR, FRONTEND_DIR.
 */
public class App {
    public static void main(String[] args) throws Exception {
        Path frontend = findFrontend();
        Path root = frontend.toAbsolutePath().getParent();
        Path data = env("DATA_DIR") != null ? Path.of(env("DATA_DIR")) : root.resolve("data");
        int port = 8080;
        try { if (env("PORT") != null) port = Integer.parseInt(env("PORT")); }
        catch (NumberFormatException e) { System.err.println("PORT must be a number. Using 8080."); }
        String host = env("HOST") != null ? env("HOST") : "127.0.0.1";

        DataStore store = new DataStore(data, Clock.systemDefaultZone());
        boolean fresh = store.load();
        ApiServer api = new ApiServer(store, frontend);
        api.start(host, port);

        // every 10 minutes: forget expired sessions
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sweeper");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(api.auth::sweep, 10, 10, TimeUnit.MINUTES);

        System.out.println("==============================================");
        System.out.println(" NGO Management System is running");
        System.out.println(" Open http://localhost:" + api.port() + " in your browser");
        System.out.println(" Data folder: " + data.toAbsolutePath().normalize());
        if (fresh) System.out.println(" Demo data created: " + store.programs.size() + " programs, " + store.activities.size()
                + " activities, " + store.volunteers.size() + " volunteers, " + store.donations.size() + " donations");
        System.out.println(" Demo logins: admin/admin123, kavita/manager123, rahul/finance123, aman/volunteer123");
        System.out.println(" Press Ctrl+C to stop.");
        System.out.println("==============================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.stop();
            System.out.println("Stopped.");
        }));
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** Works when started from the project folder, the backend folder, or IntelliJ. */
    private static Path findFrontend() {
        if (env("FRONTEND_DIR") != null) return Path.of(env("FRONTEND_DIR"));
        for (String c : new String[]{"frontend", "../frontend", "../../frontend"}) {
            Path p = Path.of(c);
            if (Files.isRegularFile(p.resolve("index.html"))) return p.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Cannot find the frontend folder. Start the app from the project folder or the backend folder.");
    }
}
