package com.shoppinglive.commerce.coupons.domain;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

public record CouponDefinition(String id, String sellerId, String name, long fixedDiscount,
        int issuanceLimit, int issuedCount, Instant startsAt, Instant endsAt, Instant expiresAt,
        long version, List<Long> productIds) {
    public CouponDefinition {
        productIds = List.copyOf(productIds);
    }

    public static void validate(String name, long discount, int limit, Instant start, Instant end,
            Instant expiration, List<Long> products) {
        if (name == null || name.isBlank() || name.length() > 100 || discount <= 0 || limit < 1 || limit > 10000
                || start == null || end == null || expiration == null || !start.isBefore(end)
                || start.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || expiration.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))
                || Duration.between(start, end).compareTo(Duration.ofHours(720)) > 0
                || expiration.isBefore(end) || products == null || products.isEmpty()
                || products.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(products).size() != products.size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "쿠폰 금액·수량·기간·대상 상품을 확인해 주세요.");
        }
    }
}
