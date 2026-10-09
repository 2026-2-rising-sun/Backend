package com.shoppinglive.contracts.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SalesInfoEventContractTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serializesAndReadsTheVersionedFullSnapshotEnvelope() throws Exception {
        SalesInfoEvent event = new SalesInfoEvent(
                "74b623b8-90ee-4f65-919c-d9ad2ea46fb2",
                SalesInfoEventType.SalesRegistered,
                1,
                Instant.parse("2026-10-09T06:00:00Z"),
                new SalesInfoSnapshot(9001, 501, 1, 27000, SalesInfoStatus.READY, 20, "KRW"));

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(event));

        assertEquals("74b623b8-90ee-4f65-919c-d9ad2ea46fb2", json.get("eventId").asText());
        assertEquals("SalesRegistered", json.get("eventType").asText());
        assertEquals(1, json.get("schemaVersion").asInt());
        assertEquals("2026-10-09T06:00:00Z", json.get("occurredAt").asText());
        assertEquals(9001, json.at("/payload/salesId").asLong());
        assertEquals(501, json.at("/payload/productId").asLong());
        assertEquals(1, json.at("/payload/snapshotVersion").asLong());
        assertEquals(27000, json.at("/payload/price").asLong());
        assertEquals("READY", json.at("/payload/status").asText());
        assertEquals(20, json.at("/payload/available").asInt());
        assertEquals("KRW", json.at("/payload/currency").asText());
        assertEquals(event, mapper.treeToValue(json, SalesInfoEvent.class));
    }

    @Test
    void exposesTheFourDocumentedEventNames() {
        assertEquals(
                List.of("SalesRegistered", "PriceChanged", "InventoryChanged", "SalesStatusChanged"),
                Arrays.stream(SalesInfoEventType.values()).map(Enum::name).toList());
    }

    @Test
    void definesTheDocumentedTopicAndStableSalesIdKey() {
        assertEquals("commerce.sales-info.v1", SalesInfoEventContract.TOPIC_NAME);
        assertEquals("salesId", SalesInfoEventContract.MESSAGE_KEY_FIELD);
        assertEquals("9001", SalesInfoEventContract.messageKey(snapshot()));
    }

    @Test
    void rejectsInvalidEnvelopeAndSnapshotValues() {
        assertThrows(IllegalArgumentException.class, () -> event("not-a-uuid", snapshot()));
        assertThrows(IllegalArgumentException.class,
                () -> event("74b623b8-90ee-4f65-919c-d9ad2ea46fb2", 0, snapshot()));
        assertThrows(IllegalArgumentException.class,
                () -> event("74b623b8-90ee-4f65-919c-d9ad2ea46fb2", 2, snapshot()));
        assertThrows(IllegalArgumentException.class,
                () -> new SalesInfoSnapshot(0, 501, 1, 27000, SalesInfoStatus.READY, 20, "KRW"));
        assertThrows(IllegalArgumentException.class,
                () -> new SalesInfoSnapshot(9001, 501, 1, 27000, SalesInfoStatus.READY, -1, "KRW"));
        assertThrows(IllegalArgumentException.class,
                () -> new SalesInfoSnapshot(9001, 501, 1, 27000, SalesInfoStatus.READY, 20, "USD"));
    }

    private static SalesInfoEvent event(String eventId, SalesInfoSnapshot payload) {
        return event(eventId, 1, payload);
    }

    private static SalesInfoEvent event(String eventId, int schemaVersion, SalesInfoSnapshot payload) {
        return new SalesInfoEvent(
                eventId, SalesInfoEventType.PriceChanged, schemaVersion, Instant.parse("2026-10-09T06:10:00Z"), payload);
    }

    private static SalesInfoSnapshot snapshot() {
        return new SalesInfoSnapshot(9001, 501, 2, 25000, SalesInfoStatus.ON_SALE, 20, "KRW");
    }
}
