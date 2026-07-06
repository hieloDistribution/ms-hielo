package com.sales.sync.auth.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the {@link RestClient} used by {@link OrderInternalVendorsClientImpl}
 * to reach {@code order-service}'s reverse-direction
 * {@code /internal/vendors/by-user/{userId}} endpoint.
 *
 * <p>Per design §3.2: {@link SimpleClientHttpRequestFactory} with timeouts
 * from {@link OrderInternalProperties} (D-14), base URL pinned to
 * order-service, {@code Authorization: Bearer <token>} header added by
 * default.
 *
 * <p><strong>Cache deferred</strong>: a positive Caffeine cache (D-04) may
 * sit in front of this client in a future commit; v1 sends the HTTP probe
 * every {@code delete}.
 */
@Configuration
@EnableConfigurationProperties(OrderInternalProperties.class)
public class OrderInternalHttpConfig {

    @Bean
    RestClient orderInternalRestClient(OrderInternalProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) props.getReadTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getServiceToken())
                .requestFactory(factory)
                .build();
    }
}