package com.shoppinglive.commerce.client.shopping;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 운영 어댑터는 항상 HTTP를 사용한다. URL은 실행 환경에서 반드시 지정한다. */
@ConfigurationProperties("commerce.shopping-client")
public record ShoppingClientProperties(
        String baseUrl, Duration connectTimeout, Duration readTimeout, String serviceToken) {

    public ShoppingClientProperties {
        connectTimeout = boundedTimeout(connectTimeout);
        readTimeout = boundedTimeout(readTimeout);
    }

    private static Duration boundedTimeout(Duration timeout) {
        Duration value = timeout == null ? Duration.ofSeconds(1) : timeout;
        if (value.toMillis() < 1 || value.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Shopping 호출 제한 시간은 1~2147483647밀리초여야 합니다.");
        }
        return value;
    }
}
