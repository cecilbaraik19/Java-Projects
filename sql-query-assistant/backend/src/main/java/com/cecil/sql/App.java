package com.cecil.sql;

import com.cecil.sql.ai.ClaudeSql;
import com.cecil.sql.db.Database;
import com.cecil.sql.model.Settings;
import com.cecil.sql.model.User;
import com.cecil.sql.service.AuthService;
import com.cecil.sql.service.DatabaseService;
import com.cecil.sql.service.QueryService;
import com.cecil.sql.store.DataStore;
import com.cecil.sql.store.DemoDatabases;
import com.cecil.sql.store.SeedData;
import com.cecil.sql.util.Clock;
import com.cecil.sql.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Prashna – AI-Powered SQL Query Assistant. Ask a database a question in plain English and get the SQL,
 * the answer and a plain-English explanation of every step.
 *
 * Starts everything: finds the project folder, loads (or creates) the data, builds the services and opens
 * the web server on http://localhost:8080 (set PORT to change it).
 */
public final class App {
    public static void main(String[] args) throws Exception {
        Path root = projectRoot();
        Path frontend = root.resolve("frontend").toAbsolutePath().normalize();
        Path dataDir = System.getenv("DATA_DIR") != null ? Path.of(System.getenv("DATA_DIR")) : root.resolve("data");
        DataStore store = new DataStore(dataDir.toAbsolutePath().normalize());

        // databases: create the two demo databases on the first run
        List<Database> loaded = store.loadDatabases();
        boolean firstRun = loaded.isEmpty();
        if (firstRun) {
            System.out.println("First run: creating the demo databases (college + shop)...");
            store.saveDatabase(DemoDatabases.college(Clock.today()));
            store.saveDatabase(DemoDatabases.shop(Clock.today()));
            loaded = store.loadDatabases();
        }

        List<User> users = store.loadUsers();
        if (users.isEmpty()) {
            users = SeedData.users();
            store.saveUsers(users);
        }
        Settings settings = store.loadSettings();
        ClaudeSql claude = ClaudeSql.fromEnvironment();

        AuthService auth = new AuthService(store, users);
        DatabaseService dbs = new DatabaseService(store, loaded);
        QueryService queries = new QueryService(store, dbs, claude, settings);
        if (firstRun) seed(auth, queries);

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        ApiServer server = new ApiServer(auth, dbs, queries, frontend);
        server.start("127.0.0.1", port);
        System.out.println();
        System.out.println("  Prashna - AI-Powered SQL Query Assistant");
        System.out.println("  Open http://localhost:" + server.port() + "  (Ctrl+C to stop)");
        System.out.println("  Data folder: " + store.dir);
        System.out.println("  Claude: " + (claude.ready() ? "API key found (" + claude.model() + "), mode " + settings.claudeMode : "off (no ANTHROPIC_API_KEY) - fully offline"));
        System.out.println("  Demo logins: admin/admin123  neha/analyst123  arjun/viewer123");
    }

    /** The folder that has "frontend" in it: works when started from the project folder or from backend/. */
    static Path projectRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("frontend")) && Files.isDirectory(p.resolve("backend"))) return p;
        }
        return here;
    }

    private static void seed(AuthService auth, QueryService queries) {
        for (String[] q : SeedData.FIRST_QUESTIONS) {
            User u = auth.find(q[0]);
            if (u != null) try { queries.ask(u, q[1], q[2]); } catch (RuntimeException ignored) { }
        }
        for (String[] s : SeedData.SAVED) {
            User u = auth.find(s[0]);
            if (u != null) try { queries.save(u, s[1], s[2], s[3], null); } catch (RuntimeException ignored) { }
        }
    }
}
