package com.cecil.attendance.repository;

import com.cecil.attendance.model.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    List<Employee> findByActiveTrueOrderByFullNameAsc();

    List<Employee> findAllByOrderByFullNameAsc();

    long countByActiveTrue();

    boolean existsByEmployeeCodeIgnoreCase(String code);

    boolean existsByEmailIgnoreCase(String email);

    Optional<Employee> findByEmployeeCodeIgnoreCase(String code);

    @Query("select distinct e.department from Employee e order by e.department")
    List<String> findDistinctDepartments();
}
