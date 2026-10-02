package com.cecil.cloudmonitor.repository;

import com.cecil.cloudmonitor.model.Alert;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {
    List<Alert> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Alert> findByAcknowledgedFalseOrderByCreatedAtDesc();

    List<Alert> findByResourceIdOrderByCreatedAtDesc(Long resourceId, Pageable pageable);

    long countByAcknowledgedFalse();
}
