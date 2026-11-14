package com.cecil.kaksh;

import com.cecil.kaksh.core.RoomCalendar;
import com.cecil.kaksh.core.SlotFinder;
import com.cecil.kaksh.service.AuthService;
import com.cecil.kaksh.service.BookingService;
import com.cecil.kaksh.service.ViewService;
import com.cecil.kaksh.store.DataStore;
import com.cecil.kaksh.store.SeedData;
import com.cecil.kaksh.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Kaksh (कक्ष, "room") – Meeting Room Booking System. Starts everything:
 *
 *   1. find the frontend folder (website files) and the data folder
 *   2. load the saved data, or build the demo office on the very first start
 *   3. build the conflict index (RoomCalendar) from the bookings
 *   4. start the background job (every 30 seconds): no-shows, finished meetings, old requests
 *   5. start the web server on http://localhost:8080
 *
 * Settings (optional environment variables): PORT (default 8080), DATA_DIR (default: the project's data folder).
 */
public final class App {
    private App() { }

    /** Builds all the parts on top of one data store (also used by the tests). */
    public static ApiServer.Parts parts(DataStore store) {
        RoomCalendar cal = RoomCalendar.build(store);
        SlotFinder finder = new SlotFinder(store, cal);
        AuthService auth = new AuthService(store);
        BookingService bookings = new BookingService(store, cal, finder);
        ViewService views = new ViewService(store, cal, finder);
        return new ApiServer.Parts(store, cal, finder, auth, bookings, views);
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
            System.out.println("First start: building the demo office (Nimbus Works, Ranchi - all people are made up)...");
            SeedData.load(store, p.bookings());
        }
        p.bookings().tick();

        ApiServer server = new ApiServer(frontend, p);
        server.start(port);

        ScheduledExecutorService jobs = Executors.newScheduledThreadPool(1);
        jobs.scheduleAtFixedRate(() -> {
            try {
                p.bookings().tick();
            } catch (Throwable t) {
                System.err.println("Background job failed: " + t);
            }
        }, 30, 30, TimeUnit.SECONDS);

        System.out.println();
        System.out.println("  Kaksh - Meeting Room Booking is running.");
        System.out.println("  Open  http://localhost:" + port + "  in your browser.");
        System.out.println("  Data folder: " + data);
        System.out.println("  Demo logins: priya/member123, arjun/member123, neha/member123, admin/admin123 (facility manager)");
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
