package com.cecil.credchain.blockchain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingTransactionRepository extends JpaRepository<PendingTransactionEntity, String> {
    List<PendingTransactionEntity> findAllByOrderByCreatedAtAsc();
}
