package com.cecil.docqa.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QaServiceTest {

    @Test
    void overviewQuestionsAreDetected() {
        assertThat(QaService.isOverviewQuestion("tell me everything")).isTrue();
        assertThat(QaService.isOverviewQuestion("Summarize this document")).isTrue();
        assertThat(QaService.isOverviewQuestion("What is this document about?")).isTrue();
        assertThat(QaService.isOverviewQuestion("give me the key points")).isTrue();
    }

    @Test
    void normalQuestionsAreNotOverview() {
        assertThat(QaService.isOverviewQuestion("what is paper leak")).isFalse();
        assertThat(QaService.isOverviewQuestion("what are the main rules")).isFalse();
        assertThat(QaService.isOverviewQuestion("late fee")).isFalse();
    }

    @Test
    void spreadPicksStartMiddleAndEnd() {
        assertThat(QaService.spread(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), 3)).containsExactly(1, 5, 9);
        assertThat(QaService.spread(List.of(1, 2), 6)).containsExactly(1, 2);
    }
}
