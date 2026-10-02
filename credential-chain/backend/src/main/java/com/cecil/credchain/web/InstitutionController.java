package com.cecil.credchain.web;

import com.cecil.credchain.institution.Institution;
import com.cecil.credchain.institution.InstitutionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class InstitutionController {

    private final InstitutionService service;

    public InstitutionController(InstitutionService service) {
        this.service = service;
    }

    public record RegisterRequest(@NotBlank @Size(max = 150) String name,
                                  @Email @Size(max = 120) String email,
                                  @Size(max = 200) String website) {}

    public record RegisterResponse(Institution institution, String apiKey) {}

    public record LoginRequest(@NotBlank String apiKey) {}

    @GetMapping("/institutions")
    public List<Institution> list() {
        return service.list();
    }

    @GetMapping("/institutions/{id}")
    public Institution get(@PathVariable String id) {
        return service.get(id);
    }

    /** Registers an issuer. The API key is returned ONCE — store it safely. */
    @PostMapping("/institutions")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest req) {
        Institution inst = service.register(req.name(), req.email(), req.website(), null);
        return new RegisterResponse(inst, inst.getApiKey());
    }

    @PostMapping("/auth/login")
    public Institution login(@Valid @RequestBody LoginRequest req) {
        return service.authenticate(req.apiKey());
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        Institution i = service.authenticate(apiKey);
        return Map.of("id", i.getId(), "name", i.getName());
    }
}
