package com.shoppinglive.commerce.payments.api;

import com.shoppinglive.commerce.payments.domain.PaymentScenario;

/**
 * 결제 시작 요청 body.
 *
 * <p>{@code scenario} 는 optional. 생략 시 INSTANT_SUCCESS default. Sprint 3 에서 프로덕션
 * 프로파일에서는 이 필드 무시 · default 만 사용하도록 격리 예정 (결제 4 이슈).
 */
public record StartPaymentRequest(PaymentScenario scenario) {
}
