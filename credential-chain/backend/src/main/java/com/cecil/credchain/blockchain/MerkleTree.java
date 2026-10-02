package com.cecil.credchain.blockchain;

import com.cecil.credchain.crypto.HashUtil;

import java.util.ArrayList;
import java.util.List;

/** Computes a Merkle root from a list of transaction hashes (odd leaf is paired with itself). */
public final class MerkleTree {

    private MerkleTree() {}

    public static String root(List<String> leaves) {
        if (leaves == null || leaves.isEmpty()) {
            return HashUtil.sha256("");
        }
        List<String> level = new ArrayList<>(leaves);
        while (level.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < level.size(); i += 2) {
                String left = level.get(i);
                String right = (i + 1 < level.size()) ? level.get(i + 1) : left;
                next.add(HashUtil.sha256(left + right));
            }
            level = next;
        }
        return level.get(0);
    }
}
