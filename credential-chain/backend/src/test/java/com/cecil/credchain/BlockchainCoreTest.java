package com.cecil.credchain;

import com.cecil.credchain.blockchain.Block;
import com.cecil.credchain.blockchain.MerkleTree;
import com.cecil.credchain.blockchain.Transaction;
import com.cecil.credchain.blockchain.TransactionType;
import com.cecil.credchain.credential.CredentialData;
import com.cecil.credchain.crypto.HashUtil;
import com.cecil.credchain.crypto.KeyUtil;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests for the blockchain building blocks (no Spring context). */
class BlockchainCoreTest {

    @Test
    void sha256IsDeterministic() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", HashUtil.sha256("abc"));
    }

    @Test
    void signatureVerifiesOnlyWithMatchingKeyAndData() {
        KeyPair a = KeyUtil.generateKeyPair();
        KeyPair b = KeyUtil.generateKeyPair();
        String sig = KeyUtil.sign("hello", a.getPrivate());
        assertTrue(KeyUtil.verify("hello", sig, a.getPublic()));
        assertFalse(KeyUtil.verify("hello!", sig, a.getPublic()));
        assertFalse(KeyUtil.verify("hello", sig, b.getPublic()));
        // keys survive Base64 round-trip
        assertTrue(KeyUtil.verify("hello", sig, KeyUtil.decodePublicKey(KeyUtil.encode(a.getPublic()))));
    }

    @Test
    void miningProducesHashWithLeadingZeros() {
        KeyPair k = KeyUtil.generateKeyPair();
        Transaction tx = Transaction.create(TransactionType.ISSUE, "C1", "I1", HashUtil.sha256("x"), "", k.getPrivate());
        Block block = new Block(1, "0".repeat(64), List.of(tx), 3);
        block.mine();
        assertTrue(block.getHash().startsWith("000"));
        assertEquals(block.getHash(), block.calculateHash());
        assertTrue(tx.verifySignature(k.getPublic()));
    }

    @Test
    void tamperingWithTransactionIsDetectable() {
        KeyPair k = KeyUtil.generateKeyPair();
        Transaction tx = Transaction.create(TransactionType.ISSUE, "C1", "I1", HashUtil.sha256("x"), "", k.getPrivate());
        tx.setDataHash(HashUtil.sha256("forged"));
        assertNotEquals(tx.getId(), tx.computeId());
        assertFalse(tx.verifySignature(k.getPublic()));
    }

    @Test
    void merkleRootChangesWhenAnyLeafChanges() {
        String r1 = MerkleTree.root(List.of("a", "b", "c"));
        String r2 = MerkleTree.root(List.of("a", "b", "d"));
        assertNotEquals(r1, r2);
        assertEquals(r1, MerkleTree.root(List.of("a", "b", "c")));
    }

    @Test
    void credentialHashChangesWithAnyField() {
        CredentialData d1 = new CredentialData("C1", "Degree", "Asha", "S1", "B.Sc IT", "", "8.5", "2026-01-01", "I1", "Uni");
        CredentialData d2 = new CredentialData("C1", "Degree", "Asha", "S1", "B.Sc IT", "", "9.5", "2026-01-01", "I1", "Uni");
        assertNotEquals(d1.hash(), d2.hash());
        assertEquals(64, d1.hash().length());
    }
}
