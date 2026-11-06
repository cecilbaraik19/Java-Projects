package com.cecil.certify.chain;

/** The kinds of record that can be written on the Pramaan blockchain. */
public enum TxType {
    /** First record of the chain. Holds the registrar's public key: the root of all trust. */
    GENESIS("Network created"),
    /** The registrar approves an institution and records its public key. */
    REGISTER_ISSUER("Institution approved"),
    /** The registrar stops an institution from issuing new certificates. */
    SUSPEND_ISSUER("Institution suspended"),
    /** An institution records the fingerprint of a new certificate. */
    ISSUE_CERT("Certificate issued"),
    /** An institution cancels a certificate it issued earlier. */
    REVOKE_CERT("Certificate revoked");

    public final String label;

    TxType(String label) { this.label = label; }
}
