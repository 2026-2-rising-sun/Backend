package com.shoppinglive.common.events;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Instant time = Instant.parse("2026-10-14T00:00:00Z");

    @DisplayName("새 이벤트 ID와 메타데이터를 생성하고 제네릭 payload를 복원한다")
    @Test
    void createsMetadataAndRestoresGenericPayload() throws Exception {
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

    @DisplayName("공통 봉투는 모르는 이벤트명과 양수 버전을 허용한다")
    @Test
    void allowsUnknownEventTypeAndPositiveSchemaVersion() throws Exception {
        var event =
                EventEnvelope.create(
                        "FutureEvent", 2, "future-service", "42", time, Map.of("id", 42));
        var received =
                mapper.readValue(
                        mapper.writeValueAsBytes(event),
                        new TypeReference<EventEnvelope<Map<String, Integer>>>() {});
        assertThat(received).isEqualTo(event);
    }

    @DisplayName("필수 메타데이터가 없거나 잘못되면 이벤트 봉투 생성을 거절한다")
    @Test
    void rejectsInvalidRequiredMetadata() {
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
