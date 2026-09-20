package com.shoppinglive.commerce.sales.api;

import com.shoppinglive.commerce.sales.domain.SalesStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 판매 상태 변경 요청 body.
 *
 * <p>관리자는 {@code ON_SALE} 또는 {@code PRIVATE} 만 지정 가능하다. {@code SOLD_OUT} ·
 * {@code READY} 를 요청하면 서비스 계층이 400 응답으로 매핑한다.
 */
public record ChangeStatusRequest(
    @NotNull SalesStatus status
) {
}
