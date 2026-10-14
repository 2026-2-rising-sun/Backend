package com.shoppinglive.common.web;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** HTTP 전송 설정만 조립한다. 설정 검증·재시도·응답 계약은 각 호출 서비스가 소유한다. */
public final class ServiceRestClientFactory {
    private ServiceRestClientFactory() {}

    public static RestClient create(
            RestClient.Builder builder,
            String baseUrl,
            String serviceToken,
            Duration connectTimeout,
            Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return builder.clone()
                .baseUrl(baseUrl)
                .defaultHeader("X-Service-Token", serviceToken)
                .requestFactory(factory)
                .build();
    }
}
