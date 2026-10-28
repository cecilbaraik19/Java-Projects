package com.cecil.edge.model;

/** Counters for one node on one day: what came in at the edge and what was sent on to the cloud. */
public class DayStat {
    public String date;
    public String nodeId;
    public long raw;
    public long invalid;
    public long forwarded;
    public long rawBytes;
    public long fwdBytes;
    public long buffered;
    public long alerts;
    public double edgeMsSum;
    public long edgeMsN;
    /** Seconds the node was seen online vs. seconds watched (for uptime %). */
    public long onlineSec;
    public long watchedSec;

    public String key() { return date + "|" + nodeId; }
}
