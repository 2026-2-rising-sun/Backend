package com.shoppinglive.contracts.events;

import java.util.Objects;

/**
 * Full Commerce sales snapshot carried by every sales-info event.
 *
 * @param snapshotVersion monotonic per-sales revision; advances whenever any snapshot field changes
 */
public record SalesInfoSnapshot(
        long salesId,
        long productId,
        long snapshotVersion,
        long price,
        SalesInfoStatus status,
        int available,
        String currency) {

    public SalesInfoSnapshot {
        if (salesId <= 0) throw new IllegalArgumentException("salesId must be positive");
        if (productId <= 0) throw new IllegalArgumentException("productId must be positive");
        if (snapshotVersion <= 0) throw new IllegalArgumentException("snapshotVersion must be positive");
        if (price <= 0) throw new IllegalArgumentException("price must be positive");
        Objects.requireNonNull(status, "status must not be null");
        if (available < 0) throw new IllegalArgumentException("available must not be negative");
        if (!"KRW".equals(currency)) throw new IllegalArgumentException("currency must be KRW");
    }
}
