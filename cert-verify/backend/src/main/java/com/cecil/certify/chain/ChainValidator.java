package com.cecil.certify.chain;

import com.cecil.certify.util.Crypto;

import java.security.PublicKey;
import java.util.List;

/**
 * Checks a whole chain from the first block to the last, and replays it to build the {@link ChainState}.
 * It stops at the FIRST problem and says exactly which block is bad and why, for example
 * "Block 7: its stored hash does not match its contents (the block was edited)".
 */
public final class ChainValidator {
    private ChainValidator() {}

    public static final String ZERO_HASH = "0".repeat(64);
    /** A transaction may not claim to be from more than 2 minutes after its block. */
    private static final long CLOCK_SKEW_MS = 120_000;

    public static final class Result {
        public boolean ok = true;
        public int badBlock = -1;
        public String reason = "";
        public String problemKind = "";   // HASH, LINK, WORK, MERKLE, SIGNATURE, RULE, GENESIS
        public final ChainState state = new ChainState();

        void fail(int block, String kind, String why) {
            ok = false;
            badBlock = block;
            problemKind = kind;
            reason = "Block " + block + ": " + why;
        }
    }

    /**
     * @param expectedGenesisHash the genesis hash this node was set up with (trust anchor), or null to accept any
     * @param minDifficulty       blocks easier than this are refused
     */
    public static Result validate(List<Block> chain, String expectedGenesisHash, int minDifficulty) {
        Result r = new Result();
        ChainState st = r.state;
        if (chain == null || chain.isEmpty()) {
            r.fail(0, "GENESIS", "the chain is empty");
            return r;
        }
        for (int i = 0; i < chain.size(); i++) {
            Block b = chain.get(i);
            if (b.index != i) { r.fail(i, "LINK", "block number is " + b.index + " but it sits at position " + i); return r; }
            if (b.hash == null || !b.hash.equals(b.computeHash())) {
                r.fail(i, "HASH", "its stored hash does not match its contents (the block was edited after sealing)");
                return r;
            }
            if (b.difficulty < minDifficulty || !Block.meetsDifficulty(b.hash, b.difficulty)) {
                r.fail(i, "WORK", "proof of work is missing (hash must start with " + minDifficulty + " zeros)");
                return r;
            }
            if (b.merkleRoot == null || !b.merkleRoot.equals(b.computeMerkleRoot())) {
                r.fail(i, "MERKLE", "a transaction inside it was changed (Merkle root does not match)");
                return r;
            }
            if (i == 0) {
                if (!ZERO_HASH.equals(b.previousHash)) { r.fail(0, "GENESIS", "first block must point to 000...0"); return r; }
                if (expectedGenesisHash != null && !expectedGenesisHash.equals(b.hash)) {
                    r.fail(0, "GENESIS", "this is not the Pramaan network's first block (genesis hash differs)");
                    return r;
                }
                if (b.transactions.size() != 1 || b.transactions.get(0).type != TxType.GENESIS) {
                    r.fail(0, "GENESIS", "first block must hold exactly one GENESIS record");
                    return r;
                }
                Transaction g = b.transactions.get(0);
                PublicKey regKey = Crypto.decodePublic(g.get("registrarKey"));
                if (regKey == null || !Crypto.verify(regKey, g.signingText(), g.signature)) {
                    r.fail(0, "SIGNATURE", "the registrar's signature on the GENESIS record is not valid");
                    return r;
                }
                st.registrarKey = g.get("registrarKey");
                st.networkName = g.get("network");
                st.genesisHash = b.hash;
            } else {
                Block prev = chain.get(i - 1);
                if (!prev.hash.equals(b.previousHash)) {
                    r.fail(i, "LINK", "it does not point to block " + (i - 1) + " (previous hash differs) - the chain is broken here");
                    return r;
                }
                if (b.time < prev.time) { r.fail(i, "RULE", "its time is earlier than the block before it"); return r; }
                if (b.transactions.isEmpty()) { r.fail(i, "RULE", "it has no transactions"); return r; }
                for (Transaction tx : b.transactions) {
                    String why = apply(st, tx, b);
                    if (why != null) {
                        r.fail(i, why.startsWith("signature") ? "SIGNATURE" : "RULE", why);
                        return r;
                    }
                }
            }
            st.height = i;
            st.tipHash = b.hash;
        }
        return r;
    }

