package com.cecil.idwallet.web;

import com.cecil.idwallet.auth.RateLimiter;
import com.cecil.idwallet.repo.IssuerRepository;
import com.cecil.idwallet.service.VerifyService;
import com.cecil.idwallet.service.Views;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Public endpoints: no login needed (verifiers don't have accounts). Rate-limited per IP. */
@RestController
@RequestMapping("/api/public")
public class VerifyController {

    private final VerifyService verify;
    private final IssuerRepository issuers;
    private final RateLimiter limiter;

    public VerifyController(VerifyService verify, IssuerRepository issuers, RateLimiter limiter) {
        this.verify = verify;
        this.issuers = issuers;
        this.limiter = limiter;
    }

    @GetMapping("/share/{token}")
    public Map<String, Object> share(@PathVariable String token, HttpServletRequest req) {
        String ip = RequestInfo.ip(req);
        if (!limiter.allow("verify:" + ip, 60, 60_000)) throw ApiException.tooMany("Too many requests, slow down");
        return verify.verifyToken(token, ip, RequestInfo.userAgent(req));
    }

    @PostMapping("/verify")
    public Map<String, Object> presentation(@RequestBody String body, HttpServletRequest req) {
        if (!limiter.allow("verify:" + RequestInfo.ip(req), 60, 60_000)) throw ApiException.tooMany("Too many requests, slow down");
        if (body.length() > 200_000) throw ApiException.bad("File is too large");
        return verify.verifyPresentation(body);
    }

    /** The trust registry: which organisations' signatures this wallet accepts. */
    @GetMapping("/issuers")
    public List<Map<String, Object>> registry() {
        return issuers.findAll().stream().map(Views::issuer).toList();
    }
}
