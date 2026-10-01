package com.shoppinglive.commerce.shopping.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 운영 어댑터는 항상 HTTP를 사용한다. URL은 실행 환경에서 반드시 지정한다. */
@ConfigurationProperties("commerce.shopping-client")
public record ShoppingClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout, String serviceToken) {

    public ShoppingClientProperties {
        connectTimeout = boundedTimeout(connectTimeout);
        readTimeout = boundedTimeout(readTimeout);
    }

    private static Duration boundedTimeout(Duration timeout) {
        Duration value = timeout == null ? Duration.ofSeconds(1) : timeout;
        if (value.toMillis() < 1 || value.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("shopping timeout must be between 1ms and 2147483647ms");
        }
        return value;
    }
}
