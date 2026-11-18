package com.shoppinglive.payment.client.toss;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("payment.toss")
public record TossProperties(
        String baseUrl,
        String secretKey,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("60s") Duration readTimeout) {

    /** 설정 출력·바인딩 로그에 비밀 키가 남지 않게 한다. */
    @Override
    public String toString() {
        return "TossProperties[baseUrl=%s, secretKey=***, connectTimeout=%s, readTimeout=%s]"
                .formatted(baseUrl, connectTimeout, readTimeout);
    }
}
