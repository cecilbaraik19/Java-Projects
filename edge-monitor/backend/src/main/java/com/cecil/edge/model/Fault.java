package com.cecil.edge.model;

import com.cecil.edge.model.Enums.FaultType;

/** A problem injected by the simulator ("fault lab") for a while, to test the edge. */
public class Fault {
    public String id;
    public String nodeId;
    public String sensorId;
    public FaultType type;
    public double magnitude;
    public long startedAt;
    public long endsAt;
    public String by;
    /** STUCK keeps this value. */
    public Double stuckValue;
}
