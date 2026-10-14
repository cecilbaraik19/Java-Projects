package com.cecil.docqa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs the whole app (with an in-memory database) and calls the REST API. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ApiIntegrationTest {

    private static final String HANDBOOK = """
            Student Handbook 2026

            Attendance: Students must attend at least 75 percent of classes in every course.
            Students below 75 percent attendance cannot sit the end-semester exam.

            Library: The central library opens at 9 am and closes at 8 pm on weekdays.
            Students may borrow up to five books for fourteen days.

            Fees: The semester fee must be paid before the 10th of the first month.
            A late fee of 500 rupees is charged after that date.
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void uploadAskHistoryDelete() throws Exception {
        long id = upload("handbook.txt", HANDBOOK);

        mvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("handbook.txt"))
                .andExpect(jsonPath("$[0].fileType").value("txt"));

        JsonNode answer = ask("What attendance is required to sit the exam?");
        assertThat(answer.get("answer").asText()).contains("75 percent");
        assertThat(answer.get("mode").asText()).isEqualTo("offline");
        assertThat(answer.get("sources").get(0).get("fileName").asText()).isEqualTo("handbook.txt");
        assertThat(answer.get("confidence").asText()).isIn("high", "medium");

        JsonNode fee = ask("How much is the late fee?");
        assertThat(fee.get("answer").asText()).contains("500 rupees");

        mvc.perform(get("/api/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].question").value("How much is the late fee?"));

        mvc.perform(get("/api/documents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("Student Handbook")));

        mvc.perform(delete("/api/documents/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/documents/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/status"))
                .andExpect(jsonPath("$.documentCount").value(0))
                .andExpect(jsonPath("$.chunkCount").value(0))
                .andExpect(jsonPath("$.aiMode").value("offline"));
    }

    @Test
    void canAskOnlySelectedDocuments() throws Exception {
        long handbook = upload("handbook.txt", HANDBOOK);
        upload("menu.md", "# Canteen menu\nThe canteen serves masala dosa for 40 rupees and tea for 10 rupees.");

        String body = "{\"question\":\"How many rupees does tea cost?\",\"documentIds\":[" + handbook + "]}";
        JsonNode answer = mapper.readTree(mvc.perform(post("/api/ask")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode s : answer.get("sources")) {
            assertThat(s.get("documentId").asLong()).isEqualTo(handbook);
        }
    }

    @Test
    void tellMeEverythingGivesASummary() throws Exception {
        upload("handbook.txt", HANDBOOK);
        JsonNode answer = ask("Tell me everything");
        assertThat(answer.get("confidence").asText()).isEqualTo("summary");
        assertThat(answer.get("answer").asText()).startsWith("Here are the main points:").contains("75 percent");
        assertThat(answer.get("sources").size()).isGreaterThan(0);
    }

    @Test
    void unknownTopicSaysNotFound() throws Exception {
        upload("handbook.txt", HANDBOOK);
        JsonNode answer = ask("Who is the cricket captain?");
        assertThat(answer.get("answer").asText()).startsWith("I couldn't find");
        assertThat(answer.get("confidence").asText()).isEqualTo("none");
    }

    @Test
    void badRequestsGiveClearErrors() throws Exception {
        mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "virus.exe", "application/octet-stream", new byte[]{1, 2})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Unsupported file type")));

        mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0])))
                .andExpect(status().isBadRequest());

        // asking before any upload
        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi there\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Upload a document")));

        mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Please type a question"));
    }

    private long upload(String name, String text) throws Exception {
        String json = mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", name, "text/plain", text.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("id").asLong();
    }

    private JsonNode ask(String question) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of("question", question));
        String json = mvc.perform(post("/api/ask").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }
}
