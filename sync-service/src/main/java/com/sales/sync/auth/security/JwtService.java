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
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Nimbus-backed HS256 JWT signer / parser.
 *
 * <p>sign: produces a compact JWS with claims
 * {@code sub}, {@code email}, {@code role}, {@code vendor_id},
 * {@code iss}, {@code aud}, {@code iat}, {@code exp}, {@code jti}.
 *
 * <p>parse: verifies the HMAC signature, the issuer and the audience.
 * Returns a {@link ParsedToken} on success; throws
 * {@link TokenInvalidException} on signature/iss/aud failure,
 * {@link TokenExpiredException} on past {@code exp}.
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

    public String sign(UUID userId, UUID vendorId, String email, User.Role role) {
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
                    .claim("role", role.name());
            if (vendorId != null) {
                b.claim("vendor_id", vendorId.toString());
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
            String roleStr = c.getStringClaim("role");
            User.Role role = roleStr != null ? User.Role.valueOf(roleStr) : User.Role.repartidor;
            return new ParsedToken(userId, email, role, vendorId);
        } catch (ParseException | JOSEException e) {
            throw new TokenInvalidException("JWT parse failure: " + e.getMessage());
        }
    }

    public record ParsedToken(UUID userId, String email, User.Role role, UUID vendorId) {}
}