package com.sales.order.auth.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private VendorContext vendorContext;
    private DriverContext driverContext;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        vendorContext = new VendorContext();
        driverContext = new DriverContext();
        filter = new JwtAuthenticationFilter(jwtService, vendorContext, driverContext);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missing_authorization_header_passes_through_anonymously() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orders/catalog");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(vendorContext.get()).isEmpty();
        assertThat(driverContext.get()).isEmpty();
    }

    @Test
    void valid_token_populates_security_context_and_vendor_context() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        when(jwtService.parse("good-token"))
                .thenReturn(new JwtService.ParsedToken(userId, vendorId, null));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orders/catalog");
        req.addHeader("Authorization", "Bearer good-token");
        MockHttpServletResponse res = new MockHttpServletResponse();

        // Capture the state observed inside the chain, before the filter's
        // finally block resets VendorContext and DriverContext to Optional.empty().
        final VendorContext ctxRef = vendorContext;
        final DriverContext driverCtxRef = driverContext;
        final var capturedVendorId = new java.util.concurrent.atomic.AtomicReference<Optional<UUID>>();
        final var capturedDriverId = new java.util.concurrent.atomic.AtomicReference<Optional<UUID>>();
        final var capturedAuth = new java.util.concurrent.atomic.AtomicReference<org.springframework.security.core.Authentication>();
        FilterChain chain = (request, response) -> {
            capturedVendorId.set(ctxRef.get());
            capturedDriverId.set(driverCtxRef.get());
            capturedAuth.set(SecurityContextHolder.getContext().getAuthentication());
        };

        filter.doFilter(req, res, chain);

        assertThat(capturedAuth.get()).isNotNull();
        assertThat(capturedAuth.get().getPrincipal()).isEqualTo(userId);
        assertThat(capturedVendorId.get()).contains(vendorId);
        assertThat(capturedDriverId.get()).contains(vendorId);
    }

    @Test
    void invalid_token_does_not_set_security_context_and_passes_through() throws Exception {
        when(jwtService.parse("bad-token"))
                .thenThrow(new TokenInvalidException("bad"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orders/catalog");
        req.addHeader("Authorization", "Bearer bad-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(vendorContext.get()).isEmpty();
        assertThat(driverContext.get()).isEmpty();
        verify(chain).doFilter(req, res);
    }

    @Test
    void non_bearer_authorization_header_is_ignored() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orders/catalog");
        req.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(jwtService, never()).parse(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
