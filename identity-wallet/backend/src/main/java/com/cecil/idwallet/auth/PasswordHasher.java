package com.cecil.idwallet.auth;

import com.cecil.idwallet.crypto.CryptoUtil;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256, 16-byte random salt, 210,000 iterations (OWASP 2023 guidance).
 * Stored as: pbkdf2$&lt;iterations&gt;$&lt;salt b64&gt;$&lt;hash b64&gt;
 */
public final class PasswordHasher {

    private static final int ITERATIONS = 210_000;

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] salt = CryptoUtil.randomBytes(16);
        return "pbkdf2$" + ITERATIONS + "$" + CryptoUtil.b64(salt) + "$" + CryptoUtil.b64(derive(password, salt, ITERATIONS));
    }

    public static boolean matches(String password, String stored) {
        try {
            String[] p = stored.split("\\$");
            int it = Integer.parseInt(p[1]);
            return CryptoUtil.constantTimeEquals(derive(password, CryptoUtil.fromB64(p[2]), it), CryptoUtil.fromB64(p[3]));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** At least 8 characters with a letter, a digit and a symbol. */
    public static String policyError(String password) {
        if (password == null || password.length() < 8) return "Password must be at least 8 characters";
        if (password.length() > 128) return "Password is too long";
        if (!password.matches(".*[A-Za-z].*")) return "Password needs at least one letter";
        if (!password.matches(".*\\d.*")) return "Password needs at least one digit";
        if (!password.matches(".*[^A-Za-z0-9].*")) return "Password needs at least one symbol";
        return null;
    }
}
