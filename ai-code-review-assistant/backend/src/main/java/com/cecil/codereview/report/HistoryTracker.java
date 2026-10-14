package com.cecil.codereview.report;

import com.cecil.codereview.model.ReviewReport;
import com.cecil.codereview.model.Severity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Remembers past scores in history.csv so you can see if the code is getting better. */
public final class HistoryTracker {
    private static final String HEADER = "timestamp,target,files,codeLines,score,grade,critical,major,minor,info";

    private final Path file;

    public HistoryTracker(Path outputDir) {
        this.file = outputDir.resolve("history.csv");
    }

    /** Last recorded score for the same target, or null. */
    public Double previousScore(String target) {
        try {
            if (!Files.exists(file)) return null;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 1; i--) {
                String[] parts = lines.get(i).split(",");
                if (parts.length >= 6 && unquote(parts[1]).equals(target)) {
                    return Double.parseDouble(parts[4]);
                }
            }
        } catch (IOException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    public void record(String target, ReviewReport r) throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            Files.write(file, (HEADER + "\n").getBytes(StandardCharsets.UTF_8));
        }
        Map<Severity, Integer> c = r.countBySeverity();
        String row = String.format(Locale.ROOT, "%s,\"%s\",%d,%d,%.1f,%s,%d,%d,%d,%d%n",
                r.createdAt().withNano(0), target.replace("\"", "'").replace(",", ";"), r.files().size(),
                r.totalCodeLines(), r.overallScore(), r.grade(), c.get(Severity.CRITICAL), c.get(Severity.MAJOR),
                c.get(Severity.MINOR), c.get(Severity.INFO));
        Files.write(file, row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    /** All saved rows (oldest first) as column-name -> value maps. Used by the website. */
    public List<Map<String, String>> entries() {
        List<Map<String, String>> out = new java.util.ArrayList<>();
        try {
            if (!Files.exists(file)) return out;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String[] head = HEADER.split(",");
            for (int i = 1; i < lines.size(); i++) {
                String[] parts = lines.get(i).split(",");
                if (parts.length < head.length) continue;
                Map<String, String> row = new java.util.LinkedHashMap<>();
                for (int k = 0; k < head.length; k++) row.put(head[k], unquote(parts[k]));
                out.add(row);
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    /** Deletes the history file. */
    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }

    private static String unquote(String s) {
        return s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2 ? s.substring(1, s.length() - 1) : s;
    }
}
