package com.cecil.idwallet.config;

import com.cecil.idwallet.auth.AuthUser;
import com.cecil.idwallet.auth.TokenService;
import com.cecil.idwallet.web.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TokenService tokens;

    public WebConfig(TokenService tokens) {
        this.tokens = tokens;
    }

    /** Any controller parameter of type AuthUser requires a valid "Authorization: Bearer" token. */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            public boolean supportsParameter(MethodParameter p) {
                return p.getParameterType() == AuthUser.class;
            }

            public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest req, WebDataBinderFactory f) {
                String h = req.getHeader("Authorization");
                AuthUser u = (h != null && h.startsWith("Bearer ")) ? tokens.parse(h.substring(7), "access") : null;
                if (u == null) throw ApiException.unauthorized("Please sign in");
                return u;
            }
        });
    }

    /** When the React build is bundled into the jar, let client-side routes load index.html. */
    @Override
    public void addViewControllers(ViewControllerRegistry r) {
        for (String path : new String[]{"/login", "/register", "/wallet/**", "/vault", "/shares", "/security",
                "/issuer/**", "/admin/**", "/verify", "/v/**", "/dashboard"}) {
            r.addViewController(path).setViewName("forward:/index.html");
        }
    }

    /** Defensive HTTP headers on every response. */
    @Bean
    public OncePerRequestFilter securityHeaders() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                res.setHeader("X-Content-Type-Options", "nosniff");
                res.setHeader("Referrer-Policy", "no-referrer");
                res.setHeader("Permissions-Policy", "camera=(self), microphone=(), geolocation=()");
                if (!req.getRequestURI().startsWith("/h2-console")) {
                    res.setHeader("X-Frame-Options", "DENY");
                }
                if (req.getRequestURI().startsWith("/api/")) {
                    res.setHeader("Cache-Control", "no-store");
                }
                chain.doFilter(req, res);
            }
        };
    }
}
