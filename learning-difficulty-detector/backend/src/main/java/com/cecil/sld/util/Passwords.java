package com.cecil.sld.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing with PBKDF2-SHA256 and a random salt (built into the JDK).
 * Stored format:  pbkdf2$iterations$salt$hash   (salt and hash in Base64)
 */
public final class Passwords {
    private Passwords() {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 120_000;

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] h = pbkdf2(password.toCharArray(), salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(h);
    }

    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2")) return false;
        try {
            int it = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, it);
            return MessageDigest.isEqual(expected, actual);   // same time for every comparison
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** A random token for sessions (URL-safe, 32 bytes). */
    public static String token() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** The rules for a new password. Returns an error message, or null when it is fine. */
    public static String problem(String password) {
        if (password == null || password.length() < 8) return "Password must be at least 8 characters.";
        if (password.length() > 100) return "Password is too long.";
        boolean letter = false, digit = false;
        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) letter = true;
            if (Character.isDigit(c)) digit = true;
        }
        if (!letter || !digit) return "Password needs at least one letter and one number.";
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
}
