package com.shoppinglive.notification.broadcast.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 방송 시작 알림 신청 요청. 누가 신청하는지는 토큰에서 꺼내므로 본문에 회원 ID 를 받지 않는다. */
public record BroadcastAlertRequest(@NotNull @Positive Long broadcastId) {}
