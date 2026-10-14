package com.cecil.codereview.git;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds files and lines changed in Git, so the reviewer can check only a
 * pull request / your latest work instead of the whole project.
 *
 * base == null  -> uncommitted changes (working tree vs HEAD) + new untracked files
 * base != null  -> everything changed since that branch/commit (e.g. "main")
 */
public final class GitChanges {
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private final Path repoDir;
    private final String base;

    public GitChanges(Path repoDir, String base) {
        this.repoDir = repoDir;
        this.base = base;
    }

    /** Changed files (relative to the repo root) that still exist. */
    public List<Path> changedFiles() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        if (base == null) {
            names.addAll(run("git", "diff", "--name-only", "--diff-filter=ACMR", "HEAD"));
            names.addAll(run("git", "ls-files", "--others", "--exclude-standard"));
        } else {
            names.addAll(run("git", "diff", "--name-only", "--diff-filter=ACMR", base + "...HEAD"));
        }
        Path root = repoRoot();
        List<Path> files = new ArrayList<>();
        for (String n : names) {
            Path p = root.resolve(n);
            if (Files.isRegularFile(p)) files.add(p);
        }
        return files;
    }

    /** Map of file (relative to repo root, with /) -> changed line numbers in the new version. */
    public Map<String, Set<Integer>> changedLines() throws IOException {
        Map<String, Set<Integer>> result = new HashMap<>();
        List<String> diff = base == null
                ? run("git", "diff", "-U0", "HEAD")
                : run("git", "diff", "-U0", base + "...HEAD");
        String current = null;
        for (String line : diff) {
            if (line.startsWith("+++ ")) {
                String name = line.substring(4).trim();
                current = name.startsWith("b/") ? name.substring(2) : null;
                if (current != null) result.computeIfAbsent(current, k -> new HashSet<>());
            } else if (current != null) {
                Matcher m = HUNK.matcher(line);
                if (m.find()) {
                    int start = Integer.parseInt(m.group(1));
                    int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                    for (int i = 0; i < count; i++) result.get(current).add(start + i);
                }
            }
        }
        if (base == null) {
            // untracked files are completely new: every line counts as changed
            for (String n : run("git", "ls-files", "--others", "--exclude-standard")) {
                Set<Integer> all = new HashSet<>();
                Path p = repoRoot().resolve(n);
                if (Files.isRegularFile(p)) {
                    long lines = Files.readAllLines(p, StandardCharsets.UTF_8).size();
                    for (int i = 1; i <= lines + 1; i++) all.add(i);
                }
                result.put(n, all);
            }
        }
        return result;
    }

    public Path repoRoot() throws IOException {
        List<String> out = run("git", "rev-parse", "--show-toplevel");
        if (out.isEmpty()) throw new IOException("Not a git repository: " + repoDir);
        return Path.of(out.get(0).trim());
    }

    private List<String> run(String... cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repoDir.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (!l.isBlank()) lines.add(l);
            }
        }
        try {
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("git command failed (" + String.join(" ", cmd) + "): "
                        + String.join(" ", lines));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git", e);
        }
        return lines;
    }
}
