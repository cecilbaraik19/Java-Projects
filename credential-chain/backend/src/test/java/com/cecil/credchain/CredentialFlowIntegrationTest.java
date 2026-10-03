package com.cecil.credchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Full REST flow: register issuer -> issue -> mine -> verify -> tamper -> revoke. */
@SpringBootTest
@AutoConfigureMockMvc
class CredentialFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void fullCredentialLifecycle() throws Exception {
        // 1. Register an institution
        String reg = mvc.perform(post("/api/institutions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test University\",\"email\":\"reg@test.edu\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String apiKey = mapper.readTree(reg).get("apiKey").asText();

        // 2. Issuing without API key is rejected
        String body = "{\"credentialType\":\"Bachelor's Degree\",\"studentName\":\"Cecil\",\"studentId\":\"S-1\","
                + "\"program\":\"B.Sc. IT\",\"major\":\"Security\",\"grade\":\"9.0 CGPA\",\"issueDate\":\"2026-06-30\"}";
        mvc.perform(post("/api/credentials").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        // 2b. Future issue dates are rejected
        mvc.perform(post("/api/credentials").header("X-API-Key", apiKey).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("2026-06-30", java.time.LocalDate.now().plusDays(5).toString())))
                .andExpect(status().isBadRequest());

        // 3. Issue
        String issued = mvc.perform(post("/api/credentials").header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(issued).at("/credential/credentialId").asText();

        // 4. Mine and verify
        mvc.perform(post("/api/chain/mine")).andExpect(status().isOk());
        mvc.perform(get("/api/verify/" + id))
                .andExpect(jsonPath("$.status").value("VALID"));

        // 5. Verify the downloaded document
        String doc = mvc.perform(get("/api/credentials/" + id + "/document"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/verify/document").contentType(MediaType.APPLICATION_JSON).content(doc))
                .andExpect(jsonPath("$.status").value("VALID"));

        // 6. A forged document (grade changed) fails
        JsonNode forged = mapper.readTree(doc);
        ((com.fasterxml.jackson.databind.node.ObjectNode) forged.get("credential")).put("grade", "10.0 CGPA");
        mvc.perform(post("/api/verify/document").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(forged)))
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.checks[?(@.name == 'Issuer signature (ECDSA)')].passed").value(false));

        // 7. Tamper with DB record -> INVALID, restore -> VALID
        mvc.perform(post("/api/demo/tamper-credential/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/verify/" + id)).andExpect(jsonPath("$.status").value("INVALID"));
        mvc.perform(post("/api/demo/restore")).andExpect(status().isOk());
        mvc.perform(get("/api/verify/" + id)).andExpect(jsonPath("$.status").value("VALID"));

        // 8. Tamper with a block -> chain invalid, restore -> valid
        mvc.perform(post("/api/demo/tamper-block/1")).andExpect(status().isOk());
        mvc.perform(get("/api/chain/validate")).andExpect(jsonPath("$.valid").value(false));
        mvc.perform(post("/api/demo/restore")).andExpect(status().isOk());
        mvc.perform(get("/api/chain/validate")).andExpect(jsonPath("$.valid").value(true));

        // 9. Revoke
        mvc.perform(post("/api/credentials/" + id + "/revoke").header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Issued in error\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/chain/mine")).andExpect(status().isOk());
        mvc.perform(get("/api/verify/" + id))
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revocationReason").value("Issued in error"));
    }
}
