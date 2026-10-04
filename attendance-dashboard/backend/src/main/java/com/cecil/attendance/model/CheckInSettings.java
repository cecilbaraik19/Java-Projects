package com.cecil.attendance.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Company-wide rules for employee self check-in (a single row, id = 1).
 * Admin/manager-entered attendance is not affected - it is trusted and audited instead.
 */
@Entity
@Table(name = "checkin_settings")
public class CheckInSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    // ---- rotating QR code ----
    @Column(nullable = false)
    private boolean requireQr;

    @Column(nullable = false)
    private int qrRotationSeconds = 30;

    /** HMAC key for the QR codes. Never sent to clients. */
    @Column(nullable = false, length = 100)
    private String qrSecret;

    // ---- geofence ----
    @Column(nullable = false)
    private boolean requireLocation;

    private Double officeLatitude;
    private Double officeLongitude;

    @Column(nullable = false)
    private int radiusMeters = 200;

    // ---- network ----
    @Column(nullable = false)
    private boolean requireNetwork;

    /** Comma/line separated IPs or CIDR ranges, e.g. "192.168.1.0/24, 203.0.113.7". */
    @Column(length = 1000)
    private String allowedNetworks;

    private Instant updatedAt;

    @Column(length = 40)
    private String updatedBy;

    protected CheckInSettings() {
    }

    public CheckInSettings(String qrSecret) {
        this.qrSecret = qrSecret;
    }

    public Long getId() { return id; }
    public boolean isRequireQr() { return requireQr; }
    public void setRequireQr(boolean requireQr) { this.requireQr = requireQr; }
    public int getQrRotationSeconds() { return qrRotationSeconds; }
    public void setQrRotationSeconds(int qrRotationSeconds) { this.qrRotationSeconds = qrRotationSeconds; }
    public String getQrSecret() { return qrSecret; }
    public void setQrSecret(String qrSecret) { this.qrSecret = qrSecret; }
    public boolean isRequireLocation() { return requireLocation; }
    public void setRequireLocation(boolean requireLocation) { this.requireLocation = requireLocation; }
    public Double getOfficeLatitude() { return officeLatitude; }
    public void setOfficeLatitude(Double officeLatitude) { this.officeLatitude = officeLatitude; }
    public Double getOfficeLongitude() { return officeLongitude; }
    public void setOfficeLongitude(Double officeLongitude) { this.officeLongitude = officeLongitude; }
    public int getRadiusMeters() { return radiusMeters; }
    public void setRadiusMeters(int radiusMeters) { this.radiusMeters = radiusMeters; }
    public boolean isRequireNetwork() { return requireNetwork; }
    public void setRequireNetwork(boolean requireNetwork) { this.requireNetwork = requireNetwork; }
    public String getAllowedNetworks() { return allowedNetworks; }
    public void setAllowedNetworks(String allowedNetworks) { this.allowedNetworks = allowedNetworks; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
