package com.shoppinglive.commerce.payment.exception;

public class MockRefundResultUnknownException extends RuntimeException {
    public MockRefundResultUnknownException(long refundRequestId) {
        super("Mock 환불 결과가 확인되지 않았습니다: requestId=" + refundRequestId);
    }
}
