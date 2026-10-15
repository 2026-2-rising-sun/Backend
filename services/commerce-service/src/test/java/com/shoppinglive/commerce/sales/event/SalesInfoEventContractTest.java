package com.shoppinglive.commerce.sales.event;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shoppinglive.common.events.EventEnvelope;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SalesInfoEventContractTest {
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @DisplayName("실제 직렬화한 판매정보 스냅샷을 YAML 검증 표본으로 내보낸다")
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

    @DisplayName("공통 봉투로 전체 판매정보를 직렬화하고 복원한다")
    @Test
    void serializesAndRestoresFullSalesSnapshotInCommonEnvelope() throws Exception {
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

    @DisplayName("기존 이벤트명·토픽·판매 ID 키를 유지한다")
    @Test
    void preservesEventNamesTopicAndSalesIdKey() {
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

    @DisplayName("잘못된 판매 스냅샷과 이벤트명을 거절한다")
    @Test
    void rejectsInvalidSnapshotAndEventType() {
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
