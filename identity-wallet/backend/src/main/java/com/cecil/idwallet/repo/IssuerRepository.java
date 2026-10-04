package com.cecil.idwallet.repo;

import com.cecil.idwallet.domain.Issuer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IssuerRepository extends JpaRepository<Issuer, Long> {
    Optional<Issuer> findByDid(String did);
    Optional<Issuer> findByNameIgnoreCase(String name);
}
