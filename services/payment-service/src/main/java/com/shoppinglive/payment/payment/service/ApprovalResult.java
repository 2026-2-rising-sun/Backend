package com.shoppinglive.payment.payment.service;

/** 승인 요청의 결과. 승인 API({@code ApprovalController})가 HTTP 응답으로 바꾼다. */
public sealed interface ApprovalResult {
    long paymentId();

    /** 200. 이미 승인된 결제의 재요청 포함. */
    record Approved(long paymentId) implements ApprovalResult {}

    /** 202. 처리 중이거나 결과 불명. */
    record Pending(long paymentId) implements ApprovalResult {}

    /** 422 DECLINED. 첫 호출에서 토스가 거절했다. CREATED로 돌아갔다. */
    record Declined(long paymentId, String code, String message) implements ApprovalResult {}

    /** 503 PG_UNAVAILABLE. 첫 호출이 토스에 가지 않았다. CREATED로 돌아갔다. */
    record PgUnavailable(long paymentId) implements ApprovalResult {}

    /** 409 PAYMENT_CLOSED. 토스 승인 응답이 맞지 않아 보상 취소했다. */
    record Closed(long paymentId) implements ApprovalResult {}
}
