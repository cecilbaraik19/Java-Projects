package com.cecil.invoice.extract;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Gets the text out of a PDF with no outside library.
 *
 * A PDF page is drawn by a small program called a "content stream", usually zipped
 * (FlateDecode). Text is drawn with commands like:
 *     BT  /F1 10 Tf  1 0 0 1 72 700 Tm  (Invoice No: 2041) Tj  ET
 * which means: begin text, font F1 size 10, move to x=72 y=700, show "Invoice No: 2041".
 *
 * This class unzips each stream, finds the text commands and remembers where each
 * piece is drawn. Pieces with the same height (y) become one line, placed left to right
 * at their x position, so columns stay lined up - the extractor needs that.
 *
 * Works for PDFs made by software (billing apps, Word, "Save as PDF").
 * A scanned PDF is only a photo, so it has no text: use Claude for those.
 */
public final class PdfText {
    private PdfText() {}

    private record Piece(double x, double y, double size, String text) {}

    public static String extract(byte[] pdf) {
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        if (!raw.startsWith("%PDF")) throw new IllegalArgumentException("This file is not a PDF.");
        List<Piece> pieces = new ArrayList<>();
        int pos = 0;
        while (true) {
            int s = raw.indexOf("stream", pos);
            if (s < 0) break;
            if (s >= 3 && raw.startsWith("end", s - 3)) { pos = s + 6; continue; }
            int dataStart = s + 6;
            if (dataStart < raw.length() && raw.charAt(dataStart) == '\r') dataStart++;
            if (dataStart < raw.length() && raw.charAt(dataStart) == '\n') dataStart++;
            int e = raw.indexOf("endstream", dataStart);
            if (e < 0) break;
            int dictStart = raw.lastIndexOf("obj", s);
            String dict = dictStart < 0 ? "" : raw.substring(dictStart, s);
            pos = e + 9;
            if (dict.contains("/Image") || dict.contains("/FontFile") || dict.contains("/Length1") || dict.contains("/XRef")
                    || dict.contains("/ObjStm") || dict.contains("/Metadata")) continue;
            byte[] data = Arrays.copyOfRange(pdf, dataStart, e);
            // filters are undone in the order they are listed, e.g. [ /ASCII85Decode /FlateDecode ]
            boolean ok = true;
            java.util.regex.Matcher fm = java.util.regex.Pattern.compile("/(ASCII85Decode|A85|FlateDecode|Fl|ASCIIHexDecode|AHx|[A-Za-z0-9]+Decode)").matcher(dict);
            while (ok && fm.find()) {
                switch (fm.group(1)) {
                    case "ASCII85Decode", "A85" -> data = ascii85(data);
                    case "FlateDecode", "Fl" -> data = inflate(data);
                    case "ASCIIHexDecode", "AHx" -> data = asciiHex(data);
                    default -> data = null;                         // other compressions (images) are not supported
                }
                ok = data != null;
            }
            if (!ok) continue;
            String content = new String(data, StandardCharsets.ISO_8859_1);
            if (content.contains("BT") && (content.contains("Tj") || content.contains("TJ") || content.contains("'"))) {
                pieces.addAll(parse(content));
            }
        }
        return layout(pieces);
    }

    /** ASCII85 (base-85 text) back to bytes. */
    static byte[] ascii85(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long tuple = 0; int count = 0;
        for (byte b0 : in) {
            int c = b0 & 0xff;
            if (c == '~') break;
            if (Character.isWhitespace(c)) continue;
            if (c == 'z' && count == 0) { out.write(0); out.write(0); out.write(0); out.write(0); continue; }
            if (c < '!' || c > 'u') return null;
            tuple = tuple * 85 + (c - '!');
            if (++count == 5) {
                for (int k = 3; k >= 0; k--) out.write((int) (tuple >> (8 * k)) & 0xff);
                tuple = 0; count = 0;
            }
        }
        if (count > 1) {
            for (int k = count; k < 5; k++) tuple = tuple * 85 + 84;
            for (int k = 3; k >= 5 - count; k--) out.write((int) (tuple >> (8 * k)) & 0xff);
        }
        return out.toByteArray();
    }

