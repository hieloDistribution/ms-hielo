package com.sales.sync.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads {@code Authorization: Bearer <token>}, validates it via
 * {@link JwtService}, populates the {@link SecurityContextHolder} and
 * stashes the {@code vendor_id} claim into {@link VendorContext}.
 *
 * <p>Authoritative role set comes from {@link JwtService.ParsedToken#roles()}
 * (carrying the multi-role claim from the JWT, dual-shape-aware). Each
 * role in the set becomes a {@code ROLE_*} Spring Security authority.
 *
 * <p>On any token failure the filter is silent — the
 * {@code authenticationEntryPoint} in {@link SecurityConfig} emits
 * the canonical 401 body.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final VendorContext vendorContext;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   VendorContext vendorContext) {
        this.jwtService = jwtService;
        this.vendorContext = vendorContext;
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
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                for (com.sales.sync.auth.model.User.Role role : parsed.roles()) {
                    String r = role.name().toLowerCase(Locale.ROOT);
                    if ("admin".equals(r)) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    } else if ("repartidor".equals(r)) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_REPARTIDOR"));
                    } else if ("cliente".equals(r)) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_CLIENTE"));
                    }
                }
                var auth = new UsernamePasswordAuthenticationToken(
                        parsed.userId(),
                        null,
                        authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
                vendorContext.set(Optional.ofNullable(parsed.vendorId()));
            } catch (TokenInvalidException | TokenExpiredException ex) {
                SecurityContextHolder.clearContext();
                vendorContext.set(Optional.empty());
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            vendorContext.set(Optional.empty());
        }
    }
}
