package com.cecil.cloudmonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A server, database, container etc. that the dashboard monitors. Holds its latest metrics. */
@Entity
@Table(name = "cloud_resources")
@Getter @Setter @NoArgsConstructor
public class CloudResource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private ResourceType type;

    @Enumerated(EnumType.STRING)
    private Provider provider;

    private String region;
    private String instanceType;
    private String ipAddress;
    private String os;

    @Enumerated(EnumType.STRING)
    private ResourceStatus status;

    /** Latest metric values: percentages for cpu/memory/disk, KB/s for network. */
    private double cpu;
    private double memory;
    private double disk;
    private double networkIn;
    private double networkOut;

    /** USD per hour while running. */
    private double hourlyCost;

    private Instant startedAt;
    private Instant lastUpdated;

    public CloudResource(String name, ResourceType type, Provider provider, String region,
                         String instanceType, String ipAddress, String os, double hourlyCost) {
        this.name = name;
        this.type = type;
        this.provider = provider;
        this.region = region;
        this.instanceType = instanceType;
        this.ipAddress = ipAddress;
        this.os = os;
        this.hourlyCost = hourlyCost;
        this.status = ResourceStatus.RUNNING;
        this.startedAt = Instant.now();
        this.lastUpdated = Instant.now();
    }

    public boolean isStopped() {
        return status == ResourceStatus.STOPPED;
    }
}
