package com.shoppinglive.live.client.member;

public class MemberProfileException extends RuntimeException {
    public enum Reason {
        UNAUTHORIZED,
        UNAVAILABLE
    }

    private final Reason reason;

    public MemberProfileException(final Reason reason) {
        super("회원 프로필 조회 결과: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
