package com.cecil.idwallet.service;

import com.cecil.idwallet.crypto.CryptoUtil;
import com.cecil.idwallet.domain.AuditEvent;
import com.cecil.idwallet.repo.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Writes hash-chained audit events and checks the chain for tampering. */
@Service
public class AuditService {

    public static final String GENESIS = "0".repeat(64);
    private final AuditEventRepository repo;
    private final TransactionTemplate tx;

    public AuditService(AuditEventRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
        // own transaction: the event is kept even if the caller's work fails (e.g. a failed login)
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * The lock is held until the event's transaction has COMMITTED, so two events can
     * never both read the same "previous hash" and fork the chain.
     */
    public synchronized void log(Long userId, String actor, String action, String detail, String ip) {
        tx.executeWithoutResult(status -> append(userId, actor, action, detail, ip));
    }

    private void append(Long userId, String actor, String action, String detail, String ip) {
        AuditEvent e = new AuditEvent();
        e.userId = userId;
        e.actor = actor;
        e.action = action;
        e.detail = detail == null ? null : (detail.length() > 500 ? detail.substring(0, 500) : detail);
        e.ip = ip;
        e.at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        e.prevHash = repo.findTopByOrderByIdDesc().map(p -> p.hash).orElse(GENESIS);
        e.hash = hashOf(e);
        repo.save(e);
    }

    static String hashOf(AuditEvent e) {
        return CryptoUtil.sha256Hex(String.join("|", e.prevHash, String.valueOf(e.userId), String.valueOf(e.actor),
                e.action, String.valueOf(e.detail), String.valueOf(e.ip), String.valueOf(e.at.toEpochMilli())));
    }

    /** Walks the whole chain; returns {valid, events, brokenAt}. */
    @Transactional(readOnly = true)
    public Map<String, Object> verifyChain() {
        List<AuditEvent> all = repo.findAllByOrderByIdAsc();
        String prev = GENESIS;
        for (AuditEvent e : all) {
            if (!e.prevHash.equals(prev) || !e.hash.equals(hashOf(e))) {
                return Map.of("valid", false, "events", all.size(), "brokenAt", e.id);
            }
            prev = e.hash;
        }
        return Map.of("valid", true, "events", all.size());
    }
}
