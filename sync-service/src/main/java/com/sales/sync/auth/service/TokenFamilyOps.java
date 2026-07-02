package com.sales.sync.auth.service;

import com.sales.sync.auth.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Family-burn operation runs in {@link Propagation#REQUIRES_NEW} so the
 * SQL update commits even when the caller's transaction rolls back (e.g.
 * because the rotation flow threw {@code TokenRevokedException}).
 */
@Service
public class TokenFamilyOps {

    private final RefreshTokenRepository tokens;

    public TokenFamilyOps(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int burn(UUID family) {
        return tokens.burnFamily(family);
    }
}
