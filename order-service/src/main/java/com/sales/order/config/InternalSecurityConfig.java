package com.sales.order.config;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Separate {@link SecurityFilterChain} for {@code /internal/**} endpoints on
 * order-service (PR-2b reverse direction).
 *
 * <p>Authentication: static bearer service-token validated against
 * {@code hielo.order.internal.internal-service-token} (env var
 * {@code SYNC_SERVICE_TOKEN}). No default is supplied: a missing env var
 * leaves {@code expectedToken} empty and the filter rejects every bearer
 * token — fail-closed at runtime rather than boot. On a valid token, an
 * {@link Authentication} with {@code SCOPE_internal:read} is set on the
 * context so {@code .authenticated()} downstream admits the call.
 * Critically: this chain does NOT participate in
 * {@link com.sales.order.auth.security.JwtAuthenticationFilter} — the
 * internal endpoint must remain reachable from sync-service with only the
 * static token, no JWT.
 */
@Configuration
@EnableWebSecurity
public class InternalSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(InternalSecurityConfig.class);
    static final String INTERNAL_READ_AUTHORITY = "SCOPE_internal:read";

    @Bean
    @Order(50) // higher priority than the default JWT-validated catch-all chain
    SecurityFilterChain internalSecurityFilterChain(
            HttpSecurity http,
            @Value("${hielo.order.internal.internal-service-token:}") String expectedToken)
            throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .addFilterBefore(
                        new BearerServiceTokenFilter(expectedToken,
                                List.of(new SimpleGrantedAuthority(INTERNAL_READ_AUTHORITY))),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

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