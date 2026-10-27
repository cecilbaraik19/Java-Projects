package com.cecil.pipeline;

import com.cecil.pipeline.store.DataStore;
import com.cecil.pipeline.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sales Pipeline Manager - track potential sales from first contact to closed deal.
 *
 * Starts one web server that serves both the REST API and the website.
 * Settings (all optional, as environment variables):
 *   PORT      port to listen on (default 8080)
 *   HOST      address to listen on (default 127.0.0.1, only this computer; use 0.0.0.0 to share on your network)
 *   DATA_DIR  folder for the JSON data files (default: the "data" folder next to backend and frontend)
 *
 * Works when started from the project folder (IntelliJ) or from the backend folder (run.bat / run.sh).
 */
public final class App {

    private App() { }

    public static void main(String[] args) throws Exception {
        Path root = findProjectRoot();
        Path frontend = root.resolve("frontend");
        Path data = env("DATA_DIR") != null ? Path.of(env("DATA_DIR")) : root.resolve("data");
        int port = Integer.parseInt(env("PORT") != null ? env("PORT") : "8080");
        String host = env("HOST") != null ? env("HOST") : "127.0.0.1";

        DataStore store = new DataStore(data);
        store.load(true);

        ApiServer server = new ApiServer(store, frontend);
        server.start(host, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            store.save();
        }));

        System.out.println();
        System.out.println("  Sales Pipeline Manager is running");
        System.out.println("  Open:   http://localhost:" + server.port());
        System.out.println("  Data:   " + data.toAbsolutePath().normalize());
        System.out.println("  Logins: admin / admin123   neha / manager123   arjun, priya or rahul / sales123");
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** The folder that contains "frontend": the current folder (IntelliJ) or its parent (run from backend). */
    static Path findProjectRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isDirectory(cwd.resolve("frontend"))) return cwd;
        Path parent = cwd.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("frontend"))) return parent;
        return cwd;
    }
}
