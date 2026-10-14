package com.cecil.docqa.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SearchIndexTest {

    private SearchIndex index;

    @BeforeEach
    void setUp() {
        index = new SearchIndex();
        index.add(new SearchIndex.Entry(1, 10, 1, "Refunds are given within 30 days of purchase with a receipt."));
        index.add(new SearchIndex.Entry(2, 10, 2, "Shipping takes 5 to 7 business days inside India."));
        index.add(new SearchIndex.Entry(3, 20, null, "The library opens at 9 am and closes at 8 pm on weekdays."));
        index.add(new SearchIndex.Entry(4, 20, null, "Students may borrow up to five books for two weeks."));
    }

    @Test
    void bestChunkComesFirst() {
        List<SearchIndex.Hit> hits = index.search("How many days do I have to get a refund?", 3, null);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).entry().chunkId()).isEqualTo(1);
    }

    @Test
    void wordFormsStillMatch() {
        List<SearchIndex.Hit> hits = index.search("borrowing books", 3, null);
        assertThat(hits.get(0).entry().chunkId()).isEqualTo(4);
        assertThat(hits.get(0).coverage()).isEqualTo(1.0);
    }

    @Test
    void canLimitToSomeDocuments() {
        List<SearchIndex.Hit> hits = index.search("days", 5, Set.of(20L));
        assertThat(hits).allMatch(h -> h.entry().documentId() == 20);
    }

    @Test
    void noMatchGivesEmptyList() {
        assertThat(index.search("quantum entanglement", 5, null)).isEmpty();
        assertThat(index.search("the and of", 5, null)).isEmpty();
    }

    @Test
    void removingADocumentRemovesItsChunks() {
        index.removeDocument(10);
        assertThat(index.size()).isEqualTo(2);
        assertThat(index.search("refund", 5, null)).isEmpty();
    }

    @Test
    void topKLimitsResults() {
        assertThat(index.search("days books library refund shipping", 2, null)).hasSize(2);
    }
}
