package com.cecil.complaints;

import com.cecil.complaints.ai.AiEngine;
import com.cecil.complaints.ai.LlmClient;
import com.cecil.complaints.service.AnalyticsService;
import com.cecil.complaints.service.AuthService;
import com.cecil.complaints.service.ComplaintService;
import com.cecil.complaints.service.KnowledgeBaseService;
import com.cecil.complaints.store.DataStore;
import com.cecil.complaints.store.SeedData;
import com.cecil.complaints.web.ApiServer;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AI Customer Complaint Resolution Assistant - entry point.
 *
 * Settings (environment variables, all optional):
 *   PORT               web port (default 8080)
 *   DATA_DIR           where JSON data is saved (default ./data)
 *   FRONTEND_DIR       where the website files are (default: ./frontend or ../frontend)
 *   APP_TIMEZONE       time zone for charts (default Asia/Kolkata)
 *   ANTHROPIC_API_KEY  turns on LLM-polished replies (otherwise fully offline)
 *   CLAUDE_MODEL       model name for the LLM feature
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("PORT", "8080"));
        Path dataDir = Path.of(env("DATA_DIR", "data"));
        ZoneId zone = ZoneId.of(env("APP_TIMEZONE", "Asia/Kolkata"));

        DataStore store = new DataStore(dataDir);
        store.load();

        AiEngine ai = new AiEngine(LlmClient.fromEnvironment());
        AuthService auth = new AuthService(store);
        ComplaintService complaints = new ComplaintService(store, ai);
        KnowledgeBaseService kb = new KnowledgeBaseService(store);
        AnalyticsService analytics = new AnalyticsService(store, zone);

        boolean firstRun = store.isEmpty();
        auth.seedUsers();
        if (store.solutions().isEmpty()) {
            SeedData.solutions().forEach(s -> store.solutions().put(s.id, s));
            store.save();
        }
        if (firstRun) {
            System.out.println("First run: loading demo complaints...");
            complaints.seedDemoComplaints(SeedData.complaints(), auth);
        } else {
            synchronized (store) {
                ai.learnFrom(new ArrayList<>(store.complaints().values()));
            }
        }

        // SLA monitor: every minute, escalate complaints that missed their deadline
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sla-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                int n = complaints.escalateOverdue();
                if (n > 0) {
                    System.out.println("[SLA] Auto-escalated " + n + " overdue complaint(s)");
                }
            } catch (Exception e) {
                System.err.println("[SLA] " + e.getMessage());
            }
        }, 5, 60, TimeUnit.SECONDS);

        ApiServer server = new ApiServer(store, ai, auth, complaints, kb, analytics);
        server.start(port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Saving data and shutting down...");
            server.stop();
            store.save();
        }));

        System.out.println();
        System.out.println("==============================================================");
        System.out.println("  AI Customer Complaint Resolution Assistant is running");
        System.out.println("  Agent console  : http://localhost:" + port + "/");
        System.out.println("  Customer portal: http://localhost:" + port + "/portal");
        System.out.println("  Logins         : admin / admin123   priya / agent123   rahul / agent123");
        System.out.println("  AI mode        : " + (ai.llm().isEnabled()
                ? "Offline NLP + LLM replies (" + ai.llm().model() + ")" : "Offline NLP (no API key needed)"));
        System.out.println("  Frontend folder: " + server.frontendDir());
        System.out.println("  Data folder    : " + dataDir.toAbsolutePath());
        System.out.println("==============================================================");
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
