package com.cecil.cloudmonitor.dto;

import java.util.Map;

public record OverviewDto(
        int totalResources,
        int running,
        int warning,
        int critical,
        int stopped,
        double avgCpu,
        double avgMemory,
        double avgDisk,
        double totalNetworkIn,
        double totalNetworkOut,
        long activeAlerts,
        double hourlyCost,
        double monthlyCostEstimate,
        Map<String, Long> byProvider,
        Map<String, Long> byType,
        long timestamp
) {
}
