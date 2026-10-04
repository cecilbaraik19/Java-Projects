package com.cecil.attendance.repository;

import com.cecil.attendance.model.AttendanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<AttendanceRecord, Long> {

    Optional<AttendanceRecord> findByEmployeeIdAndDate(Long employeeId, LocalDate date);

    List<AttendanceRecord> findByDateOrderByCheckInAsc(LocalDate date);

    List<AttendanceRecord> findByDateBetween(LocalDate from, LocalDate to);

    List<AttendanceRecord> findByEmployeeIdAndDateBetweenOrderByDateDesc(Long employeeId, LocalDate from, LocalDate to);

    @Modifying
    @Query("delete from AttendanceRecord r where r.employee.id = :employeeId")
    void deleteByEmployeeId(@Param("employeeId") Long employeeId);
}
