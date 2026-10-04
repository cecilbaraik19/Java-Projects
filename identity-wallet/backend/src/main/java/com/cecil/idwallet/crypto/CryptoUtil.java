package com.cecil.idwallet.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Small helpers: SHA-256, secure random bytes, Base64url. */
public final class CryptoUtil {

    public static final SecureRandom RANDOM = new SecureRandom();

    private CryptoUtil() {}

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(String s) {
        return HexFormat.of().formatHex(sha256(s));
    }

    public static String sha256Hex(byte[] b) {
        return HexFormat.of().formatHex(sha256(b));
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /** URL-safe random token, e.g. for share links. */
    public static String randomToken(int bytes) {
        return b64url(randomBytes(bytes));
    }

    public static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static byte[] fromB64url(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    public static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    public static byte[] fromB64(String s) {
        return Base64.getDecoder().decode(s);
    }

    /** Constant-time comparison, so attackers can't learn a secret one byte at a time. */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
