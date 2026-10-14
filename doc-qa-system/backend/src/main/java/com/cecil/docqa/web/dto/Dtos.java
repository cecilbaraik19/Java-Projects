package com.cecil.docqa.web.dto;

import com.cecil.docqa.model.Document;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** All request/response shapes used by the REST API. */
public final class Dtos {

    private Dtos() {}

    public record DocumentDto(Long id, String fileName, String fileType, long sizeBytes,
                              int pageCount, int chunkCount, int wordCount, Instant uploadedAt) {
        public static DocumentDto from(Document d) {
            return new DocumentDto(d.getId(), d.getFileName(), d.getFileType(), d.getSizeBytes(),
                    d.getPageCount(), d.getChunkCount(), d.getWordCount(), d.getUploadedAt());
        }
    }

    public record DocumentTextDto(DocumentDto document, String text, boolean truncated) {}

    public record AskRequest(
            @NotBlank(message = "Please type a question")
            @Size(max = 1000, message = "Question is too long (max 1000 characters)")
            String question,
            List<Long> documentIds) {}

    public record SourceDto(int number, Long documentId, String fileName, Integer pageNumber,
                            String snippet, double score) {}

    public record AnswerDto(Long id, String question, String answer, String mode, String confidence,
                            List<SourceDto> sources, long durationMs, Instant askedAt, String note) {}

    public record StatusDto(String aiMode, String model, long documentCount, int chunkCount) {}

    public record ErrorDto(String error) {}
}
