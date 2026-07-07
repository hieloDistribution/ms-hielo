package com.sales.sync.auth.service;

import com.sales.sync.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RefreshTokenCleanupService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupService.class);
    private final RefreshTokenRepository tokens;

    public RefreshTokenCleanupService(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Scheduled(cron = "${auth.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public void cleanupExpiredTokens() {
        log.info("Starting expired refresh token cleanup...");
        int deleted = tokens.deleteExpiredTokens(Instant.now());
        log.info("Refresh token cleanup completed. Deleted {} rows.", deleted);
    }
}
