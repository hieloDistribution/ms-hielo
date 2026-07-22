package com.sales.sync.auth.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Default {@link OrderAssignmentCascadeClient} backed by the shared internal
 * {@link RestClient}. POSTs to {@code /internal/users/{userId}/cascade-close-vendor-assignments}
 * and parses the {@code {assignmentsClosed: N}} response.
 */
@Component
public class OrderAssignmentCascadeClientImpl implements OrderAssignmentCascadeClient {

    private static final Logger log = LoggerFactory.getLogger(OrderAssignmentCascadeClientImpl.class);

    private final RestClient restClient;

    public OrderAssignmentCascadeClientImpl(RestClient orderInternalRestClient) {
        this.restClient = orderInternalRestClient;
    }

    @Override
    public int cascadeCloseForUser(UUID userId) {
        try {
            CascadeResponse body = restClient.post()
                    .uri("/internal/users/{userId}/cascade-close-vendor-assignments", userId)
                    .retrieve()
                    .body(CascadeResponse.class);
            int closed = body == null ? 0 : body.assignmentsClosed();
            log.info("cascade-close for userId={} closed {} assignments", userId, closed);
            return closed;
        } catch (ResourceAccessException ex) {
            // Network / timeout: log + re-throw as OrderServiceUnavailable
            // so the caller (AdminService.deactivate) can decide what to do.
            throw new OrderServiceUnavailable(
                    "order-service unreachable while cascade-closing assignments for " + userId, ex);
        } catch (HttpServerErrorException ex) {
            throw new OrderServiceUnavailable(
                    "order-service returned " + ex.getStatusCode().value()
                            + " during cascade-close for " + userId, ex);
        }
    }

    /** Response shape from order-service's {@code CascadeResponse} record. */
    public record CascadeResponse(UUID userId, int assignmentsClosed) {}
}