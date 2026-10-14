package com.cecil.docqa.service;

import com.cecil.docqa.config.DocQaProperties;
import com.cecil.docqa.model.Document;
import com.cecil.docqa.model.QaRecord;
import com.cecil.docqa.repository.DocumentRepository;
import com.cecil.docqa.repository.QaRecordRepository;
import com.cecil.docqa.service.answer.AnswerGenerator.Answer;
import com.cecil.docqa.service.answer.AnswerGenerator.Context;
import com.cecil.docqa.service.answer.ClaudeAnswerGenerator;
import com.cecil.docqa.service.answer.ExtractiveAnswerGenerator;
import com.cecil.docqa.web.dto.Dtos.AnswerDto;
import com.cecil.docqa.web.dto.Dtos.SourceDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The question-answering flow:
 * 1. find the best matching chunks (search),
 * 2. give them to an answerer (Claude, or the offline one),
 * 3. save the question and answer in history.
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);
    private static final String NOTHING_FOUND =
            "I couldn't find anything about that in your documents. Try other words, or upload a document that covers it.";

    private final SearchIndex index;
    private final DocumentRepository documents;
    private final QaRecordRepository history;
    private final ClaudeAnswerGenerator claude;
    private final ExtractiveAnswerGenerator offline;
    private final ObjectMapper mapper;
    private final int topK;

    public QaService(SearchIndex index, DocumentRepository documents, QaRecordRepository history,
                     ClaudeAnswerGenerator claude, ExtractiveAnswerGenerator offline,
                     ObjectMapper mapper, DocQaProperties props) {
        this.index = index;
        this.documents = documents;
        this.history = history;
        this.claude = claude;
        this.offline = offline;
        this.mapper = mapper;
        this.topK = props.retrieval().topK();
    }

    public AnswerDto ask(String question, List<Long> documentIds) {
        long start = System.currentTimeMillis();
        String q = question.strip();

        if (documents.count() == 0) {
            throw new IllegalStateException("Upload a document first, then ask your question.");
        }

        List<SearchIndex.Hit> hits = index.search(q, topK, documentIds);
        Set<Long> hitDocIds = hits.stream().map(h -> h.entry().documentId()).collect(Collectors.toSet());
        Map<Long, String> names = documents.findAllById(hitDocIds).stream()
                .collect(Collectors.toMap(Document::getId, Document::getFileName, (a, b) -> a));

        List<SourceDto> sources = new ArrayList<>();
        List<Context> contexts = new ArrayList<>();
        int n = 1;
        for (SearchIndex.Hit h : hits) {
            String fileName = names.getOrDefault(h.entry().documentId(), "unknown");
            contexts.add(new Context(n, fileName, h.entry().pageNumber(), h.entry().text()));
            sources.add(new SourceDto(n, h.entry().documentId(), fileName, h.entry().pageNumber(),
                    h.entry().text(), round(h.score())));
            n++;
        }

        String answerText;
        String mode;
        String note = null;
        if (hits.isEmpty()) {
            answerText = NOTHING_FOUND;
            mode = claude.isEnabled() ? "claude" : "offline";
        } else {
            Answer answer = answer(q, contexts);
            answerText = answer.text();
            mode = answer.mode();
            if (claude.isEnabled() && mode.equals("offline")) {
                note = "Claude could not be reached, so this answer was made offline.";
            }
        }

        String confidence = confidence(hits);
        long took = System.currentTimeMillis() - start;
        QaRecord saved = history.save(new QaRecord(q, answerText, mode, confidence, toJson(sources), took));
        return new AnswerDto(saved.getId(), q, answerText, mode, confidence, sources, took, saved.getAskedAt(), note);
    }

    private Answer answer(String question, List<Context> contexts) {
        if (claude.isEnabled()) {
            try {
                return claude.generate(question, contexts);
            } catch (Exception e) {
                log.warn("Claude answer failed, using offline answer instead: {}", e.getMessage());
            }
        }
        return offline.generate(question, contexts);
    }

    /** How well the best chunk matches the question's words. */
    static String confidence(List<SearchIndex.Hit> hits) {
        if (hits.isEmpty()) return "none";
        double coverage = hits.get(0).coverage();
        if (coverage >= 0.75) return "high";
        if (coverage >= 0.4) return "medium";
        return "low";
    }

    public List<AnswerDto> history() {
        return history.findTop50ByOrderByIdDesc().stream().map(this::toDto).toList();
    }

    public void clearHistory() {
        history.deleteAll();
    }

    private AnswerDto toDto(QaRecord r) {
        return new AnswerDto(r.getId(), r.getQuestion(), r.getAnswer(), r.getMode(), r.getConfidence(),
                fromJson(r.getSourcesJson()), r.getDurationMs(), r.getAskedAt(), null);
    }

    private String toJson(List<SourceDto> sources) {
        try {
            return mapper.writeValueAsString(sources);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<SourceDto> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<SourceDto>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
