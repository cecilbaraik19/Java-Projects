package com.cecil.credchain.web;

import com.cecil.credchain.auth.AuthService;
import com.cecil.credchain.auth.Role;
import com.cecil.credchain.institution.Institution;
import com.cecil.credchain.institution.InstitutionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class InstitutionController {

    private final InstitutionService service;
    private final AuthService auth;

    public InstitutionController(InstitutionService service, AuthService auth) {
        this.service = service;
        this.auth = auth;
    }

    public record RegisterRequest(@NotBlank @Size(max = 150) String name,
                                  @NotBlank @Email @Size(max = 120) String email,
                                  @Size(max = 200) String website,
                                  @NotBlank @Size(min = 6, max = 100) String password) {}

    public record RegisterResponse(Institution institution, String loginEmail, String apiKey) {}

    @GetMapping("/institutions")
    public List<Institution> list() {
        return service.list();
    }

    @GetMapping("/institutions/{id}")
    public Institution get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * ADMIN only. Registers an institution, anchors its public key on-chain and creates
     * its staff login (email + password). The API key is returned once, for scripts.
     */
    @PostMapping("/institutions")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RegisterResponse register(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @Valid @RequestBody RegisterRequest req) {
        auth.requireAdmin(authorization);
        auth.checkNewLogin(req.email(), req.password());
        Institution inst = service.register(req.name(), req.email(), req.website(), null);
        auth.createUser(req.email(), inst.getName() + " Registrar", req.password(), Role.ISSUER, inst.getId());
        return new RegisterResponse(inst, req.email().trim().toLowerCase(), inst.getApiKey());
    }
}
