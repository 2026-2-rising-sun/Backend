package com.shoppinglive.payment.payment.dto;

/** 422의 data. code·message는 토스 거절 코드·메시지 그대로다. */
public record DeclinedResponse(String status, String code, String message) {}
