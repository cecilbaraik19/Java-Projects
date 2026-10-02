package com.cecil.cloudmonitor.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/** Only lets a WebSocket client connect if it sends a valid JWT in the STOMP CONNECT frame. */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;

    public StompAuthInterceptor(JwtService jwtService, AppUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String header = accessor.getFirstNativeHeader("Authorization");
            String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;
            String username = token == null ? null : jwtService.validateAndGetUsername(token).orElse(null);
            if (username == null) {
                throw new MessagingException("Unauthorized WebSocket connection");
            }
            UserDetails user = userDetailsService.loadUserByUsername(username);
            accessor.setUser(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        }
        return message;
    }
}
