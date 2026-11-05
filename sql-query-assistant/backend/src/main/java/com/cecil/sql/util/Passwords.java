package com.cecil.sql.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Password hashing with PBKDF2-HMAC-SHA256 and a random salt (JDK only). */
public final class Passwords {
    private Passwords() {}

    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int ITERATIONS = 120_000;

    /** Returns "pbkdf2$iterations$salt$hash". */
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
        int it = Integer.parseInt(p[1]);
        byte[] salt = Base64.getDecoder().decode(p[2]);
        byte[] expected = Base64.getDecoder().decode(p[3]);
        byte[] actual = pbkdf2(password.toCharArray(), salt, it);
        return MessageDigest.isEqual(expected, actual);   // constant time compare
    }

    /** Used to spend the same time when the user does not exist (no timing hint). */
    public static void dummyVerify(String password) {
        pbkdf2((password == null ? "" : password).toCharArray(), new byte[16], ITERATIONS);
    }

    /** A random token for session ids. */
    public static String token() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
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

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
}
