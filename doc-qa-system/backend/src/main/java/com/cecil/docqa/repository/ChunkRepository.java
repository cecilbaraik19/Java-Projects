package com.cecil.docqa.repository;

import com.cecil.docqa.model.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChunkRepository extends JpaRepository<DocumentChunk, Long> {
    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(Long documentId);

    @Transactional
    void deleteByDocumentId(Long documentId);
}
