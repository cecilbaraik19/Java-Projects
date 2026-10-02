package com.cecil.credchain.blockchain;

import com.cecil.credchain.crypto.HashUtil;

import java.util.ArrayList;
import java.util.List;

/** A block in the chain, sealed with proof-of-work. */
public class Block {

    private int index;
    private long timestamp;
    private String previousHash;
    private String merkleRoot;
    private long nonce;
    private int difficulty;
    private String hash;
    private long miningTimeMs;
    private List<Transaction> transactions = new ArrayList<>();

    public Block() {}

    public Block(int index, String previousHash, List<Transaction> transactions, int difficulty) {
        this.index = index;
        this.timestamp = System.currentTimeMillis();
        this.previousHash = previousHash;
        this.transactions = new ArrayList<>(transactions);
        this.difficulty = difficulty;
        this.merkleRoot = computeMerkleRoot();
    }

    public String computeMerkleRoot() {
        return MerkleTree.root(transactions.stream().map(Transaction::getId).toList());
    }

    public String calculateHash() {
        return HashUtil.sha256(index + "|" + timestamp + "|" + previousHash + "|" + merkleRoot + "|" + nonce + "|" + difficulty);
    }

    /** Proof-of-work: find a nonce so the hash starts with {@code difficulty} zeros. */
    public void mine() {
        long start = System.currentTimeMillis();
        String target = "0".repeat(difficulty);
        nonce = 0;
        hash = calculateHash();
        while (!hash.startsWith(target)) {
            nonce++;
            hash = calculateHash();
        }
        miningTimeMs = System.currentTimeMillis() - start;
    }

    public boolean meetsDifficulty() {
        return hash != null && hash.startsWith("0".repeat(difficulty));
    }

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public String getPreviousHash() { return previousHash; }
    public void setPreviousHash(String previousHash) { this.previousHash = previousHash; }
    public String getMerkleRoot() { return merkleRoot; }
    public void setMerkleRoot(String merkleRoot) { this.merkleRoot = merkleRoot; }
    public long getNonce() { return nonce; }
    public void setNonce(long nonce) { this.nonce = nonce; }
    public int getDifficulty() { return difficulty; }
    public void setDifficulty(int difficulty) { this.difficulty = difficulty; }
    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }
    public long getMiningTimeMs() { return miningTimeMs; }
    public void setMiningTimeMs(long miningTimeMs) { this.miningTimeMs = miningTimeMs; }
    public List<Transaction> getTransactions() { return transactions; }
    public void setTransactions(List<Transaction> transactions) { this.transactions = transactions; }
}
