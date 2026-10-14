package com.cecil.docqa.service;

import com.cecil.docqa.model.Document;
import com.cecil.docqa.model.DocumentChunk;
import com.cecil.docqa.repository.ChunkRepository;
import com.cecil.docqa.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;

/** Upload, list and delete documents, and keep the search index in sync. */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final TextExtractor extractor;
    private final TextChunker chunker;
    private final SearchIndex index;

    public DocumentService(DocumentRepository documents, ChunkRepository chunks, TextExtractor extractor,
                           TextChunker chunker, SearchIndex index) {
        this.documents = documents;
        this.chunks = chunks;
        this.extractor = extractor;
        this.chunker = chunker;
        this.index = index;
    }

    /** On start-up, load every saved chunk back into the in-memory search index. */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildIndex() {
        index.clear();
        for (DocumentChunk c : chunks.findAll()) index.add(toEntry(c));
        log.info("Search index ready: {} chunks from {} documents", index.size(), documents.count());
    }

    @Transactional
    public Document upload(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "untitled" : file.getOriginalFilename().strip();
        if (file.isEmpty()) throw new IllegalArgumentException("The file '" + name + "' is empty.");
        String ext = TextExtractor.extensionOf(name);
        if (!TextExtractor.SUPPORTED.contains(ext)) {
            throw new IllegalArgumentException("Unsupported file type '." + ext + "'. Use: "
                    + String.join(", ", TextExtractor.SUPPORTED.stream().sorted().toList()));
        }

        List<TextExtractor.Page> pages;
        try {
            pages = extractor.extract(name, file.getBytes());
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read '" + name + "'. Is the file damaged?");
        }
        List<TextChunker.Chunk> pieces = chunker.chunk(pages);
        if (pieces.isEmpty()) {
            throw new IllegalArgumentException("No text found in '" + name
                    + "'. If it is a scanned PDF (images only), it needs OCR first.");
        }

        StringBuilder full = new StringBuilder();
        for (TextExtractor.Page p : pages) {
            if (!full.isEmpty()) full.append("\n\n");
            full.append(TextChunker.clean(p.text()));
        }

        Document doc = new Document(name, ext, file.getSize());
        doc.setContent(full.toString());
        doc.setPageCount(ext.equals("pdf") ? pages.size() : 1);
        doc.setChunkCount(pieces.size());
        doc.setWordCount(full.toString().split("\\s+").length);
        doc = documents.save(doc);

        int i = 0;
        for (TextChunker.Chunk piece : pieces) {
            DocumentChunk saved = chunks.save(new DocumentChunk(doc.getId(), i++, piece.pageNumber(), piece.text()));
            index.add(toEntry(saved));
        }
        log.info("Uploaded '{}' -> {} chunks", name, pieces.size());
        return doc;
    }

    public List<Document> list() {
        return documents.findAllByOrderByUploadedAtDesc();
    }

    public Document get(Long id) {
        return documents.findById(id).orElseThrow(() -> new NoSuchElementException("Document " + id + " not found"));
    }

    @Transactional
    public void delete(Long id) {
        Document doc = get(id);
        chunks.deleteByDocumentId(id);
        documents.delete(doc);
        index.removeDocument(id);
    }

    public long count() {
        return documents.count();
    }

    private static SearchIndex.Entry toEntry(DocumentChunk c) {
        return new SearchIndex.Entry(c.getId(), c.getDocumentId(), c.getPageNumber(), c.getText());
    }
}
