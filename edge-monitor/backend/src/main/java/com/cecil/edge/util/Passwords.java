package com.cecil.edge.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password and key helpers.
 * - Passwords: PBKDF2 with SHA-256, a random 16-byte salt and 120,000 rounds (slow on purpose).
 * - Node API keys and session tokens: 32 random bytes. Node keys are stored only as a SHA-256 hash.
 */
public final class Passwords {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ROUNDS = 120_000;
    private Passwords() { }

    public static String newSalt() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    public static String hash(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), ROUNDS, 256);
            byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(out);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares in constant time, so the time taken does not leak how many characters matched. */
    public static boolean matches(String password, String salt, String expectedHash) {
        if (password == null || salt == null || expectedHash == null) return false;
        return MessageDigest.isEqual(hash(password, salt).getBytes(), expectedHash.getBytes());
    }

    /** Random URL-safe token (used for sessions). */
    public static String token() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Random API key for an edge node, e.g. "edge_3f9a...". Shown once, then only its hash is kept. */
    public static String nodeKey() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return "edge_" + HexFormat.of().formatHex(b);
    }

    public static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean sameHash(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}
