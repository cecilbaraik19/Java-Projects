package com.cecil.edge.model;

import com.cecil.edge.model.Enums.AlertStatus;
import com.cecil.edge.model.Enums.Severity;

/** Raised when a rule fires, or by the built-in health checks (node offline, CPU, heat, buffer). */
public class Alert {
    public String id;
    public String ruleId;
    public String ruleName;
    public String nodeId;
    public String sensorId;
    public Severity severity;
    public String message;
    public double value;
    public double threshold;
    public AlertStatus status = AlertStatus.OPEN;
    public long openedAt;
    public long lastSeenAt;
    /** How many times it fired again while still open. */
    public int repeats;
    /** When the condition stopped being true (0 = still true). */
    public long clearedAt;
    public String ackBy;
    public long ackAt;
    public String resolvedBy;
    public long resolvedAt;
    public String note;
    /** Time to decide at the edge vs. the time a cloud round trip would have taken (ms). */
    public double edgeMs;
    public double cloudMs;
    public String action;
}
