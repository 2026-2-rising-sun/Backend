package com.shoppinglive.member.auth.application;

public record TokenPair(String accessToken, String tokenType, long expiresIn, String refreshToken) {
    @Override public String toString() { return "TokenPair[REDACTED]"; }
}
