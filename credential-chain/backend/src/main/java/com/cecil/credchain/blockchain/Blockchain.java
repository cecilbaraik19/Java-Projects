package com.cecil.credchain.blockchain;

import com.cecil.credchain.crypto.HashUtil;
import com.cecil.credchain.crypto.KeyUtil;
import com.cecil.credchain.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.PublicKey;
import java.util.*;

/**
 * The ledger: holds the chain in memory (backed by the DB), the pending pool, and
 * fast lookup indexes. All public methods are synchronized so mining and new
 * transactions never interleave.
 */
@Service
public class Blockchain {

    private static final Logger log = LoggerFactory.getLogger(Blockchain.class);
    public static final String GENESIS_PREVIOUS_HASH = "0".repeat(64);

    private final BlockRepository blockRepository;
    private final PendingTransactionRepository pendingRepository;
    private final ObjectMapper mapper;
    private final int difficulty;
    private final boolean autoMine;

    private final List<Block> chain = new ArrayList<>();
    private final List<Transaction> pending = new ArrayList<>();

    // Indexes rebuilt from chain + pending
    private final Map<String, TxLocation> issueByCredential = new HashMap<>();
    private final Map<String, TxLocation> revokeByCredential = new HashMap<>();
    private final Map<String, TxLocation> issuerRegistrations = new HashMap<>();

    public Blockchain(BlockRepository blockRepository,
                      PendingTransactionRepository pendingRepository,
                      ObjectMapper mapper,
                      @Value("${credchain.difficulty:4}") int difficulty,
                      @Value("${credchain.auto-mine:true}") boolean autoMine) {
        this.blockRepository = blockRepository;
        this.pendingRepository = pendingRepository;
        this.mapper = mapper;
        this.difficulty = difficulty;
        this.autoMine = autoMine;
    }

    @PostConstruct
    public synchronized void init() {
        loadFromDatabase();
        if (chain.isEmpty()) {
            Block genesis = new Block(0, GENESIS_PREVIOUS_HASH, List.of(), difficulty);
            genesis.mine();
            chain.add(genesis);
            persistBlock(genesis);
            log.info("Created genesis block {}", genesis.getHash());
        }
        rebuildIndexes();
        log.info("Blockchain ready: {} blocks, {} pending transactions", chain.size(), pending.size());
    }

    /** Re-reads chain and mempool from the database (also used to undo the tamper demo). */
    public synchronized void loadFromDatabase() {
        chain.clear();
        pending.clear();
        for (BlockEntity entity : blockRepository.findAllByOrderByIndexAsc()) {
            chain.add(fromJson(entity.getJson(), Block.class));
        }
        for (PendingTransactionEntity entity : pendingRepository.findAllByOrderByCreatedAtAsc()) {
            pending.add(fromJson(entity.getJson(), Transaction.class));
        }
        rebuildIndexes();
    }

    // ------------------------------------------------------------------ transactions

