package com.cecil.attendance.security;

import com.cecil.attendance.model.UserAccount;
import com.cecil.attendance.repository.UserAccountRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Reads "Authorization: Bearer &lt;jwt&gt;", verifies it and loads the user fresh from the database,
 * so disabling a user, changing their role or resetting their password takes effect immediately.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final UserAccountRepository users;

    public JwtAuthFilter(JwtService jwt, UserAccountRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            Claims claims = jwt.parse(header.substring(7).trim());
            if (claims != null && claims.getSubject() != null) {
                users.findByUsernameIgnoreCase(claims.getSubject())
                        .filter(u -> isStillValid(u, claims))
                        .ifPresent(this::authenticate);
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isStillValid(UserAccount u, Claims claims) {
        Integer ver = claims.get("ver", Integer.class);
        return u.isEnabled()
                && !u.isLocked(Instant.now())
                && ver != null && ver == u.getTokenVersion();
    }

    private void authenticate(UserAccount u) {
        CurrentUser principal = CurrentUser.of(u);
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority(u.getRole().authority())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
