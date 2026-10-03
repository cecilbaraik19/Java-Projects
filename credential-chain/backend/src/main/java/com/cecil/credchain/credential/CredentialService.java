package com.cecil.credchain.credential;

import com.cecil.credchain.blockchain.Blockchain;
import com.cecil.credchain.blockchain.Transaction;
import com.cecil.credchain.blockchain.TransactionType;
import com.cecil.credchain.blockchain.TxLocation;
import com.cecil.credchain.crypto.KeyUtil;
import com.cecil.credchain.institution.Institution;
import com.cecil.credchain.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.PrivateKey;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CredentialService {

    private final CredentialRepository repository;
    private final Blockchain blockchain;

    public CredentialService(CredentialRepository repository, Blockchain blockchain) {
        this.repository = repository;
        this.blockchain = blockchain;
    }

    /**
     * Issue flow: build credential -> SHA-256 fingerprint -> issuer signs fingerprint ->
     * ISSUE transaction (hash only) goes to the chain -> full record stored off-chain.
     */
    @Transactional
    public CredentialView issue(Institution issuer, IssueRequest req) {
        String credentialId = "CRED-" + Year.now().getValue() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String issueDate = (req.issueDate() == null || req.issueDate().isBlank())
                ? LocalDate.now().toString() : req.issueDate();
        LocalDate parsedDate;
        try {
            parsedDate = LocalDate.parse(issueDate);
        } catch (java.time.format.DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Issue date is not a valid date");
        }
        if (parsedDate.isAfter(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Issue date cannot be in the future");
        }

        CredentialData data = new CredentialData(credentialId, req.credentialType().trim(),
                req.studentName().trim(), req.studentId().trim(), req.program().trim(),
                req.major() == null ? "" : req.major().trim(), req.grade().trim(), issueDate,
                issuer.getId(), issuer.getName());

        String hash = data.hash();
        PrivateKey key = KeyUtil.decodePrivateKey(issuer.getPrivateKey());
        String signature = KeyUtil.sign(hash, key);

        Transaction tx = Transaction.create(TransactionType.ISSUE, credentialId, issuer.getId(), hash, "", key);
        blockchain.submit(tx);

        CredentialEntity entity = repository.save(new CredentialEntity(data, hash, signature, tx.getId()));
        return view(entity);
    }

    @Transactional
    public CredentialView revoke(Institution issuer, String credentialId, String reason) {
        CredentialEntity entity = get(credentialId);
        if (!entity.getIssuerId().equals(issuer.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only revoke credentials your institution issued");
        }
        String cleanReason = (reason == null || reason.isBlank()) ? "No reason given" : reason.trim();
        Transaction tx = Transaction.create(TransactionType.REVOKE, credentialId, issuer.getId(),
                entity.getCredentialHash(), cleanReason, KeyUtil.decodePrivateKey(issuer.getPrivateKey()));
        blockchain.submit(tx);
        return view(entity);
    }

    public CredentialEntity get(String credentialId) {
        return find(credentialId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Credential " + credentialId + " not found"));
    }

    public Optional<CredentialEntity> find(String credentialId) {
        return credentialId == null ? Optional.empty() : repository.findById(credentialId.trim());
    }

    public List<CredentialView> byIssuer(String issuerId) {
        return repository.findByIssuerIdOrderByIssuedAtDesc(issuerId).stream().map(this::view).toList();
    }

    public List<CredentialView> byStudent(String studentId) {
        return repository.findByStudentIdIgnoreCaseOrderByIssuedAtDesc(studentId).stream().map(this::view).toList();
    }

    public List<CredentialView> recent() {
        return repository.findTop20ByOrderByIssuedAtDesc().stream().map(this::view).toList();
    }

    public long count() {
        return repository.count();
    }

    public CredentialView view(CredentialEntity e) {
        Optional<TxLocation> issue = blockchain.findIssue(e.getCredentialId());
        Optional<TxLocation> revoke = blockchain.findRevocation(e.getCredentialId());
        String status = revoke.isPresent() ? "REVOKED"
                : issue.map(l -> l.confirmed() ? "ACTIVE" : "PENDING").orElse("NOT_ON_CHAIN");
        return new CredentialView(e.toData(), status, e.getCredentialHash(), e.getTransactionId(),
                issue.map(TxLocation::blockIndex).orElse(null),
                issue.map(TxLocation::blockHash).orElse(null),
                revoke.map(l -> l.transaction().getPayload()).orElse(null),
                e.getIssuedAt());
    }

    /** Builds the portable, signed document the student can download and share. */
    public VerifiableCredential document(String credentialId) {
        CredentialEntity e = get(credentialId);
        return new VerifiableCredential(
                List.of("VerifiableCredential", "AcademicCredential"), "1.0", e.toData(),
                new VerifiableCredential.Proof("EcdsaSecp256r1Signature", e.getCredentialHash(), e.getSignature(),
                        e.getIssuerId(), e.getTransactionId(), e.getIssuedAt().toString()));
    }
}
