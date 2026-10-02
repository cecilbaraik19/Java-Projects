package com.cecil.credchain.credential;

import com.cecil.credchain.crypto.HashUtil;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The credential content that gets fingerprinted. Its SHA-256 hash is what is stored on-chain.
 * Changing even one character of any field produces a completely different hash.
 */
@JsonPropertyOrder({"credentialId", "credentialType", "studentName", "studentId", "program",
        "major", "grade", "issueDate", "issuerId", "issuerName"})
public record CredentialData(
        String credentialId,
        String credentialType,
        String studentName,
        String studentId,
        String program,
        String major,
        String grade,
        String issueDate,
        String issuerId,
        String issuerName) {

    /** Deterministic, order-fixed representation used for hashing. */
    public String canonical() {
        return String.join("\n",
                "credentialId=" + clean(credentialId),
                "credentialType=" + clean(credentialType),
                "studentName=" + clean(studentName),
                "studentId=" + clean(studentId),
                "program=" + clean(program),
                "major=" + clean(major),
                "grade=" + clean(grade),
                "issueDate=" + clean(issueDate),
                "issuerId=" + clean(issuerId),
                "issuerName=" + clean(issuerName));
    }

    public String hash() {
        return HashUtil.sha256(canonical());
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("\n", " ").replace("\r", " ");
    }
}
