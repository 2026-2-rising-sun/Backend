package com.shoppinglive.contracts.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned envelope for Commerce sales-info snapshots consumed by Shopping. */
public record SalesInfoEvent(
        String eventId,
        SalesInfoEventType eventType,
        int schemaVersion,
        Instant occurredAt,
        SalesInfoSnapshot payload)
        implements DomainEvent {

    public SalesInfoEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        try {
            UUID parsed = UUID.fromString(eventId);
            if (!parsed.toString().equalsIgnoreCase(eventId)) {
                throw new IllegalArgumentException("eventId must be a canonical UUID string");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("eventId must be a canonical UUID string", exception);
        }
        Objects.requireNonNull(eventType, "eventType must not be null");
        if (schemaVersion != SalesInfoEventContract.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported sales-info schemaVersion: " + schemaVersion);
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }
}
