package com.shoppinglive.live.integration.member;

import com.shoppinglive.live.integration.member.MemberProfileException.Reason;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Member 주소는 세션 확인과 같은 신뢰하는 설정값을 쓴다. 요청에서 주소를 받지 않고 redirect 를 따라가지 않는다.
 * 주소가 없으면 stub 성공으로 숨기지 않고 모든 조회를 UNAVAILABLE 로 실패시킨다.
 */
@Configuration(proxyBeanMethods = false)
public class MemberClientConfiguration {
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Bean
    MemberProfileClient memberProfileClient(final RestClient.Builder builder,
        @Value("${shoppinglive.security.session.base-url:}") final String memberUrl) {
        if (memberUrl.isBlank()) {
            return (authorization, memberId) -> {
                throw new MemberProfileException(Reason.UNAVAILABLE);
            };
        }
        return new HttpMemberProfileClient(http(builder, memberUrl));
    }

    static RestClient http(final RestClient.Builder builder, final String memberUrl) {
        final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
        final JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(TIMEOUT);
        return builder.clone().baseUrl(memberUrl).requestFactory(requests).build();
    }
}
