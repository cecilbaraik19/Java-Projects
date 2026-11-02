package com.cecil.rag.rag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Step 0 of RAG: get plain text out of an uploaded file. Supports .txt, .md, .csv, .html, .docx and .pdf,
 * with only the JDK:
 *   - .docx is a ZIP file; the text is in word/document.xml (paragraphs are &lt;w:p&gt;, headings use "Heading" styles)
 *   - .pdf pages are "content streams" (often zipped with FlateDecode); text is drawn with the Tj / TJ commands
 * Scanned PDFs (only pictures) have no text, so nothing can be read from them.
 */
public final class DocReader {
    private DocReader() {}

    public static String read(String fileName, byte[] data) {
        String name = fileName.toLowerCase();
        if (name.endsWith(".docx")) return docx(data);
        if (name.endsWith(".pdf")) return pdf(data);
        String s = new String(data, StandardCharsets.UTF_8);
        if (s.startsWith("﻿")) s = s.substring(1);
        if (name.endsWith(".html") || name.endsWith(".htm")) return html(s);
        if (name.endsWith(".csv")) return csv(s);
        return s.replace("\r\n", "\n");
    }

    public static boolean supported(String fileName) {
        return fileName.toLowerCase().matches(".*\\.(txt|md|markdown|csv|html|htm|docx|pdf)$");
    }

    // ------------------------------------------------------------------ CSV -> markdown table

