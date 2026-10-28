package com.cecil.edge.model;

import com.cecil.edge.model.Enums.Condition;
import com.cecil.edge.model.Enums.SensorType;
import com.cecil.edge.model.Enums.Severity;

/**
 * A rule that the edge node checks on every reading, without asking the cloud.
 * It can target one sensor, or every sensor of a type (optionally only on one node).
 */
public class Rule {
    public String id;
    public String name;
    public SensorType sensorType;
    public String sensorId;
    public String nodeId;
    public Condition condition;
    public double threshold;
    /** The condition must stay true this many seconds before the rule fires (0 = at once). */
    public int durationSec;
    public Severity severity = Severity.WARNING;
    /** What the edge does on its own when the rule fires, e.g. "Stop pump". */
    public String action;
    public boolean enabled = true;
    public String createdBy;
    public long createdAt;
    public long hits;
    public long lastHit;
}
