package com.cecil.certify.chain;

import java.util.ArrayList;
import java.util.List;

/**
 * One validator node. Each node keeps its OWN full copy of the chain (in its own file),
 * like each bank branch keeping its own copy of the ledger.
 */
public final class Node {
    public final String id;        // "A", "B", "C"
    public final String name;      // "Node A"
    public final String keeper;    // who runs it, e.g. "Koel Valley Institute of Technology"
    public List<Block> chain = new ArrayList<>();

    /** Result of the last full check of this node's chain (null = needs checking). */
    ChainValidator.Result lastCheck;

    public Node(String id, String name, String keeper) {
        this.id = id;
        this.name = name;
        this.keeper = keeper;
    }

    public int height() {
        return chain.size() - 1;
    }

    public String tipHash() {
        return chain.isEmpty() ? "" : chain.get(chain.size() - 1).hash;
    }

    public ChainValidator.Result check(String genesisHash, int minDifficulty) {
        if (lastCheck == null) lastCheck = ChainValidator.validate(chain, genesisHash, minDifficulty);
        return lastCheck;
    }

    /** Call after the chain changes so the next check runs again. */
    public void changed() {
        lastCheck = null;
    }

    /** Deep copy of this node's chain. */
    public List<Block> copyChain() {
        List<Block> out = new ArrayList<>();
        for (Block b : chain) out.add(b.copy());
        return out;
    }
}
