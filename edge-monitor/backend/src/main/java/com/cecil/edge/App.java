package com.cecil.edge;

import com.cecil.edge.service.Hub;
import com.cecil.edge.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Edge Computing Monitoring System - starts everything.
 *
 *  1. Loads the data folder (or creates demo data on the first run).
 *  2. Warms up the simulator so the live charts already show the last hour.
 *  3. Starts a timer: every few seconds the simulated edge nodes send heartbeats and sensor readings.
 *  4. Starts the web server on http://localhost:8080 (website + JSON API).
 *
 * Settings (environment variables): PORT (8080), HOST (127.0.0.1), DATA_DIR (data folder of the project).
 */
public final class App {
    private App() { }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("PORT", "8080"));
        String host = env("HOST", "127.0.0.1");
        Path root = projectRoot();
        Path frontend = root.resolve("frontend");
        Path data = Path.of(env("DATA_DIR", root.resolve("data").toString()));

        System.out.println("Edge Computing Monitoring System");
        System.out.println("  data folder:     " + data.toAbsolutePath().normalize());
        System.out.println("  website folder:  " + frontend.toAbsolutePath().normalize());
        System.out.println("  warming up the edge simulator (last 60 minutes)...");

        Hub hub = Hub.open(data, ZoneId.systemDefault(), 60);
        ApiServer server = new ApiServer(hub, frontend);
        server.start(host, port);

        // The timer wakes every second and runs a simulator step when "tickSec" seconds have passed.
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "edge-tick");
            t.setDaemon(true);
            return t;
        });
        long[] lastTick = {0};
        timer.scheduleWithFixedDelay(() -> {
            try {
                long now = System.currentTimeMillis();
                if (now - lastTick[0] >= hub.store.settings.tickSec * 1000L) {
                    lastTick[0] = now;
                    hub.tick();
                }
            } catch (Exception e) {
                System.err.println("Tick failed: " + e);
                e.printStackTrace();
            }
        }, 1, 1, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Saving and stopping...");
            timer.shutdownNow();
            server.stop();
            hub.shutdown();
        }));

        System.out.println("Ready. Open http://localhost:" + server.port() + " in your browser. Press Ctrl+C to stop.");
        System.out.println("Demo logins: admin / admin123, priya / engineer123, arjun / viewer123");
    }

    private static String env(String k, String dflt) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    /** The project folder: the one that has "frontend" in it (works when started from the project or the backend folder). */
    private static Path projectRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("frontend")) && Files.isDirectory(p.resolve("backend"))) return p;
        }
        return cwd;
    }
}
