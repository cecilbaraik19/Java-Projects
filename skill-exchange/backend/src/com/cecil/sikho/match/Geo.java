package com.cecil.sikho.match;

/** Distances on the Earth's surface. */
public final class Geo {
    public static final double EARTH_KM = 6371.0;

    private Geo() { }

    /**
     * Haversine formula: the shortest distance over the Earth's surface between two points.
     *   a = sin²(Δφ/2) + cos φ1 · cos φ2 · sin²(Δλ/2)
     *   d = 2R · atan2(√a, √(1−a))
     */
    public static double km(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_KM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** What other people are shown: rounded up to the next half kilometre, never less than 0.5 km (privacy). */
    public static double shown(double km) {
        return Math.max(0.5, Math.ceil(km * 2) / 2.0);
    }
}
