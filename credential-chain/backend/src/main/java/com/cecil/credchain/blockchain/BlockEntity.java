package com.cecil.credchain.blockchain;

import jakarta.persistence.*;

/** Persists each block as JSON so the chain survives restarts. */
@Entity
@Table(name = "blocks")
public class BlockEntity {

    @Id
    @Column(name = "block_index")
    private Integer index;

    @Lob
    @Column(nullable = false)
    private String json;

    protected BlockEntity() {}

    public BlockEntity(Integer index, String json) {
        this.index = index;
        this.json = json;
    }

    public Integer getIndex() { return index; }
    public String getJson() { return json; }
}
