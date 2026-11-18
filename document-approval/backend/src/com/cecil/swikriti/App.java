package com.cecil.swikriti;

import com.cecil.swikriti.service.AdminService;
import com.cecil.swikriti.service.AuthService;
import com.cecil.swikriti.service.DocumentService;
import com.cecil.swikriti.service.ViewService;
import com.cecil.swikriti.service.WorkflowService;
import com.cecil.swikriti.store.DataStore;
import com.cecil.swikriti.store.SeedData;
import com.cecil.swikriti.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Swikriti (स्वीकृति, "approval") - Document Approval Workflow. Starts everything:
 *
 *   1. find the frontend folder (website files) and the data folder
 *   2. load the saved data, or build the demo company on the very first start
 *   3. start the background job (every 5 minutes): reminders and escalation of overdue approvals
 *   4. start the web server on http://localhost:8080
 *
 * Settings (optional environment variables): PORT (default 8080), DATA_DIR (default: the project's data folder).
 */
public final class App {
    private App() { }

    /** Builds all the parts on top of one data store (also used by the tests). */
    public static ApiServer.Parts parts(DataStore store) {
        AuthService auth = new AuthService(store);
        WorkflowService flow = new WorkflowService(store);
        DocumentService docs = new DocumentService(store, flow);
        AdminService admin = new AdminService(store, flow);
        ViewService views = new ViewService(store, flow);
        return new ApiServer.Parts(store, auth, docs, flow, admin, views);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");   // the demo drawings are painted without a screen
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
            System.out.println("First start: building the demo company (Damodar Consulting Engineers, 20 people - all made up)...");
            SeedData.load(p);
        }

        ApiServer server = new ApiServer(frontend, p);
        server.start(port);

        ScheduledExecutorService jobs = Executors.newScheduledThreadPool(1);
        jobs.scheduleAtFixedRate(() -> {
            try {
                p.flow().tick();
            } catch (Throwable t) {
                System.err.println("Background job failed: " + t);
            }
        }, 5, 300, TimeUnit.SECONDS);

        System.out.println();
        System.out.println("  Swikriti - Document Approval Workflow is running.");
        System.out.println("  Open  http://localhost:" + port + "  in your browser.");
        System.out.println("  Data folder: " + data);
        System.out.println("  Demo logins: ankit/member123 (author), sunita/manager123 (manager), rahul/manager123 (finance),");
        System.out.println("               kavita/manager123 (director), neha/admin123 (Document Controller)");
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
