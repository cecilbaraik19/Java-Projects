package com.cecil.sikho;

import com.cecil.sikho.ledger.TimeBank;
import com.cecil.sikho.match.Matcher;
import com.cecil.sikho.match.SwapFinder;
import com.cecil.sikho.service.AuthService;
import com.cecil.sikho.service.CircleService;
import com.cecil.sikho.service.ExchangeService;
import com.cecil.sikho.service.InsightService;
import com.cecil.sikho.service.ListingService;
import com.cecil.sikho.service.ModerationService;
import com.cecil.sikho.service.Views;
import com.cecil.sikho.store.DataStore;
import com.cecil.sikho.store.SeedData;
import com.cecil.sikho.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sikho-Sikhao - Community Skill Exchange. Starts everything:
 *
 *   1. find the frontend folder (website files) and the data folder
 *   2. load the saved data, or build the demo community on the very first start
 *   3. start the background job (every minute): expire old requests, auto-complete sessions, close old circles
 *   4. start the web server on http://localhost:8080
 *
 * Settings (optional environment variables): PORT (default 8080), DATA_DIR (default: the project's data folder).
 */
public final class App {
    private App() { }

    /** Builds all the parts on top of one data store (also used by the tests). */
    public static ApiServer.Parts parts(DataStore store) {
        TimeBank bank = new TimeBank(store);
        AuthService auth = new AuthService(store, bank);
        ListingService listings = new ListingService(store);
        ExchangeService exchanges = new ExchangeService(store, bank);
        CircleService circles = new CircleService(store, bank);
        ModerationService moderation = new ModerationService(store, bank, auth, circles);
        Matcher matcher = new Matcher(store);
        SwapFinder swaps = new SwapFinder(store);
        Views views = new Views(store);
        InsightService insights = new InsightService(store, bank, matcher, swaps, views);
        return new ApiServer.Parts(store, bank, auth, listings, exchanges, circles, moderation, insights, matcher, swaps, views);
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
            System.out.println("First start: building the demo community (Sikho-Sikhao Ranchi - all people are made up)...");
            SeedData.load(store, p.bank(), p.exchanges(), p.circles(), p.moderation());
        }
        p.exchanges().tick();
        p.circles().tick();

        ApiServer server = new ApiServer(frontend, p);
        server.start(port);

        ScheduledExecutorService jobs = Executors.newScheduledThreadPool(1);
        jobs.scheduleAtFixedRate(() -> {
            try {
                p.exchanges().tick();
                p.circles().tick();
            } catch (Throwable t) {
                System.err.println("Background job failed: " + t);
            }
        }, 60, 60, TimeUnit.SECONDS);

        System.out.println();
        System.out.println("  Sikho-Sikhao is running.");
        System.out.println("  Open  http://localhost:" + port + "  in your browser.");
        System.out.println("  Data folder: " + data);
        System.out.println("  Demo logins: ananya/member123, ravi/member123, meera/member123 (new member), admin/admin123 (moderator)");
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
