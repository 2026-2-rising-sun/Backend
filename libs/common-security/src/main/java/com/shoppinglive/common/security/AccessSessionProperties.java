package com.shoppinglive.common.security;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("shoppinglive.security.session")
public record AccessSessionProperties(URI baseUrl, String serviceToken, Duration connectTimeout, Duration readTimeout) {
    public AccessSessionProperties {
        if (baseUrl == null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
            || baseUrl.getHost() == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
            || baseUrl.getFragment() != null || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath()))) {
            throw new IllegalArgumentException("Session verification requires an HTTP(S) origin base-url");
        }
        if (serviceToken == null || serviceToken.length() < 32 || serviceToken.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Session verification requires a service-token of at least 32 characters");
        }
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(1) : readTimeout;
        validateTimeout(connectTimeout);
        validateTimeout(readTimeout);
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Session verification timeouts must be between 1ms and 5s");
        }
    }

    @Override public String toString() {
        return "AccessSessionProperties[baseUrl=" + baseUrl + ", serviceToken=[redacted], connectTimeout="
            + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
