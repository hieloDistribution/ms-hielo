package com.sales.order.sync;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the {@link RestClient} used by {@link SyncAuthRestClientImpl} for the
 * forward-direction cross-DB integrity call to {@code sync-service}'s
 * {@code /internal/auth/users/{id}}.
 *
 * <p>Per design §3.2: {@link SimpleClientHttpRequestFactory} with the timeouts
 * from {@link SyncAuthProperties} (D-14), the base URL pinned to sync-service,
 * and the {@code Authorization: Bearer <token>} header added by default.
 *
 * <p><strong>Cache deferred</strong>: per design D-03 a Caffeine 5-min positive
 * cache should sit in front of this client. It is intentionally NOT applied
 * in PR-2a to keep the forward-direction slice small; the cache will be
 * layered in a follow-up commit of the same PR if time permits, otherwise a
 * follow-up changeset.
 */
@Configuration
@EnableConfigurationProperties(SyncAuthProperties.class)
public class SyncHttpConfig {

    @Bean
    RestClient.Builder syncRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    RestServiceTokenInjector restServiceTokenInjector(
            SyncAuthProperties props, SyncAuthTokenProvider tokenProvider) {
        return new RestServiceTokenInjector(props, tokenProvider);
    }

    public record RestServiceTokenInjector(SyncAuthProperties props,
                                            SyncAuthTokenProvider tokenProvider) {
        public RestClient buildForBase() {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) props.getConnectTimeout().toMillis());
            factory.setReadTimeout((int) props.getReadTimeout().toMillis());
            return RestClient.builder()
                    .baseUrl(props.getBaseUrl())
                    .defaultHeader("Authorization",
                            "Bearer " + tokenProvider.getServiceToken())
                    .requestFactory(factory)
                    .build();
        }
    }
}