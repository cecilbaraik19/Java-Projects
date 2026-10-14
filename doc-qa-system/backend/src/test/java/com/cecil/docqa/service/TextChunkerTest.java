package com.cecil.docqa.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkerTest {

    private final TextChunker chunker = new TextChunker(200, 40);

    @Test
    void shortTextIsOneChunk() {
        List<TextChunker.Chunk> out = chunker.chunk(List.of(new TextExtractor.Page(null, "Hello world. Short text.")));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).text()).isEqualTo("Hello world. Short text.");
    }

    @Test
    void longTextIsSplitWithinSizeAtSentenceEnds() {
        String sentence = "This is sentence number %d about chunking documents.";
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) sb.append(String.format(sentence, i)).append(' ');

        List<TextChunker.Chunk> out = chunker.chunk(List.of(new TextExtractor.Page(3, sb.toString())));

        assertThat(out.size()).isGreaterThan(5);
        for (TextChunker.Chunk c : out) {
            assertThat(c.text().length()).isLessThanOrEqualTo(200);
            assertThat(c.pageNumber()).isEqualTo(3);
        }
        // every chunk except possibly the last ends at a sentence end
        for (int i = 0; i < out.size() - 1; i++) assertThat(out.get(i).text()).endsWith(".");
    }

    @Test
    void neighbouringChunksOverlap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("word").append(i).append(' ');
        List<TextChunker.Chunk> out = chunker.chunk(List.of(new TextExtractor.Page(null, sb.toString())));
        String endOfFirst = out.get(0).text().substring(out.get(0).text().lastIndexOf(' ') + 1);
        assertThat(out.get(1).text()).contains(endOfFirst);
    }

    @Test
    void blankPagesAreSkipped() {
        assertThat(chunker.chunk(List.of(new TextExtractor.Page(1, "  \n\n "), new TextExtractor.Page(2, "")))).isEmpty();
    }

    @Test
    void cleanJoinsHyphenatedWords() {
        assertThat(TextChunker.clean("infor-\nmation   is   here")).isEqualTo("information is here");
    }

    @Test
    void rejectsBadSettings() {
        assertThatThrownBy(() -> new TextChunker(50, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunker(400, 300)).isInstanceOf(IllegalArgumentException.class);
    }
}
