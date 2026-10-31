package com.cecil.shop.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * Signed login tokens (the same idea as a JWT).
 *
 *   token = base64(payload JSON) + "." + base64(HMAC-SHA256 signature)
 *
 * The auth service SIGNS the token at login. The gateway only CHECKS the signature with the shared key,
 * so it does not need to call the auth service on every request ("stateless" authentication).
 * If anyone changes the payload (for example role CUSTOMER -> ADMIN) the signature no longer matches.
 */
public final class Tokens {
    private Tokens() {}

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    public static String sign(Map<String, Object> payload, String key) {
        String body = ENC.encodeToString(Json.write(payload).getBytes(StandardCharsets.UTF_8));
        return body + "." + ENC.encodeToString(hmac(body, key));
    }

    /** Returns the payload if the signature is right and the token has not expired, else null. */
    public static Map<String, Object> verify(String token, String key, long nowMs) {
        if (token == null || token.length() > 4096) return null;
        int dot = token.indexOf('.');
        if (dot <= 0) return null;
        String body = token.substring(0, dot);
        try {
            byte[] sig = DEC.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sig, hmac(body, key))) return null;
            Map<String, Object> p = Json.parseObject(new String(DEC.decode(body), StandardCharsets.UTF_8));
            if (Json.lng(p, "exp") < nowMs) return null;
            return p;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] hmac(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("token:" + key).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
