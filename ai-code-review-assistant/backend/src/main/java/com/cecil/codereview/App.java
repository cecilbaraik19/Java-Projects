package com.cecil.codereview;

import com.cecil.codereview.server.ApiServer;

import java.awt.Desktop;
import java.net.BindException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Main entry point.
 *
 *   java -jar code-review.jar              starts the web app on http://localhost:8080
 *   java -jar code-review.jar --port 9090  use another port
 *   java -jar code-review.jar --no-browser do not open the browser automatically
 *   java -jar code-review.jar --cli ...    command line version (see Cli)
 */
public final class App {
    private App() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--cli")) {
            Cli.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        int port = 8080;
        boolean openBrowser = true;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            else if (args[i].equals("--no-browser")) openBrowser = false;
        }

        Path root = findProjectRoot();
        ApiServer server = new ApiServer(port, root);
        try {
            server.start();
        } catch (BindException e) {
            System.err.println("Port " + port + " is already in use. Close the other program or run with --port 9090");
            System.exit(1);
        }
        String url = "http://localhost:" + server.port();
        System.out.println("==============================================");
        System.out.println("  AI Code Review Assistant  v" + Cli.VERSION);
        System.out.println("  Open  " + url + "  in your browser");
        System.out.println("  Project folder: " + root);
        System.out.println("  Press Ctrl+C to stop");
        System.out.println("==============================================");
        if (openBrowser) openBrowser(url);
    }

    /**
     * The backend can be started from the project folder or from the backend folder
     * (IntelliJ often uses the backend folder), so look for the "frontend" folder.
     */
    static Path findProjectRoot() {
        String forced = System.getProperty("app.root");
        if (forced != null) return Path.of(forced).toAbsolutePath().normalize();
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("frontend")) && Files.isDirectory(dir.resolve("backend"))) return dir;
            dir = dir.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception | Error ignored) {
            // no desktop (for example a server) - the user can open the link by hand
        }
    }
}
