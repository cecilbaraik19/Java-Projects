package com.cecil.credchain.blockchain;

/** A transaction plus where it lives: in a mined block, or still pending (blockIndex == null). */
public record TxLocation(Transaction transaction, Integer blockIndex, String blockHash, Long blockTimestamp) {
    public boolean confirmed() {
        return blockIndex != null;
    }
}
