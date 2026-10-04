package com.cecil.attendance.repository;

import com.cecil.attendance.model.CorrectionRequest;
import com.cecil.attendance.model.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CorrectionRequestRepository extends JpaRepository<CorrectionRequest, Long> {

    List<CorrectionRequest> findByEmployeeIdOrderByCreatedAtDesc(Long employeeId);

    List<CorrectionRequest> findByStatusOrderByCreatedAtAsc(RequestStatus status);

    List<CorrectionRequest> findTop200ByOrderByCreatedAtDesc();

    boolean existsByEmployeeIdAndDateAndStatus(Long employeeId, LocalDate date, RequestStatus status);

    @Modifying
    @Query("delete from CorrectionRequest c where c.employee.id = :employeeId")
    void deleteByEmployeeId(@Param("employeeId") Long employeeId);
}
