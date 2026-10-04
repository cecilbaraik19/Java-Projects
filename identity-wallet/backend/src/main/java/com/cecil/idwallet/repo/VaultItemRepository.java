package com.cecil.idwallet.repo;

import com.cecil.idwallet.domain.VaultItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VaultItemRepository extends JpaRepository<VaultItem, Long> {
    List<VaultItem> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId);
}
