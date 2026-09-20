package com.shoppinglive.commerce.payments.api;

import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import jakarta.validation.constraints.NotNull;

/**
 * dev 시나리오 사전 지정 요청 body (결제 4).
 */
public record SetPaymentScenarioRequest(@NotNull PaymentScenario scenario) {
}
