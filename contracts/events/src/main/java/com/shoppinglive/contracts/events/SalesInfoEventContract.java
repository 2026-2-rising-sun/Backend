package com.shoppinglive.contracts.events;

import java.util.Objects;

/**
 * Transport-level constants for the Commerce-to-Shopping sales-info event contract.
 *
 * <p>All event types use the sales ID as their Kafka key so events for a sale share a partition.
 * Consumers apply a snapshot only when its per-sale {@code snapshotVersion} exceeds the last
 * applied version; timestamps and event IDs are not ordering signals. The version advances for
 * any changed field in the full snapshot, including changes from inventory operations.
 */
public final class SalesInfoEventContract {

    public static final String TOPIC_NAME = "commerce.sales-info.v1";
    public static final String MESSAGE_KEY_FIELD = "salesId";
    public static final int SCHEMA_VERSION = 1;

    private SalesInfoEventContract() {
    }

    /** Returns the sales ID as a decimal string, the stable Kafka key for all event types. */
    public static String messageKey(SalesInfoSnapshot payload) {
        return Long.toString(Objects.requireNonNull(payload, "payload must not be null").salesId());
    }
}
