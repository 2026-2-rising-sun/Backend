package com.shoppinglive.commercecore.product.support;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 등록 직후의 CDC 메타데이터·기존 판매 알림·배치 대기 재고를 각각 확인한다. */
public final class ProductBundleOutboxAssertions {
    private ProductBundleOutboxAssertions() {}

    public static void assertRegisteredBundle(
            JdbcTemplate jdbc, ObjectMapper mapper, long productId, List<Long> skuIds)
            throws Exception {
        List<String> expected = new ArrayList<>();
        expected.add("commerce.catalog.snapshots.v1|ProductSnapshotChanged|product:" + productId);
        for (long id : skuIds) {
            expected.add("commerce.catalog.snapshots.v1|SkuSnapshotChanged|sku:" + id);
            expected.add("commerce.sales-info.v1|SalesRegistered|" + id);
        }
        var rows =
                jdbc.queryForList(
                        "SELECT topic,event_type,message_key,payload FROM event_outbox ORDER BY id");
        assertThat(
                        rows.stream()
                                .map(
                                        row ->
                                                row.get("topic")
                                                        + "|"
                                                        + row.get("event_type")
                                                        + "|"
                                                        + row.get("message_key"))
                                .toList())
                .containsExactlyInAnyOrderElementsOf(expected);
        for (var row : rows) {
            var envelope = mapper.readTree((String) row.get("payload"));
            assertThat(envelope.path("eventType").asText()).isEqualTo(row.get("event_type"));
            assertThat(envelope.path("aggregateId").asText()).isEqualTo(row.get("message_key"));
            assertThat(envelope.path("source").asText()).isEqualTo("commercecore-service");
            assertThat(envelope.path("schemaVersion").asInt()).isEqualTo(1);
            var payload = envelope.path("payload");
            assertThat(payload.path("productId").asLong()).isEqualTo(productId);
            switch ((String) row.get("event_type")) {
                case "ProductSnapshotChanged" -> {
                    assertThat(payload.path("metadataVersion").asLong()).isZero();
                    assertThat(payload.path("initialSkuCount").asInt()).isEqualTo(skuIds.size());
                }
                case "SkuSnapshotChanged" -> {
                    assertThat(skuIds).contains(payload.path("skuId").asLong());
                    assertThat(payload.path("metadataVersion").asLong()).isZero();
                    assertThat(payload.path("saleStatus").asText()).isEqualTo("ON_SALE");
                    assertThat(payload.has("available")).isFalse();
                    assertThat(payload.has("reserved")).isFalse();
                }
                case "SalesRegistered" -> {
                    long id = payload.path("salesId").asLong();
                    assertThat(skuIds).contains(id);
                    var sku = jdbc.queryForMap("SELECT price,available FROM sku WHERE id=?", id);
                    assertThat(payload.path("price").asLong())
                            .isEqualTo(((Number) sku.get("price")).longValue());
                    int available = ((Number) sku.get("available")).intValue();
                    assertThat(payload.path("available").asInt()).isEqualTo(available);
                    assertThat(payload.path("status").asText())
                            .isEqualTo(available == 0 ? "SOLD_OUT" : "ON_SALE");
                }
                default -> fail("등록 직후 허용하지 않은 이벤트입니다: " + row.get("event_type"));
            }
        }
        assertThat(jdbc.queryForList("SELECT inventory_version FROM sku ORDER BY id", Long.class))
                .containsOnly(0L);
        assertThat(
                        jdbc.queryForList(
                                "SELECT inventory_published_version FROM sku ORDER BY id",
                                Long.class))
                .containsOnly(-1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM sku WHERE inventory_version>inventory_published_version",
                                Long.class))
                .isEqualTo(skuIds.size());
    }
}
