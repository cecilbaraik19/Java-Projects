package com.cecil.idwallet.config;

import com.cecil.idwallet.auth.AuthUser;
import com.cecil.idwallet.domain.Role;
import com.cecil.idwallet.domain.UserAccount;
import com.cecil.idwallet.repo.UserRepository;
import com.cecil.idwallet.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates the admin account and (optionally) demo issuers, a demo holder and sample credentials on first run. */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String IP = "seed";

    private final UserRepository users;
    private final UserService userService;
    private final AdminService admin;
    private final CredentialService credentials;
    private final ShareService shares;
    private final VaultService vault;
    private final boolean seedDemo;
    private final String adminEmail;
    private final String adminPassword;

    public DataSeeder(UserRepository users, UserService userService, AdminService admin, CredentialService credentials,
                      ShareService shares, VaultService vault,
                      @Value("${idwallet.seed-demo-data:true}") boolean seedDemo,
                      @Value("${idwallet.admin-email}") String adminEmail,
                      @Value("${idwallet.admin-password}") String adminPassword) {
        this.users = users;
        this.userService = userService;
        this.admin = admin;
        this.credentials = credentials;
        this.shares = shares;
        this.vault = vault;
        this.seedDemo = seedDemo;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        UserAccount a = userService.create(adminEmail, "Platform Admin", adminPassword, Role.ADMIN, null);
        log.info("Created admin account {}", a.email);
        if (!seedDemo) return;

        admin.createIssuer(new AdminService.IssuerRequest("National Identity Authority (Demo)", "GOVERNMENT",
                "https://identity.example.gov", "Registrar Office", "registrar@identity.demo", "Issuer@123"), a.email, a.id, IP);
        admin.createIssuer(new AdminService.IssuerRequest("Demo Institute of Technology", "EDUCATION",
                "https://dit.example.edu", "Admissions Desk", "admissions@dit.demo", "Issuer@123"), a.email, a.id, IP);
        admin.createIssuer(new AdminService.IssuerRequest("Regional Transport Office (Demo)", "TRANSPORT",
                null, "Licensing Officer", "licensing@rto.demo", "Issuer@123"), a.email, a.id, IP);

        UserAccount holder = userService.create("aarav@wallet.demo", "Aarav Sharma", "User@1234", Role.USER, null);
        userService.create("priya@wallet.demo", "Priya Verma", "User@1234", Role.USER, null);

        AuthUser gov = staff("registrar@identity.demo");
        AuthUser edu = staff("admissions@dit.demo");
        AuthUser rto = staff("licensing@rto.demo");

        Map<String, Object> nid = credentials.issue(gov, new CredentialService.IssueRequest(holder.email, "NATIONAL_ID", null,
                claims("fullName", "Aarav Sharma", "dateOfBirth", "2005-03-14", "gender", "Male",
                        "idNumber", "DEMO-4821-7730-1195", "nationality", "Indian",
                        "address", "12 Lake View Road, Ranchi, Jharkhand"), null), IP);
        credentials.issue(edu, new CredentialService.IssueRequest(holder.email, "STUDENT_ID", null,
                claims("fullName", "Aarav Sharma", "studentId", "DIT-2025-BSCIT-117", "program", "B.Sc. Information Technology",
                        "institution", "Demo Institute of Technology", "enrollmentYear", "2025", "dateOfBirth", "2005-03-14"), null), IP);
        Map<String, Object> dl = credentials.issue(rto, new CredentialService.IssueRequest(holder.email, "DRIVING_LICENSE", null,
                claims("fullName", "Aarav Sharma", "dateOfBirth", "2005-03-14", "licenseNumber", "JH01-2024-0098812",
                        "vehicleClasses", "MCWG, LMV", "bloodGroup", "B+"), null), IP);
        credentials.revoke(rto, (String) dl.get("id"), "Re-issued after address change (demo of revocation)", IP);
        credentials.issue(rto, new CredentialService.IssueRequest(holder.email, "DRIVING_LICENSE", "Driving Licence (re-issued)",
                claims("fullName", "Aarav Sharma", "dateOfBirth", "2005-03-14", "licenseNumber", "JH01-2025-0101377",
                        "vehicleClasses", "MCWG, LMV", "bloodGroup", "B+",
                        "address", "12 Lake View Road, Ranchi, Jharkhand"), null), IP);

        AuthUser h = new AuthUser(holder.id, holder.email, holder.role);
        shares.create(h, new ShareService.ShareRequest((String) nid.get("id"), List.of("fullName", "age_over_18"),
                "City Cinema (age check)", "Entry to an 18+ screening", 60 * 24 * 7, 5), IP);
        vault.save(h, null, "Passport", "PASSPORT", "DEMO-P1234567", "Renew before travelling", "2027-01-15", null, IP);
        vault.save(h, null, "PAN Card", "PAN_CARD", "DEMOP1234X", "", null, null, IP);
        log.info("Demo data ready: holder aarav@wallet.demo / User@1234, issuer registrar@identity.demo / Issuer@123");
    }

    private AuthUser staff(String email) {
        UserAccount u = users.findByEmailIgnoreCase(email).orElseThrow();
        return new AuthUser(u.id, u.email, u.role);
    }

    private static Map<String, String> claims(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
