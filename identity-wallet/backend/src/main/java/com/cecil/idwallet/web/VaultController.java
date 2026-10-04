package com.cecil.idwallet.web;

import com.cecil.idwallet.auth.AuthUser;
import com.cecil.idwallet.service.VaultService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/vault")
public class VaultController {

    private final VaultService vault;

    public VaultController(VaultService vault) {
        this.vault = vault;
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return VaultService.CATEGORIES;
    }

    @GetMapping
    public List<Map<String, Object>> list(AuthUser u) {
        return vault.list(u);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(AuthUser u, @PathVariable Long id) {
        return vault.detail(u, id);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> create(AuthUser u, @RequestParam String title, @RequestParam String category,
                                      @RequestParam(required = false) String documentNumber,
                                      @RequestParam(required = false) String notes,
                                      @RequestParam(required = false) String expiryDate,
                                      @RequestParam(required = false) MultipartFile file, HttpServletRequest req) {
        return vault.save(u, null, title, category, documentNumber, notes, expiryDate, file, RequestInfo.ip(req));
    }

    @PostMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> update(AuthUser u, @PathVariable Long id, @RequestParam String title, @RequestParam String category,
                                      @RequestParam(required = false) String documentNumber,
                                      @RequestParam(required = false) String notes,
                                      @RequestParam(required = false) String expiryDate,
                                      @RequestParam(required = false) MultipartFile file, HttpServletRequest req) {
        return vault.save(u, id, title, category, documentNumber, notes, expiryDate, file, RequestInfo.ip(req));
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(AuthUser u, @PathVariable Long id, HttpServletRequest req) {
        VaultService.FileData f = vault.download(u, id, RequestInfo.ip(req));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(f.name(), StandardCharsets.UTF_8).build().toString())
                .body(f.bytes());
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(AuthUser u, @PathVariable Long id, HttpServletRequest req) {
        vault.delete(u, id, RequestInfo.ip(req));
        return Map.of("status", "deleted");
    }
}
