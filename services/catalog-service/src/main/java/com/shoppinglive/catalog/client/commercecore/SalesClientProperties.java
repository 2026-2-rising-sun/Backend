package com.shoppinglive.catalog.client.commercecore;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code catalog.sales-client.*} 설정.
 *
 * @param baseUrl commercecore-service 주소
 * @param readTimeout 기본 1초 — 목록 화면이 판매정보 때문에 오래 멈추지 않도록 짧게 둔다
 */
@ConfigurationProperties("catalog.sales-client")
public record SalesClientProperties(
        String baseUrl, Duration connectTimeout, Duration readTimeout, String serviceToken) {

    public SalesClientProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(1) : readTimeout;
    }
}
