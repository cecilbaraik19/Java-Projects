package com.cecil.idwallet;

import com.cecil.idwallet.auth.PasswordHasher;
import com.cecil.idwallet.crypto.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

class CryptoTest {

    @Test
    void totpMatchesRfc6238TestVector() {
        // RFC 6238 appendix B: secret "12345678901234567890", T=59s -> 94287082 (last 6 digits 287082)
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assertEquals("287082", Totp.generate(secret, 59 / 30));
        assertTrue(Totp.verify(secret, "287082", 59));
        assertFalse(Totp.verify(secret, "287083", 59));
    }

    @Test
    void aesGcmRoundTripAndTamperDetection() {
        byte[] key = CryptoUtil.randomBytes(32);
        byte[] blob = AesGcm.encrypt(key, "secret".getBytes(StandardCharsets.UTF_8), "owner:1");
        assertEquals("secret", new String(AesGcm.decrypt(key, blob, "owner:1"), StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> AesGcm.decrypt(key, blob, "owner:2"));
        blob[20] ^= 1;
        assertThrows(IllegalStateException.class, () -> AesGcm.decrypt(key, blob, "owner:1"));
    }

    @Test
    void ecdsaSignVerify() {
        KeyPair kp = EcKeys.generate();
        String sig = EcKeys.sign(kp.getPrivate(), "hello");
        assertTrue(EcKeys.verify(kp.getPublic(), "hello", sig));
        assertFalse(EcKeys.verify(kp.getPublic(), "hellO", sig));
        assertTrue(EcKeys.verify(EcKeys.decodePublic(EcKeys.encodePublic(kp.getPublic())), "hello", sig));
        assertTrue(EcKeys.didFor(kp.getPublic()).startsWith("did:idw:"));
    }

    @Test
    void passwordHashing() {
        String h = PasswordHasher.hash("Pass@1234");
        assertTrue(PasswordHasher.matches("Pass@1234", h));
        assertFalse(PasswordHasher.matches("Pass@1235", h));
        assertNotEquals(h, PasswordHasher.hash("Pass@1234"), "salt must differ");
        assertNotNull(PasswordHasher.policyError("short"));
        assertNull(PasswordHasher.policyError("Good@pass1"));
    }

    @Test
    void selectiveDisclosureDigestIsStable() {
        var d = SelectiveDisclosure.create("fullName", "Aarav");
        var back = SelectiveDisclosure.decode(d.encoded());
        assertEquals("fullName", back.name());
        assertEquals("Aarav", back.value().asText());
        assertEquals(d.digest(), back.digest());
        assertNotEquals(d.digest(), SelectiveDisclosure.create("fullName", "Aarav").digest(), "salt must differ");
    }
}
