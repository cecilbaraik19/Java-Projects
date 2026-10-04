package com.cecil.idwallet.repo;

import com.cecil.idwallet.domain.Share;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShareRepository extends JpaRepository<Share, Long> {
    Optional<Share> findByTokenHash(String tokenHash);
    List<Share> findByHolderIdOrderByCreatedAtDesc(Long holderId);
    List<Share> findByCredentialId(String credentialId);
}
