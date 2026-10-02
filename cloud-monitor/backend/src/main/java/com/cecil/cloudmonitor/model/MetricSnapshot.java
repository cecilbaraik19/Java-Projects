package com.cecil.cloudmonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One history point for a resource, used for the time-range charts. */
@Entity
@Table(name = "metric_snapshots", indexes = {
        @Index(name = "idx_snapshot_resource_time", columnList = "resourceId,timestamp"),
        @Index(name = "idx_snapshot_time", columnList = "timestamp")
})
@Getter @Setter @NoArgsConstructor
public class MetricSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long resourceId;
    private Instant timestamp;
    private double cpu;
    private double memory;
    private double disk;
    private double networkIn;
    private double networkOut;

    public static MetricSnapshot of(CloudResource r, Instant at) {
        MetricSnapshot s = new MetricSnapshot();
        s.resourceId = r.getId();
        s.timestamp = at;
        s.cpu = r.getCpu();
        s.memory = r.getMemory();
        s.disk = r.getDisk();
        s.networkIn = r.getNetworkIn();
        s.networkOut = r.getNetworkOut();
        return s;
    }
}
