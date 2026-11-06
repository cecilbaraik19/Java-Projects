package com.cecil.certify.model;

/** Who can do what. Anyone (no login) can verify a certificate and look at the public ledger. */
public enum Role {
    /** Runs the network: approves / suspends institutions, sees the nodes, Tamper lab, Forgery test, people. */
    REGISTRAR,
    /** Staff of an approved institution: issues and revokes that institution's certificates. */
    ISSUER,
    /** A certificate holder: sees their own certificates, downloads them, makes share links. */
    STUDENT
}
