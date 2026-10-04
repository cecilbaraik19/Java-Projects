package com.cecil.credchain.auth;

import com.cecil.credchain.institution.InstitutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Makes sure the admin account exists, and gives the demo institution a login.
 * Runs after DataSeeder, so it also works on databases created before login existed.
 */
@Component
@Order(2)
public class UserSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    private final UserRepository users;
    private final AuthService auth;
    private final InstitutionRepository institutions;
    private final String adminEmail;
    private final String adminPassword;
    private final String demoApiKey;
    private final String demoEmail;
    private final String demoPassword;

    public UserSeeder(UserRepository users, AuthService auth, InstitutionRepository institutions,
                      @Value("${credchain.admin-email:admin@credchain.local}") String adminEmail,
                      @Value("${credchain.admin-password:admin123}") String adminPassword,
                      @Value("${credchain.demo-api-key:demo-issuer-key}") String demoApiKey,
                      @Value("${credchain.demo-issuer-email:registrar@demo-institute.edu}") String demoEmail,
                      @Value("${credchain.demo-issuer-password:demo123}") String demoPassword) {
        this.users = users;
        this.auth = auth;
        this.institutions = institutions;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.demoApiKey = demoApiKey;
        this.demoEmail = demoEmail;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(String... args) {
        if (!users.existsByEmailIgnoreCase(adminEmail)) {
            auth.createUser(adminEmail, "Administrator", adminPassword, Role.ADMIN, null);
            log.info("Admin account created: {} / {}", adminEmail, adminPassword);
        }
        institutions.findByApiKey(demoApiKey).ifPresent(inst -> {
            if (!users.existsByInstitutionId(inst.getId()) && !users.existsByEmailIgnoreCase(demoEmail)) {
                auth.createUser(demoEmail, inst.getName() + " Registrar", demoPassword, Role.ISSUER, inst.getId());
                log.info("Demo issuer login created: {} / {}", demoEmail, demoPassword);
            }
        });
    }
}
