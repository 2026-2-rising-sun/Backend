package com.shoppinglive.common.security;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("shoppinglive.security")
public record ServiceCallerProperties(Map<String, String> serviceTokens) {
    public ServiceCallerProperties {
        serviceTokens = serviceTokens == null ? Map.of() : Map.copyOf(serviceTokens);
    }
}
