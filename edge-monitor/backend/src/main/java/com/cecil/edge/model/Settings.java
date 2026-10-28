package com.cecil.edge.model;

/** Settings an admin can change. */
public class Settings {
    /** A node with no heartbeat for this long is OFFLINE. */
    public int heartbeatTimeoutSec = 30;
    /** Simulator tick in seconds. */
    public int tickSec = 5;
    public boolean simulatorOn = true;
    /** Round trip to the cloud data centre (used to compare with deciding at the edge). */
    public double cloudRttMs = 180;
    /** Time from sensor to edge node on the local network. */
    public double localHopMs = 4;
    /**
     * Size of one message on the wire, in bytes: JSON body + MQTT/HTTP headers + TLS overhead.
     * A raw message carries one reading; a summary carries min / max / average / count.
     */
    public int bytesRaw = 512;
    public int bytesSummary = 640;
    /** Rupees per GB sent to the cloud (mobile data + cloud ingestion), to show money saved. */
    public double costPerGb = 60;
    public double cpuWarnPct = 85;
    public double tempWarnC = 75;
    public double bufferWarnPct = 80;
    public int historyDays = 14;
}
