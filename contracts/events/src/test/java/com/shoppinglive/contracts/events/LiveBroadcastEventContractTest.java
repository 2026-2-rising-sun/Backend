package com.shoppinglive.contracts.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiveBroadcastEventContractTest {

    private static final String EVENT_ID = "c3d2e1f0-1a2b-4c3d-9e8f-7a6b5c4d3e21";

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serializesTheStartedEventInsideTheCommonEnvelope() throws Exception {
        EventEnvelope<LiveBroadcastStartedPayload> event = new EventEnvelope<>(
                EVENT_ID, "LiveBroadcastStarted", 1, "live-service", "42",
                Instant.parse("2026-10-25T12:01:05.482913Z"),
                new LiveBroadcastStartedPayload(42, 2, "가을 신상 라이브",
                        Instant.parse("2026-10-25T12:00:00Z"),
                        Instant.parse("2026-10-25T12:01:05.482913Z"), List.of(101L, 102L)));

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
        assertEquals(event, mapper.readValue(mapper.writeValueAsBytes(event),
                new TypeReference<EventEnvelope<LiveBroadcastStartedPayload>>() { }));
    }

    @Test
    void consumersCanReadTheEnvelopeOfAnEventTypeTheyDoNotKnowYet() throws Exception {
        String future = """
                {"eventId":"%s","eventType":"LiveBroadcastCouponDropped","schemaVersion":1,
                 "source":"live-service","aggregateId":"42","occurredAt":"2026-10-25T12:10:00Z",
                 "payload":{"broadcastId":42,"dropId":"drp_1"}}
                """.formatted(EVENT_ID);

        EventEnvelope<JsonNode> envelope =
                mapper.readValue(future, new TypeReference<EventEnvelope<JsonNode>>() { });

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
                List.of("LiveBroadcastScheduled", "LiveBroadcastStarted", "LiveBroadcastEnded",
                        "LiveBroadcastPromotionOpened", "LiveBroadcastPromotionClosed"),
                Arrays.stream(LiveBroadcastEventType.values()).map(Enum::name).toList());
    }

    @Test
    void scheduledEventCarriesWhatNotificationNeedsToComputeTheReminderTime() throws Exception {
        EventEnvelope<LiveBroadcastScheduledPayload> event = EventEnvelope.create(
                "LiveBroadcastScheduled", 1, "live-service", "42",
                Instant.parse("2026-10-20T09:00:00Z"),
                new LiveBroadcastScheduledPayload(42, 0, "가을 신상 라이브",
                        Instant.parse("2026-10-25T12:00:00Z")));

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(event));

        assertEquals("LiveBroadcastScheduled", json.get("eventType").asText());
        assertEquals("가을 신상 라이브", json.at("/payload/title").asText());
        assertEquals("2026-10-25T12:00:00Z", json.at("/payload/scheduledAt").asText());
        assertEquals(0, json.at("/payload/broadcastVersion").asLong());
    }

    @Test
    void endedPayloadNormalizesClosedPromotionIdsToEmptyWhenNull() {
        Instant startedAt = Instant.parse("2026-10-25T12:00:00Z");
        Instant endedAt = Instant.parse("2026-10-25T12:10:00Z");
        LiveBroadcastEndedPayload none = new LiveBroadcastEndedPayload(42, 3, startedAt, endedAt, null);
        assertEquals(List.of(), none.closedPromotionIds());
        LiveBroadcastEndedPayload some = new LiveBroadcastEndedPayload(42, 3, startedAt, endedAt,
                List.of(7L, 11L));
        assertEquals(List.of(7L, 11L), some.closedPromotionIds());
    }

    @Test
    void serializesPromotionOpenedWithOrderedAttributes() throws Exception {
        // 삽입 순서를 뒤섞어도 payload 의 attributes 는 키 순서로 직렬화된다.
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("couponPolicyId", 7);
        attributes.put("claimLimit", 500);
        EventEnvelope<LiveBroadcastPromotionOpenedPayload> event = new EventEnvelope<>(
                EVENT_ID, "LiveBroadcastPromotionOpened", 1, "live-service", "42",
                Instant.parse("2026-10-25T12:10:00Z"),
                new LiveBroadcastPromotionOpenedPayload(42, 7, 1, "COUPON_DROP",
                        "방송 한정 3,000원 할인", attributes,
                        Instant.parse("2026-10-25T12:10:00Z"),
                        Instant.parse("2026-10-25T12:15:00Z")));

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(event));

        assertEquals("LiveBroadcastPromotionOpened", json.get("eventType").asText());
        assertEquals(42, json.at("/payload/broadcastId").asLong());
        assertEquals(7, json.at("/payload/promotionId").asLong());
        assertEquals("COUPON_DROP", json.at("/payload/type").asText());
        assertEquals(500, json.at("/payload/attributes/claimLimit").asInt());
        assertEquals(7, json.at("/payload/attributes/couponPolicyId").asInt());
        // TreeMap 정렬 후 직렬화라 fieldNames() 는 알파벳 순이어야 한다.
        List<String> fieldOrder = new java.util.ArrayList<>();
        json.at("/payload/attributes").fieldNames().forEachRemaining(fieldOrder::add);
        assertEquals(List.of("claimLimit", "couponPolicyId"), fieldOrder);
        assertEquals(event, mapper.readValue(mapper.writeValueAsBytes(event),
                new TypeReference<EventEnvelope<LiveBroadcastPromotionOpenedPayload>>() { }));
    }

    @Test
    void serializesPromotionClosedWithReason() throws Exception {
        EventEnvelope<LiveBroadcastPromotionClosedPayload> event = new EventEnvelope<>(
                EVENT_ID, "LiveBroadcastPromotionClosed", 1, "live-service", "42",
                Instant.parse("2026-10-25T12:15:00Z"),
                new LiveBroadcastPromotionClosedPayload(42, 7, 2, "COUPON_DROP", "SELLER_CLOSED",
                        Instant.parse("2026-10-25T12:10:00Z"),
                        Instant.parse("2026-10-25T12:15:00Z")));

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(event));
        assertEquals("LiveBroadcastPromotionClosed", json.get("eventType").asText());
        assertEquals("SELLER_CLOSED", json.at("/payload/reason").asText());
        assertEquals("2026-10-25T12:10:00Z", json.at("/payload/openedAt").asText());
        assertEquals("2026-10-25T12:15:00Z", json.at("/payload/closedAt").asText());
    }

    @Test
    void promotionClosedAllowsNullOpenedAtForSafety() {
        // READY→CLOSED(CANCELED) 는 Opened 가 발행되지 않아 이 이벤트 자체도 발행되지 않지만,
        // payload 레코드는 null 을 허용해 소비자가 역직렬화에 실패하지 않게 한다.
        new LiveBroadcastPromotionClosedPayload(42, 7, 1, "COUPON_DROP", "CANCELED",
                null, Instant.parse("2026-10-25T12:00:00Z"));
    }

    @Test
    void rejectsInvalidEnvelopeAndPayloadValues() {
        Instant now = Instant.parse("2026-10-25T12:00:00Z");
        LiveBroadcastEndedPayload payload = new LiveBroadcastEndedPayload(42, 3, now, now, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> new EventEnvelope<>("not-a-uuid", "LiveBroadcastEnded", 1, "live-service", "42", now, payload));
        assertThrows(IllegalArgumentException.class,
                () -> new EventEnvelope<>(EVENT_ID, " ", 1, "live-service", "42", now, payload));
        assertThrows(IllegalArgumentException.class,
                () -> new EventEnvelope<>(EVENT_ID, "LiveBroadcastEnded", 0, "live-service", "42", now, payload));
        assertThrows(IllegalArgumentException.class,
                () -> new EventEnvelope<>(EVENT_ID, "LiveBroadcastEnded", 1, "live-service", "", now, payload));
        assertThrows(NullPointerException.class,
                () -> new EventEnvelope<>(EVENT_ID, "LiveBroadcastEnded", 1, "live-service", "42", now, null));
        assertThrows(IllegalArgumentException.class, () -> LiveBroadcastEventContract.messageKey(0));
        assertThrows(IllegalArgumentException.class, () -> new LiveBroadcastEndedPayload(0, 3, now, now, null));
        assertThrows(NullPointerException.class,
                () -> new LiveBroadcastStartedPayload(42, 2, "t", now, now, null));
        assertThrows(NullPointerException.class,
                () -> new LiveBroadcastScheduledPayload(42, 0, "t", null));
        assertThrows(IllegalArgumentException.class,
                () -> new LiveBroadcastPromotionOpenedPayload(42, 0, 1, "T", "t", Map.of(), now, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LiveBroadcastPromotionClosedPayload(42, 1, 1, "T", " ", now, now));
        assertThrows(NullPointerException.class,
                () -> new LiveBroadcastPromotionOpenedPayload(42, 1, 1, "T", "t", null, now, null));
    }
}
