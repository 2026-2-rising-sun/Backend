package com.shoppinglive.commerce.refunds.application;

public class MockRefundResultUnknownException extends RuntimeException {
    public MockRefundResultUnknownException(long refundRequestId) {
        super("Mock refund result is unknown for request " + refundRequestId);
    }
}
