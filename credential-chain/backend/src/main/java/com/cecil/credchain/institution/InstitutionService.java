package com.cecil.credchain.institution;

import com.cecil.credchain.blockchain.Blockchain;
import com.cecil.credchain.blockchain.Transaction;
import com.cecil.credchain.blockchain.TransactionType;
import com.cecil.credchain.crypto.HashUtil;
import com.cecil.credchain.crypto.KeyUtil;
import com.cecil.credchain.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class InstitutionService {

    private final InstitutionRepository repository;
    private final Blockchain blockchain;
    private final SecureRandom random = new SecureRandom();

    public InstitutionService(InstitutionRepository repository, Blockchain blockchain) {
        this.repository = repository;
        this.blockchain = blockchain;
    }

    /** Generates an ECDSA key pair + API key and anchors the public key on-chain. */
    @Transactional
    public Institution register(String name, String email, String website, String fixedApiKey) {
        if (repository.existsByNameIgnoreCase(name.trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "An institution with this name already exists");
        }
        KeyPair keys = KeyUtil.generateKeyPair();
        String publicKey = KeyUtil.encode(keys.getPublic());
        String id = "INST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String apiKey = fixedApiKey != null ? fixedApiKey : "ck_" + randomHex(24);

        Institution institution = new Institution(id, name.trim(), email, website,
                publicKey, KeyUtil.encode(keys.getPrivate()), apiKey);

        Transaction tx = Transaction.create(TransactionType.REGISTER_ISSUER, null, id,
                HashUtil.sha256(publicKey), Blockchain.registrationPayload(publicKey, institution.getName()),
                keys.getPrivate());
        blockchain.submit(tx);
        institution.setRegistrationTxId(tx.getId());
        return repository.save(institution);
    }

    public Institution authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing X-API-Key header");
        }
        return repository.findByApiKey(apiKey.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid API key"));
    }

    public Institution get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Institution not found"));
    }

    public List<Institution> list() {
        return repository.findAll();
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
