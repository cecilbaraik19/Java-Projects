package com.cecil.rag.util;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing with PBKDF2-SHA256 and a random salt.
 * Stored format: pbkdf2$iterations$salt$hash (salt and hash in Base64).
 * The real password is never saved.
 */
public final class Passwords {
    private Passwords() {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 120_000;

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] h = pbkdf2(password.toCharArray(), salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$" + b64(salt) + "$" + b64(h);
    }

    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
        byte[] salt = Base64.getDecoder().decode(p[2]);
        byte[] expected = Base64.getDecoder().decode(p[3]);
        byte[] actual = pbkdf2(password.toCharArray(), salt, Integer.parseInt(p[1]));
        return MessageDigest.isEqual(expected, actual); // same time for every compare
    }

    /** A long random token (used for session ids). */
    public static String token() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Checks the rules for a new password. Returns an error message, or null when it is fine. */
    public static String problem(String pw) {
        if (pw == null || pw.length() < 8) return "Password must have at least 8 characters.";
        if (pw.length() > 128) return "Password is too long.";
        if (!pw.matches(".*[A-Za-z].*") || !pw.matches(".*\\d.*")) return "Use at least one letter and one number.";
        return null;
    }

    private static byte[] pbkdf2(char[] pw, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw, salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
}
