package com.shoppinglive.common.security;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("shoppinglive.security.session")
public record AccessSessionProperties(
        URI baseUrl, String serviceToken, Duration connectTimeout, Duration readTimeout) {
    public AccessSessionProperties {
        if (baseUrl == null
                || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null
                || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath()))) {
            throw new IllegalArgumentException("세션 검증의 base-url은 HTTP(S) 서비스 기본 주소여야 합니다.");
        }
        if (serviceToken == null
                || serviceToken.length() < 32
                || serviceToken.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("세션 검증의 service-token은 32자 이상이어야 합니다.");
        }
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(1) : readTimeout;
        validateTimeout(connectTimeout);
        validateTimeout(readTimeout);
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("세션 검증의 제한 시간은 1밀리초~5초여야 합니다.");
        }
    }

    @Override
    public String toString() {
        return "AccessSessionProperties[baseUrl="
                + baseUrl
                + ", serviceToken=[redacted], connectTimeout="
                + connectTimeout
                + ", readTimeout="
                + readTimeout
                + "]";
    }
}
