package com.cecil.mail;

import com.cecil.mail.ai.ClaudeWriter;
import com.cecil.mail.ai.EmailAiEngine;
import com.cecil.mail.service.AuthService;
import com.cecil.mail.service.MailService;
import com.cecil.mail.store.DataStore;
import com.cecil.mail.store.SeedData;
import com.cecil.mail.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Patra - AI Email Reply Recommendation System.
 *
 * Start: run.bat (Windows), ./run.sh (Mac/Linux), or Run this class in IntelliJ.
 * Then open http://localhost:8080
 *
 * Start-up order:
 *   1. find the frontend and data folders
 *   2. train the AI engine (Naive Bayes on the built-in examples)
 *   3. load data (or create demo data the first time)
 *   4. start the web server
 */
public final class App {

    private App() { }

    public static void main(String[] args) throws Exception {
        Path root = findProjectRoot();
        Path frontend = root.resolve("frontend");
        Path data = root.resolve("data");

        long t0 = System.currentTimeMillis();
        EmailAiEngine engine = new EmailAiEngine();
        DataStore store = new DataStore(data);
        SeedData.seedIfEmpty(store, engine);
        ClaudeWriter claude = ClaudeWriter.fromEnvironment();
        MailService mail = new MailService(store, engine, claude);
        AuthService auth = new AuthService(store);

        int port = 8080;
        String env = System.getenv("PORT");
        if (env != null && env.matches("\\d{2,5}")) port = Integer.parseInt(env);

        ApiServer server = new ApiServer(auth, mail, store, frontend);
        int actual = server.start(port);

        System.out.println();
        System.out.println("  Patra - AI Email Reply Recommendation System");
        System.out.println("  Model ready: " + engine.modelInfo().get("trainingExamples") + " training examples, "
                + engine.modelInfo().get("vocabularySize") + " features (" + (System.currentTimeMillis() - t0) + " ms)");
        System.out.println("  Data folder: " + data.toAbsolutePath());
        System.out.println("  Claude: " + (claude.hasKey() ? "API key found (an admin can switch it on in Settings)" : "off (no ANTHROPIC_API_KEY) - offline engine only"));
        System.out.println();
        System.out.println("  Open http://localhost:" + actual + " in your browser.   Press Ctrl+C to stop.");
        System.out.println("  Demo sign-in:  aditi / agent123   rohan / agent123   admin / admin123");
        System.out.println();
    }

    /** Works whether you start from the project folder, the backend folder, or IntelliJ. */
    static Path findProjectRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
            if (Files.isDirectory(p.resolve("frontend")) && Files.isDirectory(p.resolve("backend"))) return p;
        }
        return Path.of("").toAbsolutePath();
    }
}
