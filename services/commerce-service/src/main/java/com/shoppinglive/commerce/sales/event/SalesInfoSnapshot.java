package com.shoppinglive.commerce.sales.event;

import java.util.Objects;

/**
 * 모든 판매정보 이벤트에 포함되는 Commerce의 전체 판매정보 스냅샷.
 *
 * @param snapshotVersion 판매정보별로 증가하는 버전. 스냅샷의 어느 필드든 변경되면 증가한다.
 */
public record SalesInfoSnapshot(
        long salesId,
        long productId,
        long snapshotVersion,
        long price,
        SalesInfoStatus status,
        int available,
        String currency,
        String productName) {

    public SalesInfoSnapshot(
            long salesId,
            long productId,
            long snapshotVersion,
            long price,
            SalesInfoStatus status,
            int available,
            String currency) {
        this(salesId, productId, snapshotVersion, price, status, available, currency, "");
    }

    public SalesInfoSnapshot {
        if (salesId <= 0) {
            throw new IllegalArgumentException("salesId는 양수여야 합니다.");
        }
        if (productId <= 0) {
            throw new IllegalArgumentException("productId는 양수여야 합니다.");
        }
        if (snapshotVersion <= 0) {
            throw new IllegalArgumentException("snapshotVersion은 양수여야 합니다.");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("price는 양수여야 합니다.");
        }
        Objects.requireNonNull(status, "status는 필수입니다.");
        if (available < 0) {
            throw new IllegalArgumentException("available은 음수일 수 없습니다.");
        }
        if (!"KRW".equals(currency)) {
            throw new IllegalArgumentException("currency는 KRW여야 합니다.");
        }
    }
}
