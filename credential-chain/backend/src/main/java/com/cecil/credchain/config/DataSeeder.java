package com.cecil.credchain.config;

import com.cecil.credchain.blockchain.Blockchain;
import com.cecil.credchain.credential.CredentialService;
import com.cecil.credchain.credential.CredentialView;
import com.cecil.credchain.credential.IssueRequest;
import com.cecil.credchain.institution.Institution;
import com.cecil.credchain.institution.InstitutionRepository;
import com.cecil.credchain.institution.InstitutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** Seeds a demo issuer and a few credentials on first run so the UI isn't empty. */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final InstitutionRepository institutions;
    private final InstitutionService institutionService;
    private final CredentialService credentialService;
    private final Blockchain blockchain;
    private final boolean enabled;
    private final String demoApiKey;

    public DataSeeder(InstitutionRepository institutions, InstitutionService institutionService,
                      CredentialService credentialService, Blockchain blockchain,
                      @Value("${credchain.seed-demo-data:true}") boolean enabled,
                      @Value("${credchain.demo-api-key:demo-issuer-key}") String demoApiKey) {
        this.institutions = institutions;
        this.institutionService = institutionService;
        this.credentialService = credentialService;
        this.blockchain = blockchain;
        this.enabled = enabled;
        this.demoApiKey = demoApiKey;
    }

    @Override
    public void run(String... args) {
        if (!enabled || institutions.count() > 0) {
            return;
        }
        Institution demo = institutionService.register("Demo Institute of Technology",
                "registrar@demo-institute.edu", "https://demo-institute.edu", demoApiKey);
        blockchain.mine();

        credentialService.issue(demo, new IssueRequest("Bachelor's Degree", "Aarav Sharma", "2022-BSCIT/011",
                "B.Sc. Information Technology", "Cyber Security", "8.7 CGPA", "2025-06-30"));
        credentialService.issue(demo, new IssueRequest("Bachelor's Degree", "Priya Verma", "2022-BSCIT/027",
                "B.Sc. Information Technology", "Cloud Computing", "9.1 CGPA", "2025-06-30"));
        CredentialView rohan = credentialService.issue(demo, new IssueRequest("Certificate", "Rohan Gupta", "2023-BCA/042",
                "Ethical Hacking Workshop", "", "A+", "2026-02-15"));
        blockchain.mine();

        credentialService.revoke(demo, rohan.credential().credentialId(), "Issued in error - duplicate certificate");
        blockchain.mine();

        log.info("=================================================================");
        log.info(" Demo issuer seeded: {} ({})", demo.getName(), demo.getId());
        log.info(" Demo API key: {}", demoApiKey);
        log.info("=================================================================");
    }
}
