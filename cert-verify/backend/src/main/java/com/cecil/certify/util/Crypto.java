package com.cecil.certify.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * All the cryptography used by Pramaan, in one place. Only the JDK is used.
 *  - SHA-256           : fingerprints (hashes) of fields, certificates, transactions and blocks
 *  - ECDSA P-256       : digital signatures of institutions and of the registrar
 *  - SecureRandom      : salts, certificate IDs, share tokens, session IDs
 */
public final class Crypto {
    private Crypto() {}

    public static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    // ------------------------------------------------------------------ hashing

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String sha256Hex(String text) {
        return hex(sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** Hash of two hashes joined together - used to build Merkle trees. */
    public static String hashPair(String leftHex, String rightHex) {
        return sha256Hex(leftHex + rightHex);
    }

    public static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = HEX[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[b[i] & 0xF];
        }
        return new String(out);
    }

    public static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return hex(b);
    }

    public static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Compares two texts in the same time whether they match or not (stops timing attacks). */
    public static boolean sameText(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ signatures (ECDSA, curve P-256)

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EC keys not available", e);
        }
    }

    public static String sign(PrivateKey key, String message) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(key, RANDOM);
            s.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign", e);
        }
    }

    public static boolean verify(PublicKey key, String message, String signatureB64) {
        if (key == null || message == null || signatureB64 == null) return false;
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(key);
            s.update(message.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(signatureB64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false; // a broken signature is simply "not valid"
        }
    }

    public static String encodePublic(PublicKey k) {
        return Base64.getEncoder().encodeToString(k.getEncoded());
    }

    public static String encodePrivate(PrivateKey k) {
        return Base64.getEncoder().encodeToString(k.getEncoded());
    }

    public static PublicKey decodePublic(String b64) {
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    public static PrivateKey decodePrivate(String b64) {
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** Short, human-friendly key fingerprint, e.g. "3F9A-12C0-77DE". */
    public static String keyFingerprint(String publicKeyB64) {
        String h = sha256Hex(publicKeyB64 == null ? "" : publicKeyB64).toUpperCase();
        return h.substring(0, 4) + "-" + h.substring(4, 8) + "-" + h.substring(8, 12);
    }
}