    /** Hex text back to bytes. */
    static byte[] asciiHex(byte[] in) {
        String s = new String(in, StandardCharsets.ISO_8859_1).replaceAll("[^0-9A-Fa-f]", "");
        if (s.length() % 2 == 1) s += "0";
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static byte[] inflate(byte[] data) {
        Inflater inf = new Inflater();
        inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                out.write(buf, 0, n);
                if (out.size() > 20_000_000) return null;
            }
            return out.toByteArray();
        } catch (DataFormatException ex) {
            return out.size() > 0 ? out.toByteArray() : null;
        } finally {
            inf.end();
        }
    }

    // ------------------------------------------------------------------ content stream

    private static List<Piece> parse(String c) {
        List<Piece> out = new ArrayList<>();
        List<Object> operands = new ArrayList<>();
        double lineX = 0, lineY = 0, x = 0, y = 0, fontSize = 10, scale = 1, leading = 12;
        int i = 0, n = c.length();
        while (i < n) {
            char ch = c.charAt(i);
            if (Character.isWhitespace(ch)) { i++; continue; }
            if (ch == '%') { while (i < n && c.charAt(i) != '\n' && c.charAt(i) != '\r') i++; continue; }
            if (ch == '(') {                                   // (literal string)
                StringBuilder sb = new StringBuilder();
                int depth = 1; i++;
                while (i < n && depth > 0) {
                    char k = c.charAt(i++);
                    if (k == '\\' && i < n) {
                        char e = c.charAt(i++);
                        switch (e) {
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'b', 'f' -> { }
                            case '(', ')', '\\' -> sb.append(e);
                            case '\r', '\n' -> { }
                            default -> {
                                if (e >= '0' && e <= '7') {
                                    int v = e - '0', cnt = 1;
                                    while (cnt < 3 && i < n && c.charAt(i) >= '0' && c.charAt(i) <= '7') { v = v * 8 + (c.charAt(i++) - '0'); cnt++; }
                                    sb.append((char) v);
                                } else sb.append(e);
                            }
                        }
                    } else if (k == '(') { depth++; sb.append(k); }
                    else if (k == ')') { depth--; if (depth > 0) sb.append(k); }
                    else sb.append(k);
                }
                operands.add(new StringBuilder(decode(sb.toString())));
                continue;
            }
            if (ch == '<' && i + 1 < n && c.charAt(i + 1) == '<') { i += 2; continue; }    // dictionaries are skipped
            if (ch == '>' && i + 1 < n && c.charAt(i + 1) == '>') { i += 2; continue; }
            if (ch == '<') {                                   // <hex string>
                int end = c.indexOf('>', i);
                if (end < 0) break;
                String hex = c.substring(i + 1, end).replaceAll("\\s", "");
                if (hex.length() % 2 == 1) hex += "0";
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k + 1 < hex.length(); k += 2) {
                    try { sb.append((char) Integer.parseInt(hex.substring(k, k + 2), 16)); } catch (NumberFormatException ignored) { }
                }
                operands.add(new StringBuilder(decode(sb.toString())));
                i = end + 1;
                continue;
            }
            if (ch == '[') { operands.add("["); i++; continue; }
            if (ch == ']') {                                   // collect the array
                List<Object> arr = new ArrayList<>();
                int k = operands.lastIndexOf("[");
                if (k >= 0) {
                    arr.addAll(operands.subList(k + 1, operands.size()));
                    while (operands.size() > k) operands.remove(operands.size() - 1);
                }
                operands.add(arr);
                i++;
                continue;
            }
            if (ch == '/') {                                   // a name like /F1
                int j = i + 1;
                while (j < n && !Character.isWhitespace(c.charAt(j)) && "/[]()<>{}%".indexOf(c.charAt(j)) < 0) j++;
                operands.add(c.substring(i, j));
                i = j;
                continue;
            }
            if (ch == '-' || ch == '+' || ch == '.' || Character.isDigit(ch)) {
                int j = i + 1;
                while (j < n && (Character.isDigit(c.charAt(j)) || c.charAt(j) == '.')) j++;
                try { operands.add(Double.parseDouble(c.substring(i, j))); } catch (NumberFormatException ex) { operands.add(0.0); }
                i = j;
                continue;
            }
            // an operator
            int j = i;
            while (j < n && !Character.isWhitespace(c.charAt(j)) && "/[]()<>{}%".indexOf(c.charAt(j)) < 0) j++;
            if (j == i) j = i + 1;
            String op = c.substring(i, j);
            i = j;
            switch (op) {
                case "BT" -> { lineX = 0; lineY = 0; x = 0; y = 0; }
                case "Tf" -> fontSize = Math.abs(num(operands, 1, fontSize));
                case "TL" -> leading = num(operands, 1, leading);
                case "Td" -> { lineX += num(operands, 2, 0); lineY += num(operands, 1, 0); x = lineX; y = lineY; }
                case "TD" -> { double ty = num(operands, 1, 0); leading = -ty; lineX += num(operands, 2, 0); lineY += ty; x = lineX; y = lineY; }
                case "Tm" -> { lineX = num(operands, 2, 0); lineY = num(operands, 1, 0); x = lineX; y = lineY;
                               double sc = Math.abs(num(operands, 3, 1)); scale = sc > 0.01 ? sc : 1; }
                case "T*" -> { lineY -= leading; x = lineX; y = lineY; }
                case "Tj", "'", "\"" -> {
                    if (!op.equals("Tj")) { lineY -= leading; y = lineY; x = lineX; }
                    String t = lastString(operands);
                    double size = fontSize * scale;
                    if (t != null && !t.isEmpty()) { out.add(new Piece(x, y, size, t)); x += t.length() * size * 0.5; }
                }
                case "TJ" -> {
                    Object last = operands.isEmpty() ? null : operands.get(operands.size() - 1);
                    if (last instanceof List<?> arr) {
                        StringBuilder sb = new StringBuilder();
                        for (Object o : arr) {
                            if (o instanceof StringBuilder s) sb.append(s);
                            else if (o instanceof Double d && d < -180) sb.append(' ');
                        }
                        double size = fontSize * scale;
                        if (sb.length() > 0) { out.add(new Piece(x, y, size, sb.toString())); x += sb.length() * size * 0.5; }
                    }
                }
                default -> { }
            }
            operands.clear();
        }
        return out;
    }

    private static double num(List<Object> ops, int fromEnd, double fallback) {
        int k = ops.size() - fromEnd;
        if (k < 0 || !(ops.get(k) instanceof Double d)) return fallback;
        return d;
    }

    private static String lastString(List<Object> ops) {
        for (int k = ops.size() - 1; k >= 0; k--) if (ops.get(k) instanceof StringBuilder s) return s.toString();
        return null;
    }

    /** WinAnsi bytes to text; characters that are not printable are dropped. */
    private static String decode(String bytes) {
        StringBuilder sb = new StringBuilder();
        for (char ch : bytes.toCharArray()) {
            if (ch == 0x92 || ch == 0x91) sb.append('\'');
            else if (ch == 0x93 || ch == 0x94) sb.append('"');
            else if (ch == 0x96 || ch == 0x97) sb.append('-');
            else if (ch == 0x95) sb.append('*');
            else if (ch == 0x80) sb.append("EUR");
            else if (ch >= 0x20 && ch < 0x7f || ch >= 0xa0 && ch <= 0xff) sb.append(ch);
            else if (ch == '\t') sb.append(' ');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ layout

    /** Put the pieces back on lines (top to bottom), each at a column worked out from its x position. */
    private static String layout(List<Piece> pieces) {
        if (pieces.isEmpty()) return "";
        List<Piece> sorted = new ArrayList<>(pieces);
        sorted.sort((a, b) -> Math.abs(a.y - b.y) > 2 ? Double.compare(b.y, a.y) : Double.compare(a.x, b.x));
        double minX = Double.MAX_VALUE;
        for (Piece p : sorted) minX = Math.min(minX, p.x);
        // a typical character width (half of the most common font size)
        double charW = 0;
        for (Piece p : sorted) charW += p.size * 0.5;
        charW = Math.max(2.5, charW / sorted.size());

        StringBuilder text = new StringBuilder();
        StringBuilder line = new StringBuilder();
        double lineY = sorted.get(0).y;
        for (Piece p : sorted) {
            if (Math.abs(p.y - lineY) > 2) {
                text.append(line.toString().replaceAll("\\s+$", "")).append('\n');
                double gap = (lineY - p.y) / Math.max(8, p.size * 1.15);
                if (gap > 1.8) text.append('\n');                // a bigger gap = a blank line
                line.setLength(0);
                lineY = p.y;
            }
            int col = (int) Math.round((p.x - minX) / charW);
            if (line.length() < col) line.append(" ".repeat(col - line.length()));
            else if (line.length() > 0 && line.charAt(line.length() - 1) != ' ') line.append(' ');
            line.append(p.text);
        }
        text.append(line.toString().replaceAll("\\s+$", ""));
        return text.toString().strip();
    }
}
