package com.sales.sync.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Reads {@code Authorization: Bearer <token>}, validates it via
 * {@link JwtService}, populates the {@link SecurityContextHolder} and
 * stashes the userId/role/vendorId into the request-scoped {@link AuthContext}.
 * On any token failure the filter is silent — the {@code authenticationEntryPoint}
 * in {@link SecurityConfig} emits the canonical 401 body.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    static final String AUTH_FAIL_ATTR = "jwt.auth.failure";

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AuthContext authContext;

    public JwtAuthenticationFilter(JwtService jwtService, AuthContext authContext) {
        this.jwtService = jwtService;
        this.authContext = authContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            try {
                JwtService.ParsedToken parsed = jwtService.parse(token);
                var authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + parsed.role().name().toUpperCase()));
                var auth = new UsernamePasswordAuthenticationToken(
                        parsed.userId(),
                        null,
                        authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
                authContext.set(parsed);
            } catch (TokenExpiredException ex) {
                log.debug("JWT rejected (expired) for {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
                request.setAttribute(AUTH_FAIL_ATTR, "token_expired");
                SecurityContextHolder.clearContext();
                authContext.clear();
            } catch (TokenInvalidException ex) {
                log.warn("JWT rejected (invalid) for {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
                request.setAttribute(AUTH_FAIL_ATTR, "token_invalid");
                SecurityContextHolder.clearContext();
                authContext.clear();
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            authContext.clear();
        }
    }
}