package com.cecil.credchain.auth;

import com.cecil.credchain.institution.Institution;
import com.cecil.credchain.institution.InstitutionService;
import com.cecil.credchain.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sign-in, account creation and role checks. */
@Service
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_MINUTES = 5;

    private final UserRepository users;
    private final TokenService tokens;
    private final InstitutionService institutions;

    /** email -> failed attempts / lock time, to slow down password guessing. */
    private final Map<String, int[]> failedAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();

    public AuthService(UserRepository users, TokenService tokens, InstitutionService institutions) {
        this.users = users;
        this.tokens = tokens;
        this.institutions = institutions;
    }

    public record LoginResult(String token, Map<String, Object> user) {}

    public LoginResult login(String email, String password) {
        String key = email == null ? "" : email.trim().toLowerCase();
        Long until = lockedUntil.get(key);
        if (until != null && until > System.currentTimeMillis()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed attempts. Try again in a few minutes.");
        }
        UserAccount user = users.findByEmailIgnoreCase(key).orElse(null);
        if (user == null || !PasswordHasher.matches(password, user.getPasswordHash())) {
            int count = failedAttempts.computeIfAbsent(key, k -> new int[1])[0]++ + 1;
            if (count >= MAX_FAILED_ATTEMPTS) {
                lockedUntil.put(key, System.currentTimeMillis() + LOCK_MINUTES * 60_000);
                failedAttempts.remove(key);
            }
            // Same message for wrong email and wrong password, so attackers can't find valid emails.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        failedAttempts.remove(key);
        lockedUntil.remove(key);
        return new LoginResult(tokens.issue(user), describe(user));
    }

    /** Validates a new login before anything is created. */
    public void checkNewLogin(String email, String password) {
        if (email == null || email.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Login email is required");
        }
        if (password == null || password.length() < 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters");
        }
        if (users.existsByEmailIgnoreCase(email.trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
    }

    public UserAccount createUser(String email, String name, String password, Role role, String institutionId) {
        checkNewLogin(email, password);
        return users.save(new UserAccount("USR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                email.trim().toLowerCase(), name, PasswordHasher.hash(password), role, institutionId));
    }

    /** Lets a signed-in user replace their password (e.g. the default demo passwords). */
    public void changePassword(AuthUser current, String currentPassword, String newPassword) {
        UserAccount user = users.findById(current.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));
        if (!PasswordHasher.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Current password is wrong");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New password must be at least 8 characters");
        }
        if (newPassword.equals(currentPassword)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New password must be different from the current one");
        }
        user.setPasswordHash(PasswordHasher.hash(newPassword));
        users.save(user);
    }

    /** Reads "Authorization: Bearer <token>". */
    public AuthUser currentUser(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in");
        }
        return tokens.verify(authorizationHeader.substring(7).trim());
    }

    public AuthUser requireAdmin(String authorizationHeader) {
        AuthUser user = currentUser(authorizationHeader);
        if (user.role() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only an administrator can do this");
        }
        return user;
    }

    /**
     * The institution allowed to issue/revoke for this request: a signed-in ISSUER (browser),
     * or an X-API-Key (for scripts and other systems).
     */
    public Institution requireIssuer(String authorizationHeader, String apiKey) {
        if (authorizationHeader == null && apiKey != null && !apiKey.isBlank()) {
            return institutions.authenticate(apiKey);
        }
        AuthUser user = currentUser(authorizationHeader);
        if (user.role() != Role.ISSUER || user.institutionId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only institution staff can issue or revoke credentials");
        }
        return institutions.get(user.institutionId());
    }

    public Map<String, Object> describe(UserAccount u) {
        return describe(new AuthUser(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getInstitutionId()));
    }

    public Map<String, Object> describe(AuthUser u) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", u.id());
        m.put("email", u.email());
        m.put("name", u.name());
        m.put("role", u.role().name());
        if (u.institutionId() != null) {
            m.put("institutionId", u.institutionId());
            m.put("institutionName", institutions.get(u.institutionId()).getName());
        }
        return m;
    }
}
