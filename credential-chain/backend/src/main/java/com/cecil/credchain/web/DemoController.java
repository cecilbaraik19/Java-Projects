package com.cecil.credchain.web;

import com.cecil.credchain.auth.AuthService;
import com.cecil.credchain.blockchain.Block;
import com.cecil.credchain.blockchain.Blockchain;
import com.cecil.credchain.credential.CredentialEntity;
import com.cecil.credchain.credential.CredentialRepository;
import com.cecil.credchain.credential.CredentialService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attack simulation for presentations: tamper with the off-chain DB or with a mined block
 * and watch verification / chain validation catch it. Restore puts everything back.
 * ADMIN only: otherwise any visitor could edit grades in the database.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final Blockchain blockchain;
    private final CredentialService credentials;
    private final CredentialRepository repository;
    private final AuthService auth;
    private final Map<String, String> originalGrades = new ConcurrentHashMap<>();

    public DemoController(Blockchain blockchain, CredentialService credentials, CredentialRepository repository,
                          AuthService auth) {
        this.blockchain = blockchain;
        this.credentials = credentials;
        this.repository = repository;
        this.auth = auth;
    }

    /** Simulates an insider editing the database to upgrade a student's grade. */
    @PostMapping("/tamper-credential/{id}")
    @Transactional
    public Map<String, Object> tamperCredential(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String id) {
        auth.requireAdmin(authorization);
        CredentialEntity e = credentials.get(id);
        originalGrades.putIfAbsent(id, e.getGrade());
        e.setGrade("10.0 CGPA (forged)");
        repository.save(e);
        return Map.of("message", "Database record for " + id + " changed: grade is now '" + e.getGrade()
                + "'. Verify it to see the hash mismatch.");
    }

    /** Simulates an attacker editing a transaction inside an already-mined block. */
    @PostMapping("/tamper-block/{index}")
    public Map<String, Object> tamperBlock(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable int index) {
        auth.requireAdmin(authorization);
        Block b = blockchain.tamperBlock(index);
        return Map.of("message", "Transaction in block #" + b.getIndex() + " was altered in memory. Run chain validation to see it caught.");
    }

    @PostMapping("/restore")
    @Transactional
    public Map<String, Object> restore(@RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireAdmin(authorization);
        originalGrades.forEach((id, grade) -> repository.findById(id).ifPresent(e -> {
            e.setGrade(grade);
            repository.save(e);
        }));
        int restored = originalGrades.size();
        originalGrades.clear();
        blockchain.loadFromDatabase();
        if (!blockchain.validate().valid()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Chain still invalid after restore");
        }
        return Map.of("message", "Restored " + restored + " credential record(s) and reloaded the chain from storage.");
    }
}
