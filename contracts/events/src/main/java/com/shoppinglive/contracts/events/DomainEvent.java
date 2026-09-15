package com.shoppinglive.contracts.events;

import java.time.Instant;

public interface DomainEvent {

    String eventId();

    Instant occurredAt();
}
