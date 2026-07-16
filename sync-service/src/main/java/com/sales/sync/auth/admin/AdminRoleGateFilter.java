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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Authoritative gate for {@code /api/v1/admin/**} endpoints. Lives
 * INSIDE the Spring Security chain (added in
 * {@code SecurityConfig} via {@code addFilterAfter(jwtAuthenticationFilter, ...)})
 * so it runs AFTER {@code JwtAuthenticationFilter} has populated the
 * {@code SecurityContextHolder}.
 *
 * <p>Three layers of verification:
 * <ol>
 *   <li><b>Spring Security authority</b>: the authenticated principal
 *       must carry {@code ROLE_ADMIN} (set by
 *       {@code JwtAuthenticationFilter} from the JWT's {@code roles}
 *       claim).</li>
 *   <li><b>{@code mcp} claim</b>: the access token must NOT carry
 *       {@code mcp=true} (the must-change-password flag). Re-parses the
 *       bearer token to read the claim.</li>
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
 * <p>Owner: change {@code admin-console} PR3 (with a security-chain
 * refactor in PR4 after the BDD IT uncovered the SecurityContext
 * ordering issue).
 */
public class AdminRoleGateFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminRoleGateFilter.class);

    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin/";

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

        // Layer 1: Spring Security authority (set by JwtAuthenticationFilter).
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !hasAuthority(auth, "ROLE_ADMIN")) {
            reject(response, "admin_role_required");
            return;
        }

        // Layer 2: re-parse the bearer token for the mcp claim (the
        // SecurityContext principal is a UUID but the mcp claim is
        // not stored there).
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response, "admin_role_required");
            return;
        }
        String token = header.substring("Bearer ".length()).trim();
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
        if (auth == null) return false;
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
