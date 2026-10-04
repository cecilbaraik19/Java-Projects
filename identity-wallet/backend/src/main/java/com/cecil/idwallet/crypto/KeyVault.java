package com.cecil.idwallet.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;

/**
 * Envelope encryption.
 *
 *   master key (one, outside the DB)
 *      └── wraps each user's data-encryption key (DEK)       -> stored in users.wrapped_dek
 *              └── encrypts that user's vault + disclosures  -> stored in vault_items, credentials
 *      └── wraps every private signing key                   -> stored in users / issuers
 *
 * A stolen database alone is useless: everything sensitive is ciphertext, and the master key
 * lives in a separate file or environment variable. Rotating the master key only means
 * re-wrapping the small DEKs, not re-encrypting every document.
 */
@Component
public class KeyVault {

    private final byte[] masterKey;

    public KeyVault(@Value("${idwallet.master-key:}") String configured,
                    @Value("${idwallet.master-key-file:./data/master.key}") String file) throws IOException {
        if (configured != null && !configured.isBlank()) {
            masterKey = CryptoUtil.fromB64(configured.trim());
        } else {
            Path p = Path.of(file);
            if (Files.exists(p)) {
                masterKey = CryptoUtil.fromB64(Files.readString(p, StandardCharsets.UTF_8).trim());
            } else {
                masterKey = CryptoUtil.randomBytes(32);
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.writeString(p, CryptoUtil.b64(masterKey), StandardCharsets.UTF_8);
            }
        }
        if (masterKey.length != 32) throw new IllegalStateException("Master key must be 32 bytes (256-bit)");
    }

    public byte[] newDek() {
        return CryptoUtil.randomBytes(32);
    }

    public String wrapDek(byte[] dek, String owner) {
        return CryptoUtil.b64(AesGcm.encrypt(masterKey, dek, "dek:" + owner));
    }

    public byte[] unwrapDek(String wrapped, String owner) {
        return AesGcm.decrypt(masterKey, CryptoUtil.fromB64(wrapped), "dek:" + owner);
    }

    public String wrapPrivateKey(PrivateKey key, String owner) {
        return CryptoUtil.b64(AesGcm.encrypt(masterKey, key.getEncoded(), "sk:" + owner));
    }

    public PrivateKey unwrapPrivateKey(String wrapped, String owner) {
        return EcKeys.decodePrivate(AesGcm.decrypt(masterKey, CryptoUtil.fromB64(wrapped), "sk:" + owner));
    }

    public String encryptSecret(String plaintext, String owner) {
        return AesGcm.encryptString(masterKey, plaintext, "secret:" + owner);
    }

    public String decryptSecret(String ciphertext, String owner) {
        return AesGcm.decryptString(masterKey, ciphertext, "secret:" + owner);
    }
}
