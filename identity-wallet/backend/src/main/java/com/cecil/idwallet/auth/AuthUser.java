package com.cecil.idwallet.auth;

import com.cecil.idwallet.domain.Role;
import com.cecil.idwallet.web.ApiException;

import java.util.Arrays;

/** The logged-in user, injected into controller methods that declare an AuthUser parameter. */
public record AuthUser(Long id, String email, Role role) {
    public AuthUser require(Role... roles) {
        if (Arrays.stream(roles).noneMatch(r -> r == role)) throw ApiException.forbidden("Your role can't do this");
        return this;
    }
}
