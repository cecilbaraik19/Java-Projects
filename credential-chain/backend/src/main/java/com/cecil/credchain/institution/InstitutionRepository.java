package com.cecil.credchain.institution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InstitutionRepository extends JpaRepository<Institution, String> {
    Optional<Institution> findByApiKey(String apiKey);
    boolean existsByNameIgnoreCase(String name);
}
