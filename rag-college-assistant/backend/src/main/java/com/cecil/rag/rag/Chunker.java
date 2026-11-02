package com.cecil.rag.rag;

import com.cecil.rag.model.Chunk;
import com.cecil.rag.model.Doc;
import com.cecil.rag.util.Text;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Step 1 of RAG: cut each document into small pieces ("chunks").
 *
 * Rules:
 *  - a chunk never crosses a heading, so every chunk is about one topic, and it remembers its heading path
 *  - a chunk is about N words (Settings.chunkWords) and always ends at the end of a sentence
 *  - the next chunk repeats the last few words (overlap) so an idea cut in half is not lost
 *  - a table row becomes "Column: value; Column: value" so it still makes sense on its own
 */
public final class Chunker {

    /** Text that tries to give orders to the AI. Such chunks are kept out of answers. */
    private static final Pattern INJECTION = Pattern.compile(
            "(?i)ignore (?:all |any )?(?:the )?(?:previous|prior|above|earlier) (?:instructions|rules|prompts?)"
                    + "|disregard (?:the |all )?(?:previous|above|system) |you are now (?:a|an|in) "
                    + "|system prompt|reveal (?:your|the) (?:instructions|prompt)|act as (?:an? )?(?:unrestricted|jailbroken)"
                    + "|<\\s*/?\\s*(?:system|instructions?)\\s*>|do anything now|developer mode");

    private final int targetWords;
    private final int overlapWords;

    public Chunker(int targetWords, int overlapWords) {
        this.targetWords = Math.max(20, targetWords);
        this.overlapWords = Math.max(0, Math.min(overlapWords, targetWords / 2));
    }

    public List<Chunk> chunk(Doc doc) {
        List<Chunk> out = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        List<Integer> headingLevels = new ArrayList<>();
        List<String> units = new ArrayList<>();
        List<String> tableHeader = null;

        for (String raw : doc.text.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.startsWith("#")) {
                flush(doc, headingPath, units, out);
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                String title = line.substring(level).strip();
                // a heading closes every heading of the same or a lower level ("## B" after "## A")
                while (!headingLevels.isEmpty() && headingLevels.get(headingLevels.size() - 1) >= level) {
                    headingLevels.remove(headingLevels.size() - 1);
                    headingPath.remove(headingPath.size() - 1);
                }
                // the document title itself (level 1) is not repeated in the path
                if (level > 1 || !title.equalsIgnoreCase(doc.title)) { headingPath.add(title); headingLevels.add(level); }
                tableHeader = null;
                continue;
            }
            if (line.isEmpty()) { tableHeader = null; continue; }
            if (line.startsWith("|")) {
                List<String> cells = cells(line);
                if (line.matches("^\\|[\\s:|-]+\\|?$")) continue; // the |---|---| line
                if (tableHeader == null) { tableHeader = cells; continue; }
                units.add(tableRow(tableHeader, cells));
                continue;
            }
            tableHeader = null;
            units.addAll(Text.sentences(line));
        }
        flush(doc, headingPath, units, out);
        for (int i = 0; i < out.size(); i++) {
            out.get(i).ordinal = i + 1;
            out.get(i).id = doc.id + "#" + (i + 1);
        }
        return out;
    }

    private void flush(Doc doc, List<String> path, List<String> units, List<Chunk> out) {
        if (units.isEmpty()) return;
        String section = path.isEmpty() ? "Overview" : String.join(" > ", path);
        List<String> current = new ArrayList<>();
        int words = 0;
        int i = 0;
        while (i < units.size()) {
            String u = units.get(i);
            int w = Text.wordCount(u);
            if (words > 0 && words + w > targetWords) {
                out.add(make(doc, section, current));
                // start the next chunk with the last sentences (overlap)
                List<String> keep = new ArrayList<>();
                int kept = 0;
                for (int j = current.size() - 1; j >= 0 && kept < overlapWords; j--) {
                    keep.add(0, current.get(j));
                    kept += Text.wordCount(current.get(j));
                }
                if (kept >= words) { keep.clear(); kept = 0; } // overlap would repeat everything
                current = keep;
                words = kept;
            }
            current.add(u);
            words += w;
            i++;
        }
        // a very small tail is joined to the chunk before it
        if (!current.isEmpty()) {
            if (words < targetWords / 4 && !out.isEmpty() && out.get(out.size() - 1).section.equals(section)) {
                Chunk last = out.get(out.size() - 1);
                LinkedHashSet<String> merged = new LinkedHashSet<>(List.of(last.text.split("\n")));
                merged.addAll(current);
                out.set(out.size() - 1, make(doc, section, new ArrayList<>(merged)));
            } else {
                out.add(make(doc, section, current));
            }
        }
        units.clear();
    }

    private Chunk make(Doc doc, String section, List<String> sentences) {
        Chunk c = new Chunk();
        c.doc = doc;
        c.section = section;
        c.text = String.join("\n", sentences);
        // the title and heading are part of what the chunk is "about", so they are searchable too
        c.tokens = new ArrayList<>(Text.tokens(doc.title + " " + section));
        c.tokens.addAll(Text.tokens(c.text));
        c.tokenSet = new HashSet<>(c.tokens);
        var m = INJECTION.matcher(c.text);
        if (m.find()) {
            c.flagged = true;
            c.flagReason = "Looks like an instruction to the AI: \"" + Text.clip(m.group(), 60) + "\"";
        }
        return c;
    }

    private static List<String> cells(String line) {
        String inner = line.replaceAll("^\\||\\|$", "");
        List<String> cells = new ArrayList<>();
        for (String c : inner.split("\\|")) cells.add(c.strip());
        return cells;
    }

    private static String tableRow(List<String> header, List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).isEmpty()) continue;
            if (sb.length() > 0) sb.append("; ");
            String h = i < header.size() ? header.get(i) : "";
            sb.append(h.isEmpty() ? "" : h + ": ").append(cells.get(i));
        }
        String s = sb.toString();
        return s.endsWith(".") ? s : s + ".";
    }

    public static boolean looksLikeInjection(String text) { return INJECTION.matcher(text).find(); }
}
