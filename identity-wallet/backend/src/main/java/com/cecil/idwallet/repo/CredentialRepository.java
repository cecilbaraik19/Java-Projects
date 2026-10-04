package com.cecil.idwallet.repo;

import com.cecil.idwallet.domain.Credential;
import com.cecil.idwallet.domain.CredentialStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CredentialRepository extends JpaRepository<Credential, String> {
    List<Credential> findByHolderIdOrderByIssuedAtDesc(Long holderId);
    List<Credential> findByIssuerIdOrderByIssuedAtDesc(Long issuerId);
    long countByStatus(CredentialStatus status);
    long countByIssuerId(Long issuerId);
}
