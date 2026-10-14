package com.cecil.docqa.web;

import com.cecil.docqa.config.DocQaProperties;
import com.cecil.docqa.service.DocumentService;
import com.cecil.docqa.service.QaService;
import com.cecil.docqa.service.SearchIndex;
import com.cecil.docqa.web.dto.Dtos.AnswerDto;
import com.cecil.docqa.web.dto.Dtos.AskRequest;
import com.cecil.docqa.web.dto.Dtos.StatusDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class QaController {

    private final QaService qa;
    private final DocumentService documents;
    private final SearchIndex index;
    private final DocQaProperties props;

    public QaController(QaService qa, DocumentService documents, SearchIndex index, DocQaProperties props) {
        this.qa = qa;
        this.documents = documents;
        this.index = index;
        this.props = props;
    }

    @PostMapping("/ask")
    public AnswerDto ask(@Valid @RequestBody AskRequest request) {
        return qa.ask(request.question(), request.documentIds());
    }

    @GetMapping("/history")
    public List<AnswerDto> history() {
        return qa.history();
    }

    @DeleteMapping("/history")
    public ResponseEntity<Void> clearHistory() {
        qa.clearHistory();
        return ResponseEntity.noContent().build();
    }

    /** Tells the UI whether Claude is switched on and how much is indexed. */
    @GetMapping("/status")
    public StatusDto status() {
        boolean ai = props.ai().enabled();
        return new StatusDto(ai ? "claude" : "offline", ai ? props.ai().model() : null,
                documents.count(), index.size());
    }
}
