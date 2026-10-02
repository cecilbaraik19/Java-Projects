package com.cecil.cloudmonitor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** An alert that fired because a rule was breached. */
@Entity
@Table(name = "alerts")
@Getter @Setter @NoArgsConstructor
public class Alert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ruleId;
    private String ruleName;
    private Long resourceId;
    private String resourceName;

    @Enumerated(EnumType.STRING)
    private MetricType metric;

    /** "value" is a reserved word in H2/MySQL, so store it under a different column name. */
    @Column(name = "metric_value")
    private double value;
    private double threshold;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    private String message;
    private Instant createdAt;
    private boolean acknowledged;
    private String acknowledgedBy;
}
