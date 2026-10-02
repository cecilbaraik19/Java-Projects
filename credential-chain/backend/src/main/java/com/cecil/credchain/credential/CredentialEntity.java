package com.cecil.credchain.credential;

import jakarta.persistence.*;

import java.time.Instant;

/** Off-chain storage of the full credential (personal data never goes on the chain). */
@Entity
@Table(name = "credentials", indexes = {
        @Index(name = "idx_cred_issuer", columnList = "issuerId"),
        @Index(name = "idx_cred_student", columnList = "studentId")})
public class CredentialEntity {

    @Id
    private String credentialId;
    private String credentialType;
    private String studentName;
    private String studentId;
    private String program;
    private String major;
    private String grade;
    private String issueDate;
    private String issuerId;
    private String issuerName;

    @Column(length = 64)
    private String credentialHash;

    @Column(length = 512)
    private String signature;

    private String transactionId;
    private Instant issuedAt;

    protected CredentialEntity() {}

    public CredentialEntity(CredentialData d, String credentialHash, String signature, String transactionId) {
        this.credentialId = d.credentialId();
        this.credentialType = d.credentialType();
        this.studentName = d.studentName();
        this.studentId = d.studentId();
        this.program = d.program();
        this.major = d.major();
        this.grade = d.grade();
        this.issueDate = d.issueDate();
        this.issuerId = d.issuerId();
        this.issuerName = d.issuerName();
        this.credentialHash = credentialHash;
        this.signature = signature;
        this.transactionId = transactionId;
        this.issuedAt = Instant.now();
    }

    public CredentialData toData() {
        return new CredentialData(credentialId, credentialType, studentName, studentId, program,
                major, grade, issueDate, issuerId, issuerName);
    }

    public String getCredentialId() { return credentialId; }
    public String getCredentialType() { return credentialType; }
    public String getStudentName() { return studentName; }
    public String getStudentId() { return studentId; }
    public String getProgram() { return program; }
    public String getMajor() { return major; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
    public String getIssueDate() { return issueDate; }
    public String getIssuerId() { return issuerId; }
    public String getIssuerName() { return issuerName; }
    public String getCredentialHash() { return credentialHash; }
    public String getSignature() { return signature; }
    public String getTransactionId() { return transactionId; }
    public Instant getIssuedAt() { return issuedAt; }
}
