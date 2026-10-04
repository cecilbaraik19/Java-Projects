package com.cecil.attendance.repository;

import com.cecil.attendance.model.LeaveRequest;
import com.cecil.attendance.model.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    List<LeaveRequest> findByEmployeeIdOrderByCreatedAtDesc(Long employeeId);

    List<LeaveRequest> findByEmployeeIdAndStatusIn(Long employeeId, Collection<RequestStatus> statuses);

    List<LeaveRequest> findByStatusOrderByCreatedAtAsc(RequestStatus status);

    List<LeaveRequest> findTop200ByOrderByCreatedAtDesc();

    @Modifying
    @Query("delete from LeaveRequest l where l.employee.id = :employeeId")
    void deleteByEmployeeId(@Param("employeeId") Long employeeId);
}
