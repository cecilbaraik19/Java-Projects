package com.cecil.sld;

import com.cecil.sld.store.DataStore;
import com.cecil.sld.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Random;

/**
 * Smart Learning Difficulty Detector - starts here.
 *
 * 1. Finds the project folder (works when started from the project folder,
 *    from the backend folder, or from IntelliJ).
 * 2. Loads the data folder (or creates demo data on the first run).
 * 3. Starts the web server on http://localhost:8080 (or the PORT you set).
 */
public final class App {
    private App() {}

    public static void main(String[] args) throws Exception {
        Path root = findProjectRoot();
        Path frontend = root.resolve("frontend");
        Path data = root.resolve("data");
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && envPort.matches("\\d{2,5}")) port = Integer.parseInt(envPort);
        if (args.length > 0 && args[0].matches("\\d{2,5}")) port = Integer.parseInt(args[0]);

        System.out.println("Smart Learning Difficulty Detector");
        System.out.println("  project folder : " + root);
        boolean firstRun = !Files.exists(data.resolve("users.json"));
        if (firstRun) System.out.println("  first run      : creating demo data (takes a few seconds)...");

        DataStore db = new DataStore(data, Clock.systemDefaultZone());
        db.load();
        System.out.println("  data           : " + db.subjects.size() + " subjects, " + db.topics.size() + " topics, "
                + db.questions.size() + " questions, " + db.attempts.size() + " attempts");

        ApiServer server = new ApiServer(db, frontend, new Random());
        server.start(port);
        System.out.println("  open           : http://localhost:" + port);
        System.out.println("  demo logins    : admin/admin123  neha/teacher123  riya/student123");
        System.out.println("Press Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

    /** The folder that contains "frontend": the current folder or its parent. */
    static Path findProjectRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("frontend")) && Files.isDirectory(p.resolve("backend"))) return p;
        }
        if (Files.isDirectory(here.resolve("learning-difficulty-detector/frontend"))) return here.resolve("learning-difficulty-detector");
        return here;
    }
}
