package com.cecil.certify.chain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a chain "says" after all its blocks have been checked and replayed in order:
 * which institutions are approved, and which certificates were issued or revoked.
 */
public final class ChainState {

    public String networkName = "";
    public String registrarKey = "";
    public String genesisHash = "";
    public int height = -1;           // index of the last block
    public String tipHash = "";

    public final Map<String, IssuerEntry> issuers = new LinkedHashMap<>();
    public final Map<String, CertEntry> certs = new LinkedHashMap<>();

    public static final class IssuerEntry {
        public String id;
        public String name;
        public String publicKey;
        public long registeredAt;
        public int registeredBlock;
        public long suspendedAt;          // 0 = still active
        public String suspendReason = "";

        public boolean activeAt(long time) {
            return time >= registeredAt && (suspendedAt == 0 || time < suspendedAt);
        }
    }

    public static final class CertEntry {
        public String certId;
        public String issuerId;
        public Transaction issueTx;
        public int block;
        public String blockHash;
        public Transaction revokeTx;      // null = not revoked
        public int revokeBlock = -1;
    }
}