    /** Applies one transaction to the state. Returns null if it is allowed, or the reason it is not. */
    static String apply(ChainState st, Transaction tx, Block b) {
        if (tx.time > b.time + CLOCK_SKEW_MS) return "a " + tx.type.label.toLowerCase() + " record is dated after its block";
        switch (tx.type) {
            case GENESIS:
                return "a second GENESIS record is not allowed";
            case REGISTER_ISSUER: {
                if (!Transaction.REGISTRAR.equals(tx.signer)) return "only the registrar can approve an institution";
                if (!Crypto.verify(Crypto.decodePublic(st.registrarKey), tx.signingText(), tx.signature))
                    return "signature on 'institution approved' is not the registrar's";
                String id = tx.get("issuerId");
                if (id == null || id.isBlank()) return "institution code is missing";
                if (st.issuers.containsKey(id)) return "institution " + id + " is already registered";
                if (Crypto.decodePublic(tx.get("publicKey")) == null) return "institution " + id + " has a broken public key";
                ChainState.IssuerEntry e = new ChainState.IssuerEntry();
                e.id = id;
                e.name = tx.get("name");
                e.publicKey = tx.get("publicKey");
                e.registeredAt = tx.time;
                e.registeredBlock = b.index;
                st.issuers.put(id, e);
                return null;
            }
            case SUSPEND_ISSUER: {
                if (!Transaction.REGISTRAR.equals(tx.signer)) return "only the registrar can suspend an institution";
                if (!Crypto.verify(Crypto.decodePublic(st.registrarKey), tx.signingText(), tx.signature))
                    return "signature on 'institution suspended' is not the registrar's";
                ChainState.IssuerEntry e = st.issuers.get(tx.get("issuerId"));
                if (e == null) return "cannot suspend an unknown institution";
                if (e.suspendedAt != 0) return "institution " + e.id + " is already suspended";
                e.suspendedAt = tx.time;
                e.suspendReason = tx.get("reason") == null ? "" : tx.get("reason");
                return null;
            }
            case ISSUE_CERT: {
                ChainState.IssuerEntry e = st.issuers.get(tx.signer);
                if (e == null) return "certificate signed by an unknown institution (" + tx.signer + ")";
                if (!tx.signer.equals(tx.get("issuerId"))) return "certificate names a different institution than its signer";
                if (!e.activeAt(tx.time)) return "institution " + e.id + " was not allowed to issue at that time";
                if (!Crypto.verify(Crypto.decodePublic(e.publicKey), tx.signingText(), tx.signature))
                    return "signature on certificate " + tx.get("certId") + " does not match " + e.id + "'s key";
                String certId = tx.get("certId");
                if (certId == null || certId.isBlank()) return "certificate ID is missing";
                if (st.certs.containsKey(certId)) return "certificate " + certId + " was issued twice";
                String root = tx.get("root");
                String leaves = tx.get("fieldHashes");
                if (root == null || leaves == null || !root.equals(MerkleTree.root(List.of(leaves.split(",")))))
                    return "certificate " + certId + " fingerprint does not match its field hashes";
                ChainState.CertEntry c = new ChainState.CertEntry();
                c.certId = certId;
                c.issuerId = e.id;
                c.issueTx = tx;
                c.block = b.index;
                c.blockHash = b.hash;
                st.certs.put(certId, c);
                return null;
            }
            case REVOKE_CERT: {
                ChainState.CertEntry c = st.certs.get(tx.get("certId"));
                if (c == null) return "cannot revoke an unknown certificate";
                if (!c.issuerId.equals(tx.signer)) return "only the institution that issued a certificate can revoke it";
                ChainState.IssuerEntry e = st.issuers.get(tx.signer);
                if (e == null || !Crypto.verify(Crypto.decodePublic(e.publicKey), tx.signingText(), tx.signature))
                    return "signature on the revocation of " + c.certId + " is not valid";
                if (c.revokeTx != null) return "certificate " + c.certId + " is already revoked";
                c.revokeTx = tx;
                c.revokeBlock = b.index;
                return null;
            }
            default:
                return "unknown record type";
        }
    }
}
