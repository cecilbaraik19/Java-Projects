package com.cecil.credchain.credential;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CredentialRepository extends JpaRepository<CredentialEntity, String> {
    List<CredentialEntity> findByIssuerIdOrderByIssuedAtDesc(String issuerId);
    List<CredentialEntity> findByStudentIdIgnoreCaseOrderByIssuedAtDesc(String studentId);
    List<CredentialEntity> findTop20ByOrderByIssuedAtDesc();
}
