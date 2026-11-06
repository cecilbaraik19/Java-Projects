package com.cecil.certify;

import com.cecil.certify.cert.CertificateService;
import com.cecil.certify.cert.Verifier;
import com.cecil.certify.chain.Network;
import com.cecil.certify.service.AuthService;
import com.cecil.certify.store.DataStore;
import com.cecil.certify.store.SeedData;
import com.cecil.certify.web.ApiServer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pramaan - Blockchain Certificate Verification.
 * Starts the three-node blockchain, the services and the web server, then opens on http://localhost:8080.
 *
 * Settings (all optional):
 *   PORT        web port (default 8080)
 *   DATA_DIR    where data is saved (default: the "data" folder next to backend/ and frontend/)
 *   PUBLIC_URL  address printed inside QR codes (default http://localhost:PORT). Set it to your computer's
 *               network address (e.g. http://192.168.1.5:8080) to scan QR codes with a phone on the same Wi-Fi.
 *   DIFFICULTY  proof-of-work zeros (default 4)
 */
public final class App {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("PORT", "8080"));
        Path root = findRoot();
        Path data = Path.of(env("DATA_DIR", root.resolve("data").toString())).toAbsolutePath().normalize();
        Path frontend = root.resolve("frontend").toAbsolutePath().normalize();
        int difficulty = Integer.parseInt(env("DIFFICULTY", "4"));
        String publicUrl = env("PUBLIC_URL", "http://localhost:" + port);

        Files.createDirectories(data);
        DataStore store = new DataStore(data);
        boolean firstRun = store.isNew() || !Files.exists(data.resolve("network.json"));
        Network network;
        if (firstRun) {
            System.out.println("First run: creating the blockchain and demo data (takes a few seconds)...");
            network = Network.create(data, difficulty, SeedData.NETWORK_NAME, SeedData.KEEPERS);
        } else {
            store.load();
            network = Network.load(data);
        }
        CertificateService certs = new CertificateService(network, store);
        AuthService auth = new AuthService(store);
        Verifier verifier = new Verifier(certs);
        if (firstRun) SeedData.seed(certs, auth, verifier);

        Network.Consensus c = network.consensus();
        System.out.println("Blockchain: " + (c.state == null ? "?" : (c.state.height + 1) + " blocks, ")
                + c.message + ". Data folder: " + data);
        for (var s : c.status.values()) {
            if (!s.state.equals("AGREES")) System.out.println("  WARNING Node " + s.nodeId + ": " + s.detail);
        }

        ApiServer server = new ApiServer(auth, certs, verifier, frontend, publicUrl);
        server.start(port);
        System.out.println("Pramaan is running. Open http://localhost:" + port + " in your browser. Press Ctrl+C to stop.");
    }

    /** The project folder: the one that contains "frontend" (works when started from the project or from backend/). */
    static Path findRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("frontend")) && Files.isDirectory(p.resolve("backend"))) return p;
        }
        return here;
    }

    private static String env(String k, String fallback) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
