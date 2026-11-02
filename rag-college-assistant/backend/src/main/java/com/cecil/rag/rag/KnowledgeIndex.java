package com.cecil.rag.rag;

import com.cecil.rag.model.Chunk;
import com.cecil.rag.model.Doc;
import com.cecil.rag.model.Settings;
import com.cecil.rag.util.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Everything the search needs, built from the published documents in one go:
 * chunks -> BM25 keyword index -> LSA vectors.
 * A new index is built in the background and then swapped in, so searches never see half an index.
 */
public final class KnowledgeIndex {
    public final List<Chunk> chunks;          // searchable chunks
    public final List<Chunk> flagged;         // chunks kept out because they look like prompt injection
    public final Bm25Index bm25;
    public final LsaIndex lsa;
    public final long builtAt;
    public final long buildMs;
    public final int chunkWords, overlapWords, docs;

    private KnowledgeIndex(List<Chunk> chunks, List<Chunk> flagged, Bm25Index bm25, LsaIndex lsa,
                           long buildMs, Settings s, int docs) {
        this.chunks = chunks;
        this.flagged = flagged;
        this.bm25 = bm25;
        this.lsa = lsa;
        this.builtAt = Clock.now();
        this.buildMs = buildMs;
        this.chunkWords = s.chunkWords;
        this.overlapWords = s.overlapWords;
        this.docs = docs;
    }

    public static KnowledgeIndex build(Collection<Doc> allDocs, Settings s) {
        long t0 = System.nanoTime();
        Chunker chunker = new Chunker(s.chunkWords, s.overlapWords);
        List<Chunk> good = new ArrayList<>();
        List<Chunk> bad = new ArrayList<>();
        int docs = 0;
        for (Doc d : allDocs) {
            List<Chunk> parts = chunker.chunk(d);
            d.chunkCount = parts.size();
            d.flaggedChunks = (int) parts.stream().filter(c -> c.flagged).count();
            if (!d.searchable()) continue;
            docs++;
            for (Chunk c : parts) (c.flagged ? bad : good).add(c);
        }
        Bm25Index bm25 = new Bm25Index(good);
        LsaIndex lsa = new LsaIndex(good, s.vectorDims, 20261102L);
        return new KnowledgeIndex(good, bad, bm25, lsa, (System.nanoTime() - t0) / 1_000_000, s, docs);
    }

    public Chunk find(String chunkId) {
        for (Chunk c : chunks) if (c.id.equals(chunkId)) return c;
        for (Chunk c : flagged) if (c.id.equals(chunkId)) return c;
        return null;
    }

    public List<Chunk> chunksOf(String docId) {
        List<Chunk> out = new ArrayList<>();
        for (Chunk c : chunks) if (c.doc.id.equals(docId)) out.add(c);
        for (Chunk c : flagged) if (c.doc.id.equals(docId)) out.add(c);
        out.sort((a, b) -> Integer.compare(a.ordinal, b.ordinal));
        return out;
    }
}
