package com.cecil.credchain.web;

import com.cecil.credchain.blockchain.*;
import com.cecil.credchain.credential.CredentialService;
import com.cecil.credchain.institution.InstitutionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChainController {

    private final Blockchain blockchain;
    private final CredentialService credentials;
    private final InstitutionService institutions;

    public ChainController(Blockchain blockchain, CredentialService credentials, InstitutionService institutions) {
        this.blockchain = blockchain;
        this.credentials = credentials;
        this.institutions = institutions;
    }

    @GetMapping("/chain")
    public List<Block> chain() {
        return blockchain.getChain();
    }

    @GetMapping("/chain/blocks/{index}")
    public Block block(@PathVariable int index) {
        return blockchain.getBlock(index).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Block not found"));
    }

    @GetMapping("/chain/pending")
    public List<Transaction> pending() {
        return blockchain.getPending();
    }

    @GetMapping("/chain/tx/{id}")
    public TxLocation transaction(@PathVariable String id) {
        return blockchain.findTransaction(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Transaction not found"));
    }

    @PostMapping("/chain/mine")
    public Map<String, Object> mine() {
        Block block = blockchain.mine();
        if (block == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No pending transactions to mine");
        }
        return Map.of("block", block, "message", "Mined block #" + block.getIndex() + " in " + block.getMiningTimeMs() + " ms");
    }

    @GetMapping("/chain/validate")
    public ChainValidationReport validate() {
        return blockchain.validate();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        List<Block> chain = blockchain.getChain();
        long issued = 0, revoked = 0, txs = 0;
        for (Block b : chain) {
            for (Transaction t : b.getTransactions()) {
                txs++;
                if (t.getType() == TransactionType.ISSUE) issued++;
                if (t.getType() == TransactionType.REVOKE) revoked++;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("blocks", chain.size());
        stats.put("transactions", txs);
        stats.put("pending", blockchain.getPending().size());
        stats.put("credentialsOnChain", issued);
        stats.put("revoked", revoked);
        stats.put("credentials", credentials.count());
        stats.put("institutions", institutions.list().size());
        stats.put("difficulty", blockchain.getDifficulty());
        stats.put("latestHash", blockchain.latestBlock().getHash());
        stats.put("chainValid", blockchain.validate().valid());
        return stats;
    }
}
