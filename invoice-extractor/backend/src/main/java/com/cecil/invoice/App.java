package com.cecil.invoice;

import com.cecil.invoice.extract.ClaudeExtractor;
import com.cecil.invoice.store.DataStore;
import com.cecil.invoice.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Random;

/**
 * AI-Based Invoice Data Extractor ("Bahi") - starts here.
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

        System.out.println("Bahi - AI-Based Invoice Data Extractor");
        System.out.println("  project folder : " + root);
        DataStore db = new DataStore(data, Clock.systemDefaultZone());
        if (!db.hasFiles()) System.out.println("  first run      : reading the demo invoices (takes a few seconds)...");
        db.load();
        System.out.println("  data           : " + db.invoices.size() + " invoices, " + db.vendors.size() + " vendors, " + db.users.size() + " logins");
        System.out.println("  Claude         : " + (ClaudeExtractor.apiKey() == null ? "no ANTHROPIC_API_KEY - built-in reader only"
                : "key found - " + (db.settings.useClaude ? "turned on" : "turn it on in Settings")));

        ApiServer server = new ApiServer(db, frontend, new Random());
        server.start(port);
        System.out.println("  open           : http://localhost:" + port);
        System.out.println("  demo logins    : admin/admin123  meera/accounts123  ravi/clerk123");
        System.out.println("Press Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

    /** The folder that contains "frontend": the current folder or a parent. */
    static Path findProjectRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("frontend")) && Files.isDirectory(p.resolve("backend"))) return p;
        }
        if (Files.isDirectory(here.resolve("invoice-extractor/frontend"))) return here.resolve("invoice-extractor");
        return here;
    }
}
