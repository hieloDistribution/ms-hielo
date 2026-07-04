package com.sales.order.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Direct unit test for {@link InternalSecurityConfig.BearerServiceTokenFilter}
 * — validates the bearer-token validation pipeline in isolation, without
 * booting Spring Security.
 */
class InternalSecurityConfigFilterTest {

    private static final String TOKEN = "expected-token-abc";

    private InternalSecurityConfig.BearerServiceTokenFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new InternalSecurityConfig.BearerServiceTokenFilter(TOKEN,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_internal:read")));
        chain = Mockito.mock(FilterChain.class);
        SecurityContextHolder.clearContext(); // ensure no leftover auth
    }

    @Test
    void noHeader_rejectsWith401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/auth/users/123");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("missing_token");
        verify(chain, never()).doFilter(Mockito.any(), Mockito.any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void wrongHeader_rejectsWith401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/auth/users/123");
        req.addHeader("Authorization", "Bearer not-the-token");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("invalid_token");
        verify(chain, never()).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    void validToken_setsAuthentication_andContinuesChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/auth/users/123");
        req.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse res = new MockHttpServletResponse();

        // Capture the Authentication that was active INSIDE the chain — the filter
        // clears the SecurityContextHolder in its finally block, so by the time
        // we return from doFilter() the context is empty.
        Authentication[] capturedAuth = new Authentication[1];
        int[] invocations = new int[1];
        FilterChain capturing = (req2, res2) -> {
            invocations[0]++;
            capturedAuth[0] = SecurityContextHolder.getContext().getAuthentication();
        };

        filter.doFilter(req, res, capturing);

        assertThat(invocations[0]).isEqualTo(1);
        assertThat(capturedAuth[0]).isNotNull();
        assertThat(capturedAuth[0].getAuthorities())
                .extracting("authority")
                .containsExactly("SCOPE_internal:read");
    }
}