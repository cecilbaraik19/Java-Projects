package com.cecil.credchain.credential;

import java.time.Instant;

/** Credential + its live on-chain status, for the UI. */
public record CredentialView(
        CredentialData credential,
        String status,          // ACTIVE, PENDING, REVOKED
        String credentialHash,
        String transactionId,
        Integer blockIndex,
        String blockHash,
        String revocationReason,
        Instant issuedAt) {}
