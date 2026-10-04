package com.cecil.idwallet.repo;

import com.cecil.idwallet.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    Optional<AuditEvent> findTopByOrderByIdDesc();
    List<AuditEvent> findTop100ByUserIdOrderByIdDesc(Long userId);
    List<AuditEvent> findTop200ByOrderByIdDesc();
    List<AuditEvent> findAllByOrderByIdAsc();
}
