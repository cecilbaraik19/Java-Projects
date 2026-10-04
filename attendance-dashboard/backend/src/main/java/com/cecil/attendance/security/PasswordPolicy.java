package com.cecil.attendance.security;

import com.cecil.attendance.exception.ApiException;

/** Minimum password rules: 8+ chars with upper case, lower case and a digit. */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    public static void validate(String password, String username) {
        if (password == null || password.length() < 8) {
            throw ApiException.badRequest("Password must be at least 8 characters");
        }
        if (password.length() > 72) {
            // BCrypt only uses the first 72 bytes
            throw ApiException.badRequest("Password must be at most 72 characters");
        }
        if (!password.matches(".*[A-Z].*") || !password.matches(".*[a-z].*") || !password.matches(".*\\d.*")) {
            throw ApiException.badRequest("Password must contain an upper-case letter, a lower-case letter and a digit");
        }
        if (username != null && password.toLowerCase().contains(username.toLowerCase())) {
            throw ApiException.badRequest("Password must not contain the username");
        }
    }
}
