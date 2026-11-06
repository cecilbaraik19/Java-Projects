package com.cecil.certify.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.util.Base64;

/** Password hashing with PBKDF2-SHA256 and a random salt. Passwords are never stored as plain text. */
public final class Passwords {
    private Passwords() {}

    private static final int ITERATIONS = 120_000;

    /** Returns "iterations:salt:hash". */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        Crypto.RANDOM.nextBytes(salt);
        return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" + derive(password, salt, ITERATIONS);
    }

    public static boolean check(String password, String stored) {
        if (password == null || stored == null) return false;
        String[] p = stored.split(":");
        if (p.length != 3) return false;
        try {
            int it = Integer.parseInt(p[0]);
            byte[] salt = Base64.getDecoder().decode(p[1]);
            return Crypto.sameText(derive(password, salt, it), p[2]);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    /** A real password check that always runs, so "unknown user" takes as long as "wrong password". */
    public static final String DUMMY = hash("dummy-password-for-timing");
}
