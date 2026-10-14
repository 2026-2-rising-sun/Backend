package com.shoppinglive.live.client;

/** 외부 상품/판매 조회 실패. 정상 조회의 미존재와 인프라 장애를 구분한다. */
public class ProductLookupException extends RuntimeException {
    public enum Reason {
        NOT_FOUND,
        UNAVAILABLE
    }

    private final Reason reason;
    private final String service;

    public ProductLookupException(final String service, final Reason reason) {
        super(service + " 상품 조회 결과: " + reason.name().toLowerCase());
        this.service = service;
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public String service() {
        return service;
    }
}
