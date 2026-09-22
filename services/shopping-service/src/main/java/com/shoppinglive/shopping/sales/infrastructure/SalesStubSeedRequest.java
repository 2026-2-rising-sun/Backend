package com.shoppinglive.shopping.sales.infrastructure;

import com.shoppinglive.shopping.sales.domain.SalesStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 개발용 stub 판매정보 등록 요청.
 */
public record SalesStubSeedRequest(
    @NotNull Long salesId,
    @NotNull @PositiveOrZero Long price,
    @NotNull SalesStatus status,
    @NotNull @PositiveOrZero Integer available) {
}
