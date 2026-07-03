package com.sales.order.auth.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * HS256 JWT parser for order-service. Reads tokens issued by sync-service
 * (same HMAC secret, same issuer, audience=hielo-order) and exposes the
 * subject userId and the optional {@code vendor_id} claim. Signing is
 * intentionally NOT exposed here; sync-service is the only issuer.
 */
@Component
public class JwtService {

    private final JWSVerifier verifier;
    private final JwtProperties props;

    public JwtService(JwtProperties props) {
        try {
            this.verifier = new MACVerifier(props.secret().getBytes(StandardCharsets.UTF_8));
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
            return new ParsedToken(userId, vendorId);
        } catch (ParseException | JOSEException e) {
            throw new TokenInvalidException("JWT parse failure: " + e.getMessage());
        }
    }

    public record ParsedToken(UUID userId, UUID vendorId) {}
}
