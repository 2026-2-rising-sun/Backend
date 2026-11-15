package com.shoppinglive.payment.client.toss;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 토스 HTTP 연결 설정. 공통 ServiceRestClientFactory는 내부 서비스 토큰 헤더를 붙이므로 쓰지 않는다.
 *
 * <p>테스트가 MockRestServiceServer를 연결할 수 있게 RestClient를 만들기 전의 Builder를 돌려준다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TossProperties.class)
public class TossClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TossClientConfiguration.class);

    @Bean
    TossPaymentsClient tossPaymentsClient(RestClient.Builder builder, TossProperties properties) {
        if (!StringUtils.hasText(properties.secretKey())) {
            log.warn(
                    "payment.toss.secret-key(PAYMENT_TOSS_SECRET_KEY)가 비어 있어 토스를 호출하지 않고 모든 호출을 NotSent로 돌려준다.");
            return TossPaymentsClient.withoutSecretKey();
        }
        return new TossPaymentsClient(configure(builder.clone(), properties).build());
    }

    static RestClient.Builder configure(RestClient.Builder builder, TossProperties properties) {
        if (!StringUtils.hasText(properties.baseUrl())) {
            throw new IllegalStateException(
                    "payment.toss.base-url(PAYMENT_TOSS_BASE_URL) 설정이 필요합니다.");
        }
        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        String credentials = properties.secretKey() + ":";
        return builder.baseUrl(properties.baseUrl())
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Basic "
                                + Base64.getEncoder()
                                        .encodeToString(
                                                credentials.getBytes(StandardCharsets.UTF_8)))
                .requestFactory(requestFactory);
    }
}
