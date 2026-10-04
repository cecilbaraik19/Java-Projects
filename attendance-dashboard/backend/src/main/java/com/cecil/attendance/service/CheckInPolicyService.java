package com.cecil.attendance.service;

import com.cecil.attendance.dto.Dtos.CheckInPolicy;
import com.cecil.attendance.dto.Dtos.CheckInSettingsUpdate;
import com.cecil.attendance.dto.Dtos.CheckInSettingsView;
import com.cecil.attendance.dto.Dtos.KioskCode;
import com.cecil.attendance.dto.Dtos.SelfCheckRequest;
import com.cecil.attendance.exception.ApiException;
import com.cecil.attendance.model.CheckInSettings;
import com.cecil.attendance.repository.CheckInSettingsRepository;
import com.cecil.attendance.security.AccessGuard;
import com.cecil.attendance.security.ClientIpResolver;
import com.cecil.attendance.security.IpUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-buddy-punching rules for self check-in: rotating QR code (TOTP-style),
 * geofence around the office and an allow-list of office networks.
 * Not @Transactional on purpose: rejected attempts must still be written to the audit log.
 */
@Service
public class CheckInPolicyService {

    private static final int MAX_BAD_CODES = 5;
    private static final Duration BAD_CODE_WINDOW = Duration.ofMinutes(10);
    private static final double MAX_ACCURACY_METERS = 1000;

    private final CheckInSettingsRepository repo;
    private final ClientIpResolver ipResolver;
    private final AuditService audit;
    private final AccessGuard guard;
    private final Clock clock;
    /** employeeId -> times of recent wrong QR codes (in-memory rate limit). */
    private final Map<Long, Deque<Instant>> badCodes = new ConcurrentHashMap<>();

    public CheckInPolicyService(CheckInSettingsRepository repo, ClientIpResolver ipResolver, AuditService audit,
                                AccessGuard guard, Clock clock) {
        this.repo = repo;
        this.ipResolver = ipResolver;
        this.audit = audit;
        this.guard = guard;
        this.clock = clock;
    }

    // ---------- settings ----------

    public CheckInSettings settings() {
        return repo.findById(CheckInSettings.SINGLETON_ID).orElseGet(() -> {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            return repo.save(new CheckInSettings(Base64.getEncoder().encodeToString(key)));
        });
    }

    public CheckInPolicy policy() {
        CheckInSettings s = settings();
        return new CheckInPolicy(s.isRequireQr(), s.isRequireLocation(), s.isRequireNetwork());
    }

