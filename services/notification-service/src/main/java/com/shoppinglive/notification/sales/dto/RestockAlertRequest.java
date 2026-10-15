package com.shoppinglive.notification.sales.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 재입고 알림 신청 요청. 누가 신청하는지는 토큰에서 꺼내므로 본문에 회원 ID 를 받지 않는다. */
public record RestockAlertRequest(@NotNull @Positive Long productId) {}
