package com.sales.sync.auth.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sales.sync.auth.model.User;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Nimbus-backed HS256 JWT signer / parser.
 *
 * <p>Sign produces a compact JWS with claims:
 * {@code sub, email, role (legacy single string), roles (array),
 * vendor_id, mcp, iss, aud, iat, exp, jti}.
 *
 * <p>Parse verifies the HMAC, the issuer, the audience, and the
 * expiration; returns a {@link ParsedToken} that carries the full role
 * set parsed from either the {@code roles} array claim (preferred) or
 * the legacy {@code role} single-string claim (fallback for tokens
 * issued during the cut-over window or by older code).
 *
 * <p>Cut-over window (PR3-PR5):
 * <ul>
 *   <li>{@code role} (single string) — deprecated; dropped in PR5.</li>
 *   <li>{@code roles} (array) — authoritative post-PR5.</li>
 * </ul>
 *
 * <p>The {@code mcp} claim is the {@code must-change-password} flag
 * added by PR2. PR3's {@code RoleGateFilter} blocks
 * {@code /api/v1/admin/**} when {@code mcp=true}.
 */
@Component
public class JwtService {

    private final JWSSigner signer;
    private final JWSVerifier verifier;
    private final JwtProperties props;

    public JwtService(JwtProperties props) {
        try {
            byte[] keyBytes = props.secret().getBytes(StandardCharsets.UTF_8);
            this.signer = new MACSigner(keyBytes);
            this.verifier = new MACVerifier(keyBytes);
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot initialise HMAC signer", e);
        }
        this.props = props;
    }

    /**
     * 4-arg back-compat overload. Defaults {@code mustChangePassword=false}
     * and wraps the single role in a singleton set so the
     * {@code roles} array claim still carries one element.
     */
    public String sign(UUID userId, UUID vendorId, String email, User.Role role) {
        Set<User.Role> roles = (role == null) ? Collections.emptySet() : Set.of(role);
        return sign(userId, vendorId, email, roles, false);
    }

    /**
     * 4-arg overload with the mcp flag (kept from PR2 for the bootstrap
     * case). Used by AuthService.login.
     */
    public String sign(UUID userId, UUID vendorId, String email, User.Role role, boolean mustChangePassword) {
        Set<User.Role> roles = (role == null) ? Collections.emptySet() : Set.of(role);
        return sign(userId, vendorId, email, roles, mustChangePassword);
    }

    /**
     * Sign with the full role set. PR3 dual-shape writes BOTH the legacy
     * {@code role} (single string, first element of the set) AND the
     * new {@code roles} (array) claim. Parsers prefer the array.
     */
    public String sign(UUID userId, UUID vendorId, String email, Set<User.Role> roles, boolean mustChangePassword) {
        try {
            Instant now = Instant.now();
            Instant exp = now.plus(props.accessTokenTtl());
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                    .subject(userId.toString())
                    .issuer(props.issuer())
                    .audience(props.audience())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(exp))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("email", email)
                    .claim("mcp", mustChangePassword);
            if (vendorId != null) {
                b.claim("vendor_id", vendorId.toString());
            }
            if (roles != null && !roles.isEmpty()) {
                // Dual-shape: array (authoritative post-PR5) + single string
                // (legacy back-compat, dropped in PR5).
                List<String> roleNames = roles.stream()
                        .map(User.Role::name)
                        .toList();
                b.claim("roles", roleNames);
                b.claim("role", roleNames.get(0));
            } else {
                b.claim("roles", Collections.emptyList());
                // No legacy `role` claim when the set is empty; downstream
                // parsers treat absence as "no roles".
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                    b.build());
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign JWT", e);
        }
    }

    public ParsedToken parse(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(verifier)) {
                throw new TokenInvalidException("Invalid JWT signature");
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            if (!props.issuer().equals(c.getIssuer())) {
                throw new TokenInvalidException("Unexpected JWT issuer");
            }
            if (c.getAudience() == null || !c.getAudience().contains(props.audience())) {
                throw new TokenInvalidException("Unexpected JWT audience");
            }
            if (c.getExpirationTime() == null
                    || c.getExpirationTime().toInstant().isBefore(Instant.now())) {
                throw new TokenExpiredException("JWT is past its expiration");
            }
            UUID userId = UUID.fromString(c.getSubject());
            UUID vendorId = Optional.ofNullable(c.getStringClaim("vendor_id"))
                    .map(UUID::fromString)
                    .orElse(null);
            String email = c.getStringClaim("email");
            boolean mustChangePassword = Boolean.TRUE.equals(c.getBooleanClaim("mcp"));

            // Dual-shape parse: prefer `roles` array, fall back to `role`
            // single string. The result is always a Set<User.Role>.
            Set<User.Role> roles = new HashSet<>();
            Object rolesClaim = c.getClaim("roles");
            if (rolesClaim instanceof List<?> list) {
                for (Object o : list) {
                    if (o == null) continue;
                    try {
                        roles.add(User.Role.valueOf(o.toString()));
                    } catch (IllegalArgumentException ignored) {
                        // Unknown role name in token (e.g., a future role
                        // added by migration after this build). Skip.
                    }
                }
            }
            if (roles.isEmpty()) {
                String roleStr = c.getStringClaim("role");
                if (roleStr != null) {
                    try {
                        roles.add(User.Role.valueOf(roleStr));
                    } catch (IllegalArgumentException ignored) {
                        // Unknown legacy role name; leave the set empty.
                    }
                }
            }
            return new ParsedToken(userId, email, roles, vendorId, mustChangePassword);
        } catch (ParseException | JOSEException e) {
            throw new TokenInvalidException("JWT parse failure: " + e.getMessage());
        }
    }

    /**
     * Parsed token. The role set is always present (possibly empty for
     * legacy tokens whose claim was a name not in the current enum).
     */
    public record ParsedToken(UUID userId,
                              String email,
                              Set<User.Role> roles,
                              UUID vendorId,
                              boolean mustChangePassword) {
    }
}
