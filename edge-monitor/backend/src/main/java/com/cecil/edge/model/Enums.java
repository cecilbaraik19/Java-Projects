package com.cecil.edge.model;

/** All the fixed lists used by the app, in one place. */
public final class Enums {
    private Enums() { }

    /** Who can do what. */
    public enum Role {
        ADMIN("Admin"), ENGINEER("Edge engineer"), VIEWER("Viewer");
        public final String label;
        Role(String label) { this.label = label; }
    }

    /**
     * How an edge node handles sensor data before sending it to the cloud.
     * This is the main idea of edge computing: do the work near the sensors and send less.
     */
    public enum NodeMode {
        RAW("Raw (no edge work)", "Every reading is sent to the cloud as it is. Used as the comparison point."),
        FILTER("Filter (dead-band)", "A reading is sent only when it changed by more than the dead-band from the last value sent."),
        AGGREGATE("Aggregate (window)", "Readings are summed up at the edge; only one summary (min, max, average, count) is sent per time window."),
        SMART("Smart (aggregate + events)", "Window summaries, plus any big change and any alert are sent at once. Best balance.");
        public final String label;
        public final String help;
        NodeMode(String label, String help) { this.label = label; this.help = help; }
    }

    /**
     * Kinds of IoT sensors. lo / hi are the physical limits of the sensor:
     * a reading outside them is treated as a faulty reading and dropped at the edge.
     */
    public enum SensorType {
        TEMPERATURE("Temperature", "°C", -40, 125),
        HUMIDITY("Humidity", "%", 0, 100),
        VIBRATION("Vibration", "mm/s", 0, 50),
        PRESSURE("Pressure", "bar", 0, 16),
        POWER("Power", "kW", 0, 500),
        CO2("Air quality (CO₂)", "ppm", 300, 5000),
        FLOW("Water flow", "L/min", 0, 1000),
        LEVEL("Tank level", "%", 0, 100),
        SOIL("Soil moisture", "%", 0, 100);
        public final String label;
        public final String unit;
        public final double lo;
        public final double hi;
        SensorType(String label, String unit, double lo, double hi) {
            this.label = label; this.unit = unit; this.lo = lo; this.hi = hi;
        }
    }

    /** When a rule fires. */
    public enum Condition {
        ABOVE("Goes above"), BELOW("Goes below"),
        RATE_OF_CHANGE("Changes faster than (per minute)"),
        ANOMALY("Unusual value (z-score at least)"),
        NO_DATA("Sends no data for (seconds)");
        public final String label;
        Condition(String label) { this.label = label; }
    }

    public enum Severity { INFO, WARNING, CRITICAL }

    public enum AlertStatus { OPEN, ACKNOWLEDGED, RESOLVED }

    /** Commands an engineer can send to an edge node. The node picks them up on its next heartbeat. */
    public enum CommandType {
        PING("Ping"), REBOOT("Reboot"), SET_MODE("Change processing mode"), FLUSH_BUFFER("Send buffered data now");
        public final String label;
        CommandType(String label) { this.label = label; }
    }

    public enum CommandStatus { PENDING, DONE, FAILED }

    /** Worked out from the last heartbeat and health numbers; never stored. */
    public enum NodeStatus { ONLINE, DEGRADED, OFFLINE, DISABLED }

    /** Problems the simulator can inject, to show how the edge reacts. */
    public enum FaultType {
        SPIKE("Value spike", true, "Adds a sudden jump to a sensor's value."),
        DRIFT("Slow drift", true, "The value creeps up a little every minute, like a failing part."),
        STUCK("Stuck sensor", true, "The sensor keeps sending the same value."),
        NOISE("Noisy signal", true, "Adds heavy random noise (loose wire)."),
        BAD_DATA("Garbage readings", true, "Sends impossible values; the edge must drop them."),
        DROPOUT("Sensor silent", true, "The sensor stops sending readings."),
        UPLINK_DOWN("Internet link down", false, "The node cannot reach the cloud, so it buffers data (store and forward)."),
        NODE_DOWN("Node power loss", false, "The whole node stops; no heartbeats arrive."),
        CPU_HOG("CPU overload", false, "A runaway process uses most of the CPU.");
        public final String label;
        public final boolean forSensor;
        public final String help;
        FaultType(String label, boolean forSensor, String help) {
            this.label = label; this.forSensor = forSensor; this.help = help;
        }
    }
}
