package com.cecil.idwallet;

import com.cecil.idwallet.crypto.CryptoUtil;
import com.cecil.idwallet.crypto.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end: seed data -> issue -> selective share -> verify -> tamper -> revoke, plus vault, MFA and the audit chain. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class WalletFlowTest {

    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    String login(String email, String password) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("email", email, "password", password));
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("token").asText();
    }

    JsonNode call(String method, String url, String token, Object body, int expected) throws Exception {
        var req = switch (method) {
            case "GET" -> get(url);
            case "DELETE" -> delete(url);
            default -> post(url);
        };
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        String res = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return res.isEmpty() ? null : json.readTree(res);
    }

    @Test
    void fullFlow() throws Exception {
        String issuer = login("registrar@identity.demo", "Issuer@123");
        String holder = login("priya@wallet.demo", "User@1234");

        // issue a national ID to Priya
        var claims = new java.util.LinkedHashMap<String, String>();
        claims.put("fullName", "Priya Verma");
        claims.put("dateOfBirth", "2001-07-20");
        claims.put("gender", "Female");
        claims.put("idNumber", "TEST-1111");
        claims.put("nationality", "Indian");
        JsonNode issued = call("POST", "/api/issuer/credentials", issuer,
                java.util.Map.of("holderEmail", "priya@wallet.demo", "type", "NATIONAL_ID", "claims", claims), 200);
        String credId = issued.get("id").asText();

        // holders can't issue; the holder sees decrypted claims incl. derived age claim
        call("POST", "/api/issuer/credentials", holder, java.util.Map.of("holderEmail", "x", "type", "X", "claims", claims), 403);
        JsonNode detail = call("GET", "/api/wallet/credentials/" + credId, holder, null, 200);
        assertTrue(detail.get("signatureValid").asBoolean());
        assertTrue(detail.get("payload").asText().contains("_sd"));
        assertFalse(detail.get("payload").asText().contains("TEST-1111"), "values must not be in the signed payload");

        // share only name + age_over_18
        JsonNode share = call("POST", "/api/wallet/shares", holder, java.util.Map.of("credentialId", credId,
                "claims", java.util.List.of("fullName", "age_over_18"), "recipient", "Test Bank", "expiresInMinutes", 60, "maxViews", 2), 200);
        String token = share.get("link").asText().replaceAll(".*/v/", "");

        JsonNode v = call("GET", "/api/public/share/" + token, null, null, 200);
        assertTrue(v.get("valid").asBoolean(), v.toString());
        assertEquals(2, v.get("claims").size());
        assertEquals(5, v.get("credential").get("hiddenClaims").asInt()); // dateOfBirth, gender, idNumber, nationality, age_over_21
        assertFalse(v.toString().contains("TEST-1111"), "hidden claim leaked");

        // tamper with a revealed value -> must fail
        ObjectNode vp = (ObjectNode) json.readTree(v.get("presentation").asText());
        ArrayNode ds = (ArrayNode) vp.get("disclosures");
        String forged = CryptoUtil.b64url("[\"salt\",\"fullName\",\"Someone Else\"]".getBytes(StandardCharsets.UTF_8));
        ds.set(0, json.getNodeFactory().textNode(forged));
        JsonNode bad = call("POST", "/api/public/verify", null, vp.toString(), 200);
        assertFalse(bad.get("valid").asBoolean());

        // view limit of 2
        call("GET", "/api/public/share/" + token, null, null, 200);
        JsonNode used = call("GET", "/api/public/share/" + token, null, null, 200);
        assertFalse(used.get("valid").asBoolean());

        // revoke -> pasted presentation now fails the status check
        call("POST", "/api/issuer/credentials/" + credId + "/revoke", issuer, java.util.Map.of("reason", "test"), 200);
        JsonNode afterRevoke = call("POST", "/api/public/verify", null, v.get("presentation").asText(), 200);
        assertFalse(afterRevoke.get("valid").asBoolean());

        // other users can't see Priya's credential
        String aarav = login("aarav@wallet.demo", "User@1234");
        call("GET", "/api/wallet/credentials/" + credId, aarav, null, 404);
        call("GET", "/api/wallet/credentials", null, null, 401);

        // audit chain intact
        String admin = login("admin@idwallet.local", "Admin@123");
        assertTrue(call("GET", "/api/admin/audit/verify", admin, null, 200).get("valid").asBoolean());
    }

    @Test
    void vaultEncryptsAndChecksFileType() throws Exception {
        String t = login("aarav@wallet.demo", "User@1234");
        byte[] pdf = "%PDF-1.4 test document".getBytes(StandardCharsets.US_ASCII);
        String res = mvc.perform(multipart("/api/vault").file(new MockMultipartFile("file", "id.pdf", "application/pdf", pdf))
                        .param("title", "Test").param("category", "OTHER").param("documentNumber", "N-1")
                        .header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(res).get("id").asLong();
        byte[] back = mvc.perform(get("/api/vault/" + id + "/file").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(pdf, back);

        // an HTML file renamed to .pdf is rejected (magic-byte check)
        mvc.perform(multipart("/api/vault").file(new MockMultipartFile("file", "x.pdf", "application/pdf", "<script>".getBytes()))
                        .param("title", "Bad").param("category", "OTHER").header("Authorization", "Bearer " + t))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mfaAndLockout() throws Exception {
        String reg = json.writeValueAsString(java.util.Map.of("fullName", "Test User", "email", "mfa@test.dev", "password", "Mfa@12345"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(reg)).andExpect(status().isOk());
        String t = login("mfa@test.dev", "Mfa@12345");
        String secret = call("POST", "/api/auth/mfa/setup", t, null, 200).get("secret").asText();
        long now = Instant.now().getEpochSecond();
        call("POST", "/api/auth/mfa/confirm", t, java.util.Map.of("code", Totp.generate(secret, now / 30)), 200);

        JsonNode step1 = call("POST", "/api/auth/login", null, java.util.Map.of("email", "mfa@test.dev", "password", "Mfa@12345"), 200);
        assertTrue(step1.get("mfaRequired").asBoolean());
        assertNull(step1.get("token"));
        call("POST", "/api/auth/mfa", null, java.util.Map.of("ticket", step1.get("mfaTicket").asText(), "code", "000000"), 401);
        JsonNode ok = call("POST", "/api/auth/mfa", null, java.util.Map.of("ticket", step1.get("mfaTicket").asText(),
                "code", Totp.generate(secret, Instant.now().getEpochSecond() / 30)), 200);
        assertNotNull(ok.get("token"));

        // 5 wrong passwords lock the account
        for (int i = 0; i < 5; i++)
            call("POST", "/api/auth/login", null, java.util.Map.of("email", "mfa@test.dev", "password", "wrong"), 401);
        call("POST", "/api/auth/login", null, java.util.Map.of("email", "mfa@test.dev", "password", "Mfa@12345"), 423);
    }
}
