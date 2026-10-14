package com.shoppinglive.common.events;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Instant time = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void 새_ID와_메타데이터를_생성하고_제네릭_payload를_복원한다() throws Exception {
        var event =
                EventEnvelope.create(
                        "OrderPaid",
                        1,
                        "commerce-service",
                        "42",
                        time,
                        Map.of("orderId", "42", "unused", "extra"));
        assertThat(UUID.fromString(event.eventId()).toString()).isEqualTo(event.eventId());
        var received =
                mapper.readValue(
                        mapper.writeValueAsBytes(event),
                        new TypeReference<EventEnvelope<PartialPayload>>() {});
        assertThat(received.source()).isEqualTo("commerce-service");
        assertThat(received.aggregateId()).isEqualTo("42");
        assertThat(received.occurredAt()).isEqualTo(time);
        assertThat(received.payload().orderId()).isEqualTo("42");
    }

    @Test
    void 알수없는_이벤트명과_양수버전은_봉투에서_거절하지_않는다() throws Exception {
        var event =
                EventEnvelope.create(
                        "FutureEvent", 2, "future-service", "42", time, Map.of("id", 42));
        var received =
                mapper.readValue(
                        mapper.writeValueAsBytes(event),
                        new TypeReference<EventEnvelope<Map<String, Integer>>>() {});
        assertThat(received).isEqualTo(event);
    }

    @Test
    void 잘못된_필수값을_거절한다() {
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(
                        () ->
                                new EventEnvelope<>(
                                        "not-uuid", "Type", 1, "source", "id", time, "data"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope<>(id, " ", 1, "source", "id", time, "data"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope<>(id, "Type", 0, "source", "id", time, "data"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope<>(id, "Type", 1, null, "id", time, "data"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope<>(id, "Type", 1, "source", "", time, "data"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope<>(id, "Type", 1, "source", "id", null, "data"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventEnvelope<>(id, "Type", 1, "source", "id", time, null))
                .isInstanceOf(NullPointerException.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PartialPayload(String orderId) {}
}
