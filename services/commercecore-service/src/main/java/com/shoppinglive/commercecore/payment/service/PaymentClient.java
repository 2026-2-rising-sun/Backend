package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.payment.dto.*;

public interface PaymentClient {
    PaymentExecution start(PaymentCommand command);

    PaymentExecution get(long paymentId);

    RefundExecution refund(RefundCommand command);

    RefundExecution refund(long executionId);

    void setScenario(
            String referenceNumber,
            com.shoppinglive.commercecore.payment.entity.PaymentScenario scenario);

    void clearScenario(String referenceNumber);
}
