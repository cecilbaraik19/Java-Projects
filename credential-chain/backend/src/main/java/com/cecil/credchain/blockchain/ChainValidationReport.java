package com.cecil.credchain.blockchain;

import java.util.List;

/** Result of walking the whole chain and re-checking every hash, link, Merkle root and signature. */
public record ChainValidationReport(boolean valid, int blocksChecked, int transactionsChecked, List<Issue> issues) {

    public record Issue(int blockIndex, String check, String detail) {}
}
