package com.cecil.docqa.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenizerTest {

    @Test
    void dropsCommonWordsAndLowercases() {
        assertThat(Tokenizer.tokens("What is the Refund Policy?")).containsExactly("refund", "policy");
    }

    @Test
    void wordFormsMatchTheSameStem() {
        List<String> a = Tokenizer.tokens("storing stored stores store");
        assertThat(a).containsOnly("stor");
        assertThat(Tokenizer.tokens("policies")).containsExactly("policy");
        assertThat(Tokenizer.tokens("running runs")).containsOnly("run");
    }

    @Test
    void keepsNumbers() {
        assertThat(Tokenizer.tokens("Section 4.2 in 2026")).containsExactly("section", "4", "2", "2026");
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(Tokenizer.tokens(null)).isEmpty();
        assertThat(Tokenizer.tokens("   ")).isEmpty();
    }
}
