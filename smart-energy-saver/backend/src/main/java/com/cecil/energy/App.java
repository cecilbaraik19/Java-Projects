package com.cecil.energy;

import com.cecil.energy.store.DataStore;
import com.cecil.energy.store.SeedData;
import com.cecil.energy.web.ApiServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Smart Energy Saver - starts everything.
 *
 *   1. finds the project folder (the one with frontend/index.html)
 *   2. loads the data files, or creates demo data on the first run
 *   3. starts the web server on http://localhost:8080
 *   4. every minute: switches off appliances whose auto-off timer ended, and fills in missing days
 *
 * Settings (environment variables): PORT (default 8080), HOST (default 127.0.0.1), DATA_DIR.
 */
public class App {

    public static void main(String[] args) throws Exception {
        Path root = findProjectRoot();
        int port = Integer.parseInt(env("PORT", "8080"));
        String host = env("HOST", "127.0.0.1");
        Path dataDir = Path.of(env("DATA_DIR", root.resolve("data").toString()));

        DataStore store = new DataStore(dataDir, Clock.systemDefaultZone());
        if (store.load()) {
            System.out.println("Loaded data from " + dataDir.toAbsolutePath());
        } else {
            System.out.println("First run: creating demo data in " + dataDir.toAbsolutePath() + " ...");
            SeedData.fill(store);
        }

        ApiServer server = new ApiServer(store, root.resolve("frontend"));
        int filled = server.usage.ensureFilled();
        if (filled > 0) System.out.println("Filled " + filled + " missing usage records with estimates.");
        server.start(host, port);

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "energy-timer");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(() -> {
            try {
                List<String> off = server.appliances.autoOffSweep();
                if (!off.isEmpty()) System.out.println("Auto-off: " + String.join(", ", off));
                server.usage.ensureFilled();
                server.auth.cleanup();
            } catch (Exception e) {
                System.err.println("Timer error: " + e);
            }
        }, 20, 60, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            synchronized (store.lock) {
                store.saveAll();
            }
        }));

        System.out.println();
        System.out.println("  Smart Energy Saver is running:  http://" + (host.equals("0.0.0.0") ? "localhost" : host) + ":" + server.port());
        System.out.println("  Demo logins: admin / admin123   meera / manager123   rohan / member123");
        System.out.println("  Press Ctrl+C to stop.");
    }

    /** The project folder is the one that has frontend/index.html (we may be started from backend/ or the project root). */
    static Path findProjectRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("frontend").resolve("index.html"))) return p;
            p = p.getParent();
        }
        System.err.println("Warning: could not find the frontend folder. Start the app from the project folder.");
        return Path.of("").toAbsolutePath();
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v.trim();
    }
}
