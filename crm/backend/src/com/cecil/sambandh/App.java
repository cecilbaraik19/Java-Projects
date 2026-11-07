package com.cecil.sambandh;

import com.cecil.sambandh.store.DataStore;
import com.cecil.sambandh.store.SeedData;
import com.cecil.sambandh.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sambandh CRM - starts everything.
 *
 *   1. find the frontend folder (website files) and the data folder
 *   2. load the saved data, or create the demo data on the very first start
 *   3. start the web server on http://localhost:8080
 *
 * Settings (optional environment variables): PORT (default 8080), DATA_DIR (default: the project's data folder).
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

        DataStore store = new DataStore(data);
        boolean firstStart = store.load();
        if (firstStart) {
            System.out.println("First start: creating the demo data (Suryodaya Solar, all made up)...");
            SeedData.load(store);
            store.saveAll();
        }

        ApiServer server = new ApiServer(frontend, store);
        server.start(port);
        System.out.println();
        System.out.println("  Sambandh CRM is running.");
        System.out.println("  Open  http://localhost:" + port + "  in your browser.");
        System.out.println("  Data folder: " + data);
        System.out.println("  Demo logins: admin/admin123, ritu/manager123, arjun/agent123, kavya/agent123, sameer/agent123");
        System.out.println("  Press Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
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
