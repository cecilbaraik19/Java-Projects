package com.cecil.credchain.credential;

import com.cecil.credchain.blockchain.Blockchain;
import com.cecil.credchain.blockchain.ChainValidationReport;
import com.cecil.credchain.blockchain.TxLocation;
import com.cecil.credchain.crypto.KeyUtil;
import com.cecil.credchain.web.ApiException;
import org.springframework.stereotype.Service;

import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Verifies a credential against the blockchain. Anyone (e.g. an employer) can call this —
 * no login needed — and every step is reported as an individual check.
 */
@Service
public class VerificationService {

    private final CredentialService credentials;
    private final Blockchain blockchain;

    public VerificationService(CredentialService credentials, Blockchain blockchain) {
        this.credentials = credentials;
        this.blockchain = blockchain;
    }

    /** Verify by credential ID (e.g. from a QR code): checks the off-chain record against the chain. */
    public VerificationResult verifyById(String credentialId) {
        List<VerificationResult.Check> checks = new ArrayList<>();
        Optional<CredentialEntity> record = credentials.find(credentialId);
        if (record.isEmpty()) {
            checks.add(new VerificationResult.Check("Record exists", false, "No credential with ID " + credentialId));
            return notFound(checks);
        }
        CredentialEntity e = record.get();
        checks.add(new VerificationResult.Check("Record exists", true, "Found credential " + e.getCredentialId()));
        return evaluate(e.toData(), e.getSignature(), checks);
    }

    /** Verify a downloaded credential document (JSON file) — works even if our database were altered. */
    public VerificationResult verifyDocument(VerifiableCredential doc) {
        List<VerificationResult.Check> checks = new ArrayList<>();
        if (doc == null || doc.credential() == null || doc.proof() == null) {
            checks.add(new VerificationResult.Check("Document format", false, "Missing 'credential' or 'proof' section"));
            return notFound(checks);
        }
        CredentialData data = doc.credential();
        String recomputed = data.hash();
        boolean selfConsistent = recomputed.equals(doc.proof().credentialHash());
        checks.add(new VerificationResult.Check("Document integrity", selfConsistent, selfConsistent
                ? "Recomputed SHA-256 matches the hash in the document"
                : "Document content was edited: recomputed hash " + abbreviate(recomputed)
                  + " ≠ " + abbreviate(doc.proof().credentialHash())));
        return evaluate(data, doc.proof().signature(), checks);
    }

    private VerificationResult evaluate(CredentialData data, String signature, List<VerificationResult.Check> checks) {
        String hash = data.hash();

        // 1. Is it anchored on the chain?
        Optional<TxLocation> issue = blockchain.findIssue(data.credentialId());
        if (issue.isEmpty()) {
            checks.add(new VerificationResult.Check("Anchored on blockchain", false, "No ISSUE transaction found for this ID"));
            return result("INVALID", "This credential was never recorded on the blockchain.", data, null, hash, checks);
        }
        TxLocation loc = issue.get();
        checks.add(new VerificationResult.Check("Anchored on blockchain", true, loc.confirmed()
                ? "Found in block #" + loc.blockIndex()
                : "Transaction is in the pending pool (not yet mined)"));

        // 2. Does the data match the on-chain fingerprint?
        String onChainHash = loc.transaction().getDataHash();
        boolean hashMatch = hash.equals(onChainHash);
        checks.add(new VerificationResult.Check("Data matches on-chain hash", hashMatch, hashMatch
                ? "SHA-256 " + abbreviate(hash) + " matches the blockchain"
                : "Data was altered: " + abbreviate(hash) + " ≠ on-chain " + abbreviate(onChainHash)));

        // 3. Was it really signed by the issuer whose key is on the chain?
        PublicKey key;
        try {
            key = blockchain.issuerPublicKey(data.issuerId());
        } catch (ApiException ex) {
            key = null;
        }
        boolean issuerMatch = data.issuerId() != null && data.issuerId().equals(loc.transaction().getIssuerId());
        boolean sigOk = key != null && issuerMatch && signature != null
                && KeyUtil.verify(onChainHash, signature, key) && loc.transaction().verifySignature(key);
        checks.add(new VerificationResult.Check("Issuer signature (ECDSA)", sigOk, sigOk
                ? "Signed by " + data.issuerName() + " (" + data.issuerId() + ")"
                : "Signature does not verify with the issuer's on-chain public key"));

        // 4. Revocation
        Optional<TxLocation> revoke = blockchain.findRevocation(data.credentialId());
        checks.add(new VerificationResult.Check("Not revoked", revoke.isEmpty(), revoke
                .map(r -> "Revoked: " + r.transaction().getPayload())
                .orElse("No revocation recorded")));

        // 5. Is the ledger itself intact?
        ChainValidationReport report = blockchain.validate();
        checks.add(new VerificationResult.Check("Ledger integrity", report.valid(), report.valid()
                ? "All " + report.blocksChecked() + " blocks verified"
                : report.issues().size() + " problem(s) found in the chain"));

        boolean allIntegrity = checks.stream()
                .filter(c -> !c.name().equals("Not revoked"))
                .allMatch(VerificationResult.Check::passed);

        String status;
        String message;
        if (!allIntegrity) {
            status = "INVALID";
            message = "Verification failed — this credential cannot be trusted.";
        } else if (revoke.isPresent()) {
            status = "REVOKED";
            message = "Authentic, but revoked by the issuer: " + revoke.get().transaction().getPayload();
        } else if (!loc.confirmed()) {
            status = "PENDING";
            message = "Authentic and signed, waiting to be mined into a block.";
        } else {
            status = "VALID";
            message = "Authentic credential issued by " + data.issuerName() + ".";
        }

        return new VerificationResult(status, message, data, data.issuerName(), hash,
                loc.transaction().getId(), loc.blockIndex(), loc.blockHash(), loc.blockTimestamp(),
                revoke.map(r -> r.transaction().getPayload()).orElse(null), checks, Instant.now());
    }

    private VerificationResult result(String status, String message, CredentialData data, TxLocation loc,
                                      String hash, List<VerificationResult.Check> checks) {
        return new VerificationResult(status, message, data, data == null ? null : data.issuerName(), hash,
                null, null, null, null, null, checks, Instant.now());
    }

    private VerificationResult notFound(List<VerificationResult.Check> checks) {
        return new VerificationResult("NOT_FOUND", "No such credential exists.", null, null, null,
                null, null, null, null, null, checks, Instant.now());
    }

    private static String abbreviate(String hash) {
        return hash == null ? "null" : hash.substring(0, Math.min(12, hash.length())) + "…";
    }
}
