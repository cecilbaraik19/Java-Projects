package com.cecil.kaushal;

import com.cecil.kaushal.service.AdminService;
import com.cecil.kaushal.service.AuthService;
import com.cecil.kaushal.service.CatalogService;
import com.cecil.kaushal.service.GrowthService;
import com.cecil.kaushal.service.ProfileService;
import com.cecil.kaushal.service.StaffingService;
import com.cecil.kaushal.service.ViewService;
import com.cecil.kaushal.store.DataStore;
import com.cecil.kaushal.store.SeedData;
import com.cecil.kaushal.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Kaushal (कौशल, "skill") - Employee Skill Management Platform. Starts everything:
 *
 *   1. find the frontend folder (website files) and the data folder
 *   2. load the saved data, or build the demo company on the very first start
 *   3. start the background job (every 10 minutes): certificate expiry reminders and the monthly skills snapshot
 *   4. start the web server on http://localhost:8080
 *
 * Settings (optional environment variables): PORT (default 8080), DATA_DIR (default: the project's data folder).
 */
public final class App {
    private App() { }

    /** Builds all the parts on top of one data store (also used by the tests). */
    public static ApiServer.Parts parts(DataStore store) {
        AuthService auth = new AuthService(store);
        CatalogService catalog = new CatalogService(store);
        ProfileService profiles = new ProfileService(store);
        GrowthService growth = new GrowthService(store, profiles);
        StaffingService staffing = new StaffingService(store);
        AdminService admin = new AdminService(store, profiles);
        ViewService views = new ViewService(store, staffing, growth);
        return new ApiServer.Parts(store, auth, catalog, profiles, growth, staffing, admin, views);
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
            System.out.println("First start: building the demo company (Tarang Technologies, 24 people - all made up)...");
            SeedData.load(p);
        }

        ApiServer server = new ApiServer(frontend, p);
        server.start(port);

        ScheduledExecutorService jobs = Executors.newScheduledThreadPool(1);
        jobs.scheduleAtFixedRate(() -> {
            try {
                p.profiles().certReminders();
                p.admin().monthly();
            } catch (Throwable t) {
                System.err.println("Background job failed: " + t);
            }
        }, 5, 600, TimeUnit.SECONDS);

        System.out.println();
        System.out.println("  Kaushal - Employee Skill Management is running.");
        System.out.println("  Open  http://localhost:" + port + "  in your browser.");
        System.out.println("  Data folder: " + data);
        System.out.println("  Demo logins: asha/member123 (employee), ravi/manager123 (manager), meera/admin123 (HR admin)");
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
