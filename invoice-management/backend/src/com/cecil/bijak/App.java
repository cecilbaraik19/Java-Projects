package com.cecil.bijak;

import com.cecil.bijak.service.AdminService;
import com.cecil.bijak.service.AuthService;
import com.cecil.bijak.service.AutomationService;
import com.cecil.bijak.service.CustomerService;
import com.cecil.bijak.service.InvoiceService;
import com.cecil.bijak.service.PaymentService;
import com.cecil.bijak.service.ViewService;
import com.cecil.bijak.store.DataStore;
import com.cecil.bijak.store.SeedData;
import com.cecil.bijak.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bijak (बीजक, "invoice") - Invoice Management System. Starts everything:
 *
 *   1. find the frontend folder (website files) and the data folder
 *   2. load the saved data, or build the demo company on the very first start
 *   3. start the background job (every 10 minutes): recurring invoices and payment reminders
 *   4. start the web server on http://localhost:8080
 *
 * Settings (optional environment variables): PORT (default 8080), DATA_DIR (default: the project's data folder).
 */
public final class App {
    private App() { }

    /** Builds all the parts on top of one data store (also used by the tests). */
    public static ApiServer.Parts parts(DataStore store) {
        AuthService auth = new AuthService(store);
        CustomerService customers = new CustomerService(store);
        InvoiceService invoices = new InvoiceService(store);
        PaymentService payments = new PaymentService(store, invoices);
        AutomationService auto = new AutomationService(store, invoices);
        ViewService views = new ViewService(store, payments, auto);
        AdminService admin = new AdminService(store, views);
        return new ApiServer.Parts(store, auth, customers, invoices, payments, auto, views, admin);
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
            System.out.println("First start: building the demo company (Koel Valley Packaging, 6 months of invoices - all made up)...");
            SeedData.load(p);
        }

        ApiServer server = new ApiServer(frontend, p);
        server.start(port);

        ScheduledExecutorService jobs = Executors.newScheduledThreadPool(1);
        jobs.scheduleAtFixedRate(() -> {
            try {
                p.auto().tick();
            } catch (Throwable t) {
                System.err.println("Background job failed: " + t);
            }
        }, 3, 600, TimeUnit.SECONDS);

        System.out.println();
        System.out.println("  Bijak - Invoice Management System is running.");
        System.out.println("  Open  http://localhost:" + port + "  in your browser.");
        System.out.println("  Data folder: " + data);
        System.out.println("  Demo logins: meera/owner123 (owner), arjun/accounts123 (accountant),");
        System.out.println("               salma/sales123 (sales), vikram/audit123 (auditor, read only)");
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
