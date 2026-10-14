package com.shoppinglive.commerce.payment.dto;

import com.shoppinglive.commerce.payment.entity.PaymentScenario;
import jakarta.validation.constraints.NotNull;

/** dev 시나리오 사전 지정 요청 body (결제 4). */
public record SetPaymentScenarioRequest(@NotNull(message = "필수 값입니다.") PaymentScenario scenario) {}
