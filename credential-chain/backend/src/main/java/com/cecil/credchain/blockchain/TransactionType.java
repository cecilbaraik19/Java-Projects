package com.cecil.credchain.blockchain;

public enum TransactionType {
    /** Anchors an institution's public key on the chain (self-signed). */
    REGISTER_ISSUER,
    /** Records the SHA-256 fingerprint of an issued credential, signed by the issuer. */
    ISSUE,
    /** Revokes a previously issued credential, signed by the same issuer. */
    REVOKE
}
