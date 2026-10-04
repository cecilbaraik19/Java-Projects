package com.cecil.attendance.repository;

import com.cecil.attendance.model.Shift;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

    List<Shift> findAllByOrderByStartTimeAsc();

    boolean existsByNameIgnoreCase(String name);
}
