package com.cecil.mail.service;

import com.cecil.mail.model.User;
import com.cecil.mail.store.DataStore;
import com.cecil.mail.util.Clock;
import com.cecil.mail.util.Passwords;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign in, sign out and "who is this request from?".
 *
 *  - Passwords are checked with PBKDF2 (see Passwords).
 *  - 5 wrong passwords in a row lock the account for 5 minutes (slows down guessing).
 *  - A session is a long random token kept in an HttpOnly cookie. It expires after 12 idle hours.
 */
public class AuthService {

    public static final int MAX_FAILS = 5;
    public static final long LOCK_MILLIS = 5 * 60 * 1000L;
    public static final long IDLE_MILLIS = 12 * 60 * 60 * 1000L;

    public record Session(String token, String username, long lastSeen) { }

    public static class AuthException extends RuntimeException {
        public AuthException(String msg) { super(msg); }
    }

    private final DataStore store;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AuthService(DataStore store) { this.store = store; }

    /** Returns a new session token, or throws AuthException with a message for the user. */
    public String login(String username, String password) {
        Optional<User> found = store.user(username);
        // Same message for "no such user" and "wrong password", so nobody can find out which usernames exist.
        if (found.isEmpty()) {
            Passwords.verify(password == null ? "" : password, Passwords.hash("timing-equaliser"));
            throw new AuthException("Wrong username or password.");
        }
        User u = found.get();
        long now = Clock.millis();
        if (u.lockedUntil > now) {
            long mins = Math.max(1, (u.lockedUntil - now + 59_999) / 60_000);
            throw new AuthException("Too many wrong passwords. Try again in " + mins + " minute" + (mins == 1 ? "" : "s") + ".");
        }
        if (!Passwords.verify(password, u.passwordHash)) {
            u.failedLogins++;
            if (u.failedLogins >= MAX_FAILS) {
                u.lockedUntil = now + LOCK_MILLIS;
                u.failedLogins = 0;
                store.saveUser(u);
                throw new AuthException("Too many wrong passwords. The account is locked for 5 minutes.");
            }
            store.saveUser(u);
            throw new AuthException("Wrong username or password.");
        }
        u.failedLogins = 0;
        u.lockedUntil = 0;
        store.saveUser(u);
        String token = Passwords.randomToken();
        sessions.put(token, new Session(token, u.username, now));
        return token;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    /** Finds the signed-in user for a session token and refreshes the idle timer. */
    public Optional<User> userFor(String token) {
        if (token == null) return Optional.empty();
        Session s = sessions.get(token);
        if (s == null) return Optional.empty();
        long now = Clock.millis();
        if (now - s.lastSeen() > IDLE_MILLIS) {
            sessions.remove(token);
            return Optional.empty();
        }
        sessions.put(token, new Session(token, s.username(), now));
        return store.user(s.username());
    }

    public void changePassword(User u, String oldPw, String newPw) {
        if (!Passwords.verify(oldPw, u.passwordHash)) throw new AuthException("Your current password is not correct.");
        if (newPw == null || newPw.length() < 8) throw new AuthException("The new password needs at least 8 characters.");
        u.passwordHash = Passwords.hash(newPw);
        store.saveUser(u);
        // sign out every other session of this user
        sessions.values().removeIf(s -> s.username().equals(u.username));
    }

    public void unlock(String username) {
        store.user(username).ifPresent(u -> {
            u.lockedUntil = 0;
            u.failedLogins = 0;
            store.saveUser(u);
        });
    }
}
