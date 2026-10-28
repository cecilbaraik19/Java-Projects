package com.cecil.chatbot;

import com.cecil.chatbot.model.Provider;
import com.cecil.chatbot.store.DataStore;
import com.cecil.chatbot.store.SeedData;
import com.cecil.chatbot.web.ApiServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * GenAI Chatbot - starts everything.
 *
 *   1. finds the project folder (the one with frontend/index.html)
 *   2. loads the data files, or creates demo data on the first run
 *   3. starts the web server on http://localhost:8080
 *   4. every 10 minutes removes expired sign-in sessions
 *
 * Settings (environment variables): PORT (default 8080), HOST (default 127.0.0.1), DATA_DIR,
 * and API keys: GEMINI_API_KEY, GROQ_API_KEY, OPENAI_API_KEY, ANTHROPIC_API_KEY.
 */
public class App {

    public static void main(String[] args) throws Exception {
        Path root = findProjectRoot();
        int port = Integer.parseInt(env("PORT", "8080"));
        String host = env("HOST", "127.0.0.1");
        Path dataDir = Path.of(env("DATA_DIR", root.resolve("data").toString()));

        DataStore store = new DataStore(dataDir, Clock.systemDefaultZone());
        if (store.load()) {
            System.out.println("Loaded data from " + dataDir.toAbsolutePath());
        } else {
            System.out.println("First run: creating demo data in " + dataDir.toAbsolutePath() + " ...");
            SeedData.fill(store);
        }

        ApiServer server = new ApiServer(store, root.resolve("frontend"));
        server.start(host, port);

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cleanup-timer");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(server.auth::cleanup, 10, 10, TimeUnit.MINUTES);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            synchronized (store.lock) {
                store.saveAll();
            }
        }));

        System.out.println();
        System.out.println("  GenAI Chatbot is running:  http://" + (host.equals("0.0.0.0") ? "localhost" : host) + ":" + server.port());
        System.out.println("  Demo logins: admin / admin123   priya / trainer123   rahul / member123");
        synchronized (store.lock) {
            StringBuilder ready = new StringBuilder();
            for (Provider p : store.providers) if (p.ready()) ready.append(ready.length() == 0 ? "" : ", ").append(p.name);
            System.out.println("  AI models ready: " + ready);
            System.out.println("  Default model:   " + store.provider(store.settings.defaultProviderId).name);
        }
        System.out.println("  Tip: set GEMINI_API_KEY (free from Google AI Studio) or GROQ_API_KEY before starting,");
        System.out.println("       or paste a key on the 'AI models' page, to get real AI answers.");
        System.out.println("  Press Ctrl+C to stop.");
    }

    /** The project folder is the one that has frontend/index.html (we may be started from backend/ or the project root). */
    static Path findProjectRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("frontend").resolve("index.html"))) return p;
            p = p.getParent();
        }
        System.err.println("Warning: could not find the frontend folder. Start the app from the project folder.");
        return Path.of("").toAbsolutePath();
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v.trim();
    }
}
