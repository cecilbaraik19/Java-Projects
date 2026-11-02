package com.cecil.rag;

import com.cecil.rag.service.AskService;
import com.cecil.rag.service.AuthService;
import com.cecil.rag.service.EvalService;
import com.cecil.rag.service.InsightService;
import com.cecil.rag.service.KnowledgeService;
import com.cecil.rag.service.UserService;
import com.cecil.rag.store.DataStore;
import com.cecil.rag.store.SeedData;
import com.cecil.rag.web.ApiServer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Starts Pothi, the RAG-Based College Knowledge Assistant.
 *  1. finds the project folder (the one with "frontend" and "knowledge" inside)
 *  2. loads the data folder, or creates demo data on the first run
 *  3. builds the search index and starts the web server on http://localhost:8080
 */
public final class App {
    private App() {}

    public static void main(String[] args) throws Exception {
        Path root = findRoot();
        DataStore store = new DataStore(root.resolve("data"));
        boolean first = !store.exists();
        if (!first) store.load();

        KnowledgeService[] ks = new KnowledgeService[1];
        AskService[] as = new AskService[1];
        if (first) {
            System.out.println("First run: creating demo data (college documents, users, one month of questions)...");
            long t = System.currentTimeMillis();
            SeedData.seed(store, root.resolve("knowledge"),
                    () -> as[0] = new AskService(store, ks[0]),
                    () -> ks[0] = new KnowledgeService(store));
            new EvalService(store, ks[0]).run(); // so the Test bench already shows results
            System.out.println("Demo data ready in " + (System.currentTimeMillis() - t) + " ms.");
        } else {
            ks[0] = new KnowledgeService(store);
            as[0] = new AskService(store, ks[0]);
        }
        KnowledgeService knowledge = ks[0];
        AskService ask = as[0];
        AuthService auth = new AuthService(store);
        ApiServer server = new ApiServer(store, auth, new UserService(store, auth), knowledge, ask,
                new InsightService(store, knowledge), new EvalService(store, knowledge), root.resolve("frontend").toAbsolutePath().normalize());

        int port = 8080;
        try { if (System.getenv("PORT") != null) port = Integer.parseInt(System.getenv("PORT")); } catch (NumberFormatException ignored) {}
        int actual = server.start("127.0.0.1", port);
        var stats = knowledge.stats();
        System.out.println();
        System.out.println("  Pothi - RAG-Based College Knowledge Assistant");
        System.out.println("  " + stats.get("published") + " documents, " + stats.get("chunks") + " chunks indexed in " + stats.get("buildMs") + " ms");
        System.out.println("  Claude: " + (ask.claudeAvailable() ? "API key found (switch on in Settings)" : "no API key - offline answers"));
        System.out.println("  Open http://localhost:" + actual + "   (Ctrl+C to stop)");
        System.out.println("  Logins: aarav / student123   meena / faculty123   admin / admin123");
    }

    /** Works when started from the project folder, the backend folder, or by IntelliJ. */
    static Path findRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++) {
            if (Files.isDirectory(p.resolve("frontend")) && Files.isDirectory(p.resolve("knowledge"))) return p;
            p = p.getParent();
        }
        System.err.println("Could not find the project folder (with 'frontend' and 'knowledge'). Using the current folder.");
        return Path.of("").toAbsolutePath();
    }
}
