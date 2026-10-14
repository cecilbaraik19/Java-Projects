package com.cecil.docqa.model;

import jakarta.persistence.*;

import java.time.Instant;

/** One question and its answer, kept as history. */
@Entity
@Table(name = "qa_history")
public class QaRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 2000, nullable = false)
    private String question;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String answer;

    /** "claude" or "offline". */
    private String mode;

    private String confidence;

    /** Sources as JSON text, so the UI can show them again later. */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String sourcesJson;

    private long durationMs;

    private Instant askedAt = Instant.now();

    protected QaRecord() {}

    public QaRecord(String question, String answer, String mode, String confidence,
                    String sourcesJson, long durationMs) {
        this.question = question;
        this.answer = answer;
        this.mode = mode;
        this.confidence = confidence;
        this.sourcesJson = sourcesJson;
        this.durationMs = durationMs;
    }

    public Long getId() { return id; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getMode() { return mode; }
    public String getConfidence() { return confidence; }
    public String getSourcesJson() { return sourcesJson; }
    public long getDurationMs() { return durationMs; }
    public Instant getAskedAt() { return askedAt; }
}
