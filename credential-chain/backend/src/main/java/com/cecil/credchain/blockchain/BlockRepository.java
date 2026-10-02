package com.cecil.credchain.blockchain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BlockRepository extends JpaRepository<BlockEntity, Integer> {
    List<BlockEntity> findAllByOrderByIndexAsc();
}
