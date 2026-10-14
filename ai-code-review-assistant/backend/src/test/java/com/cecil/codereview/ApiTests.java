package com.cecil.codereview;

import com.cecil.codereview.server.ApiServer;
import com.cecil.codereview.server.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Starts the real backend on a free port and calls the REST API like the frontend does. */
final class ApiTests {
    private ApiTests() { }

    private static int passed;
    private static int failed;

    static boolean run() {
        passed = 0;
        failed = 0;
        System.out.println("\nRunning API tests...\n");
        ApiServer server = null;
        try {
            Path root = Files.createTempDirectory("acr-test");
            Files.createDirectories(root.resolve("frontend"));
            Files.createDirectories(root.resolve("backend"));
            Files.createDirectories(root.resolve("samples"));
            Files.writeString(root.resolve("frontend/index.html"), "<h1>hello</h1>");
            Files.writeString(root.resolve("secret.txt"), "top secret");
            Files.writeString(root.resolve("samples/A.java"), "class A { }");
            server = new ApiServer(0, root);
            server.start();
            String base = "http://localhost:" + server.port();
            HttpClient http = HttpClient.newHttpClient();

            HttpResponse<String> r = get(http, base + "/api/info");
            check("GET /api/info works", r.statusCode() == 200 && r.body().contains("\"ruleCount\":"));

            r = get(http, base + "/api/rules");
            check("GET /api/rules lists rules", r.statusCode() == 200 && r.body().contains("SQL_INJECTION"));

            r = get(http, base + "/api/samples");
            check("GET /api/samples returns sample files", r.body().contains("A.java"));

            String code = "class T { boolean f(String s) { return s == \\\"yes\\\"; } }";
            String body = "{\"files\":[{\"name\":\"T.java\",\"content\":\"" + code + "\"}],\"saveHistory\":true}";
            r = post(http, base + "/api/review", body);
            Map<String, Object> json = JsonParser.parseObject(r.body());
            @SuppressWarnings("unchecked")
            Map<String, Object> report = (Map<String, Object>) json.get("report");
            check("POST /api/review returns a report", r.statusCode() == 200 && report != null && report.containsKey("grade"));
            check("Review finds STRING_EQUALITY", r.body().contains("STRING_EQUALITY"));

            r = post(http, base + "/api/fix", body);
            check("POST /api/fix returns fixed code", r.body().contains("\\\"yes\\\".equals(s)"));

            r = post(http, base + "/api/export?format=md", body);
            check("POST /api/export?format=md returns markdown", r.statusCode() == 200 && r.body().startsWith("## AI Code Review"));

            r = get(http, base + "/api/history");
            @SuppressWarnings("unchecked")
            List<Object> entries = (List<Object>) JsonParser.parseObject(r.body()).get("entries");
            check("Review was saved to history", entries != null && entries.size() == 1);

            r = post(http, base + "/api/load-path", "{\"path\":\"" + root.resolve("samples").toString().replace("\\", "\\\\") + "\"}");
            check("POST /api/load-path reads a folder", r.statusCode() == 200 && r.body().contains("A.java"));

            r = post(http, base + "/api/review", "{\"files\":[]}");
            check("Empty request gives 400", r.statusCode() == 400);

            r = post(http, base + "/api/review", "{broken json");
            check("Broken JSON gives 400", r.statusCode() == 400);

            HttpResponse<String> form = http.send(HttpRequest.newBuilder(URI.create(base + "/api/review"))
                    .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            check("Non-JSON POST is blocked (CSRF protection)", form.statusCode() == 415);

            r = get(http, base + "/");
            check("Frontend index.html is served", r.statusCode() == 200 && r.body().contains("hello"));

            r = get(http, base + "/..%2Fsecret.txt");
            check("Path traversal is blocked", r.statusCode() == 404 && !r.body().contains("top secret"));

            r = get(http, base + "/api/nothing");
            check("Unknown API gives 404", r.statusCode() == 404);
        } catch (Exception e) {
            check("API tests crashed: " + e, false);
        } finally {
            if (server != null) server.stop();
        }
        System.out.println();
        System.out.println("API result: " + passed + " passed, " + failed + " failed.");
        return failed == 0;
    }

    private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> post(HttpClient http, String url, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (ok) passed++; else failed++;
    }
}
