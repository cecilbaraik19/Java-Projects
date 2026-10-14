package com.cecil.docqa.service;

import com.cecil.docqa.config.DocQaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits long text into small overlapping pieces ("chunks").
 * It tries to cut at the end of a sentence so chunks stay readable.
 */
@Component
public class TextChunker {

    public record Chunk(Integer pageNumber, String text) {}

    private final int size;
    private final int overlap;

    @Autowired
    public TextChunker(DocQaProperties props) {
        this(props.chunk().size(), props.chunk().overlap());
    }

    public TextChunker(int size, int overlap) {
        if (size < 100) throw new IllegalArgumentException("chunk size must be at least 100");
        if (overlap < 0 || overlap >= size / 2) throw new IllegalArgumentException("overlap must be 0 .. size/2");
        this.size = size;
        this.overlap = overlap;
    }

    public List<Chunk> chunk(List<TextExtractor.Page> pages) {
        List<Chunk> out = new ArrayList<>();
        for (TextExtractor.Page page : pages) {
            for (String piece : split(clean(page.text()))) {
                out.add(new Chunk(page.number(), piece));
            }
        }
        return out;
    }

    /** Joins broken lines and squeezes extra spaces, but keeps paragraph breaks. */
    static String clean(String text) {
        if (text == null) return "";
        return text.replace("\r", "")
                .replaceAll("-\n(?=\\p{Ll})", "")          // re-join words split across lines
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    List<String> split(String text) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        int len = text.length();
        while (start < len) {
            int end = Math.min(start + size, len);
            if (end < len) end = bestBreak(text, start, end);
            String piece = text.substring(start, end).trim();
            if (!piece.isBlank()) pieces.add(piece);
            if (end >= len) break;
            int next = end - overlap;
            // start the next chunk on a word boundary
            while (next > start && next < len && !Character.isWhitespace(text.charAt(next - 1))) next++;
            start = Math.max(next, start + 1);
        }
        return pieces;
    }

    /** Find a nice place to cut: paragraph end, then sentence end, then a space. */
    private int bestBreak(String text, int start, int end) {
        int min = start + size / 2;
        int para = text.lastIndexOf("\n\n", end);
        if (para >= min) return para;
        for (int i = end - 1; i >= min; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '?' || c == '!') && i + 1 < text.length()
                    && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        int space = text.lastIndexOf(' ', end);
        return space >= min ? space : end;
    }
}
