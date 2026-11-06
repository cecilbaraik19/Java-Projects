package com.cecil.certify.chain;

import com.cecil.certify.util.Crypto;
import com.cecil.certify.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One block of the chain.
 *
 *   header = index | time | previous block's hash | Merkle root of the transactions | difficulty | nonce | sealed by
 *   hash   = SHA-256(header)
 *
 * Because every header contains the previous block's hash, the blocks form a chain: change an old block and its
 * hash changes, so the next block no longer points to it, and so on to the end.
 *
 * Proof of work: the node that seals a block must find a "nonce" that makes the hash start with
 * `difficulty` zeros (for example 0000...). That takes tens of thousands of tries, so re-writing many blocks
 * costs real work - and even then the other nodes still out-vote the changed copy.
 */
public final class Block implements Json.JsonWritable {
    public final int index;
    public final long time;
    public final String previousHash;
    public String merkleRoot;
    public final int difficulty;
    public long nonce;
    public final String sealedBy;
    public final List<Transaction> transactions;
    public String hash;
    /** How long sealing took (shown in the ledger; not part of the hash). */
    public long mineMillis;

    public Block(int index, long time, String previousHash, int difficulty, String sealedBy, List<Transaction> txs) {
        this.index = index;
        this.time = time;
        this.previousHash = previousHash;
        this.difficulty = difficulty;
        this.sealedBy = sealedBy;
        this.transactions = new ArrayList<>(txs);
        this.merkleRoot = computeMerkleRoot();
    }

    public String computeMerkleRoot() {
        List<String> leaves = new ArrayList<>();
        for (Transaction t : transactions) leaves.add(t.leafHash());
        return MerkleTree.root(leaves);
    }

    public String header() {
        return index + "|" + time + "|" + previousHash + "|" + merkleRoot + "|" + difficulty + "|" + nonce + "|" + sealedBy;
    }

    public String computeHash() {
        return Crypto.sha256Hex(header());
    }

    public static boolean meetsDifficulty(String hash, int difficulty) {
        if (hash == null || hash.length() < difficulty) return false;
        for (int i = 0; i < difficulty; i++) if (hash.charAt(i) != '0') return false;
        return true;
    }

    /** Proof of work: try nonce 0, 1, 2 ... until the hash starts with enough zeros. */
    public void mine() {
        long start = System.nanoTime();
        merkleRoot = computeMerkleRoot();
        nonce = 0;
        String h = computeHash();
        while (!meetsDifficulty(h, difficulty)) {
            nonce++;
            h = computeHash();
        }
        hash = h;
        mineMillis = Math.max(1, (System.nanoTime() - start) / 1_000_000);
    }

    @Override
    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("time", time);
        m.put("previousHash", previousHash);
        m.put("merkleRoot", merkleRoot);
        m.put("difficulty", difficulty);
        m.put("nonce", nonce);
        m.put("sealedBy", sealedBy);
        m.put("hash", hash);
        m.put("mineMillis", mineMillis);
        List<Object> txs = new ArrayList<>();
        for (Transaction t : transactions) txs.add(t.toJson());
        m.put("transactions", txs);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Block fromJson(Map<String, Object> m) {
        List<Transaction> txs = new ArrayList<>();
        for (Object o : Json.arr(m, "transactions")) txs.add(Transaction.fromJson((Map<String, Object>) o));
        Block b = new Block((int) Json.num(m, "index", 0), Json.num(m, "time", 0), Json.str(m, "previousHash"),
                (int) Json.num(m, "difficulty", 0), Json.str(m, "sealedBy"), txs);
        // keep the STORED merkle root and hash: if someone edited the file, validation must notice
        b.merkleRoot = Json.str(m, "merkleRoot");
        b.nonce = Json.num(m, "nonce", 0);
        b.hash = Json.str(m, "hash");
        b.mineMillis = Json.num(m, "mineMillis", 0);
        return b;
    }

    /** A deep copy, so tampering with one node's block never touches another node's block. */
    public Block copy() {
        List<Transaction> txs = new ArrayList<>();
        for (Transaction t : transactions) txs.add(t.copy());
        Block b = new Block(index, time, previousHash, difficulty, sealedBy, txs);
        b.merkleRoot = merkleRoot;
        b.nonce = nonce;
        b.hash = hash;
        b.mineMillis = mineMillis;
        return b;
    }
}
