package com.cecil.cloudmonitor.repository;

import com.cecil.cloudmonitor.model.MetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** Per resource: [resourceId, average CPU, peak CPU] since the given time. */
    @Query("select s.resourceId, avg(s.cpu), max(s.cpu) from MetricSnapshot s where s.timestamp > :after group by s.resourceId")
    List<Object[]> cpuUsageSince(@Param("after") Instant after);

    /** [resourceId, timestamp] of every history point since the given time; used to see when resources were running. */
    @Query("select s.resourceId, s.timestamp from MetricSnapshot s where s.timestamp > :after")
    List<Object[]> activitySince(@Param("after") Instant after);
}
