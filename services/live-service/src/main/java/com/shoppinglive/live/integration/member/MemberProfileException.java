package com.shoppinglive.live.integration.member;

public class MemberProfileException extends RuntimeException {
    public enum Reason { UNAUTHORIZED, UNAVAILABLE }

    private final Reason reason;

    public MemberProfileException(final Reason reason) {
        super("member profile " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
