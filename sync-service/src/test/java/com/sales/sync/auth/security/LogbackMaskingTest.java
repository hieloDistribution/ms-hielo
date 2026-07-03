package com.sales.sync.auth.security;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.sales.sync.auth.support.BearerMaskingConverter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogbackMaskingTest {

    private final BearerMaskingConverter converter = new BearerMaskingConverter();

    @Test
    void replaces_bearer_token_in_message() {
        ILoggingEvent ev = mock(ILoggingEvent.class);
        when(ev.getMessage())
                .thenReturn("some request line: Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.abc");

        String masked = converter.convert(ev);

        assertThat(masked).contains("Authorization: Bearer ***");
        assertThat(masked).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
    }

    @Test
    void passes_through_message_without_authorization_header() {
        ILoggingEvent ev = mock(ILoggingEvent.class);
        when(ev.getMessage()).thenReturn("GET /actuator/health 200 OK");

        String masked = converter.convert(ev);

        assertThat(masked).isEqualTo("GET /actuator/health 200 OK");
    }

    @Test
    void null_message_returns_empty_string() {
        ILoggingEvent ev = mock(ILoggingEvent.class);
        when(ev.getMessage()).thenReturn(null);

        String masked = converter.convert(ev);

        assertThat(masked).isEmpty();
    }
}
