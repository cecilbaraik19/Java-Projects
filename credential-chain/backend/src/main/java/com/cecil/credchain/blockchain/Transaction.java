package com.cecil.credchain.blockchain;

import com.cecil.credchain.crypto.HashUtil;
import com.cecil.credchain.crypto.KeyUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * A signed blockchain transaction. Only fingerprints (hashes) of credentials go on-chain;
 * the personal data itself stays off-chain in the database.
 */
public class Transaction {

    private String id;
    private TransactionType type;
    private String credentialId;
    private String issuerId;
    /** ISSUE: credential hash. REVOKE: credential hash being revoked. REGISTER_ISSUER: hash of public key. */
    private String dataHash;
    /** REGISTER_ISSUER: Base64 public key + name. REVOKE: reason. ISSUE: empty. */
    private String payload;
    private long timestamp;
    private String signature;

    public Transaction() {}

    public static Transaction create(TransactionType type, String credentialId, String issuerId,
                                     String dataHash, String payload, PrivateKey signingKey) {
        Transaction tx = new Transaction();
        tx.type = type;
        tx.credentialId = credentialId == null ? "" : credentialId;
        tx.issuerId = issuerId;
        tx.dataHash = dataHash;
        tx.payload = payload == null ? "" : payload;
        tx.timestamp = System.currentTimeMillis();
        tx.signature = KeyUtil.sign(tx.signingData(), signingKey);
        tx.id = tx.computeId();
        return tx;
    }

    /** The exact string the issuer signs. */
    @JsonIgnore
    public String signingData() {
        return type + "|" + credentialId + "|" + issuerId + "|" + dataHash + "|" + payload + "|" + timestamp;
    }

    public String computeId() {
        return HashUtil.sha256(signingData() + "|" + signature);
    }

    public boolean verifySignature(PublicKey publicKey) {
        return publicKey != null && KeyUtil.verify(signingData(), signature, publicKey);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public TransactionType getType() { return type; }
    public void setType(TransactionType type) { this.type = type; }
    public String getCredentialId() { return credentialId; }
    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }
    public String getIssuerId() { return issuerId; }
    public void setIssuerId(String issuerId) { this.issuerId = issuerId; }
    public String getDataHash() { return dataHash; }
    public void setDataHash(String dataHash) { this.dataHash = dataHash; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
}
