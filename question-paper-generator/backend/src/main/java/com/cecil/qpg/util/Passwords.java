package com.cecil.qpg.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing with PBKDF2-SHA256 and a random salt.
 * Stored format: pbkdf2$iterations$salt$hash (salt and hash in Base64).
 */
public final class Passwords {
    private static final SecureRandom RNG = new SecureRandom();
    private static final int ITERATIONS = 120_000;

    private Passwords() {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] h = pbkdf2(password.toCharArray(), salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(h);
    }

    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
        try {
            int it = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] want = Base64.getDecoder().decode(p[3]);
            byte[] got = pbkdf2(password.toCharArray(), salt, it);
            return MessageDigest.isEqual(want, got); // constant-time compare
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Rules for new passwords: 8+ characters with at least one letter and one digit. */
    public static void checkStrength(String pw) {
        if (pw == null || pw.length() < 8) throw new IllegalArgumentException("Password must be at least 8 characters");
        if (pw.length() > 128) throw new IllegalArgumentException("Password is too long");
        if (!pw.matches(".*[A-Za-z].*") || !pw.matches(".*[0-9].*"))
            throw new IllegalArgumentException("Password needs at least one letter and one number");
    }

    /** A random 256-bit token for sessions. */
    public static String newToken() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] pbkdf2(char[] pw, byte[] salt, int it) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw, salt, it, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }
}
