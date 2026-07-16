package com.sales.sync.auth.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.security.JwtService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for {@link AdminRoleGateFilter}. Exercises the
 * three layers (Spring Security authority, mcp claim, DB re-query)
 * across the three failure modes plus the happy path.
 *
 * <p>Owner: change {@code admin-console} PR3. Spec reference: R2 of
 * {@code openspec/changes/admin-console/specs/admin-roles/spec.md}.
 */
@ExtendWith(MockitoExtension.class)
class AdminRoleGateFilterTest {

    @Mock private JwtService jwtService;
    @Mock private RoleRequeryService roleRequery;

    private AdminRoleGateFilter filter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        filter = new AdminRoleGateFilter(jwtService, roleRequery, objectMapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Helper: build an admin-shaped SecurityContext with roleRequery mocked active. */
    private void primeAdminSecurityContext(UUID userId) {
        var auth = new UsernamePasswordAuthenticationToken(
                userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(roleRequery.isActiveAdmin(eq(userId))).thenReturn(true);
    }

    @Test
    @DisplayName("happy path: admin token + mcp=false + active in DB -> chain continues")
    void admin_path_with_valid_token_passes() throws Exception {
        UUID userId = UUID.randomUUID();
        primeAdminSecurityContext(userId);
        when(jwtService.parse("good-token"))
                .thenReturn(new JwtService.ParsedToken(userId, "u@hielo.local",
                        Set.of(User.Role.admin), null, false));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        req.addHeader("Authorization", "Bearer good-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200); // not 403 — chain wrote default 200
    }

    @Test
    @DisplayName("non-admin path: filter passes through without checks")
    void non_admin_path_is_ignored() throws Exception {
        UUID userId = UUID.randomUUID();
        // anonymous caller
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(jwtService, never()).parse(any());
        verify(roleRequery, never()).isActiveAdmin(any());
    }

    @Test
    @DisplayName("R2 scenario: caller has client role only -> 403 admin_role_required")
    void non_admin_caller_gets_403() throws Exception {
        UUID userId = UUID.randomUUID();
        // SecurityContext has only ROLE_USER (no ROLE_ADMIN)
        var auth = new UsernamePasswordAuthenticationToken(
                userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_CLIENTE")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        req.addHeader("Authorization", "Bearer client-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("\"error\":\"admin_role_required\"");
    }

    @Test
    @DisplayName("R2 scenario: token has roles=[admin] but user is deactivated in DB -> 403 admin_role_required")
    void deactivated_admin_gets_403() throws Exception {
        UUID userId = UUID.randomUUID();
        // SecurityContext says admin (token had admin role).
        var auth = new UsernamePasswordAuthenticationToken(
                userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        // Token parsing succeeds.
        when(jwtService.parse("admin-token"))
                .thenReturn(new JwtService.ParsedToken(userId, "u@hielo.local",
                        Set.of(User.Role.admin), null, false));
        // But DB re-query says no — user is deactivated.
        when(roleRequery.isActiveAdmin(eq(userId))).thenReturn(false);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        req.addHeader("Authorization", "Bearer admin-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("\"error\":\"admin_role_required\"");
    }

    @Test
    @DisplayName("B2 scenario: bootstrap admin with mcp=true -> 403 must_change_password_required")
    void mcp_admin_gets_403_for_admin_path_but_passes_for_other_paths() throws Exception {
        UUID userId = UUID.randomUUID();
        // Set up only the bits the filter actually consults for this path:
        // SecurityContext + JWT mcp. roleRequery is never reached because
        // the filter rejects at the mcp check before the DB re-query.
        var auth = new UsernamePasswordAuthenticationToken(
                userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(jwtService.parse("bootstrap-token"))
                .thenReturn(new JwtService.ParsedToken(userId, "bootstrap@hielo.local",
                        Set.of(User.Role.admin), null, true));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        req.addHeader("Authorization", "Bearer bootstrap-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString())
                .contains("\"error\":\"must_change_password_required\"");
    }

    @Test
    @DisplayName("B2 scenario: mcp=true admin calling non-admin path -> filter ignores the request entirely")
    void mcp_admin_passes_through_non_admin_paths() throws Exception {
        UUID userId = UUID.randomUUID();
        // Note: JwtAuthenticationFilter is what blocks mcp=true callers
        // for non-admin paths in real life (via @PreAuthorize). Our gate
        // filter is path-scoped, so non-admin paths pass through here
        // without inspecting the auth context or the bearer token.
        // primeAdminSecurityContext() not called here on purpose — the
        // gate doesn't touch the SecurityContextHolder for non-admin paths.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/users/me");
        req.addHeader("Authorization", "Bearer bootstrap-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(jwtService, never()).parse(any());
    }

    @Test
    @DisplayName("missing Authorization header on admin path -> 403 admin_role_required")
    void missing_authorization_header_on_admin_path_rejected() throws Exception {
        UUID userId = UUID.randomUUID();
        // SecurityContext is empty (no auth). Filter rejects at layer 1.
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("\"error\":\"admin_role_required\"");
    }

    @Test
    @DisplayName("invalid bearer token (signature fails) -> 403 admin_role_required")
    void invalid_bearer_token_rejected() throws Exception {
        UUID userId = UUID.randomUUID();
        var auth = new UsernamePasswordAuthenticationToken(
                userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(jwtService.parse("bad-token"))
                .thenThrow(new com.sales.sync.auth.security.TokenInvalidException("bad"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        req.addHeader("Authorization", "Bearer bad-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
    }
}
