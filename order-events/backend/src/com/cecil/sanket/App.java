package com.cecil.sanket;

import com.cecil.sanket.broker.EventBroker;
import com.cecil.sanket.store.DataStore;
import com.cecil.sanket.store.SeedData;
import com.cecil.sanket.util.Clock;
import com.cecil.sanket.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sanket - Event-Driven Order Processing System. Starts everything:
 *
 *   1. find the frontend folder (website files) and the data folder
 *   2. load the saved data and the event log, or build the demo shop (with 3½ days of history) on the first start
 *   3. start the background jobs:
 *        every 200 ms  outbox relay + dispatcher (deliver events to the consumer groups)
 *        every 1 s     save the changed data files
 *        every 2 s     the simulator (shoppers, warehouse, supplier, faults)
 *        every 10 s    remember each group's lag for the lag chart
 *   4. start the web server on http://localhost:8080
 *
 * Settings (optional environment variables): PORT (default 8080), DATA_DIR (default: the project's data folder),
 * SIMULATOR=off to switch off the made-up shoppers (then only real orders from the website happen).
 */
public final class App {
    private App() { }

    public static void main(String[] args) throws Exception {
        Path project = findProjectFolder();
        Path frontend = project.resolve("frontend").toAbsolutePath().normalize();
        String dataEnv = System.getenv("DATA_DIR");
        Path data = (dataEnv != null && !dataEnv.isBlank() ? Path.of(dataEnv) : project.resolve("data")).toAbsolutePath().normalize();
        int port = 8080;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && portEnv.matches("\\d{2,5}")) port = Integer.parseInt(portEnv);
        boolean simulate = !"off".equalsIgnoreCase(System.getenv("SIMULATOR"));

        DataStore store = new DataStore(data);
        boolean firstStart = store.load();
        EventBroker broker = new EventBroker(data.resolve("log"));
        broker.load();
        Platform p = new Platform(store, broker, System.nanoTime());
        if (firstStart) {
            System.out.println("First start: building the demo shop (Jharkhand Haat - all made up).");
            System.out.println("Playing 3.5 days of orders through the event system... (takes a few seconds)");
            SeedData.load(p, true);
            System.out.println("Done: " + broker.total() + " events in the log, " + store.views.size() + " orders.");
        }

        ApiServer server = new ApiServer(frontend, p);
        server.start(port);

        ScheduledExecutorService jobs = Executors.newScheduledThreadPool(2);
        jobs.scheduleWithFixedDelay(safe("dispatcher", p::pump), 500, 200, TimeUnit.MILLISECONDS);
        jobs.scheduleWithFixedDelay(safe("save", store::flush), 1, 1, TimeUnit.SECONDS);
        if (simulate) jobs.scheduleWithFixedDelay(safe("simulator", () -> p.sim.tick(Clock.millis())), 3, 2, TimeUnit.SECONDS);
        jobs.scheduleWithFixedDelay(safe("lag sample", p.analytics::sampleLag), 1, 10, TimeUnit.SECONDS);

        System.out.println();
        System.out.println("  Sanket order processing is running.");
        System.out.println("  Open  http://localhost:" + port + "  in your browser.");
        System.out.println("  Data folder: " + data + "   (event log: " + data.resolve("log") + ")");
        System.out.println("  Simulator: " + (simulate ? "on (about " + p.sim.ordersPerMinute() + " orders a minute)" : "off"));
        System.out.println("  Demo logins: admin/admin123, priya/staff123, neha/customer123, arjun/customer123");
        System.out.println("  Press Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            jobs.shutdownNow();
            server.stop();
            p.dispatcher.saveState();
            store.saveAll();
        }));
    }

    private static Runnable safe(String name, Runnable r) {
        return () -> {
            try { r.run(); } catch (Throwable t) { System.err.println("Background job '" + name + "' failed: " + t); }
        };
    }

    /** The folder that has both "frontend" and "backend" in it: the current folder or the one above it. */
    static Path findProjectFolder() {
        Path here = Path.of("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("frontend")) && Files.isDirectory(p.resolve("backend"))) return p;
        }
        return here;
    }
}
