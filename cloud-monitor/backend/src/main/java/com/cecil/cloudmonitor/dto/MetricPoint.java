package com.cecil.cloudmonitor.dto;

/** One averaged point on a chart. time = epoch millis. */
public record MetricPoint(long time, double cpu, double memory, double disk, double networkIn, double networkOut) {
}