    static String csv(String s) {
        StringBuilder sb = new StringBuilder();
        String[] lines = s.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> cells = splitCsv(lines[i]);
            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (i == 0) sb.append("|").append(" --- |".repeat(cells.size())).append("\n");
        }
        return sb.toString();
    }

    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') { if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = !q; }
            else if (c == ',' && !q) { out.add(cur.toString().strip()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString().strip());
        return out;
    }

    // ------------------------------------------------------------------ HTML

    static String html(String s) {
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<h1[^>]*>", "\n# ").replaceAll("(?i)<h2[^>]*>", "\n## ").replaceAll("(?i)<h3[^>]*>", "\n### ");
        s = s.replaceAll("(?i)<li[^>]*>", "\n- ").replaceAll("(?i)</(p|div|h\\d|li|tr|br)>|<br\\s*/?>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        return entities(s).replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n\n").strip();
    }

    private static String entities(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&#8377;", "₹");
    }

    // ------------------------------------------------------------------ DOCX

    static String docx(byte[] data) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.getName().equals("word/document.xml")) continue;
                String xml = new String(readLimited(zip, 30_000_000), StandardCharsets.UTF_8);
                StringBuilder sb = new StringBuilder();
                Matcher para = Pattern.compile("(?s)<w:p[ >].*?</w:p>|<w:p/>").matcher(xml);
                while (para.find()) {
                    String p = para.group();
                    Matcher style = Pattern.compile("<w:pStyle w:val=\"(?:Heading|heading)(\\d)\"").matcher(p);
                    String prefix = style.find() ? "#".repeat(Math.min(3, Integer.parseInt(style.group(1)) + 1)) + " " : "";
                    if (p.contains("<w:numPr>")) prefix = "- ";
                    StringBuilder line = new StringBuilder();
                    Matcher t = Pattern.compile("(?s)<w:t(?: [^>]*)?>(.*?)</w:t>|<w:tab/>").matcher(p);
                    while (t.find()) line.append(t.group(1) == null ? " " : entities(t.group(1)));
                    if (line.length() > 0) sb.append(prefix).append(line).append("\n");
                    else sb.append("\n");
                }
                return sb.toString().replaceAll("\\n{3,}", "\n\n").strip();
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not read this Word file: " + ex.getMessage());
        }
        throw new IllegalArgumentException("This .docx file has no word/document.xml inside.");
    }

    private static byte[] readLimited(java.io.InputStream in, int max) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new java.io.IOException("file inside the ZIP is too big");
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ PDF

    static String pdf(byte[] data) {
        String raw = new String(data, StandardCharsets.ISO_8859_1);
        if (!raw.startsWith("%PDF")) throw new IllegalArgumentException("This is not a PDF file.");
        StringBuilder text = new StringBuilder();
        Matcher m = Pattern.compile("(?s)<<(.{0,600}?)>>\\s*stream\\r?\\n").matcher(raw);
        int from = 0;
        while (m.find(from)) {
            String dict = m.group(1);
            int start = m.end();
            int end = raw.indexOf("endstream", start);
            if (end < 0) break;
            from = end;
            if (dict.contains("/Subtype /Image") || dict.contains("/Subtype/Image") || dict.contains("/FontFile")
                    || dict.contains("/Length1") || dict.contains("/Type /XRef") || dict.contains("/Type/XRef")) continue;
            byte[] bytes = raw.substring(start, end).getBytes(StandardCharsets.ISO_8859_1);
            try {
                if (dict.contains("/ASCII85Decode") || dict.contains("/A85")) bytes = ascii85(bytes);
                if (dict.contains("/FlateDecode") || dict.contains("/Fl ") || dict.contains("/Fl]")) bytes = inflate(bytes);
            } catch (Exception e) { continue; }
            String content = new String(bytes, StandardCharsets.ISO_8859_1);
            if (!content.contains("Tj") && !content.contains("TJ")) continue;
            text.append(contentText(content)).append("\n\n");
        }
        String out = text.toString().replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n").strip();
        if (out.isBlank()) throw new IllegalArgumentException("No text found in this PDF. It may be a scanned image (needs OCR first).");
        return markHeadings(out);
    }

    /**
     * PDFs have no "heading" tag, so short lines that look like titles (few words, starts with a capital,
     * no full stop at the end) are marked as headings. The first one becomes the title.
     */
    static String markHeadings(String text) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String line : text.split("\n")) {
            String l = line.strip();
            int words = l.isEmpty() ? 0 : l.split("\\s+").length;
            boolean heading = words >= 1 && words <= (first ? 12 : 6) && Character.isUpperCase(l.charAt(0))
                    && !l.matches(".*[.,;:]$") && !l.matches(".*\\d{3,}.*");
            if (heading) {
                sb.append(first ? "# " : "## ").append(l).append('\n');
                first = false;
            } else sb.append(l).append('\n');
        }
        return sb.toString().strip();
    }

    /** Reads the text drawing commands of one page. */
    static String contentText(String c) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\\[((?:\\\\.|[^\\]])*)]\\s*TJ|\\(((?:\\\\.|[^\\\\)])*)\\)\\s*(?:Tj|'|\")|<([0-9A-Fa-f\\s]+)>\\s*Tj|(T\\*|Td|TD|Tm|ET)\\b").matcher(c);
        boolean lineHasText = false;
        while (m.find()) {
            if (m.group(1) != null) {
                Matcher s = Pattern.compile("\\(((?:\\\\.|[^\\\\)])*)\\)|(-?\\d+(?:\\.\\d+)?)").matcher(m.group(1));
                while (s.find()) {
                    if (s.group(1) != null) sb.append(unescape(s.group(1)));
                    else if (Double.parseDouble(s.group(2)) < -200) sb.append(' '); // big gap = space
                }
                lineHasText = true;
            } else if (m.group(2) != null) {
                sb.append(unescape(m.group(2)));
                lineHasText = true;
            } else if (m.group(3) != null) {
                String hex = m.group(3).replaceAll("\\s", "");
                for (int i = 0; i + 1 < hex.length(); i += 2) sb.append((char) Integer.parseInt(hex.substring(i, i + 2), 16));
                lineHasText = true;
            } else if (lineHasText) {
                sb.append('\n');
                lineHasText = false;
            }
        }
        return sb.toString().replace('\u0092', '\'').replace('\u0095', '-').replace('\u0080', '€');
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { sb.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 'r' -> {}
                case 't' -> sb.append(' ');
                case '(', ')', '\\' -> sb.append(n);
                default -> {
                    if (n >= '0' && n <= '7') {
                        int j = i, v = 0;
                        while (j < s.length() && j < i + 3 && s.charAt(j) >= '0' && s.charAt(j) <= '7') v = v * 8 + (s.charAt(j++) - '0');
                        sb.append((char) v);
                        i = j - 1;
                    } else sb.append(n);
                }
            }
        }
        return sb.toString();
    }

    static byte[] inflate(byte[] in) throws Exception {
        Inflater inf = new Inflater();
        inf.setInput(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
            out.write(buf, 0, n);
            if (out.size() > 20_000_000) throw new IllegalStateException("stream too big");
        }
        inf.end();
        return out.toByteArray();
    }

    static byte[] ascii85(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long tuple = 0;
        int count = 0;
        for (byte b0 : in) {
            char c = (char) (b0 & 0xff);
            if (c == '~') break;
            if (Character.isWhitespace(c)) continue;
            if (c == 'z' && count == 0) { out.write(0); out.write(0); out.write(0); out.write(0); continue; }
            if (c < '!' || c > 'u') continue;
            tuple = tuple * 85 + (c - '!');
            if (++count == 5) {
                for (int k = 3; k >= 0; k--) out.write((int) (tuple >> (8 * k)) & 0xff);
                tuple = 0;
                count = 0;
            }
        }
        if (count > 1) {
            for (int k = count; k < 5; k++) tuple = tuple * 85 + 84;
            for (int k = 3; k > 4 - count; k--) out.write((int) (tuple >> (8 * k)) & 0xff);
        }
        return out.toByteArray();
    }
}
