package com.sales.order.sync;

import com.sales.order.sync.exceptions.SyncAuthMisconfiguredException;
import com.sales.order.sync.exceptions.SyncServiceUnavailableException;
import com.sales.order.sync.exceptions.UnknownUserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Optional;
import java.util.UUID;

/**
 * Production {@link SyncAuthClient} backed by a Spring {@link RestClient}.
 *
 * <p>Makes {@code GET <sync>/internal/auth/users/{id}} on every call. Maps
 * responses per design §3.1:
 * <ul>
 *   <li>200 OK → parse the body into a {@link RemoteUser}; if {@code locked}
 *       or {@code deletedAt != null} the user is treated as "unknown" (empty
 *       {@link Optional}).</li>
 *   <li>404 Not Found → empty Optional (no exception; the caller throws
 *       {@link UnknownUserException}).</li>
 *   <li>401 Unauthorized → {@link SyncAuthMisconfiguredException}.</li>
 *   <li>5xx / timeout / ResourceAccessException →
 *       {@link SyncServiceUnavailableException}.</li>
 * </ul>
 *
 * <p><strong>No cache</strong> in PR-2a — every call goes over the wire. The
 * positive Caffeine cache (D-03 / D-04) lands in a follow-up commit.
 */
@Component
public class SyncAuthRestClientImpl implements SyncAuthClient {

    private static final Logger log = LoggerFactory.getLogger(SyncAuthRestClientImpl.class);

    private final SyncHttpConfig.RestServiceTokenInjector injectorBean;

    public SyncAuthRestClientImpl(SyncHttpConfig.RestServiceTokenInjector injectorBean) {
        this.injectorBean = injectorBean;
    }

    @Override
    public Optional<RemoteUser> getUserById(UUID userId) {
        try {
            RemoteUser user = injectorBean.buildForBase().get()
                    .uri("/internal/auth/users/{id}", userId)
                    .retrieve()
                    .body(RemoteUser.class);

            if (user == null) {
                return Optional.empty(); // defensive: 200 with empty body
            }
            if (user.locked() || user.deletedAt() != null) {
                // Treated as "unknown" per design 3.1 step 5: a locked or
                // deleted user cannot back a Vendor write.
                log.debug("sync-service returned user {} with locked={} deletedAt={}",
                        user.id(), user.locked(), user.deletedAt());
                return Optional.empty();
            }
            return Optional.of(user);
        } catch (HttpClientErrorException.NotFound notFound) {
            // 404 → empty Optional. Caller throws UnknownUserException.
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized unauthorized) {
            throw new SyncAuthMisconfiguredException(
                    "sync-service rejected the service token (401)");
        } catch (ResourceAccessException ex) {
            // Connect / read timeout, connection refused
            throw new SyncServiceUnavailableException("sync-service unreachable", ex);
        } catch (HttpServerErrorException ex) {
            // 5xx
            throw new SyncServiceUnavailableException(
                    "sync-service returned " + ex.getStatusCode().value(), ex);
        }
    }
}