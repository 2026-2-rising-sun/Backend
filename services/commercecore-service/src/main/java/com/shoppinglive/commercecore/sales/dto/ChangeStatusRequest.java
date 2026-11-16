package com.shoppinglive.commercecore.sales.dto;

import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 판매 상태 변경 요청 body.
 *
 * <p>판매자는 {@code ON_SALE} 또는 {@code PRIVATE} 만 지정 가능하다. {@code SOLD_OUT} · {@code READY} 를 요청하면 서비스
 * 계층이 400 응답으로 매핑한다.
 */
public record ChangeStatusRequest(@NotNull(message = "필수 값입니다.") SalesStatus status) {}
