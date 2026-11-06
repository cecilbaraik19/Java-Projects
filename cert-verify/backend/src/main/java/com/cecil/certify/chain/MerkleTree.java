package com.cecil.certify.chain;

import com.cecil.certify.util.Crypto;

import java.util.ArrayList;
import java.util.List;

/**
 * A Merkle tree turns a list of hashes into ONE hash (the "root").
 * If any item in the list changes, the root changes too.
 *
 *                 root
 *               /      \
 *           h(ab)      h(cd)
 *           /  \       /   \
 *          a    b     c     d      <- leaves
 *
 * Pramaan uses it twice:
 *  1. In every block: the leaves are the transactions, so the block header (and its hash) seals all of them.
 *  2. In every certificate: the leaves are the salted field hashes (name, roll no., result ...), so one root
 *     seals the whole certificate, and we can still tell WHICH field was changed.
 *
 * Safety details: leaves and inner nodes are hashed with different prefixes ("leaf:" and "node:") so a node can
 * never pretend to be a leaf, and an odd node is carried up as it is (not copied), which avoids the
 * "duplicate last item" trick that once affected Bitcoin.
 */
public final class MerkleTree {
    private MerkleTree() {}

    public static final String EMPTY_ROOT = Crypto.sha256Hex("empty");

    public static String leaf(String data) {
        return Crypto.sha256Hex("leaf:" + data);
    }

    private static String node(String left, String right) {
        return Crypto.sha256Hex("node:" + left + right);
    }

    /** Root of a list of already-hashed leaves. */
    public static String root(List<String> leafHashes) {
        if (leafHashes == null || leafHashes.isEmpty()) return EMPTY_ROOT;
        List<String> level = new ArrayList<>(leafHashes);
        while (level.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < level.size(); i += 2) {
                if (i + 1 < level.size()) next.add(node(level.get(i), level.get(i + 1)));
                else next.add(level.get(i)); // odd one out goes up unchanged
            }
            level = next;
        }
        return level.get(0);
    }

    /** One step of a proof: the sibling hash and whether it sits on the LEFT. */
    public record Step(String hash, boolean left) {}

    /** The sibling hashes needed to rebuild the root from one leaf (a "Merkle proof"). */
    public static List<Step> proof(List<String> leafHashes, int index) {
        List<Step> steps = new ArrayList<>();
        List<String> level = new ArrayList<>(leafHashes);
        int i = index;
        while (level.size() > 1) {
            int sibling = (i % 2 == 0) ? i + 1 : i - 1;
            if (sibling < level.size()) steps.add(new Step(level.get(sibling), sibling < i));
            List<String> next = new ArrayList<>();
            for (int k = 0; k < level.size(); k += 2) {
                if (k + 1 < level.size()) next.add(node(level.get(k), level.get(k + 1)));
                else next.add(level.get(k));
            }
            level = next;
            i = i / 2;
        }
        return steps;
    }

    /** Rebuilds the root from a leaf and its proof. */
    public static String rootFromProof(String leafHash, List<Step> proof) {
        String h = leafHash;
        for (Step s : proof) h = s.left() ? node(s.hash(), h) : node(h, s.hash());
        return h;
    }
}
