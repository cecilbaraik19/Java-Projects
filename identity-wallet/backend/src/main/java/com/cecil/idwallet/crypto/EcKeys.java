package com.cecil.idwallet.crypto;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * ECDSA over the NIST P-256 curve with SHA-256 ("ES256").
 * Issuers sign credentials with it; holders sign presentations with it.
 */
public final class EcKeys {

    private EcKeys() {}

    public static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), CryptoUtil.RANDOM);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sign(PrivateKey key, String message) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(key);
            s.update(message.getBytes(StandardCharsets.UTF_8));
            return CryptoUtil.b64url(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(PublicKey key, String message, String signatureB64url) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(key);
            s.update(message.getBytes(StandardCharsets.UTF_8));
            return s.verify(CryptoUtil.fromB64url(signatureB64url));
        } catch (Exception e) {
            return false;
        }
    }

    public static String encodePublic(PublicKey k) {
        return CryptoUtil.b64(k.getEncoded());
    }

    public static PublicKey decodePublic(String b64) {
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(CryptoUtil.fromB64(b64)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid public key", e);
        }
    }

    public static PrivateKey decodePrivate(byte[] pkcs8) {
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid private key", e);
        }
    }

    /**
     * A decentralized-identifier-style ID derived from the public key:
     * did:idw:&lt;first 20 bytes of SHA-256(publicKey), hex&gt;. Anyone holding the key can recompute it.
     */
    public static String didFor(PublicKey k) {
        return "did:idw:" + CryptoUtil.sha256Hex(k.getEncoded()).substring(0, 40);
    }

    /** Short human-friendly fingerprint for the UI. */
    public static String fingerprint(String publicKeyB64) {
        String h = CryptoUtil.sha256Hex(CryptoUtil.fromB64(publicKeyB64)).toUpperCase();
        return String.join(":", h.substring(0, 4), h.substring(4, 8), h.substring(8, 12), h.substring(12, 16));
    }
}
