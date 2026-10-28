package com.cecil.edge.model;

import com.cecil.edge.model.Enums.NodeMode;

/**
 * An edge node (gateway) such as a Raspberry Pi or Jetson that sits next to the sensors,
 * processes their data locally and sends only what matters to the cloud.
 */
public class EdgeNode {
    public String id;
    public String name;
    public String siteId;
    public String hardware;
    public String ip;
    public String firmware = "1.4.2";
    public NodeMode mode = NodeMode.SMART;
    /** AGGREGATE / SMART: one summary per window of this many seconds. */
    public int windowSec = 60;
    /** FILTER / SMART: send a reading only if it moved more than this % of the sensor's normal range. */
    public double deadbandPct = 2.0;
    /** Store-and-forward: how many messages the node can keep while the cloud link is down. */
    public int bufferCapacity = 5000;
    public boolean enabled = true;
    /** true = data comes from the built-in simulator; false = a real device posts to /api/ingest. */
    public boolean simulated = true;
    /** SHA-256 hash of the node's API key (the key itself is shown only once). */
    public String keyHash;
    public String keyHint;
    public long createdAt;
    public String notes;

    // Live state (sent with each heartbeat)
    public long lastSeen;
    public long bootAt;
    public double cpu;
    public double mem;
    public double disk;
    public double temp;
    public boolean uplinkUp = true;
    /** Messages waiting in the store-and-forward buffer. */
    public int buffered;
    public long bufferedBytes;
    /** Messages lost because the buffer was full. */
    public long lost;
}
