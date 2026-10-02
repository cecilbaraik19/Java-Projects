package com.cecil.cloudmonitor.dto;

import java.util.List;

/** Everything the Costs page needs, built by CostService. All money values are USD. */
public record CostReport(
        double hourlyBurn,          // what running resources cost per hour right now
        double projectedMonthly,    // hourlyBurn x 730 hours
        double last24h,             // spend over the last 24 hours, based on when resources were running
        double monthlyBudget,
        double budgetUsedPct,       // projectedMonthly as % of budget
        double potentialSavings,    // sum of all recommendation savings, per month
        double stoppedSavings,      // what stopped resources would cost per month if running
        List<Slice> byProvider,
        List<Slice> byType,
        List<HourCost> hourly,
        List<ResourceCost> resources,
        List<Recommendation> recommendations,
        long generatedAt
) {
    /** One slice of a breakdown (by provider or by type). */
    public record Slice(String key, double monthly, int count) {}

    /** Spend in one clock hour. cost is null when the app was not running in that hour (no data). */
    public record HourCost(long time, Double cost, int runningResources) {}

    public record ResourceCost(
            Long id, String name, String type, String provider, String instanceType, String region,
            String status, double hourly, double monthly, double sharePct,
            Double avgCpu24h, Double peakCpu24h) {}

    /** A cost-saving suggestion. kind = DOWNSIZE or SCHEDULE. */
    public record Recommendation(
            Long resourceId, String resourceName, String kind, String title, String detail, double monthlySaving) {}
}
