package com.cecil.idwallet.repo;

import com.cecil.idwallet.domain.Role;
import com.cecil.idwallet.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByEmailIgnoreCase(String email);
    Optional<UserAccount> findByDid(String did);
    List<UserAccount> findByIssuerId(Long issuerId);
    long countByRole(Role role);
}
