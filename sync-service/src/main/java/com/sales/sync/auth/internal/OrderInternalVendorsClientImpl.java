package com.sales.sync.auth.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Default {@link OrderInternalVendorsClient} backed by a {@link RestClient}.
 * Calls {@code GET <order-service>/internal/vendors/by-user/{userId}} once per
 * {@code delete}, parses {@code {hasActiveVendor: true|false}}, and maps
 * transport failures to {@link OrderServiceUnavailable} (which surfaces as a
 * 503 from {@code sync-service.UserService.delete}).
 */
@Component
public class OrderInternalVendorsClientImpl implements OrderInternalVendorsClient {

    private static final Logger log = LoggerFactory.getLogger(OrderInternalVendorsClientImpl.class);

    private final RestClient restClient;

    public OrderInternalVendorsClientImpl(RestClient orderInternalRestClient) {
        this.restClient = orderInternalRestClient;
    }

    @Override
    public boolean hasActiveVendorForUser(UUID userId) {
        try {
            HasActiveVendorResponse body = restClient.get()
                    .uri("/internal/vendors/by-user/{userId}", userId)
                    .retrieve()
                    .body(HasActiveVendorResponse.class);

            if (body == null) {
                log.warn("order-service returned a 200 with an empty body for userId={}", userId);
                return false;
            }
            return body.hasActiveVendor();
        } catch (ResourceAccessException ex) {
            // Connect / read timeout, connection refused
            throw new OrderServiceUnavailable(
                    "order-service unreachable while consulting active-vendor for " + userId, ex);
        } catch (HttpServerErrorException ex) {
            throw new OrderServiceUnavailable(
                    "order-service returned " + ex.getStatusCode().value()
                            + " while consulting active-vendor for " + userId, ex);
        } catch (HttpClientErrorException ex) {
            // 4xx other than auth — treat as blueprint mismatch / fail-closed
            throw new OrderServiceUnavailable(
                    "order-service returned " + ex.getStatusCode().value()
                            + " while consulting active-vendor for " + userId, ex);
        }
    }

    @Override
    public void createVendor(UUID id, UUID userId, String displayName, String email, String phone) {
        try {
            restClient.post()
                    .uri("/internal/vendors")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(new InternalVendorDto(id, userId, displayName, email, phone))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            throw new OrderServiceUnavailable(
                    "order-service unreachable or failed while provisioning vendor for " + userId, ex);
        }
    }

    private record InternalVendorDto(UUID id, UUID userId, String displayName, String email, String phone) {}
}