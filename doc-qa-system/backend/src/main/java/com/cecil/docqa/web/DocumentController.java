package com.cecil.docqa.web;

import com.cecil.docqa.model.Document;
import com.cecil.docqa.service.DocumentService;
import com.cecil.docqa.web.dto.Dtos.DocumentDto;
import com.cecil.docqa.web.dto.Dtos.DocumentTextDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final int PREVIEW_LIMIT = 20_000;

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<DocumentDto> upload(@RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentDto.from(service.upload(file)));
    }

    @GetMapping
    public List<DocumentDto> list() {
        return service.list().stream().map(DocumentDto::from).toList();
    }

    /** Document details plus its text (first 20,000 characters) for the preview panel. */
    @GetMapping("/{id}")
    public DocumentTextDto get(@PathVariable Long id) {
        Document d = service.get(id);
        String text = d.getContent() == null ? "" : d.getContent();
        boolean truncated = text.length() > PREVIEW_LIMIT;
        return new DocumentTextDto(DocumentDto.from(d), truncated ? text.substring(0, PREVIEW_LIMIT) : text, truncated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
