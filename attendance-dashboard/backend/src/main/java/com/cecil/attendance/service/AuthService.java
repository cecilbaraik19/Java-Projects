package com.cecil.attendance.service;

import com.cecil.attendance.dto.Dtos.LoginResponse;
import com.cecil.attendance.dto.Dtos.MeView;
import com.cecil.attendance.exception.ApiException;
import com.cecil.attendance.model.UserAccount;
import com.cecil.attendance.repository.UserAccountRepository;
import com.cecil.attendance.security.AccessGuard;
import com.cecil.attendance.security.CurrentUser;
import com.cecil.attendance.security.JwtService;
import com.cecil.attendance.security.PasswordPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Login, lockout and password changes. Deliberately not @Transactional as a whole:
 * failed-attempt counters and audit rows must be saved even when login is refused.
 */
@Service
public class AuthService {

    private static final String INVALID = "Invalid username or password";

    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final AccessGuard guard;
    private final int maxFailed;
    private final Duration lockDuration;
    /** Hash of a random string, compared against when the username does not exist (equal timing). */
    private final String dummyHash;

    public AuthService(UserAccountRepository users, PasswordEncoder encoder, JwtService jwt, AuditService audit,
                       AccessGuard guard,
                       @Value("${attendance.security.max-failed-logins:5}") int maxFailed,
                       @Value("${attendance.security.lock-minutes:15}") long lockMinutes) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.guard = guard;
        this.maxFailed = maxFailed;
        this.lockDuration = Duration.ofMinutes(lockMinutes);
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    public LoginResponse login(String username, String password) {
        String name = username.trim();
        UserAccount user = users.findByUsernameIgnoreCase(name).orElse(null);
        Instant now = Instant.now();

        if (user == null) {
            encoder.matches(password, dummyHash); // same work as a real check
            audit.logAs(name, AuditService.LOGIN_FAILED, "User", null, "Unknown username");
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID);
        }
        if (user.isLocked(now)) {
            long mins = Math.max(1, Duration.between(now, user.getLockedUntil()).toMinutes() + 1);
            audit.logAs(user.getUsername(), AuditService.LOGIN_FAILED, "User", user.getId(), "Attempt while locked");
            throw new ApiException(HttpStatus.LOCKED,
                    "Account locked after too many failed attempts. Try again in " + mins + " minute(s).");
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            int attempts = user.getFailedAttempts() + 1;
            if (attempts >= maxFailed) {
                user.setFailedAttempts(0);
                user.setLockedUntil(now.plus(lockDuration));
                users.save(user);
                audit.logAs(user.getUsername(), AuditService.ACCOUNT_LOCKED, "User", user.getId(),
                        maxFailed + " failed attempts - locked for " + lockDuration.toMinutes() + " min");
                throw new ApiException(HttpStatus.LOCKED, "Too many failed attempts. Account locked for "
                        + lockDuration.toMinutes() + " minutes.");
            }
            user.setFailedAttempts(attempts);
            users.save(user);
            audit.logAs(user.getUsername(), AuditService.LOGIN_FAILED, "User", user.getId(),
                    "Wrong password (" + attempts + "/" + maxFailed + ")");
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID);
        }
        if (!user.isEnabled()) {
            audit.logAs(user.getUsername(), AuditService.LOGIN_FAILED, "User", user.getId(), "Account disabled");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "This account has been disabled. Contact your administrator.");
        }

        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        users.save(user);
        audit.logAs(user.getUsername(), AuditService.LOGIN, "User", user.getId(), null);
        return new LoginResponse(jwt.generate(user), jwt.lifetimeSeconds(), MeView.of(user));
    }

    public MeView me() {
        return MeView.of(currentAccount());
    }

    /** Changes the caller's password and returns a fresh token (old tokens stop working). */
    public LoginResponse changePassword(String currentPassword, String newPassword) {
        UserAccount user = currentAccount();
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("Current password is incorrect");
        }
        if (encoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("New password must be different from the current one");
        }
        PasswordPolicy.validate(newPassword, user.getUsername());
        user.setPasswordHash(encoder.encode(newPassword));
        user.invalidateTokens();
        users.save(user);
        audit.log(AuditService.PASSWORD_CHANGED, "User", user.getId(), null);
        return new LoginResponse(jwt.generate(user), jwt.lifetimeSeconds(), MeView.of(user));
    }

    private UserAccount currentAccount() {
        CurrentUser cu = guard.requireUser();
        return users.findById(cu.id()).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Please log in"));
    }
}
