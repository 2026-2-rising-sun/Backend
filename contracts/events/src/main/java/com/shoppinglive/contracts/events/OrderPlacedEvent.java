package com.shoppinglive.contracts.events;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderPlacedEvent(
        String eventId,
        Instant occurredAt,
        String orderId,
        String memberId,
        BigDecimal totalAmount)
        implements DomainEvent {
}
