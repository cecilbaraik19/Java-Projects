package com.cecil.docqa.model;

import jakarta.persistence.*;

import java.time.Instant;

/** An uploaded file and its extracted text. */
@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String fileType;

    private long sizeBytes;
    private int pageCount;
    private int chunkCount;
    private int wordCount;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String content;

    private Instant uploadedAt = Instant.now();

    protected Document() {}

    public Document(String fileName, String fileType, long sizeBytes) {
        this.fileName = fileName;
        this.fileType = fileType;
        this.sizeBytes = sizeBytes;
    }

    public Long getId() { return id; }
    public String getFileName() { return fileName; }
    public String getFileType() { return fileType; }
    public long getSizeBytes() { return sizeBytes; }
    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public int getWordCount() { return wordCount; }
    public void setWordCount(int wordCount) { this.wordCount = wordCount; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Instant getUploadedAt() { return uploadedAt; }
}
