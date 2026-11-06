package com.cecil.certify.service;

import com.cecil.certify.cert.CertFields;
import com.cecil.certify.chain.Block;
import com.cecil.certify.chain.MerkleTree;
import com.cecil.certify.chain.Network;
import com.cecil.certify.chain.Node;
import com.cecil.certify.chain.Transaction;
import com.cecil.certify.chain.TxType;
import com.cecil.certify.model.CertificateRecord;
import com.cecil.certify.store.DataStore;
import com.cecil.certify.util.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Tamper lab (registrar only): plays the part of a dishonest insider who has full control of ONE node's files,
 * so you can watch the network catch it.
 */
public final class LabService {

    public enum Attack { EDIT, RESEAL, DELETE_REVOCATION }

    private final Network network;
    private final DataStore store;

    public LabService(Network network, DataStore store) {
        this.network = network;
        this.store = store;
    }

    public String attack(String nodeId, Attack attack, String certId, String field, String newValue) {
        CertificateRecord r = store.certificates.get(certId);
        if (r == null) throw new IllegalArgumentException("Choose a certificate");
        if (attack == Attack.DELETE_REVOCATION) {
            if (!r.revoked) throw new IllegalArgumentException("That certificate is not revoked. Choose a revoked one for this attack.");
            return network.tamperDeleteRevocation(nodeId, certId);
        }
        int pos = CertFields.position(field);
        if (pos < 0) throw new IllegalArgumentException("Choose which detail to change");
        if (newValue == null || newValue.isBlank()) throw new IllegalArgumentException("Type the forged value");
        if (CertFields.normalise(newValue).equals(CertFields.normalise(r.fields.get(field))))
            throw new IllegalArgumentException("That is the same as the real value - type a different one");

        // The insider knows the salt (they control the institution's database) and writes the
        // fingerprint the forged certificate would need.
        String forgedLeaf = CertFields.fieldHash(field, newValue, r.salts.get(field));
        Transaction tx = findIssue(network.node(nodeId), certId);
        List<String> leaves = new ArrayList<>(Arrays.asList(tx.get("fieldHashes").split(",")));
        leaves.set(pos, forgedLeaf);
        String newRoot = MerkleTree.root(leaves);
        String replacement = pos + ":" + forgedLeaf;
        String msg = attack == Attack.EDIT
                ? network.tamperEdit(nodeId, certId, replacement, newRoot)
                : network.tamperEditAndReseal(nodeId, certId, replacement, newRoot);
        return msg + " Forged " + CertFields.label(field) + ": '" + newValue + "'.";
    }

    private static Transaction findIssue(Node n, String certId) {
        for (Block b : n.chain)
            for (Transaction t : b.transactions)
                if (t.type == TxType.ISSUE_CERT && certId.equals(t.get("certId"))) return t;
        throw new IllegalArgumentException(certId + " is not on " + n.name);
    }

    /** The forged certificate file that the attacker would now hand to an employer (to test against the network). */
    public Map<String, Object> forgedFile(String certId, String field, String newValue) {
        CertificateRecord r = store.certificates.get(certId);
        if (r == null) throw new IllegalArgumentException("No such certificate");
        Map<String, Object> fields = new java.util.LinkedHashMap<>(r.fields);
        if (field != null && CertFields.position(field) >= 0 && newValue != null && !newValue.isBlank()) fields.put(field, newValue);
        return Json.map("format", "pramaan-certificate/1", "certId", r.certId, "issuerId", r.issuerId,
                "fields", fields, "salts", new java.util.LinkedHashMap<String, Object>(r.salts));
    }
}
