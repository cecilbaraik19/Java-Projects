package com.cecil.docqa.service;

import com.cecil.docqa.config.DocQaProperties;
import com.cecil.docqa.model.Document;
import com.cecil.docqa.model.DocumentChunk;
import com.cecil.docqa.model.QaRecord;
import com.cecil.docqa.repository.ChunkRepository;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final ChunkRepository chunks;
    private final QaRecordRepository history;
    private final ClaudeAnswerGenerator claude;
    private final ExtractiveAnswerGenerator offline;
    private final ObjectMapper mapper;
    private final int topK;

    public QaService(SearchIndex index, DocumentRepository documents, ChunkRepository chunks,
                     QaRecordRepository history, ClaudeAnswerGenerator claude, ExtractiveAnswerGenerator offline,
                     ObjectMapper mapper, DocQaProperties props) {
        this.index = index;
        this.documents = documents;
        this.chunks = chunks;
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

        if (isOverviewQuestion(q)) {
            return overview(q, documentIds, start);
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

    /* ---------------- summary mode ---------------- */

    /** Words that mean "give me the big picture", e.g. "tell me everything", "summarize". */
    private static final Set<String> OVERVIEW_WORDS = new HashSet<>(Tokenizer.tokens(
            "everything summary summarize summarise summarized overview main point points key topic topics "
                    + "gist brief briefly short whole entire full content contents contain contains cover covers "
                    + "outline highlights important idea ideas abstract notes note file pdf"));

    /** True when every meaningful word of the question is an "overview" word (or there are none). */
    static boolean isOverviewQuestion(String question) {
        List<String> tokens = Tokenizer.tokens(question);
        return OVERVIEW_WORDS.containsAll(tokens);
    }

    private static final int MAX_OVERVIEW_DOCS = 3;
    private static final int CLAUDE_CHUNKS_PER_DOC = 6;
    private static final int SENTENCES_PER_DOC = 5;
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

    private AnswerDto overview(String q, List<Long> documentIds, long start) {
        List<Document> docs;
        String note = null;
        if (documentIds != null && !documentIds.isEmpty()) {
            docs = documents.findAllById(documentIds);
        } else {
            docs = documents.findAllByOrderByUploadedAtDesc();
            if (docs.size() > MAX_OVERVIEW_DOCS) {
                note = "This is a summary of your " + MAX_OVERVIEW_DOCS
                        + " newest documents. Tick a document on the left to summarize only that one.";
            }
        }
        docs = docs.stream().limit(MAX_OVERVIEW_DOCS).toList();

        // Every chunk of each document, numbered so the answer can cite them
        Map<String, List<Context>> byDoc = new LinkedHashMap<>();
        Map<Integer, SourceDto> allSources = new LinkedHashMap<>();
        int n = 1;
        for (Document d : docs) {
            List<Context> list = new ArrayList<>();
            for (DocumentChunk c : chunks.findByDocumentIdOrderByChunkIndex(d.getId())) {
                list.add(new Context(n, d.getFileName(), c.getPageNumber(), c.getText()));
                allSources.put(n, new SourceDto(n, d.getId(), d.getFileName(), c.getPageNumber(), c.getText(), 0));
                n++;
            }
            byDoc.put(d.getFileName(), list);
        }

        Answer answer = null;
        if (claude.isEnabled()) {
            try {
                List<Context> sample = new ArrayList<>();
                byDoc.values().forEach(list -> sample.addAll(spread(list, CLAUDE_CHUNKS_PER_DOC)));
                String prompt = "Give a clear overview of these documents: what each one is about and its "
                        + "key points as a short list. The user asked: \"" + q + "\"";
                answer = claude.generate(prompt, sample);
            } catch (Exception e) {
                log.warn("Claude summary failed, using offline summary instead: {}", e.getMessage());
                note = "Claude could not be reached, so this summary was made offline.";
            }
        }
        if (answer == null) answer = offline.summarize(byDoc, docs.size() == 1 ? 8 : SENTENCES_PER_DOC);

        // Only list the chunks the answer actually cites
        Set<Integer> cited = new TreeSet<>();
        Matcher m = CITATION.matcher(answer.text());
        while (m.find()) cited.add(Integer.parseInt(m.group(1)));
        List<SourceDto> sources = cited.stream().map(allSources::get).filter(Objects::nonNull).toList();

        long took = System.currentTimeMillis() - start;
        QaRecord saved = history.save(new QaRecord(q, answer.text(), answer.mode(), "summary", toJson(sources), took));
        return new AnswerDto(saved.getId(), q, answer.text(), answer.mode(), "summary", sources, took,
                saved.getAskedAt(), note);
    }

    /** Picks up to "count" chunks spread evenly through the document (start, middle, end). */
    static <T> List<T> spread(List<T> list, int count) {
        if (list.size() <= count) return list;
        List<T> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(list.get((int) Math.round(i * (list.size() - 1) / (double) (count - 1))));
        return out;
    }

    /* ---------------- normal questions ---------------- */

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
