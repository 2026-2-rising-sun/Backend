package com.shoppinglive.commercecore.payment.entity;

/** 공개 DTO의 개발 시나리오 이름. 선택 상태와 실행 의미는 Payment가 소유한다. */
public enum PaymentScenario {
    INSTANT_SUCCESS,
    INSTANT_FAIL,
    DELAYED_SUCCESS,
    DELAYED_FAIL,
    SUCCESS_RESPONSE_LOST,
    FAILURE_RESPONSE_LOST,
    UNAVAILABLE_BEFORE_RESULT
}
