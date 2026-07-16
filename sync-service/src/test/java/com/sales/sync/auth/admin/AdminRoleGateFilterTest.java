package com.sales.sync.auth.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.security.JwtService;
import com.sales.sync.auth.security.TokenInvalidException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for {@link AdminRoleGateFilter}. The filter
 * runs INSIDE the Spring Security chain (after JwtAuthenticationFilter
 * populates the SecurityContext), so the test sets up the
 * SecurityContext explicitly.
 *
 * <p>Owner: change {@code admin-console} PR3 + PR4.
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

    /** Set up an authenticated admin principal in the SecurityContext. */
    private void primeAdminContext(UUID userId) {
        var auth = new UsernamePasswordAuthenticationToken(
                userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** Set up an authenticated non-admin (cliente) principal. */
    private void primeClienteContext(UUID userId) {
        var auth = new UsernamePasswordAuthenticationToken(
                userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_CLIENTE")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private JwtService.ParsedToken tokenFor(UUID userId, Set<User.Role> roles, boolean mcp) {
        return new JwtService.ParsedToken(userId, "u@hielo.local", roles, null, mcp);
    }

    private MockHttpServletRequest adminReq(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        if (token != null) {
            req.addHeader("Authorization", "Bearer " + token);
        }
        return req;
    }

    @Test
    @DisplayName("happy path: admin SecurityContext + mcp=false + active in DB -> chain continues")
    void admin_path_with_valid_token_passes() throws Exception {
        UUID userId = UUID.randomUUID();
        primeAdminContext(userId);
        when(jwtService.parse("good-token"))
                .thenReturn(tokenFor(userId, Set.of(User.Role.admin), false));
        when(roleRequery.isActiveAdmin(userId)).thenReturn(true);

        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(adminReq("good-token"), res, chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("non-admin path: filter passes through without checks")
    void non_admin_path_is_ignored() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(jwtService, never()).parse(any());
        verify(roleRequery, never()).isActiveAdmin(any());
    }

    @Test
    @DisplayName("no SecurityContext on admin path -> 403 admin_role_required")
    void no_security_context_on_admin_path() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(adminReq("any-token"), res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("\"error\":\"admin_role_required\"");
    }

    @Test
    @DisplayName("caller has client role only -> 403 admin_role_required")
    void non_admin_caller_gets_403() throws Exception {
        UUID userId = UUID.randomUUID();
        primeClienteContext(userId);

        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(adminReq("client-token"), res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("\"error\":\"admin_role_required\"");
    }

    @Test
    @DisplayName("deactivated admin (DB re-query returns false) -> 403 admin_role_required")
    void deactivated_admin_gets_403() throws Exception {
        UUID userId = UUID.randomUUID();
        primeAdminContext(userId);
        when(jwtService.parse("admin-token"))
                .thenReturn(tokenFor(userId, Set.of(User.Role.admin), false));
        when(roleRequery.isActiveAdmin(userId)).thenReturn(false);

        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(adminReq("admin-token"), res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("mcp=true admin -> 403 must_change_password_required")
    void mcp_admin_gets_403_for_admin_path() throws Exception {
        UUID userId = UUID.randomUUID();
        primeAdminContext(userId);
        when(jwtService.parse("bootstrap-token"))
                .thenReturn(tokenFor(userId, Set.of(User.Role.admin), true));

        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(adminReq("bootstrap-token"), res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString())
                .contains("\"error\":\"must_change_password_required\"");
        verify(roleRequery, never()).isActiveAdmin(any());
    }

    @Test
    @DisplayName("invalid bearer token (signature fails) -> 403 admin_role_required")
    void invalid_bearer_token_rejected() throws Exception {
        UUID userId = UUID.randomUUID();
        primeAdminContext(userId);
        when(jwtService.parse("bad-token"))
                .thenThrow(new TokenInvalidException("bad"));

        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(adminReq("bad-token"), res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("missing Authorization header on admin path -> 403 admin_role_required")
    void missing_authorization_header_on_admin_path_rejected() throws Exception {
        UUID userId = UUID.randomUUID();
        primeAdminContext(userId);

        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(adminReq(null), res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(403);
    }
}
