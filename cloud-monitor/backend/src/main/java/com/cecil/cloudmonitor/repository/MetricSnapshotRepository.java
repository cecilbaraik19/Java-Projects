package com.cecil.cloudmonitor.repository;

import com.cecil.cloudmonitor.model.MetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, Long> {

    List<MetricSnapshot> findByResourceIdAndTimestampAfterOrderByTimestampAsc(Long resourceId, Instant after);

    List<MetricSnapshot> findByTimestampAfterOrderByTimestampAsc(Instant after);

    @Modifying
    @Transactional
    @Query("delete from MetricSnapshot s where s.timestamp < :before")
    int deleteOlderThan(Instant before);

    @Modifying
    @Transactional
    @Query("delete from MetricSnapshot s where s.resourceId = :resourceId")
    void deleteByResourceId(Long resourceId);
}
