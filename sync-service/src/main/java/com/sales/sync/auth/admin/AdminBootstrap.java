package com.sales.sync.auth.admin;

import com.sales.sync.auth.model.Role;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.RoleRepository;
import com.sales.sync.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * First-admin seeder. Runs once at application startup against a fresh
 * database; idempotent across subsequent boots.
 *
 * <p>Contract (per {@code openspec/changes/admin-console/specs/admin-bootstrap/spec.md},
 * requirements B1 and B3):
 * <ul>
 *   <li>On a database with zero active admin rows: creates one admin
 *       with {@code roles=[admin]}, {@code must_change_password=true},
 *       {@code active=true}, {@code locked=false}. Prints the email and a
 *       random base64url password to {@link System#out} (NOT to logback,
 *       NEVER to an HTTP response). Does nothing on subsequent boots.</li>
 *   <li>If any active admin already exists: does nothing. Logs INFO.</li>
 *   <li>If {@code app.admin.recover-email} is set AND no active admin
 *       exists: creates the admin with that email. Used by
 *       {@code --admin-recover=<email>} style recovery.</li>
 *   <li>If {@code app.admin.recover-email} is set AND an active admin
 *       already exists: logs WARN and does nothing. Recovery is a no-op
 *       when an admin exists.</li>
 *   <li>If {@code app.admin.bootstrap-enabled=false}: logs INFO and does
 *       nothing. Used in CI to keep credentials out of build logs.</li>
 * </ul>
 *
 * <p>Owner: change {@code admin-console} PR2 (shape B — uses
 * {@code Role} entity from the {@code roles} table seeded by V6).
 */
@Component
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final RandomPasswordGenerator passwordGenerator;
    private final AdminBootstrapProperties props;

    public AdminBootstrap(UserRepository users,
                          RoleRepository roles,
                          PasswordEncoder passwordEncoder,
                          RandomPasswordGenerator passwordGenerator,
                          AdminBootstrapProperties props) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.passwordGenerator = passwordGenerator;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!props.isBootstrapEnabled()) {
            log.info("admin bootstrap disabled by app.admin.bootstrap-enabled=false");
            return;
        }

        long activeAdminCount = users.countActiveByRoleName("admin");

        String recoverEmail = (props.getRecoverEmail() == null || props.getRecoverEmail().isBlank())
                ? null
                : props.getRecoverEmail().trim().toLowerCase();

        if (activeAdminCount > 0 && recoverEmail == null) {
            log.info("admin already bootstrapped (active admin count: {}); skipping", activeAdminCount);
            return;
        }

        if (activeAdminCount > 0) {
            log.warn("admin recovery requested (recoverEmail={}) but active admin already exists; "
                    + "skipping — refusing to override existing admin", recoverEmail);
            return;
        }

        String email = recoverEmail != null
                ? recoverEmail
                : "admin+" + UUID.randomUUID().toString().substring(0, 8) + "@bootstrap.local";

        if (users.findByEmail(email).isPresent()) {
            log.warn("admin bootstrap requested email={} but a user with that email already exists; "
                    + "skipping — refusing to clobber", email);
            return;
        }

        Role adminRole = roles.findByName("admin")
                .orElseThrow(() -> new IllegalStateException(
                        "Role 'admin' not seeded; V6 migration must run before bootstrap can fire"));

        String cleartextPassword = passwordGenerator.generate(16);
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(cleartextPassword));
        u.setLocked(false);
        u.setActive(true);
        u.setMustChangePassword(true);
        u.setRoles(java.util.Set.<Role>of(adminRole));
        users.save(u);

        // Print credentials to stdout ONLY. Never to logback (which may
        // tee to a file or to a remote log shipper). Never returned via
        // any HTTP endpoint. The operator is expected to capture the line
        // immediately and rotate.
        System.out.println("[admin-bootstrap] credentials email=" + email
                + " password=" + cleartextPassword
                + " — capture now, this will not be shown again");

        log.info("admin bootstrap created user email={}; credentials printed to stdout (not logs)", email);
    }
}
