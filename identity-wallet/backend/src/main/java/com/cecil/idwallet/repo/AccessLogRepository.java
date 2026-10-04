package com.cecil.idwallet.repo;

import com.cecil.idwallet.domain.AccessLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
    List<AccessLog> findByShareIdOrderByAtDesc(Long shareId);
    List<AccessLog> findTop50ByShareIdInOrderByAtDesc(Collection<Long> shareIds);
}
