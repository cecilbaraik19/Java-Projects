package com.cecil.attendance.repository;

import com.cecil.attendance.model.Role;
import com.cecil.attendance.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    Optional<UserAccount> findByEmployeeId(Long employeeId);

    List<UserAccount> findAllByOrderByUsernameAsc();

    long countByRoleAndEnabledTrue(Role role);
}
