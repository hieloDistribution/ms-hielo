package com.sales.sync.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Separate {@link SecurityFilterChain} for {@code /internal/**} endpoints.
 *
 * <p>Authentication is via a static bearer service-token validated against the
 * environment-configured shared secret {@code SYNC_SERVICE_TOKEN}. The token
 * is the same value {@code order-service} uses to authenticate to
 * {@code sync-service} (D-13 / R-3). On a valid token, an
 * {@link Authentication} is set with the {@code SCOPE_internal:read}
 * authority so paths may use {@code @PreAuthorize} (deferred to a follow-up
 * SDD; here it just gates {@code /internal/**} behind any authenticated
 * principal).
 *
 * <p>{@link Order} is set explicitly so this chain is evaluated BEFORE the
 * broader catch-all chain in {@code SecurityConfig} for paths under
 * {@code /internal/**}.
 */
@Configuration
@EnableWebSecurity
public class InternalSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(InternalSecurityConfig.class);

    static final String INTERNAL_READ_AUTHORITY = "SCOPE_internal:read";

    @Bean
    @Order(50) // lower than the default catch-all chain (no @Order ⇒ 100)
    SecurityFilterChain internalSecurityFilterChain(
            HttpSecurity http,
            @Value("${hielo.sync.auth.internal-service-token:dev-shared-secret-change-me}") String expectedToken)
            throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .addFilterBefore(
                        new BearerServiceTokenFilter(expectedToken, internalReadAuthority()),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static List<SimpleGrantedAuthority> internalReadAuthority() {
        return List.of(new SimpleGrantedAuthority(INTERNAL_READ_AUTHORITY));
    }

    /**
     * Validates the {@code Authorization: Bearer <token>} header against the
     * expected value. On a mismatch or missing token, responds {@code 401} with
     * a small JSON body. On a match, sets an {@link Authentication} on the
     * security context so {@code .authenticated()} downstream admits the
     * request.
     */
    static class BearerServiceTokenFilter extends OncePerRequestFilter {

        private static final Logger LOG = LoggerFactory.getLogger(BearerServiceTokenFilter.class);

        private final String expectedToken;
        private final List<SimpleGrantedAuthority> authorities;

        BearerServiceTokenFilter(String expectedToken, List<SimpleGrantedAuthority> authorities) {
            this.expectedToken = expectedToken;
            this.authorities = authorities;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                       HttpServletResponse response,
                                       FilterChain chain) throws ServletException, IOException {
            String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (auth == null || !auth.startsWith("Bearer ")) {
                reject(response, "missing_token");
                return;
            }
            String token = auth.substring("Bearer ".length()).trim();
            if (!expectedToken.equals(token)) {
                reject(response, "invalid_token");
                return;
            }

            Authentication authResult = UsernamePasswordAuthenticationToken.authenticated(
                    "internal-service", null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authResult);
            try {
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        private void reject(HttpServletResponse response, String error) throws IOException {
            LOG.warn("internal endpoint rejected: {}", error);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"" + error + "\"}");
        }
    }
}