package com.cecil.idwallet.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Selective disclosure in the style of SD-JWT (IETF draft).
 *
 * For every claim the issuer creates a "disclosure":  base64url( ["salt", "name", value] )
 * and puts only its digest  base64url( SHA-256(disclosure) )  into the signed credential.
 *
 *  • The signed credential reveals nothing: digests can't be reversed, and the random salt
 *    stops guessing attacks (e.g. trying every possible date of birth).
 *  • To share "name" and "age_over_18" only, the holder sends the signed credential plus just
 *    those two disclosures. The verifier hashes each one and checks the digest is in the
 *    signed list, so values can't be faked, while the other claims stay hidden.
 */
public final class SelectiveDisclosure {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SelectiveDisclosure() {}

    public record Disclosure(String encoded, String salt, String name, JsonNode value) {
        public String digest() {
            return SelectiveDisclosure.digest(encoded);
        }
    }

    public static Disclosure create(String name, Object value) {
        try {
            String salt = CryptoUtil.randomToken(16);
            String json = JSON.writeValueAsString(List.of(salt, name, value));
            String encoded = CryptoUtil.b64url(json.getBytes(StandardCharsets.UTF_8));
            return new Disclosure(encoded, salt, name, JSON.valueToTree(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String digest(String encodedDisclosure) {
        return CryptoUtil.b64url(CryptoUtil.sha256(encodedDisclosure.getBytes(StandardCharsets.US_ASCII)));
    }

    public static Disclosure decode(String encoded) {
        try {
            JsonNode arr = JSON.readTree(CryptoUtil.fromB64url(encoded));
            if (!arr.isArray() || arr.size() != 3) throw new IllegalArgumentException("Bad disclosure");
            return new Disclosure(encoded, arr.get(0).asText(), arr.get(1).asText(), arr.get(2));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad disclosure", e);
        }
    }
}