    /** Validates and adds a transaction to the pending pool. */
    @Transactional
    public synchronized void submit(Transaction tx) {
        if (!tx.getId().equals(tx.computeId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Transaction id does not match its contents");
        }
        PublicKey key = switch (tx.getType()) {
            case REGISTER_ISSUER -> {
                if (issuerRegistrations.containsKey(tx.getIssuerId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "Issuer already registered on chain");
                }
                yield KeyUtil.decodePublicKey(publicKeyFromPayload(tx.getPayload()));
            }
            case ISSUE -> {
                if (issueByCredential.containsKey(tx.getCredentialId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "Credential already issued on chain");
                }
                yield issuerPublicKey(tx.getIssuerId());
            }
            case REVOKE -> {
                TxLocation issued = issueByCredential.get(tx.getCredentialId());
                if (issued == null) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "Credential not found on chain");
                }
                if (!issued.transaction().getIssuerId().equals(tx.getIssuerId())) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "Only the original issuer can revoke");
                }
                if (revokeByCredential.containsKey(tx.getCredentialId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "Credential already revoked");
                }
                yield issuerPublicKey(tx.getIssuerId());
            }
        };
        if (!tx.verifySignature(key)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid transaction signature");
        }
        pending.add(tx);
        pendingRepository.save(new PendingTransactionEntity(tx.getId(), tx.getTimestamp(), toJson(tx)));
        index(tx, null);
    }

    /** Mines all pending transactions into a new block. Returns null if nothing is pending. */
    @Transactional
    public synchronized Block mine() {
        if (pending.isEmpty()) {
            return null;
        }
        Block last = latestBlock();
        Block block = new Block(last.getIndex() + 1, last.getHash(), pending, difficulty);
        block.mine();
        chain.add(block);
        persistBlock(block);
        pendingRepository.deleteAllById(pending.stream().map(Transaction::getId).toList());
        pending.clear();
        rebuildIndexes();
        log.info("Mined block #{} with {} tx (nonce={}, {} ms)", block.getIndex(),
                block.getTransactions().size(), block.getNonce(), block.getMiningTimeMs());
        return block;
    }

    @Scheduled(fixedDelayString = "${credchain.auto-mine-interval-ms:30000}", initialDelay = 30000)
    public void autoMine() {
        if (autoMine) {
            synchronized (this) {
                if (!pending.isEmpty()) {
                    mine();
                }
            }
        }
    }

    // ------------------------------------------------------------------ validation

    /** Walks every block: hash, proof-of-work, link, Merkle root, transaction ids and signatures. */
    public synchronized ChainValidationReport validate() {
        List<ChainValidationReport.Issue> issues = new ArrayList<>();
        Map<String, PublicKey> keys = new HashMap<>();
        int txCount = 0;

        for (int i = 0; i < chain.size(); i++) {
            Block block = chain.get(i);
            if (!block.calculateHash().equals(block.getHash())) {
                issues.add(new ChainValidationReport.Issue(i, "Block hash", "Stored hash does not match recalculated hash"));
            }
            if (!block.meetsDifficulty()) {
                issues.add(new ChainValidationReport.Issue(i, "Proof of work", "Hash does not meet difficulty " + block.getDifficulty()));
            }
            String expectedPrev = i == 0 ? GENESIS_PREVIOUS_HASH : chain.get(i - 1).getHash();
            if (!expectedPrev.equals(block.getPreviousHash())) {
                issues.add(new ChainValidationReport.Issue(i, "Chain link", "previousHash does not point to block #" + (i - 1)));
            }
            if (!block.computeMerkleRoot().equals(block.getMerkleRoot())) {
                issues.add(new ChainValidationReport.Issue(i, "Merkle root", "Transactions were modified after mining"));
            }
            for (Transaction tx : block.getTransactions()) {
                txCount++;
                if (!tx.computeId().equals(tx.getId())) {
                    issues.add(new ChainValidationReport.Issue(i, "Transaction id", "Tx " + shortId(tx.getId()) + " contents changed"));
                }
                PublicKey key;
                if (tx.getType() == TransactionType.REGISTER_ISSUER) {
                    key = safeDecode(publicKeyFromPayload(tx.getPayload()));
                    if (key != null) keys.put(tx.getIssuerId(), key);
                } else {
                    key = keys.get(tx.getIssuerId());
                }
                if (!tx.verifySignature(key)) {
                    issues.add(new ChainValidationReport.Issue(i, "Signature", "Tx " + shortId(tx.getId()) + " has an invalid issuer signature"));
                }
            }
        }
        return new ChainValidationReport(issues.isEmpty(), chain.size(), txCount, issues);
    }

    // ------------------------------------------------------------------ queries

    public synchronized List<Block> getChain() { return List.copyOf(chain); }
    public synchronized List<Transaction> getPending() { return List.copyOf(pending); }
    public synchronized Block latestBlock() { return chain.get(chain.size() - 1); }
    public synchronized Optional<Block> getBlock(int index) {
        return index >= 0 && index < chain.size() ? Optional.of(chain.get(index)) : Optional.empty();
    }
    public synchronized Optional<TxLocation> findIssue(String credentialId) { return Optional.ofNullable(issueByCredential.get(credentialId)); }
    public synchronized Optional<TxLocation> findRevocation(String credentialId) { return Optional.ofNullable(revokeByCredential.get(credentialId)); }
    public synchronized Optional<TxLocation> findIssuerRegistration(String issuerId) { return Optional.ofNullable(issuerRegistrations.get(issuerId)); }
    public int getDifficulty() { return difficulty; }

    public synchronized Optional<TxLocation> findTransaction(String txId) {
        for (Block block : chain) {
            for (Transaction tx : block.getTransactions()) {
                if (tx.getId().equals(txId)) return Optional.of(new TxLocation(tx, block.getIndex(), block.getHash(), block.getTimestamp()));
            }
        }
        return pending.stream().filter(t -> t.getId().equals(txId)).findFirst().map(t -> new TxLocation(t, null, null, null));
    }

    /** Public key of an issuer as anchored on the chain (not as stored in the DB). */
    public synchronized PublicKey issuerPublicKey(String issuerId) {
        TxLocation reg = issuerRegistrations.get(issuerId);
        if (reg == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Issuer " + issuerId + " is not registered on chain");
        }
        return KeyUtil.decodePublicKey(publicKeyFromPayload(reg.transaction().getPayload()));
    }

    /** Demo only: silently edits a transaction inside a mined block (in memory) to show tamper detection. */
    public synchronized Block tamperBlock(int index) {
        Block block = getBlock(index).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Block not found"));
        if (block.getTransactions().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Block #" + index + " has no transactions to tamper with");
        }
        Transaction tx = block.getTransactions().get(0);
        tx.setDataHash(HashUtil.sha256("forged-" + tx.getDataHash()));
        rebuildIndexes();
        return block;
    }

    // ------------------------------------------------------------------ helpers

    /** REGISTER_ISSUER payload format: "<base64 public key>|<institution name>". */
    public static String registrationPayload(String publicKeyBase64, String name) {
        return publicKeyBase64 + "|" + name;
    }

    public static String publicKeyFromPayload(String payload) {
        int sep = payload.indexOf('|');
        return sep < 0 ? payload : payload.substring(0, sep);
    }

    private void rebuildIndexes() {
        issueByCredential.clear();
        revokeByCredential.clear();
        issuerRegistrations.clear();
        for (Block block : chain) {
            for (Transaction tx : block.getTransactions()) {
                index(tx, block);
            }
        }
        for (Transaction tx : pending) {
            index(tx, null);
        }
    }

    private void index(Transaction tx, Block block) {
        TxLocation loc = block == null
                ? new TxLocation(tx, null, null, null)
                : new TxLocation(tx, block.getIndex(), block.getHash(), block.getTimestamp());
        switch (tx.getType()) {
            case REGISTER_ISSUER -> issuerRegistrations.put(tx.getIssuerId(), loc);
            case ISSUE -> issueByCredential.put(tx.getCredentialId(), loc);
            case REVOKE -> revokeByCredential.put(tx.getCredentialId(), loc);
        }
    }

    private void persistBlock(Block block) {
        blockRepository.save(new BlockEntity(block.getIndex(), toJson(block)));
    }

    private static PublicKey safeDecode(String key) {
        try {
            return KeyUtil.decodePublicKey(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.substring(0, Math.min(10, id.length())) + "…";
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt chain data in database", e);
        }
    }
}
