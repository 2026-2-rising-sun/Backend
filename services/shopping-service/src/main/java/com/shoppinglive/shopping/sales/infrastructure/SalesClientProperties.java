package com.shoppinglive.shopping.sales.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code shopping.sales-client.*} 설정.
 *
 * @param baseUrl commerce-service 주소. http 모드에서만 필요하다
 * @param readTimeout 기본 1초 — 목록 화면이 판매정보 때문에 오래 멈추지 않도록 짧게 둔다
 */
@ConfigurationProperties("shopping.sales-client")
public record SalesClientProperties(Mode mode, String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public SalesClientProperties {
        mode = mode == null ? Mode.STUB : mode;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(1) : readTimeout;
    }

    public enum Mode {
        STUB,
        HTTP
    }
}
