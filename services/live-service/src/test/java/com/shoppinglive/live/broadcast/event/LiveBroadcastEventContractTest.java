package com.shoppinglive.live.broadcast.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shoppinglive.common.events.EventEnvelope;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveBroadcastEventContractTest {

    private static final String EVENT_ID = "c3d2e1f0-1a2b-4c3d-9e8f-7a6b5c4d3e21";

    private final ObjectMapper mapper =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void exportsActualSerializedEventsForYamlContractValidation() throws Exception {
        var directory = java.nio.file.Path.of("build/contract-samples");
        java.nio.file.Files.createDirectories(directory);
        var at = Instant.parse("2026-10-25T12:00:00Z");
        Object[] payloads = {
            new LiveBroadcastScheduledPayload(42, 0, "방송", at),
            new LiveBroadcastStartedPayload(42, 2, "방송", at, at.plusSeconds(1), List.of(101L)),
            new LiveBroadcastEndedPayload(42, 3, at.plusSeconds(1), at.plusSeconds(60))
        };
        var types = List.of("LiveBroadcastScheduled", "LiveBroadcastStarted", "LiveBroadcastEnded");
        for (int index = 0; index < types.size(); index++) {
            var type = types.get(index);
            var event = EventEnvelope.create(type, 1, "live-service", "42", at, payloads[index]);
            mapper.writeValue(
                    directory.resolve(type + ".json").toFile(),
                    java.util.Map.of(
                            "contract", "live-broadcast.yaml", "message", type, "payload", event));
        }
    }

    @Test
    void serializesTheStartedEventInsideTheCommonEnvelope() throws Exception {
        EventEnvelope<LiveBroadcastStartedPayload> event =
                new EventEnvelope<>(
                        EVENT_ID,
                        "LiveBroadcastStarted",
                        1,
                        "live-service",
                        "42",
                        Instant.parse("2026-10-25T12:01:05.482913Z"),
                        new LiveBroadcastStartedPayload(
                                42,
                                2,
                                "가을 신상 라이브",
                                Instant.parse("2026-10-25T12:00:00Z"),
                                Instant.parse("2026-10-25T12:01:05.482913Z"),
                                List.of(101L, 102L)));

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(event));

        assertEquals(EVENT_ID, json.get("eventId").asText());
        assertEquals("LiveBroadcastStarted", json.get("eventType").asText());
        assertEquals(1, json.get("schemaVersion").asInt());
        assertEquals("live-service", json.get("source").asText());
        assertEquals("42", json.get("aggregateId").asText());
        assertEquals("2026-10-25T12:01:05.482913Z", json.get("occurredAt").asText());
        assertEquals(42, json.at("/payload/broadcastId").asLong());
        assertEquals(2, json.at("/payload/broadcastVersion").asLong());
        assertEquals("2026-10-25T12:00:00Z", json.at("/payload/scheduledAt").asText());
        assertEquals(102, json.at("/payload/productIds/1").asLong());
        assertEquals(
                event,
                mapper.readValue(
                        mapper.writeValueAsBytes(event),
                        new TypeReference<EventEnvelope<LiveBroadcastStartedPayload>>() {}));
    }

    @Test
    void consumersCanReadTheEnvelopeOfAnEventTypeTheyDoNotKnowYet() throws Exception {
        String future =
                """
                {"eventId":"%s","eventType":"LiveBroadcastCouponDropped","schemaVersion":1,
                 "source":"live-service","aggregateId":"42","occurredAt":"2026-10-25T12:10:00Z",
                 "payload":{"broadcastId":42,"dropId":"drp_1"}}
                """
                        .formatted(EVENT_ID);

        EventEnvelope<JsonNode> envelope =
                mapper.readValue(future, new TypeReference<EventEnvelope<JsonNode>>() {});

        assertEquals("LiveBroadcastCouponDropped", envelope.eventType());
        assertEquals("42", envelope.aggregateId());
        assertEquals("drp_1", envelope.payload().get("dropId").asText());
    }

    @Test
    void definesOneTopicKeyedByBroadcastIdForEveryBroadcastEvent() {
        assertEquals("live.broadcast.v1", LiveBroadcastEventContract.TOPIC_NAME);
        assertEquals("broadcastId", LiveBroadcastEventContract.MESSAGE_KEY_FIELD);
        assertEquals("42", LiveBroadcastEventContract.messageKey(42));
        assertEquals(
                List.of("LiveBroadcastScheduled", "LiveBroadcastStarted", "LiveBroadcastEnded"),
                Arrays.stream(LiveBroadcastEventType.values()).map(Enum::name).toList());
    }

    @Test
    void scheduledEventCarriesWhatNotificationNeedsToComputeTheReminderTime() throws Exception {
        EventEnvelope<LiveBroadcastScheduledPayload> event =
                EventEnvelope.create(
                        "LiveBroadcastScheduled",
                        1,
                        "live-service",
                        "42",
                        Instant.parse("2026-10-20T09:00:00Z"),
                        new LiveBroadcastScheduledPayload(
                                42, 0, "가을 신상 라이브", Instant.parse("2026-10-25T12:00:00Z")));

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(event));

        assertEquals("LiveBroadcastScheduled", json.get("eventType").asText());
        assertEquals("가을 신상 라이브", json.at("/payload/title").asText());
        assertEquals("2026-10-25T12:00:00Z", json.at("/payload/scheduledAt").asText());
        assertEquals(0, json.at("/payload/broadcastVersion").asLong());
    }

    @Test
    void rejectsInvalidEnvelopeAndPayloadValues() {
        Instant now = Instant.parse("2026-10-25T12:00:00Z");
        LiveBroadcastEndedPayload payload = new LiveBroadcastEndedPayload(42, 3, now, now);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EventEnvelope<>(
                                "not-a-uuid",
                                "LiveBroadcastEnded",
                                1,
                                "live-service",
                                "42",
                                now,
                                payload));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EventEnvelope<>(EVENT_ID, " ", 1, "live-service", "42", now, payload));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EventEnvelope<>(
                                EVENT_ID,
                                "LiveBroadcastEnded",
                                0,
                                "live-service",
                                "42",
                                now,
                                payload));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EventEnvelope<>(
                                EVENT_ID,
                                "LiveBroadcastEnded",
                                1,
                                "live-service",
                                "",
                                now,
                                payload));
        assertThrows(
                NullPointerException.class,
                () ->
                        new EventEnvelope<>(
                                EVENT_ID,
                                "LiveBroadcastEnded",
                                1,
                                "live-service",
                                "42",
                                now,
                                null));
        assertThrows(
                IllegalArgumentException.class, () -> LiveBroadcastEventContract.messageKey(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LiveBroadcastEndedPayload(0, 3, now, now));
        assertThrows(
                NullPointerException.class,
                () -> new LiveBroadcastStartedPayload(42, 2, "t", now, now, null));
        assertThrows(
                NullPointerException.class,
                () -> new LiveBroadcastScheduledPayload(42, 0, "t", null));
    }
}
