package com.cecil.edge.model;

import com.cecil.edge.model.Enums.SensorType;

/** An IoT sensor wired to an edge node. */
public class Sensor {
    public String id;
    public String nodeId;
    public String name;
    public SensorType type;
    public String unit;
    /** Normal operating range. Outside it the value is shown as "out of range". */
    public double low;
    public double high;
    /** How often the sensor sends a reading, in seconds. */
    public int sampleSec = 5;
    public boolean enabled = true;

    // Simulator shape: value = base + swing * daily wave + noise
    public double base;
    public double swing;
    public double noise;

    // Latest reading
    public Double lastValue;
    public long lastAt;
}
