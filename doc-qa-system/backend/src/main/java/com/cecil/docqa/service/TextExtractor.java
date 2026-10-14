package com.cecil.docqa.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pulls plain text out of PDF, Word (.docx) and text files. */
@Component
public class TextExtractor {

    public static final Set<String> SUPPORTED = Set.of("pdf", "docx", "txt", "md", "csv", "json", "html");

    /** One page of text. For non-PDF files the whole file is one "page" with number null. */
    public record Page(Integer number, String text) {}

    public static String extensionOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public List<Page> extract(String fileName, byte[] data) throws IOException {
        String ext = extensionOf(fileName);
        return switch (ext) {
            case "pdf" -> readPdf(data);
            case "docx" -> List.of(new Page(null, readDocx(data)));
            case "txt", "md", "csv", "json" -> List.of(new Page(null, new String(data, StandardCharsets.UTF_8)));
            case "html" -> List.of(new Page(null, stripHtml(new String(data, StandardCharsets.UTF_8))));
            default -> throw new IllegalArgumentException(
                    "Unsupported file type '." + ext + "'. Use one of: " + String.join(", ", SUPPORTED));
        };
    }

    private List<Page> readPdf(byte[] data) throws IOException {
        List<Page> pages = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= pdf.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(new Page(p, stripper.getText(pdf)));
            }
        }
        return pages;
    }

    private String readDocx(byte[] data) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private String stripHtml(String html) {
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">");
    }
}
