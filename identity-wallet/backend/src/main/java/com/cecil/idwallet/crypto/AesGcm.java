package com.cecil.idwallet.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * AES-256-GCM authenticated encryption.
 * Output layout: [12-byte random IV][ciphertext + 16-byte auth tag].
 * GCM detects any tampering: decrypting a modified blob throws instead of returning garbage.
 * The optional "aad" (additional authenticated data) binds a ciphertext to its owner/record,
 * so an attacker with DB access can't copy one user's encrypted blob into another user's row.
 */
public final class AesGcm {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private AesGcm() {}

    public static byte[] encrypt(byte[] key, byte[] plaintext, String aad) {
        try {
            byte[] iv = CryptoUtil.randomBytes(IV_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null) c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plaintext);
            return ByteBuffer.allocate(IV_LEN + ct.length).put(iv).put(ct).array();
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public static byte[] decrypt(byte[] key, byte[] blob, String aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN));
            if (aad != null) c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return c.doFinal(blob, IV_LEN, blob.length - IV_LEN);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed (wrong key or data was tampered with)", e);
        }
    }

    public static String encryptString(byte[] key, String plaintext, String aad) {
        return CryptoUtil.b64(encrypt(key, plaintext.getBytes(StandardCharsets.UTF_8), aad));
    }

    public static String decryptString(byte[] key, String b64, String aad) {
        return new String(decrypt(key, CryptoUtil.fromB64(b64), aad), StandardCharsets.UTF_8);
    }
}
