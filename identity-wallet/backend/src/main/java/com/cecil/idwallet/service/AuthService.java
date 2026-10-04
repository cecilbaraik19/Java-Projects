package com.cecil.idwallet.service;

import com.cecil.idwallet.auth.AuthUser;
import com.cecil.idwallet.auth.PasswordHasher;
import com.cecil.idwallet.auth.TokenService;
import com.cecil.idwallet.crypto.KeyVault;
import com.cecil.idwallet.crypto.Totp;
import com.cecil.idwallet.domain.UserAccount;
import com.cecil.idwallet.repo.UserRepository;
import com.cecil.idwallet.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuthService {

    static final int MAX_FAILED = 5;
    static final Duration LOCK = Duration.ofMinutes(5);
    /** Used to spend the same time on unknown emails, so attackers can't tell which emails exist. */
    private static final String DUMMY_HASH = PasswordHasher.hash("dummy-password-1!");

    private final UserRepository users;
    private final TokenService tokens;
    private final KeyVault vault;
    private final AuditService audit;

    public AuthService(UserRepository users, TokenService tokens, KeyVault vault, AuditService audit) {
        this.users = users;
        this.tokens = tokens;
        this.vault = vault;
        this.audit = audit;
    }

    /** Step 1: password. Returns either a session token, or an MFA ticket if 2FA is on. */
    /** noRollbackFor: the failed-attempt counter must be saved even though we throw. */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> login(String email, String password, String ip) {
        UserAccount u = users.findByEmailIgnoreCase(email == null ? "" : email.trim()).orElse(null);
        if (u == null || !u.active) {
            PasswordHasher.matches(password == null ? "" : password, DUMMY_HASH);
            audit.log(null, email, "LOGIN_FAILED", "Unknown account", ip);
            throw ApiException.unauthorized("Wrong email or password");
        }
        if (u.lockedUntil != null && u.lockedUntil.isAfter(Instant.now())) {
            long mins = Math.max(1, Duration.between(Instant.now(), u.lockedUntil).toMinutes() + 1);
            throw new ApiException(org.springframework.http.HttpStatus.LOCKED,
                    "Too many wrong attempts. Account locked, try again in " + mins + " min");
        }
        if (!PasswordHasher.matches(password == null ? "" : password, u.passwordHash)) {
            u.failedLogins++;
            String detail = "Wrong password (" + u.failedLogins + "/" + MAX_FAILED + ")";
            if (u.failedLogins >= MAX_FAILED) {
                u.lockedUntil = Instant.now().plus(LOCK);
                u.failedLogins = 0;
                detail = "Account locked for 5 minutes after " + MAX_FAILED + " wrong passwords";
            }
            audit.log(u.id, u.email, "LOGIN_FAILED", detail, ip);
            throw ApiException.unauthorized("Wrong email or password");
        }
        u.failedLogins = 0;
        u.lockedUntil = null;
        if (u.mfaEnabled) {
            audit.log(u.id, u.email, "LOGIN_PASSWORD_OK", "Waiting for authenticator code", ip);
            return Map.of("mfaRequired", true, "mfaTicket", tokens.issueMfaTicket(u.id, u.email, u.role));
        }
        return session(u, ip, "Password");
    }

    /** Step 2 (only when 2FA is on): exchange the MFA ticket + 6-digit code for a session. */
    @Transactional
    public Map<String, Object> verifyMfa(String ticket, String code, String ip) {
        AuthUser p = tokens.parse(ticket == null ? "" : ticket, "mfa");
        if (p == null) throw ApiException.unauthorized("Sign-in step expired, please enter your password again");
        UserAccount u = users.findById(p.id()).orElseThrow(() -> ApiException.unauthorized("Account not found"));
        String secret = vault.decryptSecret(u.totpSecretEnc, u.did);
        if (!Totp.verify(secret, code == null ? "" : code.trim(), Instant.now().getEpochSecond())) {
            audit.log(u.id, u.email, "MFA_FAILED", "Wrong authenticator code", ip);
            throw ApiException.unauthorized("Wrong code. Check the time on your phone and try again");
        }
        return session(u, ip, "Password + authenticator");
    }

    private Map<String, Object> session(UserAccount u, String ip, String method) {
        u.lastLoginAt = Instant.now();
        audit.log(u.id, u.email, "LOGIN", method, ip);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("token", tokens.issueAccess(u.id, u.email, u.role));
        r.put("user", Views.user(u));
        return r;
    }

    @Transactional
    public Map<String, Object> startMfaSetup(UserAccount u) {
        if (u.mfaEnabled) throw ApiException.bad("Two-factor authentication is already on");
        String secret = Totp.newSecret();
        u.pendingTotpSecretEnc = vault.encryptSecret(secret, u.did);
        users.save(u);
        return Map.of("secret", secret, "otpauthUri", Totp.otpauthUri("IdentityWallet", u.email, secret));
    }

    @Transactional
    public void confirmMfa(UserAccount u, String code, String ip) {
        if (u.pendingTotpSecretEnc == null) throw ApiException.bad("Start the setup first");
        String secret = vault.decryptSecret(u.pendingTotpSecretEnc, u.did);
        if (!Totp.verify(secret, code, Instant.now().getEpochSecond())) throw ApiException.bad("That code didn't match. Try the next one");
        u.totpSecretEnc = u.pendingTotpSecretEnc;
        u.pendingTotpSecretEnc = null;
        u.mfaEnabled = true;
        users.save(u);
        audit.log(u.id, u.email, "MFA_ENABLED", "Authenticator app linked", ip);
    }

    @Transactional
    public void disableMfa(UserAccount u, String password, String code, String ip) {
        if (!u.mfaEnabled) throw ApiException.bad("Two-factor authentication is not on");
        if (!PasswordHasher.matches(password == null ? "" : password, u.passwordHash)) throw ApiException.bad("Wrong password");
        if (!Totp.verify(vault.decryptSecret(u.totpSecretEnc, u.did), code, Instant.now().getEpochSecond()))
            throw ApiException.bad("Wrong authenticator code");
        u.mfaEnabled = false;
        u.totpSecretEnc = null;
        users.save(u);
        audit.log(u.id, u.email, "MFA_DISABLED", null, ip);
    }

    @Transactional
    public void changePassword(UserAccount u, String current, String next, String ip) {
        if (!PasswordHasher.matches(current == null ? "" : current, u.passwordHash)) throw ApiException.bad("Current password is wrong");
        String err = PasswordHasher.policyError(next);
        if (err != null) throw ApiException.bad(err);
        u.passwordHash = PasswordHasher.hash(next);
        users.save(u);
        audit.log(u.id, u.email, "PASSWORD_CHANGED", null, ip);
    }
}
