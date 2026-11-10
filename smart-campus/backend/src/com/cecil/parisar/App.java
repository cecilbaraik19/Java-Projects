package com.cecil.parisar;

import com.cecil.parisar.ai.Assistant;
import com.cecil.parisar.ai.EnergyForecaster;
import com.cecil.parisar.ai.RiskModel;
import com.cecil.parisar.cloud.CloudService;
import com.cecil.parisar.iot.IotService;
import com.cecil.parisar.iot.Simulator;
import com.cecil.parisar.service.AcademicService;
import com.cecil.parisar.service.AnalyticsService;
import com.cecil.parisar.service.AuthService;
import com.cecil.parisar.store.DataStore;
import com.cecil.parisar.store.SeedData;
import com.cecil.parisar.util.Clock;
import com.cecil.parisar.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Parisar - Smart Digital Campus Platform. Starts everything:
 *
 *   1. find the frontend folder (website files) and the data folder
 *   2. load the saved data, or create the demo campus on the very first start
 *   3. train the AI risk model
 *   4. start the background jobs: IoT simulator (every 15 s), offline check (every minute),
 *      cloud sync (every 5 minutes) and the daily cloud backup
 *   5. start the web server on http://localhost:8080
 *
 * Settings (optional environment variables): PORT (default 8080), DATA_DIR (default: the project's data folder),
 * SIMULATOR=off to switch off the made-up sensors (then only real devices send data).
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

        IotService iot = new IotService(store);
        AcademicService academic = new AcademicService(store);
        iot.setAcademic(academic);
        Simulator sim = new Simulator(store, iot, academic, System.nanoTime());
        RiskModel model = new RiskModel();
        model.train(RiskModel.history(7), 1500, 0.3, 0.01);
        EnergyForecaster energy = new EnergyForecaster(store);
        AnalyticsService analytics = new AnalyticsService(store, academic, model, energy);
        Assistant assistant = new Assistant(store, academic, analytics, energy);

        if (firstStart) {
            System.out.println("First start: building the demo campus (Kanchan Institute of Technology - all made up).");
            System.out.println("Creating 14 days of sensor history... (takes a few seconds)");
            SeedData.load(store, iot, academic, sim, true);
        }
        CloudService cloud = new CloudService(store, iot, data.resolve("cloud"));
        if (firstStart) { cloud.sync(null); cloud.backup(null); }

        ApiServer server = new ApiServer(frontend, new ApiServer.Parts(store, new AuthService(store), iot, academic, analytics,
                assistant, energy, cloud, sim, model));
        server.start(port);

        ScheduledExecutorService jobs = Executors.newScheduledThreadPool(1);
        if (simulate) jobs.scheduleAtFixedRate(safe("simulator", () -> sim.tick(Clock.millis())), 2, 15, TimeUnit.SECONDS);
        jobs.scheduleAtFixedRate(safe("offline check", () -> { academic.autoSessions(Clock.millis()); iot.checkOffline(); }), 30, 60, TimeUnit.SECONDS);
        jobs.scheduleAtFixedRate(safe("cloud sync", () -> cloud.sync(null)), 5, 5, TimeUnit.MINUTES);
        jobs.scheduleAtFixedRate(safe("daily backup", cloud::dailyBackupIfDue), 10, 60, TimeUnit.MINUTES);

        System.out.println();
        System.out.println("  Parisar Smart Campus is running.");
        System.out.println("  Open  http://localhost:" + port + "  in your browser.");
        System.out.println("  Data folder: " + data);
        System.out.println("  IoT simulator: " + (simulate ? "on (a reading from every device every 15 seconds)" : "off"));
        System.out.println("  Demo logins: admin/admin123, rkumar/faculty123, pmunda/faculty123, aditi/student123, rohit/student123");
        System.out.println("  Press Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            jobs.shutdownNow();
            server.stop();
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
