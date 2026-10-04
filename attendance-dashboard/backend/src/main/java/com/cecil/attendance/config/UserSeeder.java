package com.cecil.attendance.config;

import com.cecil.attendance.model.Role;
import com.cecil.attendance.model.UserAccount;
import com.cecil.attendance.repository.EmployeeRepository;
import com.cecil.attendance.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first admin account when there are no users yet, plus demo manager/employee
 * accounts when demo data is enabled. Runs after {@link DataSeeder}.
 */
@Component
@Order(2)
public class UserSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    private final UserAccountRepository users;
    private final EmployeeRepository employees;
    private final PasswordEncoder encoder;
    private final AttendanceProperties props;
    private final String adminPassword;

    public UserSeeder(UserAccountRepository users, EmployeeRepository employees, PasswordEncoder encoder,
                      AttendanceProperties props,
                      @Value("${attendance.security.initial-admin-password:Admin@123}") String adminPassword) {
        this.users = users;
        this.employees = employees;
        this.encoder = encoder;
        this.props = props;
        this.adminPassword = adminPassword;
    }

    /** 16 random characters that satisfy the password policy. */
    private static String randomPassword() {
        java.security.SecureRandom r = new java.security.SecureRandom();
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ", lower = "abcdefghijkmnopqrstuvwxyz", digits = "23456789";
        String all = upper + lower + digits;
        StringBuilder sb = new StringBuilder()
                .append(upper.charAt(r.nextInt(upper.length())))
                .append(lower.charAt(r.nextInt(lower.length())))
                .append(digits.charAt(r.nextInt(digits.length())));
        while (sb.length() < 16) sb.append(all.charAt(r.nextInt(all.length())));
        return sb.toString();
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (users.count() > 0) return;

        String password = adminPassword;
        if (password == null || password.isBlank()) {
            password = randomPassword();
            log.warn("Created initial account 'admin' with generated password: {}  (shown only once - log in and change it)", password);
        } else {
            log.warn("Created initial account 'admin' - log in and change its password right away.");
        }
        users.save(new UserAccount("admin", encoder.encode(password), Role.ADMIN, null));

        if (!props.seedDemoData()) return;
        // Vikram Rao (Tech Lead, Engineering) manages Engineering; Aarav Sharma is an engineer
        employees.findByEmployeeCodeIgnoreCase("EMP005").ifPresent(e ->
                users.save(new UserAccount("manager", encoder.encode("Manager@123"), Role.MANAGER, e)));
        employees.findByEmployeeCodeIgnoreCase("EMP001").ifPresent(e ->
                users.save(new UserAccount("employee", encoder.encode("Employee@123"), Role.EMPLOYEE, e)));
        log.info("Created demo accounts: manager / Manager@123, employee / Employee@123");
    }
}
