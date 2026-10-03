package com.cecil.credchain.blockchain;

import jakarta.persistence.*;

/** Persists each block as JSON so the chain survives restarts. */
@Entity
@Table(name = "blocks")
public class BlockEntity {

    /** Large enough that MySQL uses LONGTEXT (up to 4 GB) instead of TINYTEXT (255 chars). */
    private static final int LONG_TEXT = 16_777_216;

    @Id
    @Column(name = "block_index")
    private Integer index;

    @Lob
    @Column(nullable = false, length = LONG_TEXT)
    private String json;

    protected BlockEntity() {}

    public BlockEntity(Integer index, String json) {
        this.index = index;
        this.json = json;
    }

    public Integer getIndex() { return index; }
    public String getJson() { return json; }
}
