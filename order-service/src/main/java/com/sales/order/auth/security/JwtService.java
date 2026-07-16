package com.sales.order.auth.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
 * HS256 JWT parser for order-service. Reads tokens issued by
 * sync-service (same HMAC secret, same issuer, audience=hielo-order)
 * and exposes the user id, the multi-role set, and the optional
 * {@code vendor_id} claim.
 *
 * <p>Dual-shape parsing (PR3 of change {@code admin-console}):
 * prefers the {@code roles} array claim (authoritative post-PR5);
 * falls back to the legacy {@code role} single-string claim during
 * the cut-over window. The result is always a {@code Set<Role>}.
 *
 * <p>Order-service is a resource server; it does NOT sign tokens. The
 * {@code sign} method is intentionally absent.
 *
 * <p>Owner: change {@code admin-console} PR3.
 */
@Component
public class JwtService {

    private final JWSVerifier verifier;
    private final JwtProperties props;

    public JwtService(JwtProperties props) {
        try {
            this.verifier = new MACVerifier(
                    props.secret().getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot initialise HMAC verifier", e);
        }
        this.props = props;
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
            boolean mustChangePassword = Boolean.TRUE.equals(c.getBooleanClaim("mcp"));

            // Dual-shape parse: prefer `roles` array, fall back to `role`.
            Set<Role> roles = new HashSet<>();
            Object rolesClaim = c.getClaim("roles");
            if (rolesClaim instanceof List<?> list) {
                for (Object o : list) {
                    if (o == null) continue;
                    try {
                        roles.add(Role.valueOf(o.toString()));
                    } catch (IllegalArgumentException ignored) {
                        // Unknown role name in token; skip.
                    }
                }
            }
            if (roles.isEmpty()) {
                String roleStr = c.getStringClaim("role");
                if (roleStr != null) {
                    try {
                        roles.add(Role.valueOf(roleStr));
                    } catch (IllegalArgumentException ignored) {
                        // Unknown legacy role name.
                    }
                }
            }
            return new ParsedToken(userId, vendorId, roles, mustChangePassword);
        } catch (ParseException | JOSEException e) {
            throw new TokenInvalidException("JWT parse failure: " + e.getMessage());
        }
    }

    /**
     * Parsed token. The role set is always present (possibly empty for
     * legacy tokens whose claim was a name not in the current enum).
     */
    public record ParsedToken(UUID userId, UUID vendorId, Set<Role> roles, boolean mustChangePassword) {}
}
