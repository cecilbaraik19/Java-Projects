package com.cecil.credchain.blockchain;

import jakarta.persistence.*;

/** Mempool persisted to DB, so un-mined transactions are not lost on restart. */
@Entity
@Table(name = "pending_transactions")
public class PendingTransactionEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private long createdAt;

    @Lob
    @Column(nullable = false)
    private String json;

    protected PendingTransactionEntity() {}

    public PendingTransactionEntity(String id, long createdAt, String json) {
        this.id = id;
        this.createdAt = createdAt;
        this.json = json;
    }

    public String getId() { return id; }
    public long getCreatedAt() { return createdAt; }
    public String getJson() { return json; }
}
