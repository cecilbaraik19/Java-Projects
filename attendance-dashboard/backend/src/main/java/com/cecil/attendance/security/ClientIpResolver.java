package com.cecil.attendance.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * Works out the real client IP.
 * X-Forwarded-For is only honoured when the direct connection comes from a trusted proxy
 * (e.g. the Vite dev server or nginx on localhost); otherwise anyone could fake their IP
 * by sending the header themselves.
 */
@Component
public class ClientIpResolver {

    private final List<IpUtils.Cidr> trustedProxies;

    public ClientIpResolver(@Value("${attendance.security.trusted-proxies:127.0.0.1,::1}") String trusted) {
        this.trustedProxies = IpUtils.parseList(trusted);
    }

    public String resolve(HttpServletRequest req) {
        String remote = req.getRemoteAddr();
        if (!IpUtils.matchesAny(remote, trustedProxies)) return remote;

        String xff = req.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) return remote;
        // Walk from the right: skip our own trusted proxies, the first other address is the client
        String[] hops = xff.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (IpUtils.parseIp(hop) == null) return remote; // garbage header -> don't trust it
            if (!IpUtils.matchesAny(hop, trustedProxies)) return hop;
        }
        return hops[0].trim();
    }

    /** Client IP of the current HTTP request, or null outside a request. */
    public String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return resolve(attrs.getRequest());
        }
        return null;
    }
}
