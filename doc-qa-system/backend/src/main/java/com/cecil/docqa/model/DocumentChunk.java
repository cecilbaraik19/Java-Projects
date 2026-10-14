package com.cecil.docqa.model;

import jakarta.persistence.*;

/** A small piece of a document. Questions are matched against chunks. */
@Entity
@Table(name = "chunks", indexes = @Index(columnList = "documentId"))
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long documentId;

    /** Position of this chunk inside its document (0, 1, 2 ...). */
    private int chunkIndex;

    /** Page number for PDFs, null for other files. */
    private Integer pageNumber;

    @Column(length = 4000, nullable = false)
    private String text;

    protected DocumentChunk() {}

    public DocumentChunk(Long documentId, int chunkIndex, Integer pageNumber, String text) {
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.pageNumber = pageNumber;
        this.text = text;
    }

    public Long getId() { return id; }
    public Long getDocumentId() { return documentId; }
    public int getChunkIndex() { return chunkIndex; }
    public Integer getPageNumber() { return pageNumber; }
    public String getText() { return text; }
}
