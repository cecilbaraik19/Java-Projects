package com.cecil.shop.common;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Password hashing with PBKDF2-SHA256 and a random salt. The plain password is never stored. */
public final class Passwords {
    private static final SecureRandom RNG = new SecureRandom();
    private static final int ITERATIONS = 60_000;

    private Passwords() {}

    /** Returns "salt:hash" (both Base64). */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(pbkdf2(password, salt));
    }

    public static boolean matches(String password, String stored) {
        if (password == null || stored == null || !stored.contains(":")) return false;
        String[] p = stored.split(":", 2);
        byte[] salt = Base64.getDecoder().decode(p[0]);
        byte[] expected = Base64.getDecoder().decode(p[1]);
        return MessageDigest.isEqual(expected, pbkdf2(password, salt)); // constant-time compare
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String randomKey(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
