package com.cecil.credchain.web;

import com.cecil.credchain.credential.*;
import com.cecil.credchain.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CredentialController {

    private final CredentialService credentials;
    private final VerificationService verification;
    private final AuthService auth;

    public CredentialController(CredentialService credentials, VerificationService verification,
                                AuthService auth) {
        this.credentials = credentials;
        this.verification = verification;
        this.auth = auth;
    }

    public record RevokeRequest(String reason) {}

    // ---------------- issuer (signed-in ISSUER, or X-API-Key) ----------------

    @PostMapping("/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    public CredentialView issue(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                @Valid @RequestBody IssueRequest req) {
        return credentials.issue(auth.requireIssuer(authorization, apiKey), req);
    }

    @PostMapping("/credentials/{id}/revoke")
    public CredentialView revoke(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                 @PathVariable String id, @RequestBody(required = false) RevokeRequest req) {
        return credentials.revoke(auth.requireIssuer(authorization, apiKey), id, req == null ? null : req.reason());
    }

    @GetMapping("/credentials/mine")
    public List<CredentialView> mine(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        return credentials.byIssuer(auth.requireIssuer(authorization, apiKey).getId());
    }

    // ---------------- public ----------------

    @GetMapping("/credentials")
    public List<CredentialView> search(@RequestParam(required = false) String studentId,
                                       @RequestParam(required = false) String issuerId) {
        if (studentId != null && !studentId.isBlank()) return credentials.byStudent(studentId.trim());
        if (issuerId != null && !issuerId.isBlank()) return credentials.byIssuer(issuerId.trim());
        return credentials.recent();
    }

    @GetMapping("/credentials/{id}")
    public CredentialView get(@PathVariable String id) {
        return credentials.view(credentials.get(id));
    }

    /** Download the signed credential document (JSON) for the student to share. */
    @GetMapping("/credentials/{id}/document")
    public ResponseEntity<VerifiableCredential> document(@PathVariable String id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + ".json\"")
                .body(credentials.document(id));
    }

    @GetMapping("/verify/{id}")
    public VerificationResult verify(@PathVariable String id) {
        return verification.verifyById(id);
    }

    @PostMapping("/verify/document")
    public VerificationResult verifyDocument(@RequestBody VerifiableCredential document) {
        return verification.verifyDocument(document);
    }
}
