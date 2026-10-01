package com.shoppinglive.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import org.springframework.security.authentication.BadCredentialsException;

/** 호출자의 주장인 ID 헤더는 읽지 않는다. 등록된 opaque 키가 서비스 ID를 결정한다. */
public final class ServiceCallerTokenValidator {
    private final Map<String, byte[]> tokens;

    public ServiceCallerTokenValidator(Map<String, String> configuredTokens) {
        var uniqueTokens = new HashSet<String>();
        configuredTokens.forEach((service, token) -> {
            if (!service.matches("[a-z][a-z0-9-]{0,31}") || token == null || token.length() < 32
                || !token.equals(token.strip()) || !uniqueTokens.add(token)) {
                throw new IllegalArgumentException("Service tokens require distinct keys of at least 32 characters and valid service IDs");
            }
        });
        this.tokens = configuredTokens.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
            Map.Entry::getKey, entry -> entry.getValue().getBytes(StandardCharsets.UTF_8)));
    }

    public AuthenticatedServiceCaller validate(String token) {
        if (token == null || token.isBlank()) {
            throw new BadCredentialsException("Invalid service credential");
        }
        byte[] supplied = token.getBytes(StandardCharsets.UTF_8);
        String matched = null;
        for (var entry : tokens.entrySet()) {
            if (MessageDigest.isEqual(supplied, entry.getValue())) {
                matched = entry.getKey();
            }
        }
        if (matched == null) {
            throw new BadCredentialsException("Invalid service credential");
        }
        return new AuthenticatedServiceCaller(matched);
    }
}
