package com.shoppinglive.contracts.events;

import java.time.Instant;

public record LiveStreamStartedEvent(
        String eventId,
        Instant occurredAt,
        String streamId,
        String hostMemberId)
        implements DomainEvent {
}
