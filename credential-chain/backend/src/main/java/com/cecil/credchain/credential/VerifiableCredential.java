package com.cecil.credchain.credential;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * The portable document a student downloads and shares with an employer.
 * Loosely modelled on the W3C Verifiable Credentials data model.
 */
@JsonPropertyOrder({"type", "version", "credential", "proof"})
public record VerifiableCredential(List<String> type, String version, CredentialData credential, Proof proof) {

    @JsonPropertyOrder({"type", "credentialHash", "signature", "issuerId", "transactionId", "created"})
    public record Proof(String type, String credentialHash, String signature, String issuerId,
                        String transactionId, String created) {}
}
