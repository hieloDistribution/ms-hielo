package com.sales.sync.auth.service;

import com.sales.sync.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupServiceTest {

    @Mock
    private RefreshTokenRepository tokens;

    @InjectMocks
    private RefreshTokenCleanupService cleanupService;

    @Test
    void cleanupExpiredTokens_callsRepositoryMethod() {
        when(tokens.deleteExpiredTokens(any(Instant.class))).thenReturn(5);

        cleanupService.cleanupExpiredTokens();

        verify(tokens, times(1)).deleteExpiredTokens(any(Instant.class));
    }
}