    public CheckInSettingsView update(CheckInSettingsUpdate req) {
        CheckInSettings s = settings();
        boolean hasLocation = req.officeLatitude() != null && req.officeLongitude() != null;
        if (req.requireLocation() && !hasLocation) {
            throw ApiException.badRequest("Set the office location before turning on the location check");
        }
        String networks = req.allowedNetworks() == null ? "" : req.allowedNetworks().trim();
        List<IpUtils.Cidr> parsed;
        try {
            parsed = IpUtils.parseList(networks);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (req.requireNetwork() && parsed.isEmpty()) {
            throw ApiException.badRequest("Add at least one office IP address or range before turning on the network check");
        }
        s.setRequireQr(req.requireQr());
        s.setQrRotationSeconds(req.qrRotationSeconds());
        s.setRequireLocation(req.requireLocation());
        s.setOfficeLatitude(req.officeLatitude());
        s.setOfficeLongitude(req.officeLongitude());
        s.setRadiusMeters(req.radiusMeters());
        s.setRequireNetwork(req.requireNetwork());
        s.setAllowedNetworks(String.join(", ", parsed.stream().map(IpUtils.Cidr::text).toList()));
        s.setUpdatedAt(Instant.now(clock));
        s.setUpdatedBy(guard.requireUser().username());
        CheckInSettings saved = repo.save(s);
        audit.log(AuditService.SETTINGS_UPDATED, "CheckInSettings", saved.getId(),
                "QR " + onOff(saved.isRequireQr()) + ", location " + onOff(saved.isRequireLocation())
                        + " (" + saved.getRadiusMeters() + " m), network " + onOff(saved.isRequireNetwork()));
        return CheckInSettingsView.of(saved);
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    // ---------- QR codes ----------

    public KioskCode currentCode() {
        CheckInSettings s = settings();
        long now = Instant.now(clock).getEpochSecond();
        long window = now / s.getQrRotationSeconds();
        long left = s.getQrRotationSeconds() - (now % s.getQrRotationSeconds());
        return new KioskCode(code(s.getQrSecret(), window), left, s.getQrRotationSeconds());
    }

    /** 6-digit HOTP-style code (RFC 4226 dynamic truncation, HMAC-SHA256). */
    static String code(String secret, long window) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(window).array());
            int offset = h[h.length - 1] & 0x0F;
            int bin = ((h[offset] & 0x7F) << 24) | ((h[offset + 1] & 0xFF) << 16)
                    | ((h[offset + 2] & 0xFF) << 8) | (h[offset + 3] & 0xFF);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC not available", e);
        }
    }

    /** Accepts the current and the previous code (so a code that just rolled over still works). */
    private boolean isValidCode(CheckInSettings s, String given) {
        if (given == null) return false;
        String g = given.replaceAll("\\s", "");
        if (!g.matches("\\d{6}")) return false;
        long window = Instant.now(clock).getEpochSecond() / s.getQrRotationSeconds();
        byte[] gb = g.getBytes(StandardCharsets.US_ASCII);
        boolean ok = false;
        for (long w = window - 1; w <= window; w++) {
            // constant-time comparison; evaluate both windows to avoid a timing hint
            ok |= MessageDigest.isEqual(gb, code(s.getQrSecret(), w).getBytes(StandardCharsets.US_ASCII));
        }
        return ok;
    }

    // ---------- verification ----------

    /**
     * Checks a self check-in/out against the active rules.
     * @return a short description of how it was verified (stored on the attendance record)
     * @throws ApiException 403 when a rule fails, 429 after too many wrong QR codes
     */
    public String verify(Long employeeId, SelfCheckRequest req, boolean checkOut) {
        CheckInSettings s = settings();
        String ip = ipResolver.current();
        List<String> proof = new ArrayList<>();
        proof.add("Self");

        if (s.isRequireNetwork()) {
            if (!IpUtils.matchesAny(ip, IpUtils.parseList(s.getAllowedNetworks()))) {
                reject(employeeId, checkOut, "network", "IP " + ip + " is not an office network",
                        "You must be connected to the office network to " + (checkOut ? "check out" : "check in")
                                + " (your IP: " + ip + ")");
            }
        }
        if (ip != null) proof.add("IP " + ip);

        if (s.isRequireLocation()) {
            if (req == null || req.latitude() == null || req.longitude() == null) {
                reject(employeeId, checkOut, "location", "no location sent",
                        "Location is required. Allow location access in your browser and try again.");
            }
            if (Math.abs(req.latitude()) > 90 || Math.abs(req.longitude()) > 180) {
                reject(employeeId, checkOut, "location", "invalid coordinates", "Invalid location");
            }
            if (req.accuracy() != null && req.accuracy() > MAX_ACCURACY_METERS) {
                reject(employeeId, checkOut, "location", "accuracy " + Math.round(req.accuracy()) + " m",
                        "Your location is too imprecise (±" + Math.round(req.accuracy()) + " m). Turn on GPS and try again.");
            }
            double d = distanceMeters(s.getOfficeLatitude(), s.getOfficeLongitude(), req.latitude(), req.longitude());
            if (d > s.getRadiusMeters()) {
                reject(employeeId, checkOut, "location", Math.round(d) + " m from office",
                        "You are " + formatDistance(d) + " from the office. You must be within "
                                + s.getRadiusMeters() + " m.");
            }
            proof.add("GPS " + Math.round(d) + " m");
        }

        if (s.isRequireQr()) {
            if (tooManyBadCodes(employeeId)) {
                reject(employeeId, checkOut, "qr", "rate limited",
                        HttpStatus.TOO_MANY_REQUESTS, "Too many wrong codes. Wait a few minutes and scan the QR code again.");
            }
            if (!isValidCode(s, req == null ? null : req.qrCode())) {
                recordBadCode(employeeId);
                reject(employeeId, checkOut, "qr", "wrong or expired code",
                        "The QR code is wrong or has expired. Scan the code on the office screen again.");
            }
            proof.add("QR");
        }
        return String.join(" · ", proof);
    }

    private void reject(Long employeeId, boolean checkOut, String rule, String detail, String message) {
        reject(employeeId, checkOut, rule, detail, HttpStatus.FORBIDDEN, message);
    }

    private void reject(Long employeeId, boolean checkOut, String rule, String detail, HttpStatus status, String message) {
        audit.log(AuditService.CHECK_IN_REJECTED, "Employee", employeeId,
                (checkOut ? "Check-out" : "Check-in") + " blocked by " + rule + " rule: " + detail);
        throw new ApiException(status, message);
    }

    private boolean tooManyBadCodes(Long employeeId) {
        Deque<Instant> q = badCodes.get(employeeId);
        if (q == null) return false;
        synchronized (q) {
            Instant cutoff = Instant.now(clock).minus(BAD_CODE_WINDOW);
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
            return q.size() >= MAX_BAD_CODES;
        }
    }

    private void recordBadCode(Long employeeId) {
        Deque<Instant> q = badCodes.computeIfAbsent(employeeId, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(Instant.now(clock));
        }
    }

    /** Great-circle distance (haversine), in metres. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private static String formatDistance(double m) {
        return m >= 1000 ? String.format("%.1f km", m / 1000) : Math.round(m) + " m";
    }
}
