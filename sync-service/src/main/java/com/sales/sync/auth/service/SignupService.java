package com.sales.sync.auth.service;

import com.sales.sync.auth.admin.AdminAuditLogger;
import com.sales.sync.auth.admin.AuditEvent;
import com.sales.sync.auth.dto.AuthResponse;
import com.sales.sync.auth.dto.SignupRequest;
import com.sales.sync.auth.model.RefreshToken;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.RefreshTokenRepository;
import com.sales.sync.auth.repository.UserRepository;
import com.sales.sync.auth.security.JwtProperties;
import com.sales.sync.auth.security.JwtService;
import com.sales.sync.auth.security.RefreshTokenCodec;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Self-service user registration owned by change admin-console (PR1).
 *
 * <p>Behavior:
 * <ul>
 *   <li>Always creates the user with {@code role = CLIENT}. Any value the
 *       client supplies for {@code role} in the request body is ignored.</li>
 *   <li>If the client supplied a non-{@code cliente} role, an audit row is
 *       written with {@code action='signup_role_ignored'} for forensic
 *       visibility. The {@code cliente} value (or null/missing) is treated
 *       as a no-op and does NOT emit an audit row.</li>
 *   <li>The {@code role} field on {@link SignupRequest} is
 *       {@code @Deprecated} but kept so that bypass attempts are still
 *       detectable after the Flutter app stops sending it. New clients
 *       should send no role at all.</li>
 *   <li>Email uniqueness is enforced before persistence. Password is
 *       bcrypt-hashed with the application's configured cost. A fresh
 *       access JWT plus an opaque refresh-token (with a new
 *       {@code token_family}) is issued, matching the canonical
 *       refresh-token rotation model.</li>
 * </ul>
 */
@Service
public class SignupService {

    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenCodec refreshTokenCodec;
    private final JwtProperties props;
    private final AdminAuditLogger auditLogger;

    public SignupService(UserRepository users,
                         RefreshTokenRepository tokens,
                         PasswordEncoder passwordEncoder,
                         JwtService jwtService,
                         RefreshTokenCodec refreshTokenCodec,
                         JwtProperties props,
                         AdminAuditLogger auditLogger) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenCodec = refreshTokenCodec;
        this.props = props;
        this.auditLogger = auditLogger;
    }

    @Transactional
    public AuthResponse signup(SignupRequest req) {
        String email = req.email().toLowerCase(Locale.ROOT);
        if (users.findByEmail(email).isPresent()) {
            throw new EmailAlreadyExistsException(email);
        }

        // Defensive: capture whether the client attempted a role bypass before
        // we overwrite it. NULL or 'cliente' (case-insensitive) is the no-op
        // path; any other value (admin, repartidor, foo) is a bypass attempt.
        boolean bypassAttempt = req.role() != null
                && !req.role().isBlank()
                && !"cliente".equalsIgnoreCase(req.role());

        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setLocked(false);
        u.setActive(true);
        // Multi-role source of truth. setRoles() also keeps the legacy
        // single-string role column in sync for the JWT cut-over window.
        u.setRoles(java.util.Set.of(User.Role.cliente));

        if (req.full_name() != null) u.setFullName(req.full_name());
        if (req.phone() != null) u.setPhone(req.phone());
        if (req.dni() != null) u.setDni(req.dni());
        if (req.business_name() != null) u.setBusinessName(req.business_name());
        if (req.business_lat() != null) u.setBusinessLat(BigDecimal.valueOf(req.business_lat()));
        if (req.business_lng() != null) u.setBusinessLng(BigDecimal.valueOf(req.business_lng()));
        if (req.business_address() != null) u.setBusinessAddress(req.business_address());

        users.save(u);

        UUID family = UUID.randomUUID();
        String access = jwtService.sign(u.getId(), u.getVendorId(), u.getEmail(), u.getRole());
        RefreshTokenCodec.OpaqueRefreshToken rt = refreshTokenCodec.generate();

        RefreshToken row = new RefreshToken();
        row.setUserId(u.getId());
        row.setTokenFamily(family);
        row.setTokenHash(rt.hash());
        row.setExpiresAt(Instant.now().plus(props.refreshTokenTtl()));
        tokens.save(row);

        if (bypassAttempt) {
            auditLogger.log(AuditEvent.anonymous(
                    "signup_role_ignored",
                    email,
                    "role=" + req.role()));
        }

        return new AuthResponse(access, rt.plaintext(), props.accessTokenTtl().toSeconds(), false);
    }
}
