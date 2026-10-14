package com.shoppinglive.commerce.sales.event;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shoppinglive.common.events.EventEnvelope;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SalesInfoEventContractTest {
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void exportsActualSerializedSnapshotsForYamlValidation() throws Exception {
        var directory = java.nio.file.Path.of("build/contract-samples");
        java.nio.file.Files.createDirectories(directory);
        var payload =
                new SalesInfoSnapshot(9001, 501, 1, 25000, SalesInfoStatus.ON_SALE, 20, "KRW");
        for (var type : SalesInfoEventType.values()) {
            var event =
                    SalesInfoEventContract.create(
                            type, java.time.Instant.parse("2026-10-14T09:00:00Z"), payload);
            mapper.writeValue(
                    directory.resolve(type.name() + ".json").toFile(),
                    java.util.Map.of(
                            "contract",
                            "commerce-sales-info.yaml",
                            "message",
                            type.name(),
                            "payload",
                            event));
        }
    }

    @Test
    void 공통_봉투로_전체_판매정보를_직렬화하고_복원한다() throws Exception {
        var event =
                SalesInfoEventContract.create(
                        SalesInfoEventType.SalesRegistered,
                        Instant.parse("2026-10-09T06:00:00Z"),
                        snapshot());
        var json = mapper.readTree(mapper.writeValueAsBytes(event));
        assertThat(json.get("eventType").asText()).isEqualTo("SalesRegistered");
        assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.get("source").asText()).isEqualTo("commerce-service");
        assertThat(json.get("aggregateId").asText()).isEqualTo("9001");
        assertThat(json.at("/payload/snapshotVersion").asLong()).isEqualTo(2);
        assertThat(json.at("/payload/salesId").asLong()).isEqualTo(9001);
        assertThat(json.at("/payload/productId").asLong()).isEqualTo(501);
        assertThat(json.at("/payload/price").asLong()).isEqualTo(25000);
        assertThat(json.at("/payload/status").asText()).isEqualTo("ON_SALE");
        assertThat(json.at("/payload/available").asInt()).isEqualTo(20);
        assertThat(json.at("/payload/currency").asText()).isEqualTo("KRW");
        assertThat(
                        mapper.readValue(
                                mapper.writeValueAsBytes(event),
                                new TypeReference<EventEnvelope<SalesInfoSnapshot>>() {}))
                .isEqualTo(event);
    }

    @Test
    void 기존_이벤트명과_토픽과_판매_ID_기반_키를_유지한다() {
        assertThat(Arrays.stream(SalesInfoEventType.values()).map(Enum::name))
                .containsExactly(
                        "SalesRegistered",
                        "PriceChanged",
                        "InventoryChanged",
                        "SalesStatusChanged");
        assertThat(SalesInfoEventContract.TOPIC_NAME).isEqualTo("commerce.sales-info.v1");
        assertThat(SalesInfoEventContract.MESSAGE_KEY_FIELD).isEqualTo("salesId");
        assertThat(SalesInfoEventContract.messageKey(snapshot())).isEqualTo("9001");
    }

    @Test
    void 잘못된_스냅샷과_이벤트명을_거절한다() {
        assertThatThrownBy(() -> SalesInfoEventContract.create(null, Instant.now(), snapshot()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new SalesInfoSnapshot(
                                        0, 501, 1, 27000, SalesInfoStatus.READY, 20, "KRW"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SalesInfoSnapshot(
                                        9001, 501, 1, 27000, SalesInfoStatus.READY, -1, "KRW"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SalesInfoSnapshot(
                                        9001, 501, 1, 27000, SalesInfoStatus.READY, 20, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SalesInfoSnapshot snapshot() {
        return new SalesInfoSnapshot(9001, 501, 2, 25000, SalesInfoStatus.ON_SALE, 20, "KRW");
    }
}
