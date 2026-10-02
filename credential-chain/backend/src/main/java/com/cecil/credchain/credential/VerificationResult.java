package com.cecil.credchain.credential;

import java.time.Instant;
import java.util.List;

public record VerificationResult(
        String status,               // VALID, PENDING, REVOKED, INVALID, NOT_FOUND
        String message,
        CredentialData credential,
        String issuerName,
        String credentialHash,
        String transactionId,
        Integer blockIndex,
        String blockHash,
        Long anchoredAt,
        String revocationReason,
        List<Check> checks,
        Instant verifiedAt) {

    public record Check(String name, boolean passed, String detail) {}
}
