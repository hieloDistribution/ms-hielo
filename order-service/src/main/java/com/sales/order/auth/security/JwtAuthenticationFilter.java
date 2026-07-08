package com.sales.order.auth.security;

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
import java.util.List;
import java.util.Optional;

/**
 * Reads {@code Authorization: Bearer <token>}, validates it via
 * {@link JwtService}, populates the {@link SecurityContextHolder} and
 * stashes the {@code vendor_id} claim into the request-scoped
 * {@link VendorContext} and {@link DriverContext}. On any token failure the filter is silent.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final VendorContext vendorContext;
    private final DriverContext driverContext;

    public JwtAuthenticationFilter(JwtService jwtService, VendorContext vendorContext, DriverContext driverContext) {
        this.jwtService = jwtService;
        this.vendorContext = vendorContext;
        this.driverContext = driverContext;
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
                var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                if ("admin".equalsIgnoreCase(parsed.role()) || "ADMIN".equalsIgnoreCase(parsed.role())) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                }
                var auth = new UsernamePasswordAuthenticationToken(
                        parsed.userId(),
                        null,
                        authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
                vendorContext.set(Optional.ofNullable(parsed.vendorId()));
                driverContext.set(Optional.ofNullable(parsed.vendorId()));
            } catch (TokenInvalidException | TokenExpiredException ex) {
                // Don't write a response here; let AuthorizationFilter deny
                // and the entry point emit the canonical 401 body.
                SecurityContextHolder.clearContext();
                vendorContext.set(Optional.empty());
                driverContext.set(Optional.empty());
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // Defensive clear; @RequestScope would also clear, but explicit
            // is safer when filters are reused across requests.
            vendorContext.set(Optional.empty());
            driverContext.set(Optional.empty());
        }
    }
}
