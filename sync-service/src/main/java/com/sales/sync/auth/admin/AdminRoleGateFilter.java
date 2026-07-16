package com.sales.sync.auth.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sales.sync.auth.security.JwtService;
import com.sales.sync.auth.security.TokenExpiredException;
import com.sales.sync.auth.security.TokenInvalidException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Authoritative gate for {@code /api/v1/admin/**} endpoints.
 *
 * <p>Three layers of verification:
 * <ol>
 *   <li><b>JWT role claim</b>: the access token must carry
 *       {@code roles} array (or legacy {@code role} string) containing
 *       {@code admin}. Enforced via Spring Security's
 *       {@code ROLE_ADMIN} authority set by
 *       {@link com.sales.sync.auth.security.JwtAuthenticationFilter}.</li>
 *   <li><b>{@code mcp} claim</b>: the access token must NOT carry
 *       {@code mcp=true} (the must-change-password flag). A bootstrap
 *       admin must rotate their credential before reaching
 *       {@code /api/v1/admin/**}. Re-parses the bearer token to read
 *       the claim.</li>
 *   <li><b>DB re-query</b>: the user row must currently be
 *       {@code active=true} and still carry the {@code admin} role.
 *       This is the "authority rule for elevated actions" half of the
 *       dual-authority contract: a deactivated admin with a still-valid
 *       token MUST NOT reach admin endpoints.</li>
 * </ol>
 *
 * <p>Any failure -> 403 with body {@code {"error":"admin_role_required"}}
 * or {@code {"error":"must_change_password_required"}}.
 *
 * <p>Owner: change {@code admin-console} PR3.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class AdminRoleGateFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminRoleGateFilter.class);

    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin/";

    private static final String AUTH_HEADER = HttpHeaders.AUTHORIZATION;
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final RoleRequeryService roleRequery;
    private final ObjectMapper objectMapper;

    public AdminRoleGateFilter(JwtService jwtService,
                               RoleRequeryService roleRequery,
                               ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.roleRequery = roleRequery;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(ADMIN_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // Layer 1: Spring Security authority set by JwtAuthenticationFilter.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !hasAuthority(auth, "ROLE_ADMIN")) {
            reject(response, "admin_role_required");
            return;
        }

        // Layer 2: re-parse the bearer token for the mcp claim.
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER)) {
            // Spring Security allowed the request (e.g., anonymous route
            // on a non-admin path that this filter should not have hit),
            // but for /api/v1/admin/** we require a bearer token.
            reject(response, "admin_role_required");
            return;
        }
        String token = header.substring(BEARER.length()).trim();
        JwtService.ParsedToken parsed;
        try {
            parsed = jwtService.parse(token);
        } catch (TokenInvalidException | TokenExpiredException ex) {
            reject(response, "admin_role_required");
            return;
        }
        if (parsed.mustChangePassword()) {
            reject(response, "must_change_password_required");
            return;
        }

        // Layer 3: DB re-query for active + admin.
        UUID userId = (UUID) auth.getPrincipal();
        if (!roleRequery.isActiveAdmin(userId)) {
            reject(response, "admin_role_required");
            return;
        }

        chain.doFilter(request, response);
    }

    private static boolean hasAuthority(Authentication auth, String authority) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (authority.equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private void reject(HttpServletResponse response, String errorCode) throws IOException {
        log.warn("admin gate rejected: {}", errorCode);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("error", errorCode));
    }
}
